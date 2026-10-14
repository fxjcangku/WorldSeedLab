#!/usr/bin/env python3
"""P3.9离线真实观测导入门槛测试，模型证明由独立Java/Z3/Fabric GUI提供。"""
import io
import json
from pathlib import Path
import tempfile
import unittest
from zipfile import ZipFile
from recover_seed import inspect, main, _collect_quarts, EvidenceError

WORLD = "a" * 64

def row(x, z, biome="minecraft:desert", world=WORLD):
    return {
        "schema": 1,
        "kind": "BIOME_OBSERVATION",
        "origin": "CLIENT_OBSERVATION",
        "world_fingerprint": world,
        "client_version": "26.2",
        "dimension": "minecraft:overworld",
        "observed_identifier": biome,
        "position": {"x": x, "y": 66, "z": z},
    }


def sample_stream(rows):
    return io.BytesIO("".join(json.dumps(s, separators=(",", ":")) + "\n"
                               for s in rows).encode())


class SeedRecoveryPreflightTest(unittest.TestCase):
    def test_realistic_empty_observation_zip_stops_without_guessing(self):
        with tempfile.TemporaryDirectory() as td:
            path = Path(td) / "observations.zip"
            prefix = f"worlds/{WORLD}/dimensions/" + "b"*24 + "/"
            with ZipFile(path, "w") as archive:
                archive.writestr(prefix+"terrain.jsonl", "{}\n")
                archive.writestr(prefix+"evidence.jsonl", "{}\n")
            found, biome = inspect(path)
            self.assertEqual(len(found), 0)
            self.assertEqual(len(biome), 1)
            self.assertEqual(main(["--input", str(path), "--minecraft", "26.2",
                                   "--dry-run"]), 2)

    def test_dry_run_unverified_presence_does_not_claim_z3_recovery(self):
        with tempfile.TemporaryDirectory() as td:
            path = Path(td) / "controlled-only.zip"
            with ZipFile(path, "w") as archive:
                for i in range(3):
                    archive.writestr(f"client-footprint-{i}.properties", "NOT_YET_VERIFIED\n")
            self.assertEqual(main(["--input", str(path), "--minecraft", "26.2",
                                   "--dry-run"]), 0)
            # This tests only the preflight count; Java validation rejects invalid
            # raster bytes before any SMT constraint is constructed.

    def test_real_auto_quart_provenance_and_spatial_separation(self):
        rows = [row(i*2048+2, 2) for i in range(5)]
        csv, count = _collect_quarts(sample_stream(rows), "26.2")
        self.assertEqual(count, 5)
        self.assertIn("sampling=AUTOMATIC_FULL_CHUNK_OBSERVATION", csv)
        self.assertNotIn("sampling=MANUAL_ALREADY_LOADED_FULL", csv)
        self.assertIn("generatorProfile=worldseedlab:unknown_worldgen", csv)
        self.assertEqual(csv.count(",FIT\n"), 3)
        self.assertEqual(csv.count(",HOLDOUT\n"), 2)
        self.assertNotIn("CONTROL_WORLD_OBSERVED", csv)

    def test_custom_biome_rejects_vanilla_high16(self):
        rows = [row(i*2048+2, 2) for i in range(5)]
        rows.append(row(12290, 2, "terralith:mirage_isles"))
        with self.assertRaisesRegex(EvidenceError, "非minecraft"):
            _collect_quarts(sample_stream(rows), "26.2")

    def test_cross_world_p1_observations_rejected(self):
        rows = [row(2, 2), row(2050, 2, world="b"*64)]
        with self.assertRaisesRegex(EvidenceError, "跨世界"):
            _collect_quarts(sample_stream(rows), "26.2")

    def test_not_enough_independent_quart_points(self):
        with self.assertRaisesRegex(EvidenceError, "5条"):
            _collect_quarts(sample_stream([row(i*4+2, 2) for i in range(5)]), "26.2")


if __name__ == "__main__":
    unittest.main()
