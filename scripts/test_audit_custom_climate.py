#!/usr/bin/env python3
"""P4.0 离线真实证据解析与安全门控的无网络正反测试。

Minecraft前向气候真值由P40CustomClimateSmokeTest的两版本FULL存档完成。
"""
import contextlib
import io
import json
from pathlib import Path
import tempfile
import unittest
import zipfile

from audit_custom_climate import encoded_quart, main, sample_evidence
from research_p37_custom_worldgen import Dataset, EvidenceRejected

HASH = "a" * 64
ROOT = "worlds/" + HASH + "/dimensions/" + "b" * 24
POINTS = [(2+4096*i, 66, 2+4096*(i % 2),
           "terralith:test_biome" if i % 2 else "minecraft:forest")
          for i in range(8)]


def row(x, y, z, biome, fingerprint=HASH):
    return dict(schema=1, id="00000000-0000-4000-8000-000000000001",
                world_fingerprint=fingerprint, dimension="minecraft:overworld",
                client_version="26.2", kind="BIOME_OBSERVATION",
                observed_identifier=biome, position=dict(x=x, y=y, z=z),
                origin="CLIENT_OBSERVATION", observed_at="2026-10-15T00:00:00Z",
                note="quart-grid;vertical-stride=8")


def build_zip(path, rows):
    with zipfile.ZipFile(path, "w", compression=zipfile.ZIP_DEFLATED) as pack:
        pack.writestr(ROOT + "/evidence.jsonl",
                      "".join(json.dumps(value, separators=(",", ":")) + "\n"
                              for value in rows))


class TestP40(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.folder = Path(self.temp.name)
        self.archive = self.folder / "observations.zip"
        build_zip(self.archive, [row(*p) for p in POINTS])

    def tearDown(self):
        self.temp.cleanup()

    def test_spatial_split_and_provenance(self):
        data = Dataset(self.archive)
        try:
            scope, selected, total, observed, namespaces = sample_evidence(data, "26.2")
        finally:
            data.close()
        self.assertEqual(total, 8)
        self.assertEqual(len(selected), 5)
        self.assertEqual(len(observed), 2)
        self.assertEqual(len(namespaces), 2)
        for i, a in enumerate(selected):
            for b in selected[i+1:]:
                self.assertGreaterEqual((a[0]-b[0])**2 + (a[2]-b[2])**2, 256**2)
        text = encoded_quart(scope, selected)
        self.assertIn("sampling=AUTOMATIC_FULL_CHUNK_OBSERVATION", text)
        self.assertEqual(text.count(",FIT"), 3)
        self.assertEqual(text.count(",HOLDOUT"), 2)

    def test_unknown_no_seed_output(self):
        output = io.StringIO()
        with contextlib.redirect_stdout(output):
            result = main(["--input", str(self.archive), "--minecraft", "26.2"])
        self.assertEqual(result, 0)
        self.assertIn("FULL64=UNKNOWN_CUSTOM_WORLDGEN", output.getvalue())
        self.assertNotIn(HASH, output.getvalue())
        self.assertNotIn("4098", output.getvalue())

    def test_registry_conflict_stops_before_candidate_search(self):
        model = self.folder / "model.json"
        model.write_text(json.dumps({
            "schema": 1, "provenance": "USER_SUPPLIED_UNVERIFIED",
            "climate_sampler": "minecraft:vanilla_overworld_noise_router",
            "entries": [{"biome": "minecraft:forest"}]}), encoding="utf-8")
        output = io.StringIO()
        with contextlib.redirect_stdout(output):
            result = main(["--input", str(self.archive), "--minecraft", "26.2",
                           "--model", str(model), "--seeds", "2731"])
        self.assertEqual(result, 0)
        self.assertIn("MODEL_REGISTRY_CONFLICT", output.getvalue())

    def test_mixed_world_fails_closed(self):
        build_zip(self.archive, [row(*POINTS[0], fingerprint="f" * 64)]
                  + [row(*p) for p in POINTS[1:]])
        data = Dataset(self.archive)
        try:
            with self.assertRaises(EvidenceRejected):
                sample_evidence(data, "26.2")
        finally:
            data.close()

    def test_inadequate_spacing_fails_closed(self):
        positions = [(2+i*4, 66, 2, "minecraft:forest") for i in range(10)]
        build_zip(self.archive, [row(*p) for p in positions])
        data = Dataset(self.archive)
        try:
            with self.assertRaises(EvidenceRejected):
                sample_evidence(data, "26.2")
        finally:
            data.close()


if __name__ == "__main__":
    unittest.main()
