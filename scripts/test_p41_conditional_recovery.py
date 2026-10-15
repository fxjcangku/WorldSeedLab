#!/usr/bin/env python3
"""P4.1 真实证据本地路由的安全测试。假轮廓仅用于preflight负例，
不参与Java结构/Z3真值声明；结构真值另由实际Fabric GUI工作流验收。
"""
import contextlib
import io
import json
from pathlib import Path
from types import SimpleNamespace
import tempfile
import unittest
from unittest import mock
from zipfile import ZipFile

from recover_seed import main
from test_audit_custom_climate import HASH, POINTS, build_zip, row


class TestP41ConditionalRouting(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory(prefix="p41-test-")
        base = Path(self.tmp.name)
        self.archive = base / "observations.zip"
        build_zip(self.archive, [row(*p) for p in POINTS])
        # CONTROLLED_ARTIFICIAL_PRECHECK_ONLY: 仅检验Python路由，不是真实结构轮廓。
        with ZipFile(self.archive, "a") as archive:
            for i in range(3):
                archive.writestr(f"client-auto-desert-artificial-{i}.properties", "NOT_REAL")
        self.model = base / "unverified.json"
        self.model.write_text(json.dumps({
            "schema": 1,
            "provenance": "USER_SUPPLIED_UNVERIFIED",
            "generator_profile": "worldseedlab:controlled_testing_only",
            "climate_sampler": "minecraft:vanilla_overworld_noise_router",
            "entries": [
                {"biome": "minecraft:forest"},
                {"biome": "terralith:test_biome"}
            ]
        }), encoding="utf-8")

    def tearDown(self):
        self.tmp.cleanup()

    def test_require_explicit_custom_or_vanilla_exclusively(self):
        result = main(["--input", str(self.archive), "--minecraft", "26.2",
                       "--custom-model", str(self.model), "--vanilla-high16"])
        self.assertEqual(result, 2)

    def test_reject_shard_or_resume_without_provenance(self):
        base = ["--input", str(self.archive), "--minecraft", "26.2",
                "--custom-model", str(self.model)]
        self.assertEqual(main(base + ["--high16-shards", "0"]), 2)
        self.assertEqual(main(base + ["--high16-shard", "16"]), 2)
        self.assertEqual(main(base + ["--high16-resume", "1024"]), 2)
        self.assertEqual(main(base + ["--high16-expected-digest", "a" * 64]), 2)

    def test_model_registry_conflict_exits_before_z3(self):
        self.model.write_text(json.dumps({
            "schema": 1,
            "provenance": "USER_SUPPLIED_UNVERIFIED",
            "climate_sampler": "minecraft:vanilla_overworld_noise_router",
            "entries": [{"biome": "minecraft:forest"}]
        }), encoding="utf-8")
        output = io.StringIO()
        with mock.patch("recover_seed.shutil.which", return_value="/dev/null"), \
                mock.patch("recover_seed.subprocess.run") as forbidden, \
                contextlib.redirect_stdout(output):
            code = main(["--input", str(self.archive), "--minecraft", "26.2",
                         "--custom-model", str(self.model)])
        self.assertEqual(code, 2)
        forbidden.assert_not_called()
        self.assertIn("P4.1_MODEL_REGISTRY_INCOMPATIBLE", output.getvalue())
        self.assertNotIn(HASH, output.getvalue())

    def test_private_quart_and_exact_resume_arguments(self):
        captured = {}
        def fake_gradle(command, **options):
            captured["command"] = command
            self.assertIn("p39RealSeedRecovery", command)
            self.assertIn("-PrecoveryBiomeMode=EXPLICIT_CUSTOM_CLIMATE_HYPOTHESIS", command)
            self.assertIn("-PrecoveryShardIndex=3", command)
            self.assertIn("-PrecoveryShardCount=16", command)
            self.assertIn("-PrecoveryHigh16MaxEvaluations=17", command)
            self.assertIn("-PrecoveryHigh16Resume=12345", command)
            self.assertIn("-PrecoveryHigh16Digest=" + "f"*64, command)
            quart = Path(next(c.split("=", 1)[1] for c in command
                              if c.startswith("-PrecoveryQuart=")))
            self.assertTrue(quart.is_file())
            contents = quart.read_text(encoding="utf-8")
            self.assertIn("sampling=AUTOMATIC_FULL_CHUNK_OBSERVATION", contents)
            self.assertEqual(contents.count(",FIT\n"), 3)
            self.assertEqual(contents.count(",HOLDOUT\n"), 2)
            captured["temp_path"] = quart
            return SimpleNamespace(returncode=0)

        output = io.StringIO()
        with mock.patch("recover_seed.shutil.which", return_value="/dev/null"), \
                mock.patch("recover_seed.subprocess.run", side_effect=fake_gradle), \
                contextlib.redirect_stdout(output):
            code = main(["--input", str(self.archive), "--minecraft", "26.2",
                         "--custom-model", str(self.model),
                         "--high16-shard", "3", "--high16-shards", "16",
                         "--high16-evaluations", "17", "--high16-resume", "12345",
                         "--high16-expected-digest", "f"*64])
        self.assertEqual(code, 0)
        self.assertFalse(captured["temp_path"].exists(), "私有quart临时文件必须自动销毁")
        self.assertNotIn(HASH, output.getvalue(), "不得泄露完整世界HMAC")
        self.assertNotIn("12345,66", output.getvalue(), "不得公开坐标")


if __name__ == "__main__":
    unittest.main()
