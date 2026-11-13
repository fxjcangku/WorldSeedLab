#!/usr/bin/env python3
"""P3.9离线真实观测导入门槛测试，模型证明由独立Java/Z3/Fabric GUI提供。"""
import contextlib
import io
import json
from pathlib import Path
from types import SimpleNamespace
import tempfile
import unittest
from unittest.mock import patch
from contextlib import redirect_stdout, redirect_stderr
from unittest import mock
from zipfile import ZipFile

from recover_seed import (EvidenceError, inspect, main, parse_args, prepare_quart,
                          validate_options)
from research_p37_custom_worldgen import EvidenceRejected

WORLD = "a" * 64
PREFIX = f"worlds/{WORLD}/dimensions/" + "b" * 24 + "/"


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


def controlled_zip(path, rows=None, footprints=3):
    """人工结构文件只检查路由；绝不声称通过Java几何或真实Z3求解。"""
    with ZipFile(path, "w") as archive:
        for index in range(footprints):
            archive.writestr(f"client-footprint-{index}.properties", "NOT_YET_VERIFIED\n")
        if rows is not None:
            archive.writestr(PREFIX + "evidence.jsonl",
                             "".join(json.dumps(value) + "\n" for value in rows))


def captured_main(arguments):
    output, errors = io.StringIO(), io.StringIO()
    with contextlib.redirect_stdout(output), contextlib.redirect_stderr(errors):
        code = main(arguments)
    return code, output.getvalue(), errors.getvalue()


