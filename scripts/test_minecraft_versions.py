#!/usr/bin/env python3
"""三版本离线入口与原版jar启动路由测试，不启动游戏、不伪造FULL真值。"""
import json
import os
from pathlib import Path
import tempfile
import unittest
from unittest import mock

from minecraft_versions import SUPPORTED_MINECRAFT_VERSIONS
from run_known_seed_server import ROOT, controlled_server_command
from run_p34_custom_worldgen import DATAPACK_NAME, install_modified_salt_pack


class TargetVersionRoutingTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory(prefix="wsl-version-routing-")
        self.root = Path(self.temporary.name)
        self.rundir = self.root / "run"

    def tearDown(self):
        self.temporary.cleanup()

    def test_targets_and_default_loom_commands_preserve_all_three_versions(self):
        self.assertEqual(SUPPORTED_MINECRAFT_VERSIONS, ("26.1.2", "26.2", "26.3"))
        for version in ("26.1.2", "26.2", "26.3"):
            with self.subTest(version=version):
                command, cwd = controlled_server_command(version, self.rundir)
                self.assertEqual(cwd, ROOT)
                self.assertEqual(command[0], "gradle")
                self.assertIn("-PmcVersion=" + version, command)
                self.assertEqual(command[-2:], ["runServer", "--args=nogui"])
        self.assertFalse(self.rundir.exists(), "仅组装命令不创建世界")

    def test_explicit_vanilla_jar_uses_java_home_and_controlled_run_directory(self):
        # 空文件仅用于不执行的路由测试；实际校准下载由官方SHA独立验证。
        jar = self.root / "controlled-server.jar"
        jar.write_bytes(b"ROUTING_TEST_ONLY")
        java = self.root / "java25" / "bin" / ("java.exe" if os.name == "nt" else "java")
        java.parent.mkdir(parents=True)
        java.write_bytes(b"NOT_EXECUTED")
        with mock.patch.dict(os.environ, {"JAVA_HOME": str(java.parent.parent)}):
            command, cwd = controlled_server_command("26.3", self.rundir, jar)
        self.assertEqual(command, [str(java), "-Xmx2G", "-jar", str(jar.resolve()), "nogui"])
        self.assertEqual(cwd, self.rundir)
        self.assertNotIn("gradle", command)

    def test_explicit_jar_can_use_java_path_without_java_home(self):
        jar = self.root / "server.jar"
        jar.write_bytes(b"ROUTING_TEST_ONLY")
        with mock.patch.dict(os.environ, {"JAVA_HOME": ""}), \
                mock.patch("run_known_seed_server.shutil.which", return_value="/test/java"):
            command, cwd = controlled_server_command("26.3", self.rundir, jar)
        self.assertEqual(command[0], "/test/java")
        self.assertEqual(cwd, self.rundir)

    def test_unsafe_jar_and_unknown_target_fail_before_java_lookup(self):
        ordinary = self.root / "server.jar"
        ordinary.write_bytes(b"ROUTING_TEST_ONLY")
        link = self.root / "linked.jar"
        link.symlink_to(ordinary)
        wrong_extension = self.root / "server.txt"
        wrong_extension.write_bytes(b"ROUTING_TEST_ONLY")
        folder = self.root / "directory.jar"
        folder.mkdir()
        for jar in (link, self.root / "missing.jar", wrong_extension, folder):
            with self.subTest(jar=jar.name), \
                    mock.patch("run_known_seed_server.shutil.which") as forbidden:
                with self.assertRaises(ValueError):
                    controlled_server_command("26.3", self.rundir, jar)
                forbidden.assert_not_called()
        with self.assertRaises(ValueError):
            controlled_server_command("26.4", self.rundir, ordinary)
        self.assertFalse(self.rundir.exists())

    def test_modified_salt_pack_uses_each_official_target_format(self):
        expected = {"26.1.2": [101, 1], "26.2": [107, 1], "26.3": [121, 0]}
        for version, pack_format in expected.items():
            with self.subTest(version=version):
                world = self.root / version
                install_modified_salt_pack(world, version)
                target = world / "datapacks" / DATAPACK_NAME
                pack = json.loads((target / "pack.mcmeta").read_text(encoding="utf-8"))["pack"]
                self.assertEqual(pack["min_format"], pack_format)
                self.assertEqual(pack["max_format"], pack_format)
                structures = json.loads((target / "data/minecraft/worldgen/structure_set/"
                                         "desert_pyramids.json").read_text(encoding="utf-8"))
                self.assertEqual(structures["placement"]["salt"], 14357844)
                with self.assertRaises(FileExistsError):
                    install_modified_salt_pack(world, version)


if __name__ == "__main__":
    unittest.main()
