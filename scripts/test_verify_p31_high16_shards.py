#!/usr/bin/env python3
"""P3.1 汇总器自身的合成反例；此测试不代表实际执行了世界生成搜索。"""
from __future__ import annotations

import tempfile
import unittest
from pathlib import Path

from verify_p31_high16_shards import aggregate, DOMAIN, INPUT_SHA, VERSIONS


def write_fixture(root: Path, shards: int = 4) -> None:
    for version in VERSIONS:
        out = root / version
        out.mkdir(parents=True, exist_ok=True)
        for i in range(shards):
            start = DOMAIN * i // shards
            end = DOMAIN * (i + 1) // shards
            props = {
                "schema": "1",
                "kind": "REAL_VANILLA_FULL64_HIGH16_EXHAUSTIVE_SHARD",
                "gameVersion": version,
                "knownLow48": "2731",
                "biomeSource": "minecraft:vanilla_overworld",
                "inputSha256": INPUT_SHA,
                "biomeSamples": "5",
                "fitSamples": "3",
                "holdoutSamples": "2",
                "shardIndex": str(i),
                "shardCount": str(shards),
                "fromIndex": str(start),
                "endExclusive": str(end),
                "domainSize": str(DOMAIN),
                "completion": "REQUESTED_WINDOW_COMPLETE",
                "evaluations": str(end-start),
                "nextIndex": str(end),
                "holdoutRejected": "0",
                "elapsedMillis": "1",
                "candidateCount": "1" if i == 0 else "0",
            }
            if i == 0:
                props.update({
                    "candidate.0.hex16": "0000000000000aab",
                    "candidate.0.fit": "3",
                    "candidate.0.holdout": "2",
                })
            (out / f"shard-{i:02d}.properties").write_text(
                "".join(f"{key}={value}\n" for key,value in props.items()),
                encoding="utf-8")


class Full64Audit(unittest.TestCase):
    def setUp(self):
        self.folder = tempfile.TemporaryDirectory(prefix="p31-audit-synthetic-")
        self.root = Path(self.folder.name)
        write_fixture(self.root)

    def tearDown(self):
        self.folder.cleanup()

    def patch(self, old: str, new: str):
        file = self.root / "26.2" / "shard-02.properties"
        data = file.read_text(encoding="utf-8")
        self.assertIn(old, data)
        file.write_text(data.replace(old, new), encoding="utf-8")

    def test_complete_synthetic_coverage(self):
        report = aggregate(self.root, 4)
        for version in VERSIONS:
            self.assertEqual(report["versions"][version]["evaluatedHigh16"], 65536)
            self.assertTrue(report["versions"][version]["uniqueInSpecifiedModel"])

    def test_missing_shard_rejected(self):
        (self.root / "26.1.2" / "shard-01.properties").unlink()
        with self.assertRaises(ValueError):
            aggregate(self.root, 4)

    def test_partial_search_rejected(self):
        self.patch("evaluations=16384", "evaluations=16383")
        with self.assertRaises(ValueError):
            aggregate(self.root, 4)

    def test_evidence_hash_mismatch_rejected(self):
        self.patch("inputSha256=" + INPUT_SHA, "inputSha256=" + "0" * 64)
        with self.assertRaises(ValueError):
            aggregate(self.root, 4)

    def test_duplicate_range_rejected(self):
        self.patch("shardIndex=2", "shardIndex=1")
        with self.assertRaises(ValueError):
            aggregate(self.root, 4)

    def test_wrong_completion_rejected(self):
        self.patch("completion=REQUESTED_WINDOW_COMPLETE", "completion=TIME_BUDGET")
        with self.assertRaises(ValueError):
            aggregate(self.root, 4)

    def test_extra_mixed_run_rejected(self):
        (self.root / "stale.properties").write_text("stale=true\n", encoding="utf-8")
        with self.assertRaises(ValueError):
            aggregate(self.root, 4)


if __name__ == "__main__":
    unittest.main()
