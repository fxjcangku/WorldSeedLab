#!/usr/bin/env python3
"""P3.9离线真实观测导入门槛测试，模型证明由独立Java/Z3/Fabric GUI提供。"""
import io
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch
from contextlib import redirect_stdout, redirect_stderr
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
