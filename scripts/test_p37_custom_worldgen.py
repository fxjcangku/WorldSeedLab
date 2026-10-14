#!/usr/bin/env python3
"""P3.7 独立模型未知/作用域/隐私/正反例；仅合成数据、不引入私服原始证据。"""
from __future__ import annotations

import contextlib
import io
import json
from pathlib import Path
import tempfile
import unittest
from zipfile import ZipFile

from research_p37_custom_worldgen import Dataset, EvidenceRejected, P37Audit, main


HASH = "a" * 64
OTHER_HASH = "b" * 64
DIMENSION_HASH = "c" * 24


def scope(hash_value=HASH, version="26.2"):
    return {
        "world_fingerprint": hash_value,
        "dimension": "minecraft:overworld",
        "client_version": version,
    }


def biome(cx, cz, biome_id="terralith:mirage_isles", hash_value=HASH, origin="CLIENT_OBSERVATION"):
    return {
        "schema": 1, **scope(hash_value),
        "kind": "BIOME_OBSERVATION",
        "observed_identifier": biome_id,
        "origin": origin,
        "position": {"x": cx * 16 + 2, "y": 66, "z": cz * 16 + 2},
    }


def terrain(cx, cz, height=75, hash_value=HASH):
    return {
        "schema": 2, "type": "terrain_chunk",
        **scope(hash_value), "chunk_x": cx, "chunk_z": cz,
        "min_build_y": -64, "max_build_y_exclusive": 320,
        "sea_level": 63, "heightmap": "WORLD_SURFACE",
        "sample_grid": "4x4_quart_centers",
        "surface_y": [height + (i % 4) for i in range(16)],
    }


def detail(cx, cz, mode="STRATA"):
    return {
        "schema": 4, "type": "terrain_detail", **scope(),
        "chunk_x": cx, "chunk_z": cz,
        "provenance": "client_loaded_block_states",
        "generation_origin_verified": False,
        "mode": mode,
        "surface": [],
        "vertical": ([{"y": 45, "block": "minecraft:stone"}]
                     if mode == "STRATA" else []),
    }


def build_zip(path: Path, rows: dict, fp=HASH):
    prefix = f"worlds/{fp}/dimensions/{DIMENSION_HASH}/"
    with ZipFile(path, "w") as z:
        for name, content in rows.items():
            z.writestr(prefix + name, "".join(json.dumps(q, ensure_ascii=False) + "\n"
                                              for q in content))


def verify_zip(path, salts=()):
    data = Dataset(path)
    try:
        return P37Audit().analyze(data, salts)
    finally:
        data.close()


