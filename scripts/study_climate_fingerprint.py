#!/usr/bin/env python3
"""P4.3: 从真实P1群系生成空间独立的临时气候指纹实验输入。

只在用户本机显式运行；原始quart XYZ/HMAC只进入临时CSV并自动删除。
无真实Seed候选或未验证NoiseRouter时只报告不可辨识，绝不强行全域搜索。
"""
from __future__ import annotations

import argparse
from collections import Counter
import hashlib
import heapq
import json
from pathlib import Path
import re
import shutil
import struct
import subprocess
import sys
import tempfile

from research_p37_custom_worldgen import (
    Dataset, EvidenceRejected, FINGERPRINT, DIMENSION, ALLOWED_VERSIONS,
    MAX_RECORDS, MAX_REGIONS,
)

MAX_ROW = 8192
MAX_PER_REGION = 48
MAX_SELECTED_REGIONS = 12
BIOME = re.compile(r"^[a-z0-9_.-]+:[a-z0-9_./-]+$")
MAGIC = "WSL_P43_SPATIAL_CLIMATE_V1"


def iterate_p1(dataset: Dataset):
    scope = None
    with dataset.opened("evidence.jsonl") as stream:
        if stream is None:
            raise EvidenceRejected("没有原始P1已加载FULL群系")
        for number, line in enumerate(stream, 1):
            if number > MAX_RECORDS or len(line) > MAX_ROW:
                raise EvidenceRejected("原始群系超出有界读取范围")
            try:
                row = json.loads(line)
            except (ValueError, UnicodeError) as exc:
                raise EvidenceRejected("原始P1内容截断/格式异常") from exc
            if not isinstance(row, dict) or row.get("schema") != 1:
                raise EvidenceRejected("客户端P1证据格式不可信")
            fp, version, dim = (row.get("world_fingerprint"),
                                row.get("client_version"), row.get("dimension"))
            if (not isinstance(fp, str) or not FINGERPRINT.fullmatch(fp)
                    or version not in ALLOWED_VERSIONS or not isinstance(dim, str)
                    or not DIMENSION.fullmatch(dim)):
                raise EvidenceRejected("世界/版本/维度作用域非法")
            current = (fp, version, dim)
            if scope is None:
                scope = current
            if (current != scope
                    or dataset.expected_fp is not None and fp != dataset.expected_fp):
                raise EvidenceRejected("原始客户端证据跨世界或不同生成时期混合")
            if row.get("kind") != "BIOME_OBSERVATION":
                continue
            if row.get("origin") != "CLIENT_OBSERVATION":
                raise EvidenceRejected("不是实际普通客户端接收的FULL区块群系")
            pos = row.get("position")
            biome = row.get("observed_identifier")
            if (not isinstance(pos, dict)
                    or any(type(pos.get(a)) is not int for a in ("x", "y", "z"))
                    or not isinstance(biome, str)
                    or len(biome) > 256 or not BIOME.fullmatch(biome)):
                raise EvidenceRejected("群系坐标、注册ID错误")
            x, y, z = pos["x"], pos["y"], pos["z"]
            if x % 4 != 2 or y % 4 != 2 or z % 4 != 2:
                raise EvidenceRejected("不是原版quart中心采样")
            qx, qy, qz = x // 4, y // 4, z // 4
            if not (-8_000_000 <= qx <= 8_000_000
                    and -1024 <= qy < 2048 and -8_000_000 <= qz <= 8_000_000):
                raise EvidenceRejected("quart超过安全坐标范围")
            yield current, (qx, qy, qz), biome


def choose_regions(regions):
    if len(regions) < 5:
        raise EvidenceRejected("不足5个可见宏区域，无法做跨地区验证")
    chosen = [min(regions)]
    while len(chosen) < min(MAX_SELECTED_REGIONS, len(regions)):
        eligible = [r for r in regions if r not in chosen and all(
            max(abs(r[0]-v[0]), abs(r[1]-v[1])) >= 3 for v in chosen)]
        if not eligible:
            break
        # 完全依赖坐标选择，避免按目标群系挑选好看的留出点。
        next_region = max(eligible, key=lambda r: (
            min((r[0]-v[0])**2 + (r[1]-v[1])**2 for v in chosen),
            -r[0], -r[1]))
        chosen.append(next_region)
    if len(chosen) < 5:
        raise EvidenceRejected("没有3FIT+2HOLDOUT且保守相隔1024格的宏区域")
    return chosen


