#!/usr/bin/env python3
"""P4.0: CLIENT_OBSERVED quart -> 真实Minecraft MultiNoise候选正反审计。

只读观测ZIP或单一原始目录；没有独立气候模型时只输出UNKNOWN，不猜Seed。
原始坐标仅在本机临时P3.2样本CSV中使用，永不输出到控制台。
"""
from __future__ import annotations

import argparse
from collections import Counter
import json
from pathlib import Path
import re
import subprocess
import sys
import tempfile

from research_p37_custom_worldgen import (Dataset, EvidenceRejected, FINGERPRINT,
                                          DIMENSION, ALLOWED_VERSIONS, MAX_RECORDS)

MIN_DISTANCE_SQ = 256 ** 2  # quart坐标中1024方块
MAX_BUCKETS = 16000
ID = re.compile(r"^[a-z0-9_.-]+:[a-z0-9_./-]+$")


def sample_evidence(dataset: Dataset, version: str):
    """完整顺序回放P1，网格内保留原版/非原版各一个代表并远距留出。"""
    candidates = {}
    namespaces = Counter()
    biome_ids = set()
    scope = None
    total = 0
    with dataset.opened("evidence.jsonl") as stream:
        if stream is None:
            raise EvidenceRejected("缺少P1真实群系证据")
        for row_number, raw in enumerate(stream, 1):
            if row_number > MAX_RECORDS or len(raw) > 8192:
                raise EvidenceRejected("P1观测超出单次流式审计安全上限")
            try:
                row = json.loads(raw)
            except (ValueError, UnicodeError) as exc:
                raise EvidenceRejected("P1原始JSONL损坏") from exc
            if not isinstance(row, dict) or row.get("schema") != 1:
                raise EvidenceRejected("P1证据schema非法")
            fp, dim, ver = (row.get("world_fingerprint"),
                            row.get("dimension"), row.get("client_version"))
            if not isinstance(fp, str) or not FINGERPRINT.fullmatch(fp) \
                    or not isinstance(dim, str) or not DIMENSION.fullmatch(dim) \
                    or ver not in ALLOWED_VERSIONS:
                raise EvidenceRejected("P1世界作用域非法")
            observed_scope = (fp, dim, ver)
            if scope is None:
                scope = observed_scope
            if observed_scope != scope or (dataset.expected_fp is not None
                                           and fp != dataset.expected_fp):
                raise EvidenceRejected("P1跨世界/维度/版本混用")
            if row.get("kind") != "BIOME_OBSERVATION":
                continue
            if row.get("origin") != "CLIENT_OBSERVATION":
                raise EvidenceRejected("P1原始群系不是普通客户端实际观察")
            pos = row.get("position")
            if not isinstance(pos, dict) or any(type(pos.get(k)) is not int
                                                 for k in ("x", "y", "z")):
                raise EvidenceRejected("P1群系坐标不存在或类型非法")
            x, y, z = pos["x"], pos["y"], pos["z"]
            if (x % 4, y % 4, z % 4) != (2, 2, 2):
                raise EvidenceRejected("P1不是可验证的真实quart中心")
            biome = row.get("observed_identifier")
            if not isinstance(biome, str) or not ID.fullmatch(biome):
                raise EvidenceRejected("P1群系注册表ID非法")
            biome_ids.add(biome)
            if len(biome_ids) > 4096:
                raise EvidenceRejected("群系ID数量超过安全上限")
            namespaces[biome.split(":", 1)[0]] += 1
            total += 1
            custom = not biome.startswith("minecraft:")
            bucket = (x // 512, z // 512, custom)
            if bucket not in candidates:
                if len(candidates) >= MAX_BUCKETS:
                    raise EvidenceRejected("跨区域观测超过有界缓存，需拆分输入")
                candidates[bucket] = (x // 4, y // 4, z // 4, biome)
    if scope is None or scope[2] != version or scope[1] != "minecraft:overworld":
        raise EvidenceRejected("证据非目标Minecraft版本主世界，拒绝混用")
    if total < 5:
        raise EvidenceRejected("实际群系点不足5处")
    pool = list(candidates.values())
    selected = []
    # 先覆盖自定义/原版两类，再最大化跨区域距离；保留3 FIT和2 HOLDOUT。
    for want_custom in (True, False, True, False, True):
        choices = [p for p in pool if (not p[3].startswith("minecraft:")) == want_custom
                   and all((p[0]-q[0])**2 + (p[2]-q[2])**2 >= MIN_DISTANCE_SQ
                           for q in selected)]
        if not choices:
            choices = [p for p in pool if all(
                (p[0]-q[0])**2 + (p[2]-q[2])**2 >= MIN_DISTANCE_SQ for q in selected)]
        if not choices:
            raise EvidenceRejected("跨区域独立quart不足5处，不能冒充3FIT/2HOLDOUT")
        chosen = max(choices, key=lambda p: (
            min(((p[0]-q[0])**2 + (p[2]-q[2])**2 for q in selected),
                default=0), -abs(p[0]), -abs(p[2]), p[3]))
        selected.append(chosen)
        pool.remove(chosen)
    return scope, selected, total, biome_ids, namespaces


def encoded_quart(scope, points):
    fp, dim, version = scope
    data = ["WSL_P32_CLIENT_QUART_V1", "worldFingerprint=" + fp,
            "gameVersion=" + version, "dimension=" + dim,
            "generatorProfile=worldseedlab:unknown_worldgen",
            "provenance=CLIENT_OBSERVED",
            "sampling=AUTOMATIC_FULL_CHUNK_OBSERVATION",
            "quart_x,quart_y,quart_z,biome_id,split"]
    for index, (qx, qy, qz, biome) in enumerate(points):
        data.append(f"{qx},{qy},{qz},{biome},{'FIT' if index < 3 else 'HOLDOUT'}")
    return "\n".join(data) + "\n"


def main(argv=None):
    parser = argparse.ArgumentParser(description="WorldSeedLab P4.0 自定义气候模型离线检验")
    parser.add_argument("--input", type=Path, required=True)
    parser.add_argument("--minecraft", choices=sorted(ALLOWED_VERSIONS), required=True)
    parser.add_argument("--model", type=Path, help="用户独立获取并审核的多噪声参数JSON")
    parser.add_argument("--seeds", help="1..32个显式完整64位候选(十进制或0x十六进制)")
    args = parser.parse_args(argv)
    try:
        if (args.model is None) != (args.seeds is None):
            raise EvidenceRejected("需要同时提供--model和--seeds，不能凭空反推Seed")
        data = Dataset(args.input)
        try:
            scope, points, total, observed, namespaces = sample_evidence(data, args.minecraft)
        finally:
            data.close()
        print("WorldSeedLab P4.0 CLIENT_OBSERVED 自定义生成器候选准备")
        print(f"P1记录={total}；群系种类={len(observed)}；命名空间数={len(namespaces)}；"
              "独立quart=5（3FIT+2HOLDOUT）")
        if args.model is None:
            print("MODEL=UNKNOWN LOW48=NOT_DERIVED FULL64=UNKNOWN_CUSTOM_WORLDGEN")
            print("缺口：没有来自目标世界实际生成配置的独立多噪声参数与噪声路由器验证")
            return 0
        if not args.model.is_file() or args.model.is_symlink() or args.model.stat().st_size > 1024*1024:
            raise EvidenceRejected("模型文件不存在、是链接或超过1MiB")
        try:
            config = json.loads(args.model.read_text(encoding="utf-8"))
            if not isinstance(config, dict) or config.get("schema") != 1 \
                    or config.get("provenance") != "USER_SUPPLIED_UNVERIFIED" \
                    or config.get("climate_sampler") != "minecraft:vanilla_overworld_noise_router":
                raise ValueError("未支持的生成器前向模型")
            entries = config.get("entries")
            if not isinstance(entries, list) or not 1 <= len(entries) <= 4096:
                raise ValueError("无效的参数表")
            allowed = {e["biome"] for e in entries}
        except (ValueError, KeyError, TypeError) as exc:
            raise EvidenceRejected("模型参数格式不合格") from exc
        if not observed.issubset(allowed):
            print(f"MODEL_REGISTRY_CONFLICT missing_biome_ids={len(observed-allowed)}")
            print("FULL64=UNKNOWN_CUSTOM_WORLDGEN；不能将注册表冲突解释为世界Seed不存在")
            return 0
        with tempfile.TemporaryDirectory(prefix="wsl-private-climate-") as td:
            csv = Path(td) / "client-quart.csv"
            csv.write_text(encoded_quart(scope, points), encoding="utf-8")
            repo = Path(__file__).resolve().parent.parent
            command = ["gradle", "--no-daemon", f"-PmcVersion={args.minecraft}",
                       "p40CustomClimateAudit", f"-Pp40Quart={csv}",
                       f"-Pp40Model={args.model.resolve()}",
                       f"-Pp40Candidates={args.seeds}"]
            subprocess.run(command, cwd=repo, check=True, timeout=600)
        return 0
    except (EvidenceRejected, OSError, subprocess.SubprocessError) as exc:
        print("P4.0 失败关闭：" + type(exc).__name__ + "（不输出原始私服坐标/路径）",
              file=sys.stderr)
        return 2


if __name__ == "__main__":
    raise SystemExit(main())