class P37AuditTest(unittest.TestCase):
    def setUp(self):
        self.td = tempfile.TemporaryDirectory(prefix="wsl-p37-")
        self.addCleanup(self.td.cleanup)
        self.path = Path(self.td.name) / "observations.zip"

    def controlled(self):
        chunks = [(0, 0), (96, 0), (192, 0), (288, 0), (384, 0)]
        return {
            "evidence.jsonl": [
                biome(x, z, "terralith:mirage_isles" if i % 2 == 0
                      else "minecraft:desert") for i, (x, z) in enumerate(chunks)
            ],
            "terrain.jsonl": [terrain(x, z) for x, z in chunks],
            "terrain-detail.jsonl": [detail(0, 0)],
        }

    def test_real_client_custom_evidence_is_not_unknown_seed_break(self):
        build_zip(self.path, self.controlled())
        result = verify_zip(self.path, (14357617, 14357844))
        self.assertEqual(result["sources"]["actual_client_biome_samples"], 5)
        self.assertEqual(result["sources"]["actual_client_terrain_chunks"], 5)
        self.assertEqual(result["sources"]["strata_vertical_cells"], 1)
        self.assertEqual(result["independence"]["regions_with_both_biome_and_terrain"], 5)
        self.assertEqual(result["independence"]["conservatively_spaced_1024_block_regions_up_to_5"], 5)
        self.assertTrue(result["independence"]["possible_future_structure_2fit_1holdout_regions"])
        self.assertTrue(result["independence"]["possible_future_biome_3fit_2holdout_regions"])
        self.assertFalse(result["independence"]["actual_model_fit_or_holdout_run"])
        self.assertEqual(result["biome"]["custom_namespace_count"], 1)
        self.assertEqual(result["biome"]["unmodified_vanilla_biome_model"],
                         "REGISTRY_MISMATCH_WITH_OBSERVED_CUSTOM_BIOMES")
        self.assertEqual(result["worldgen_hypotheses"]["salt_hypotheses_supplied"], 2)
        self.assertEqual(result["worldgen_hypotheses"]["salt_hypotheses_evaluated"], 0)
        self.assertEqual(result["worldgen_hypotheses"]["full64"],
                         "NOT_IDENTIFIABLE_WITHOUT_VERIFIED_GENERATOR_AND_HOLDOUT")
        serialized = json.dumps(result)
        self.assertNotIn(HASH, serialized)
        self.assertNotIn('"chunk_x"', serialized)
        self.assertNotIn('"chunk_z"', serialized)
        self.assertNotIn('"position"', serialized)

    def test_adjacent_macroregions_do_not_pass_1024_block_independence(self):
        rows = {
            "evidence.jsonl": [biome(0,0), biome(32,0), biome(64,0)],
            "terrain.jsonl": [terrain(0,0), terrain(32,0), terrain(64,0)],
        }
        build_zip(self.path, rows)
        result = verify_zip(self.path)
        self.assertEqual(result["independence"]["regions_with_both_biome_and_terrain"], 3)
        self.assertEqual(result["independence"]["conservatively_spaced_1024_block_regions_up_to_5"], 1)
        self.assertFalse(result["independence"]["possible_future_structure_2fit_1holdout_regions"])
        self.assertFalse(result["independence"]["possible_future_biome_3fit_2holdout_regions"])

    def test_vanilla_biome_names_do_not_authenticate_generator(self):
        rows = self.controlled()
        rows["evidence.jsonl"] = [biome(0, 0, "minecraft:plains")]
        build_zip(self.path, rows)
        result = verify_zip(self.path)
        self.assertEqual(result["biome"]["unmodified_vanilla_biome_model"],
                         "NOT_VERIFIED_BY_REGISTRY_ALONE")
        self.assertEqual(result["worldgen_hypotheses"]["generator"], "UNKNOWN")

    def test_wrong_world_scope_rejected(self):
        rows = self.controlled()
        rows["terrain.jsonl"][1] = terrain(32, 0, hash_value=OTHER_HASH)
        build_zip(self.path, rows)
        with self.assertRaisesRegex(EvidenceRejected, "跨世界"):
            verify_zip(self.path)

    def test_zip_folder_hash_must_match_record_scope(self):
        rows = self.controlled()
        rows["evidence.jsonl"] = [biome(0, 0, hash_value=OTHER_HASH)]
        rows["terrain.jsonl"] = [terrain(0, 0, hash_value=OTHER_HASH)]
        rows["terrain-detail.jsonl"] = []
        build_zip(self.path, rows, fp=HASH)
        with self.assertRaisesRegex(EvidenceRejected, "HMAC"):
            verify_zip(self.path)

    def test_controlled_data_cannot_claim_client_observation(self):
        rows = self.controlled()
        rows["evidence.jsonl"][0] = biome(0, 0, origin="CONTROLLED_TEST")
        build_zip(self.path, rows)
        with self.assertRaisesRegex(EvidenceRejected, "CLIENT_OBSERVATION"):
            verify_zip(self.path)

    def test_terrain_conflicts_only_mark_suspicion(self):
        rows = self.controlled()
        rows["terrain.jsonl"].append(terrain(0, 0, height=80))
        build_zip(self.path, rows)
        result = verify_zip(self.path)
        self.assertEqual(result["terrain"]["duplicate_rows"], 1)
        self.assertEqual(result["terrain"]["changed_reobservations"], 1)
        self.assertEqual(result["worldgen_hypotheses"]["epoch_history"],
                         "SUSPECTED_MIXED_OR_MUTATED_EVIDENCE")
        self.assertEqual(result["worldgen_hypotheses"]["generator"], "UNKNOWN")

    def test_zip_multiple_worlds_rejected(self):
        build_zip(self.path, self.controlled())
        with ZipFile(self.path, "a") as archive:
            archive.writestr(
                f"worlds/{OTHER_HASH}/dimensions/{DIMENSION_HASH}/evidence.jsonl",
                json.dumps(biome(2, 2, hash_value=OTHER_HASH)) + "\n")
        with self.assertRaisesRegex(EvidenceRejected, "仅包含一个"):
            Dataset(self.path)

    def test_zip_path_traversal_rejected(self):
        with ZipFile(self.path, "w") as archive:
            archive.writestr("../worlds/fake/evidence.jsonl", "{}")
        with self.assertRaisesRegex(EvidenceRejected, "不安全"):
            Dataset(self.path)

    def test_oversized_invalid_line_and_scope_fails_closed(self):
        rows = self.controlled()
        rows["terrain.jsonl"][0]["surface_y"] = [100]
        build_zip(self.path, rows)
        with self.assertRaisesRegex(EvidenceRejected, "16列"):
            verify_zip(self.path)

    def test_cli_never_overwrites_any_original_or_existing_file(self):
        build_zip(self.path, self.controlled())
        destination = Path(self.td.name) / "summary.json"
        destination.write_text("DO_NOT_OVERWRITE", encoding="utf-8")
        with contextlib.redirect_stderr(io.StringIO()):
            code = main(["--input", str(self.path), "--json-out", str(destination)])
        self.assertEqual(code, 2)
        self.assertEqual(destination.read_text(encoding="utf-8"), "DO_NOT_OVERWRITE")
        with contextlib.redirect_stderr(io.StringIO()):
            code = main(["--input", str(self.path), "--json-out", str(self.path)])
        self.assertEqual(code, 2)
        self.assertTrue(self.path.is_file())
        self.assertGreater(self.path.stat().st_size, 0)

    def test_local_cli_report_only_anonymous_statistics(self):
        build_zip(self.path, self.controlled())
        target = Path(self.td.name) / "summary.json"
        capture = io.StringIO()
        with contextlib.redirect_stdout(capture):
            result = main(["--input", str(self.path), "--json-out", str(target)])
        self.assertEqual(result, 0)
        output = capture.getvalue() + target.read_text(encoding="utf-8")
        self.assertNotIn(HASH, output)
        self.assertNotIn("127.0.0.1", output)
        self.assertNotIn('"position"', output)
        self.assertIn("生成器", output)
        self.assertIn("UNKNOWN", output)


if __name__ == "__main__":
    unittest.main()