class SeedRecoveryPreflightTest(unittest.TestCase):
    def test_windows_absolute_gradle_batch_path_reaches_subprocess_in_both_modes(self):
        with tempfile.TemporaryDirectory() as td:
            path = Path(td) / "controlled-only.zip"
            with ZipFile(path, "w") as archive:
                for i in range(3):
                    archive.writestr(f"client-footprint-{i}.properties", "UNVERIFIED\n")
            gradle_batch = r"D:\Local Tools\Gradle\bin\gradle.bat"
            executables = {"gradle.bat": gradle_batch, "z3": r"D:\Local Tools\Z3\z3.exe"}
            for mode in ([], ["--unknown-salt"]):
                with self.subTest(mode=mode), \
                        patch("recover_seed.shutil.which", side_effect=executables.get), \
                        patch("recover_seed.subprocess.run") as run, redirect_stdout(io.StringIO()):
                    run.return_value.returncode = 0
                    self.assertEqual(main(["--input", str(path), "--minecraft", "26.2", *mode]), 0)
                    self.assertEqual(run.call_args.args[0][0], gradle_batch)
                    self.assertNotIn("shell", run.call_args.kwargs)

    def test_unknown_salt_only_dispatches_effective_state_domain(self):
        with tempfile.TemporaryDirectory() as td:
            path = Path(td) / "controlled-only.zip"
            with ZipFile(path, "w") as archive:
                for i in range(3):
                    archive.writestr(f"client-footprint-{i}.properties", "UNVERIFIED\n")
            output = io.StringIO()
            # 这里只验证显式授权与进程参数；真实栅格/Z3由Java及真实Minecraft测试验证。
            with patch("recover_seed.shutil.which", return_value="local-tool"), \
                    patch("recover_seed.subprocess.run") as run, redirect_stdout(output):
                run.return_value.returncode = 0
                self.assertEqual(main(["--input", str(path), "--minecraft", "26.2",
                                       "--unknown-salt", "--window-end", "4096"]), 0)
            command = run.call_args.args[0]
            self.assertIn("-PrecoverySaltMode=EFFECTIVE_STRUCTURE_STATE_ONLY", command)
            self.assertIn("-PrecoveryEnd=4096", command)
            self.assertFalse(any(arg.startswith("-PrecoverySalts=") for arg in command))
            self.assertFalse(any("Quart" in arg or "BiomeMode" in arg for arg in command))
            self.assertIn("WORLD_LOW48=UNKNOWN", output.getvalue())
            self.assertIn("FULL64=UNKNOWN", output.getvalue())

    def test_unknown_salt_rejects_salt_and_every_high16_entry_before_read(self):
        for extra in (["--salts", "14357617"], ["--vanilla-high16"],
                      ["--custom-model", "not-read.json"], ["--high16-shard", "1"],
                      ["--high16-evaluations", "4"], ["--high16-shards", "2"],
                      ["--report", "not-written.json"],
                      ["--high16-resume", "1", "--high16-expected-digest", "a" * 64]):
            with self.subTest(extra=extra), patch("recover_seed.inspect") as inspect_mock, \
                    patch("recover_seed.subprocess.run") as run, redirect_stderr(io.StringIO()):
                self.assertEqual(main(["--input", "not-read.zip", "--minecraft", "26.2",
                                       "--unknown-salt", *extra]), 2)
                inspect_mock.assert_not_called()
                run.assert_not_called()

    def test_default_salt_hypothesis_stays_vanilla_and_not_effective_state(self):
        with tempfile.TemporaryDirectory() as td:
            path = Path(td) / "controlled-only.zip"
            with ZipFile(path, "w") as archive:
                for i in range(3):
                    archive.writestr(f"client-footprint-{i}.properties", "UNVERIFIED\n")
            with patch("recover_seed.shutil.which", return_value="local-tool"), \
                    patch("recover_seed.subprocess.run") as run, redirect_stdout(io.StringIO()):
                run.return_value.returncode = 0
                self.assertEqual(main(["--input", str(path), "--minecraft", "26.2"]), 0)
            command = run.call_args.args[0]
            self.assertIn("-PrecoverySalts=14357617", command)
            self.assertFalse(any("recoverySaltMode" in arg for arg in command))
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix="wsl-recovery-test-")
        self.folder = Path(self.temp.name)
        self.archive = self.folder / "observations.zip"
        self.rows = [row(i * 2048 + 2, 2) for i in range(5)]
        controlled_zip(self.archive, self.rows)
        self.arguments = ["--input", str(self.archive), "--minecraft", "26.2"]

    def tearDown(self):
        self.temp.cleanup()

    def prepared(self, rows):
        controlled_zip(self.archive, rows)
        args = parse_args(self.arguments + ["--vanilla-high16"])
        possible = validate_options(args)
        return prepare_quart(args, possible)

    def test_realistic_empty_observation_zip_stops_without_guessing(self):
        with ZipFile(self.archive, "w") as archive:
            archive.writestr(PREFIX + "terrain.jsonl", "{}\n")
            archive.writestr(PREFIX + "evidence.jsonl", "{}\n")
        found, biome = inspect(self.archive)
        self.assertEqual(len(found), 0)
        self.assertEqual(len(biome), 1)
        code, output, _ = captured_main(self.arguments + ["--dry-run"])
        self.assertEqual(code, 2)
        self.assertIn("RESULT=INSUFFICIENT_GEOMETRY_EVIDENCE", output)
        self.assertNotIn("LOW48_MODEL_CANDIDATE", output)

    def test_dry_run_unverified_presence_does_not_claim_z3_recovery(self):
        with mock.patch("recover_seed.subprocess.run") as forbidden:
            code, output, _ = captured_main(self.arguments + ["--dry-run"])
        self.assertEqual(code, 0)
        forbidden.assert_not_called()
        self.assertIn("RESULT=STRUCTURE_FILES_PRESENT_UNVERIFIED", output)
        self.assertNotIn("LOW48_MODEL_CANDIDATE", output)

    def test_real_auto_quart_provenance_and_spatial_separation(self):
        prepared = self.prepared(self.rows)
        csv = prepared.contents
        self.assertEqual(prepared.selected_count, 5)
        self.assertIn("sampling=AUTOMATIC_FULL_CHUNK_OBSERVATION", csv)
        self.assertNotIn("sampling=MANUAL_ALREADY_LOADED_FULL", csv)
        self.assertIn("generatorProfile=worldseedlab:unknown_worldgen", csv)
        self.assertEqual(csv.count(",FIT\n"), 3)
        self.assertEqual(csv.count(",HOLDOUT\n"), 2)
        self.assertNotIn("CONTROL_WORLD_OBSERVED", csv)

    def test_three_supported_versions_keep_their_own_evidence_scope(self):
        for version in ("26.1.2", "26.2", "26.3"):
            with self.subTest(version=version):
                rows = [dict(value, client_version=version) for value in self.rows]
                controlled_zip(self.archive, rows)
                args = parse_args(["--input", str(self.archive), "--minecraft", version,
                                   "--vanilla-high16"])
                prepared = prepare_quart(args, validate_options(args))
                self.assertIn("gameVersion=" + version + "\n", prepared.contents)
                self.assertEqual(prepared.selected_count, 5)

    def test_supported_versions_cannot_be_mixed_or_relabelled(self):
        for source in ("26.1.2", "26.2", "26.3"):
            for target in ("26.1.2", "26.2", "26.3"):
                if source == target:
                    continue
                with self.subTest(source=source, target=target):
                    controlled_zip(self.archive, [dict(value, client_version=source)
                                                  for value in self.rows])
                    with mock.patch("recover_seed.subprocess.run") as forbidden:
                        code, output, errors = captured_main([
                            "--input", str(self.archive), "--minecraft", target,
                            "--vanilla-high16", "--dry-run"])
                    self.assertEqual(code, 2)
                    self.assertIn("RESULT=BLOCKED", errors)
                    self.assertNotIn("LOW48_MODEL_CANDIDATE", output)
                    forbidden.assert_not_called()

    def test_custom_biome_rejects_vanilla_high16(self):
        rows = self.rows + [row(12290, 2, "terralith:mirage_isles")]
        with self.assertRaisesRegex(EvidenceError, "非minecraft"):
            self.prepared(rows)

    def test_cross_world_p1_observations_rejected(self):
        with self.assertRaisesRegex(EvidenceRejected, "跨世界"):
            self.prepared([row(2, 2), row(2050, 2, world="b" * 64)])

    def test_not_enough_independent_quart_points(self):
        with self.assertRaisesRegex(EvidenceRejected, "5处"):
            self.prepared([row(i * 4 + 2, 2) for i in range(5)])

    def test_vanilla_uses_strict_p1_provenance_even_in_dry_run(self):
        mutations = [
            ("quart_y", lambda value: value["position"].update(y=65)),
            ("path_fingerprint", lambda value: value.update(world_fingerprint="b" * 64)),
            ("nonhex_fingerprint", lambda value: value.update(world_fingerprint="g" * 64)),
            ("dimension", lambda value: value.update(dimension="minecraft:the_nether")),
            ("version", lambda value: value.update(client_version="26.1.2")),
            ("schema", lambda value: value.update(schema=2)),
            ("origin", lambda value: value.update(origin="CONTROL_WORLD_OBSERVED")),
            ("identifier", lambda value: value.update(observed_identifier="minecraft:bad\nbiome")),
        ]
        for label, change in mutations:
            with self.subTest(label=label):
                rows = [row(i * 2048 + 2, 2) for i in range(5)]
                change(rows[0])
                controlled_zip(self.archive, rows)
                with mock.patch("recover_seed.subprocess.run") as forbidden:
                    code, output, errors = captured_main(
                        self.arguments + ["--vanilla-high16", "--dry-run"])
                self.assertEqual(code, 2)
                self.assertIn("RESULT=BLOCKED", errors)
                self.assertNotIn(WORLD, output + errors)
                forbidden.assert_not_called()
        controlled_zip(self.archive, [None] + self.rows)
        code, _, errors = captured_main(self.arguments + ["--vanilla-high16", "--dry-run"])
        self.assertEqual(code, 2)
        self.assertIn("RESULT=BLOCKED", errors)

    def test_invalid_window_and_salts_do_not_bypass_dry_run(self):
        cases = [
            ["--window-end", "0"],
            ["--window-end", str((1 << 48) + 1)],
            ["--salts="],
            ["--salts=1,2,3,4,5"],
            ["--salts=1,1"],
            ["--salts=1,01"],
            ["--salts=2147483648"],
            ["--salts=-2147483649"],
            ["--salts=1,,2"],
            ["--salts=not-a-salt"],
        ]
        for options in cases:
            with self.subTest(options=options), mock.patch("recover_seed.subprocess.run") as forbidden:
                code, output, errors = captured_main(self.arguments + options + ["--dry-run"])
                self.assertEqual(code, 2)
                self.assertIn("RESULT=BLOCKED", errors)
                self.assertNotIn("STRUCTURE_FILES_PRESENT_UNVERIFIED", output)
                forbidden.assert_not_called()
        code, _, _ = captured_main(self.arguments + [
            "--salts=-2147483648,2147483647", "--window-end", "1", "--dry-run"])
        self.assertEqual(code, 0)

    def test_explicit_custom_search_options_require_custom_model(self):
        cases = [
            ["--high16-shard", "0"],
            ["--high16-shards", "16"],
            ["--high16-evaluations", "128"],
            ["--high16-resume", "0", "--high16-expected-digest", "wsl-p44-v1:" + "a" * 64],
        ]
        for options in cases:
            with self.subTest(options=options):
                code, _, errors = captured_main(self.arguments + options + ["--dry-run"])
                self.assertEqual(code, 2)
                self.assertIn("显式自定义模型", errors)

    def test_report_path_passes_to_java_without_python_writing_report(self):
        report = self.folder / "local-result.json"
        sentinel = '{"test_only": "java_owns_output"}\n'

        def fake_java(command, **options):
            self.assertIn("-PrecoveryReport=" + str(report.resolve()), command)
            self.assertFalse(report.exists(), "Python不先创建会阻挡Java CREATE_NEW的空文件")
            report.write_text(sentinel, encoding="utf-8")
            return SimpleNamespace(returncode=0)

        with mock.patch("recover_seed.shutil.which", return_value="/unused"), \
                mock.patch("recover_seed.subprocess.run", side_effect=fake_java):
            code, output, errors = captured_main(self.arguments + ["--report", str(report)])
        self.assertEqual(code, 0)
        self.assertEqual(report.read_text(encoding="utf-8"), sentinel)
        self.assertNotIn(str(report), output + errors)
        self.assertNotIn(WORLD, output + errors)

    def test_dry_run_and_preflight_never_write_solver_report(self):
        report = self.folder / "dry-result.json"
        for options, expected_code in [([], 0), (["--window-end", "0"], 2)]:
            with self.subTest(options=options), mock.patch("recover_seed.subprocess.run") as forbidden:
                code, output, _ = captured_main(
                    self.arguments + ["--report", str(report), "--dry-run"] + options)
                self.assertEqual(code, expected_code)
                self.assertFalse(report.exists())
                self.assertIn("REPORT=NOT_CREATED", output)
                forbidden.assert_not_called()

    def test_existing_report_and_symlinks_never_overwritten(self):
        existing = self.folder / "existing.json"
        existing.write_text("KEEP\n", encoding="utf-8")
        link = self.folder / "linked.json"
        link.symlink_to(existing)
        dangling = self.folder / "dangling.json"
        dangling.symlink_to(self.folder / "missing.json")
        directory_link = self.folder / "linked-dir"
        directory_link.symlink_to(self.folder, target_is_directory=True)
        targets = [existing, link, dangling, directory_link / "new.json",
                   self.folder / "missing-dir" / "new.json"]
        for target in targets:
            with self.subTest(target=target.name), mock.patch("recover_seed.subprocess.run") as forbidden:
                code, _, errors = captured_main(
                    self.arguments + ["--report", str(target), "--dry-run"])
                self.assertEqual(code, 2)
                self.assertIn("RESULT=BLOCKED", errors)
                forbidden.assert_not_called()
        self.assertEqual(existing.read_text(encoding="utf-8"), "KEEP\n")
        self.assertFalse((self.folder / "missing.json").exists())

    def test_private_quart_cleanup_on_backend_failure(self):
        for fail_to_start in (False, True):
            paths = []

            def fake_java(command, **options):
                quart = Path(next(item.split("=", 1)[1] for item in command
                                  if item.startswith("-PrecoveryQuart=")))
                self.assertTrue(quart.is_file())
                self.assertEqual(quart.read_text(encoding="utf-8").count(",HOLDOUT\n"), 2)
                paths.append(quart)
                if fail_to_start:
                    raise OSError("PRIVATE_PATH_MUST_NOT_APPEAR")
                return SimpleNamespace(returncode=7)

            with self.subTest(fail_to_start=fail_to_start), \
                    mock.patch("recover_seed.shutil.which", return_value="/unused"), \
                    mock.patch("recover_seed.subprocess.run", side_effect=fake_java):
                code, output, errors = captured_main(self.arguments + ["--vanilla-high16"])
            self.assertEqual(code, 2 if fail_to_start else 7)
            self.assertTrue(paths)
            self.assertFalse(paths[0].exists())
            self.assertNotIn("PRIVATE_PATH_MUST_NOT_APPEAR", output + errors)
            self.assertNotIn(WORLD, output + errors)

    def test_symlink_input_directory_rejected(self):
        source = self.folder / "input-folder"
        source.mkdir()
        link = self.folder / "input-link"
        link.symlink_to(source, target_is_directory=True)
        with self.assertRaisesRegex(EvidenceError, "符号链接"):
            inspect(link)


if __name__ == "__main__":
    unittest.main()
