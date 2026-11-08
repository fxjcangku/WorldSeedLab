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


def model_config(biomes=("minecraft:forest", "terralith:test_biome")):
    """独立人工参数仅用于Python路由；不代表真实生成器配置。"""
    return {
        "schema": 1,
        "provenance": "USER_SUPPLIED_UNVERIFIED",
        "generator_profile": "worldseedlab:controlled_testing_only",
        "climate_sampler": "minecraft:vanilla_overworld_noise_router",
        "entries": [{
            "biome": biome,
            "temperature": [-1.0, 1.0], "humidity": [-1.0, 1.0],
            "continentalness": [-1.0, 1.0], "erosion": [-1.0, 1.0],
            "depth": [-1.0, 1.0], "weirdness": [-1.0, 1.0], "offset": 0.0,
        } for biome in biomes],
    }


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
        self.model.write_text(json.dumps(model_config()), encoding="utf-8")

    def tearDown(self):
        self.tmp.cleanup()

    def test_require_explicit_custom_or_vanilla_exclusively(self):
        result = main(["--input", str(self.archive), "--minecraft", "26.2",
                       "--custom-model", str(self.model), "--vanilla-high16"])
        self.assertEqual(result, 2)

    def test_reject_shard_or_resume_without_provenance(self):
        base = ["--input", str(self.archive), "--minecraft", "26.2",
                "--custom-model", str(self.model), "--dry-run"]
        self.assertEqual(main(base + ["--high16-shards", "0"]), 2)
        self.assertEqual(main(base + ["--high16-shard", "16"]), 2)
        self.assertEqual(main(base + ["--high16-evaluations", "0"]), 2)
        self.assertEqual(main(base + ["--high16-evaluations", "1000001"]), 2)
        self.assertEqual(main(base + ["--high16-resume", "1024"]), 2)
        self.assertEqual(main(base + ["--high16-expected-digest", "wsl-p44-v1:" + "a" * 64]), 2)
        for index in (-1, 1 << 63):
            self.assertEqual(main(base + ["--high16-resume", str(index),
                                          "--high16-expected-digest", "wsl-p44-v1:" + "a" * 64]), 2)

    def test_old_resume_digest_requires_restart(self):
        errors = io.StringIO()
        with contextlib.redirect_stderr(errors), mock.patch("recover_seed.subprocess.run") as forbidden:
            code = main(["--input", str(self.archive), "--minecraft", "26.2",
                         "--custom-model", str(self.model), "--dry-run",
                         "--high16-resume", "128", "--high16-expected-digest", "f" * 64])
        self.assertEqual(code, 2)
        self.assertIn("旧版裸断点摘要", errors.getvalue())
        self.assertIn("本分片起点", errors.getvalue())
        forbidden.assert_not_called()

    def test_multiple_salts_cannot_share_one_resume_token(self):
        base = ["--input", str(self.archive), "--minecraft", "26.2",
                "--custom-model", str(self.model), "--salts=1,2", "--dry-run"]
        errors, output = io.StringIO(), io.StringIO()
        with contextlib.redirect_stderr(errors), contextlib.redirect_stdout(output), \
                mock.patch("recover_seed.subprocess.run") as forbidden:
            self.assertEqual(main(base), 0, "多Salt首轮仍可显式研究")
            self.assertEqual(main(base + ["--high16-resume", "128",
                                           "--high16-expected-digest", "wsl-p44-v1:" + "a" * 64]), 2)
        self.assertIn("断点续算仅支持单个结构Salt", errors.getvalue())
        forbidden.assert_not_called()

    def test_complete_model_validated_before_dry_run(self):
        mutations = [
            lambda model: model.update(schema=True),
            lambda model: model.pop("generator_profile"),
            lambda model: model.update(generator_profile="minecraft:unverified"),
            lambda model: model.update(entries=[]),
            lambda model: model["entries"][0].update(biome=None),
            lambda model: model["entries"][0].pop("temperature"),
            lambda model: model["entries"][0].update(temperature=[1, -1]),
            lambda model: model["entries"][0].update(humidity=[-float("inf"), 1]),
            lambda model: model["entries"][0].update(depth=[False, 1]),
            lambda model: model["entries"][0].update(offset=float("nan")),
        ]
        for number, mutate in enumerate(mutations):
            with self.subTest(number=number):
                config = model_config()
                mutate(config)
                self.model.write_text(json.dumps(config), encoding="utf-8")
                errors = io.StringIO()
                with contextlib.redirect_stderr(errors), \
                        mock.patch("recover_seed.subprocess.run") as forbidden:
                    code = main(["--input", str(self.archive), "--minecraft", "26.2",
                                 "--custom-model", str(self.model), "--dry-run"])
                self.assertEqual(code, 2)
                self.assertIn("RESULT=BLOCKED", errors.getvalue())
                forbidden.assert_not_called()

    def test_valid_custom_dry_run_does_not_run_solver(self):
        output = io.StringIO()
        with contextlib.redirect_stdout(output), mock.patch("recover_seed.subprocess.run") as forbidden:
            code = main(["--input", str(self.archive), "--minecraft", "26.2",
                         "--custom-model", str(self.model), "--dry-run"])
        self.assertEqual(code, 0)
        self.assertIn("RESULT=STRUCTURE_FILES_PRESENT_UNVERIFIED", output.getvalue())
        self.assertNotIn("LOW48_MODEL_CANDIDATE", output.getvalue())
        forbidden.assert_not_called()

    def test_model_registry_conflict_exits_before_z3(self):
        self.model.write_text(json.dumps(model_config(("minecraft:forest",))), encoding="utf-8")
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
            self.assertIn("-PrecoveryHigh16Digest=wsl-p44-v1:" + "f"*64, command)
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
                         "--high16-expected-digest", "wsl-p44-v1:" + "f"*64])
        self.assertEqual(code, 0)
        self.assertFalse(captured["temp_path"].exists(), "私有quart临时文件必须自动销毁")
        self.assertNotIn(HASH, output.getvalue(), "不得泄露完整世界HMAC")
        self.assertNotIn("12345,66", output.getvalue(), "不得公开坐标")


if __name__ == "__main__":
    unittest.main()