def scan_coverage(dataset, version):
    regions = set()
    observed = set()
    namespaces = Counter()
    total = 0
    scope = None
    for token, (qx, qy, qz), biome in iterate_p1(dataset):
        if scope is None:
            scope = token
        region = (qx // 128, qz // 128)
        if region not in regions:
            if len(regions) >= MAX_REGIONS:
                raise EvidenceRejected("宏区域超过16384，需要本地分段研究")
            regions.add(region)
        observed.add(biome)
        if len(observed) > 4096:
            raise EvidenceRejected("群系ID种类超出安全容量")
        namespaces[biome.split(":", 1)[0]] += 1
        total += 1
    if scope is None or scope[1] != version or scope[2] != "minecraft:overworld":
        raise EvidenceRejected("输入不是目标游戏版本真实主世界")
    return scope, choose_regions(regions), total, len(observed), len(namespaces)


def _rank(point):
    return int.from_bytes(hashlib.blake2b(
        struct.pack(">iii", *point), digest_size=8).digest(), "big")


def sample_regions(dataset, chosen):
    selected = set(chosen)
    heaps = {r: [] for r in chosen}
    for _, point, biome in iterate_p1(dataset):
        region = (point[0] // 128, point[2] // 128)
        if region not in selected:
            continue
        heap = heaps[region]
        priority = _rank(point)
        item = (-priority, point, biome)
        if len(heap) < MAX_PER_REGION:
            heapq.heappush(heap, item)
        elif item > heap[0]:
            heapq.heapreplace(heap, item)
    return {key: sorted((p, biome) for _, p, biome in heap)
            for key, heap in heaps.items()}


def encode(scope, region_order, region_points):
    fingerprint, version, dimension = scope
    count = len(region_order)
    fit_count = max(3, min(count-2, (count*2+2)//3))
    rows = [
        MAGIC, "worldFingerprint=" + fingerprint, "gameVersion=" + version,
        "dimension=" + dimension, "generatorProfile=worldseedlab:unknown_worldgen",
        "provenance=CLIENT_OBSERVED",
        "sampling=AUTOMATIC_FULL_CHUNK_OBSERVATION",
        "quart_x,quart_y,quart_z,biome_id,split",
    ]
    for index, region in enumerate(region_order):
        role = "FIT" if index < fit_count else "HOLDOUT"
        for (x, y, z), biome in region_points[region]:
            rows.append(f"{x},{y},{z},{biome},{role}")
    return "\n".join(rows) + "\n", fit_count


def main(argv=None):
    parser = argparse.ArgumentParser(description="P4.3 真实客户端气候指纹空间留出研究")
    parser.add_argument("--input", type=Path, required=True)
    parser.add_argument("--minecraft", choices=sorted(ALLOWED_VERSIONS), required=True)
    parser.add_argument("--seeds", help="1..8个用户显式完整64位候选(非自动破解)")
    parser.add_argument("--assume-vanilla-noise-router", action="store_true",
                        help="明确研究原版Overworld气候噪声作为未经验证的假设")
    args = parser.parse_args(argv)
    try:
        if (args.seeds is None) != (not args.assume_vanilla_noise_router):
            raise EvidenceRejected("必须同时明确提供完整Seed候选和未经验证的原版噪声假设")
        if args.seeds is not None and (
                len(args.seeds) > 190 or not 1 <= len(args.seeds.split(",")) <= 8
                or any(not re.fullmatch(r"(?:0x[0-9a-fA-F]{1,16}|-?\d{1,20})",
                                        part.strip()) for part in args.seeds.split(","))):
            raise EvidenceRejected("候选Seed列表超出安全范围")
        source = Dataset(args.input)
        try:
            scope, chosen, total, biome_count, ns_count = scan_coverage(
                source, args.minecraft)
            print("WorldSeedLab P4.3 CLIENT_OBSERVED 气候可辨识性")
            print(f"P1行={total}；群系种类={biome_count}；命名空间={ns_count}；"
                  f"空间分离宏区域={len(chosen)}")
            if args.seeds is None:
                print("GENERATOR=UNKNOWN FULL64=UNKNOWN_NO_SEED_HYPOTHESES")
                print("仅凭群系和地形注册ID无法推出NoiseRouter、完整Seed或低48位")
                return 0
            result = sample_regions(source, chosen)
        finally:
            source.close()
        sample_csv, fit_count = encode(scope, chosen, result)
        if not shutil.which("gradle"):
            raise EvidenceRejected("没有可运行的Java25/Gradle9.6.1本地研究环境")
        with tempfile.TemporaryDirectory(prefix="wsl-p43-private-") as temp:
            path = Path(temp)/"client-spatial-quart.csv"
            path.write_text(sample_csv, encoding="utf-8")
            cmd = ["gradle", "--no-daemon", "--console=plain",
                   "-PmcVersion="+args.minecraft,
                   "-Pp43Samples="+str(path),
                   "-Pp43Seeds="+args.seeds,
                   "p43ClimateFingerprint"]
            print(f"FIT地区={fit_count} HOLDOUT地区={len(chosen)-fit_count};"
                  " forwardNoise=EXPLICIT_UNVERIFIED；只运行用户指定的候选")
            return subprocess.run(cmd, cwd=Path(__file__).resolve().parent.parent,
                                  timeout=600, check=False).returncode
    except (EvidenceRejected, OSError, ValueError, TypeError, subprocess.SubprocessError):
        print("P4.3_FAIL_CLOSED (no raw path/coordinates/HMAC disclosed)",
              file=sys.stderr)
        return 2


if __name__ == "__main__":
    raise SystemExit(main())
