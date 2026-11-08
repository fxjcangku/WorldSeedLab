#!/usr/bin/env python3
"""P3.1 汇总器自身的合成反例；此测试不代表实际执行了世界生成搜索。"""
from __future__ import annotations

import tempfile
import unittest
import hashlib
import json
from pathlib import Path

from verify_p31_high16_shards import aggregate, DOMAIN, VERSIONS


def write_fixture(root: Path, fixture_root: Path, shards: int = 4) -> Path:
    """仅合成聚合器输入；版本内容刻意不同，不能当成真实Minecraft真值。"""
    fixture_root.mkdir(parents=True, exist_ok=True)
    fixtures = {}
    for version_index, version in enumerate(VERSIONS):
        csv_name = f"vanilla_{version}_seed2731_quart.csv"
        csv = ("quart_x,quart_y,quart_z,biome_id\n" + "".join(
            f"{point * 512 + version_index},18,2,minecraft:desert\n" for point in range(5)))
        contents = csv.encode("utf-8")
        (fixture_root / csv_name).write_bytes(contents)
        sha = hashlib.sha256(contents).hexdigest()
        fixtures[version] = {
            "file": csv_name, "sha256": sha, "known_seed": "2731", "sample_count": 5,
            "provenance": "REAL_MINECRAFT_FULL_CHUNK",
            "source_run_url": "https://github.com/fxjcangku/WorldSeedLab/actions/runs/"
                              + str(1000 + version_index),
        }
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
                "inputSha256": sha,
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
    manifest = fixture_root / "fixture-manifest.json"
    manifest.write_text(json.dumps({"schema": 1, "fixtures": fixtures}), encoding="utf-8")
    return manifest


class Full64Audit(unittest.TestCase):
    def setUp(self):
        self.folder = tempfile.TemporaryDirectory(prefix="p31-audit-synthetic-")
        self.root = Path(self.folder.name) / "reports"
        self.fixture_root = Path(self.folder.name) / "synthetic-fixtures"
        self.manifest = write_fixture(self.root, self.fixture_root)

    def tearDown(self):
        self.folder.cleanup()

    def aggregate(self, shards=4):
        return aggregate(self.root, shards, self.manifest)

    def patch(self, old: str, new: str, version="26.2"):
        file = self.root / version / "shard-02.properties"
        data = file.read_text(encoding="utf-8")
        self.assertIn(old, data)
        file.write_text(data.replace(old, new), encoding="utf-8")

    def test_complete_synthetic_coverage(self):
        report = self.aggregate()
        self.assertEqual(set(report["versions"]), {"26.1.2", "26.2", "26.3"})
        for version in VERSIONS:
            self.assertEqual(report["versions"][version]["evaluatedHigh16"], 65536)
            self.assertTrue(report["versions"][version]["uniqueInSpecifiedModel"])

    def test_all_48_synthetic_shards_required_for_three_version_job(self):
        write_fixture(self.root, self.fixture_root, 16)
        report = self.aggregate(16)
        self.assertEqual(sum(item["shards"] for item in report["versions"].values()), 48)
        (self.root / "26.3" / "shard-15.properties").unlink()
        with self.assertRaises(ValueError):
            self.aggregate(16)

    def test_independent_version_sources_may_have_identical_csv_bytes(self):
        # 真实26.3独立生成也可能恰好与旧版群系一致；身份来自分版本来源，
        # 不能把“哈希必须不同”当作独立生成的证明。
        data = json.loads(self.manifest.read_text(encoding="utf-8"))
        contents = (self.fixture_root / "vanilla_26.2_seed2731_quart.csv").read_bytes()
        shared_sha = hashlib.sha256(contents).hexdigest()
        for version in VERSIONS:
            entry = data["fixtures"][version]
            previous_sha = entry["sha256"]
            (self.fixture_root / entry["file"]).write_bytes(contents)
            entry["sha256"] = shared_sha
            for report in (self.root / version).glob("shard-*.properties"):
                report.write_text(report.read_text(encoding="utf-8").replace(
                    "inputSha256=" + previous_sha, "inputSha256=" + shared_sha), encoding="utf-8")
        self.manifest.write_text(json.dumps(data), encoding="utf-8")
        report = self.aggregate()
        self.assertEqual({item["evidenceSha256"] for item in report["versions"].values()}, {shared_sha})
        self.assertEqual(len({item["evidenceSourceRun"] for item in report["versions"].values()}), 3)

    def test_missing_shard_rejected(self):
        (self.root / "26.1.2" / "shard-01.properties").unlink()
        with self.assertRaises(ValueError):
            self.aggregate()

    def test_partial_search_rejected(self):
        self.patch("evaluations=16384", "evaluations=16383")
        with self.assertRaises(ValueError):
            self.aggregate()

    def test_evidence_hash_mismatch_rejected(self):
        fixture = json.loads(self.manifest.read_text(encoding="utf-8"))["fixtures"]["26.2"]
        self.patch("inputSha256=" + fixture["sha256"], "inputSha256=" + "0" * 64)
        with self.assertRaises(ValueError):
            self.aggregate()

    def test_new_version_cannot_reuse_another_versions_fixture_identity(self):
        fixtures = json.loads(self.manifest.read_text(encoding="utf-8"))["fixtures"]
        self.patch("inputSha256=" + fixtures["26.3"]["sha256"],
                   "inputSha256=" + fixtures["26.2"]["sha256"], version="26.3")
        with self.assertRaises(ValueError):
            self.aggregate()

    def test_cross_version_shard_label_rejected(self):
        self.patch("gameVersion=26.3", "gameVersion=26.2", version="26.3")
        with self.assertRaises(ValueError):
            self.aggregate()

    def test_missing_new_version_manifest_entry_has_no_fallback(self):
        data = json.loads(self.manifest.read_text(encoding="utf-8"))
        del data["fixtures"]["26.3"]
        self.manifest.write_text(json.dumps(data), encoding="utf-8")
        with self.assertRaisesRegex(ValueError, "26.3"):
            self.aggregate()

    def test_modified_real_input_bytes_rejected_before_aggregation(self):
        fixture = self.fixture_root / "vanilla_26.3_seed2731_quart.csv"
        fixture.write_text(fixture.read_text(encoding="utf-8") + "\n", encoding="utf-8")
        with self.assertRaisesRegex(ValueError, "SHA256"):
            self.aggregate()

    def test_manifest_cannot_name_another_versions_file(self):
        data = json.loads(self.manifest.read_text(encoding="utf-8"))
        data["fixtures"]["26.3"]["file"] = "vanilla_26.2_seed2731_quart.csv"
        self.manifest.write_text(json.dumps(data), encoding="utf-8")
        with self.assertRaisesRegex(ValueError, "26.3"):
            self.aggregate()

    def test_duplicate_range_rejected(self):
        self.patch("shardIndex=2", "shardIndex=1")
        with self.assertRaises(ValueError):
            self.aggregate()

    def test_wrong_completion_rejected(self):
        self.patch("completion=REQUESTED_WINDOW_COMPLETE", "completion=TIME_BUDGET")
        with self.assertRaises(ValueError):
            self.aggregate()

    def test_extra_mixed_run_rejected(self):
        (self.root / "stale.properties").write_text("stale=true\n", encoding="utf-8")
        with self.assertRaises(ValueError):
            self.aggregate()


if __name__ == "__main__":
    unittest.main()
