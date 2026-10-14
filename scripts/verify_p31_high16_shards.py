#!/usr/bin/env python3
"""汇总 P3.1 真正执行原版 MultiNoise 的高16位搜索分片。

只在所有独立 CI 任务成功并且每条分片清单精确覆盖原定窗口后，
才能宣称受控已知低48位的65536种高16位检查完成。
"""
from __future__ import annotations

import argparse
import json
from pathlib import Path
import re

VERSIONS = ("26.1.2", "26.2")
DOMAIN = 65536
LOW48 = 2731
INPUT_SHA = "73b8e9915d422b9632c4d411d0dcd6f0f2150e920d1a1d18630b07fb5e4e9602"
FULL64 = 2731
HEX16 = re.compile(r"[0-9a-f]{16}\Z")


def read_properties(path: Path) -> dict[str, str]:
    if not path.is_file() or path.is_symlink() or path.stat().st_size > 65536:
        raise ValueError("不存在或不安全的分片清单：" + str(path))
    values: dict[str, str] = {}
    for line in path.read_text(encoding="utf-8").splitlines():
        if not line or line.startswith("#"):
            continue
        if "=" not in line:
            raise ValueError("清单格式损坏：" + str(path))
        key, value = line.split("=", 1)
        if key in values:
            raise ValueError("重复分片属性：" + key)
        values[key] = value
    return values


def require_int(report: dict[str, str], name: str) -> int:
    raw = report.get(name)
    if raw is None or not re.fullmatch(r"\d+", raw):
        raise ValueError("分片属性非法：" + name)
    return int(raw)


def aggregate(root: Path, shard_count: int) -> dict:
    if shard_count < 1 or shard_count > 256:
        raise ValueError("分片数量必须位于1..256")
    if not root.is_dir() or root.is_symlink():
        raise ValueError("分片产物文件夹不存在")
    expected_paths: set[Path] = set()
    versions: dict[str, dict] = {}

    for version in VERSIONS:
        found: dict[int, dict] = {}
        covered = 0
        seeds: set[int] = set()
        total_candidates = 0
        elapsed_ms = 0
        for index in range(shard_count):
            file = root / version / f"shard-{index:02d}.properties"
            expected_paths.add(file.relative_to(root))
            p = read_properties(file)
            expect = {
                "schema": "1",
                "kind": "REAL_VANILLA_FULL64_HIGH16_EXHAUSTIVE_SHARD",
                "gameVersion": version,
                "knownLow48": str(LOW48),
                "biomeSource": "minecraft:vanilla_overworld",
                "inputSha256": INPUT_SHA,
                "biomeSamples": "5",
                "fitSamples": "3",
                "holdoutSamples": "2",
                "completion": "REQUESTED_WINDOW_COMPLETE",
            }
            for key, value in expect.items():
                if p.get(key) != value:
                    raise ValueError(f"{version}/{index}: {key}不匹配: {p.get(key)}")
            start, end = DOMAIN * index // shard_count, DOMAIN * (index + 1) // shard_count
            for key, expected in {
                "shardIndex": index,
                "shardCount": shard_count,
                "fromIndex": start,
                "endExclusive": end,
                "domainSize": DOMAIN,
                "nextIndex": end,
                "evaluations": end - start,
            }.items():
                if require_int(p, key) != expected:
                    raise ValueError(f"{version}/{index}: {key}不连续或未真正完成")
            count = require_int(p, "candidateCount")
            if count > 128:
                raise ValueError("候选数量超过单轮上限，清单不可信")
            for i in range(count):
                hex_seed = p.get(f"candidate.{i}.hex16")
                if not hex_seed or not HEX16.fullmatch(hex_seed):
                    raise ValueError("候选64位十六进制非法")
                candidate = int(hex_seed, 16)
                if candidate & ((1 << 48) - 1) != LOW48:
                    raise ValueError("输出候选的low48与输入结构证据不一致")
                candidate_index = candidate >> 48
                if not start <= candidate_index < end or candidate in seeds:
                    raise ValueError("跨分片重复或越界候选")
                if require_int(p, f"candidate.{i}.fit") != 3 or require_int(
                        p, f"candidate.{i}.holdout") != 2:
                    raise ValueError("候选未通过群系独立FIT/HOLDOUT")
                seeds.add(candidate)
            total_candidates += count
            covered += end - start
            elapsed_ms += require_int(p, "elapsedMillis")
            found[index] = {"from": start, "to": end,
                            "evaluations": end - start, "candidates": count}
        if covered != DOMAIN or len(found) != shard_count or FULL64 not in seeds:
            raise ValueError("分片未覆盖全部高16位或受控已知完整64位值丢失")
        ordered_seeds = sorted(seeds)
        versions[version] = {
            "status": "FULL_HIGH16_DOMAIN_CHECKED",
            "scope": "controlled_seed_2731_vanilla_overworld_only",
            "evaluatedHigh16": covered,
            "shards": shard_count,
            "candidateCount": total_candidates,
            "modelCandidatesHex16": [f"{seed:016x}" for seed in ordered_seeds],
            "uniqueInSpecifiedModel": len(ordered_seeds) == 1,
            "aggregateWorkerMillis": elapsed_ms,
            "evidenceSha256": INPUT_SHA,
            "fitSamples": 3,
            "holdoutSamples": 2,
        }

    actual_paths = {p.relative_to(root) for p in root.rglob("*") if p.is_file()}
    if actual_paths != expected_paths:
        raise ValueError("存在多余/缺失分片清单，拒绝把多个运行结果混合")
    return {
        "schema": 1,
        "experiment": "P3.1_REAL_VANILLA_FULL64_ALL_HIGH16",
        "evidenceScope": "public controlled Minecraft FULL-chunk biome fixture, NOT remote client",
        "limitations": ("One known low48 candidate. Vanilla biome model assumes unchanged worldgen "
                        "and five original observations. Neither proves custom-server seed "
                        "nor reproduces non-vanilla ore generation."),
        "versions": versions,
    }


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--root", required=True, type=Path)
    parser.add_argument("--shards", required=True, type=int)
    parser.add_argument("--output", type=Path, default=Path("build/p31-full64-summary.json"))
    args = parser.parse_args()
    result = aggregate(args.root, args.shards)
    args.output.parent.mkdir(parents=True, exist_ok=True)
    tmp = args.output.with_suffix(".json.tmp")
    tmp.write_text(json.dumps(result, ensure_ascii=False, indent=2) + "\n",
                   encoding="utf-8")
    tmp.replace(args.output)
    for version, report in result["versions"].items():
        print("WorldSeedLab P3.1 FULL HIGH16 verified", version,
              "evaluated", report["evaluatedHigh16"],
              "candidateCount", report["candidateCount"],
              "uniqueInSpecifiedModel", report["uniqueInSpecifiedModel"],
              "candidates", report["modelCandidatesHex16"])
    print("注意：全域仅指受控已知low48+明确原版生成器+五个真实quart样本；"
          "未验证未知服务器64位Seed、未知Salt、客户端自动轮廓或矿点。")


if __name__ == "__main__":
    main()
