#!/usr/bin/env python3
"""安装器故障与发布路径测试；小型伪归档只验证 I/O，不替代真实 Gradle CI。"""
import hashlib
import io
import os
from pathlib import Path
import stat
import subprocess
import tempfile
import unittest
from unittest import mock
import urllib.error
import zipfile

import setup_gradle_ci as setup


class GradleCiInstallerTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory(prefix="wsl-gradle-installer-test-")
        self.root = Path(self.temporary.name)
        self.path_file = self.root / "github-path"
        self.env_file = self.root / "github-env"
        self.path_file.touch()
        self.env_file.touch()
        self.environment = mock.patch.dict(os.environ, {
            "RUNNER_TEMP": str(self.root), "GITHUB_PATH": str(self.path_file),
            "GITHUB_ENV": str(self.env_file)})
        self.environment.start()

    def tearDown(self):
        self.environment.stop()
        self.temporary.cleanup()

    @staticmethod
    def archive_bytes():
        stream = io.BytesIO()
        with zipfile.ZipFile(stream, "w") as archive:
            archive.writestr(f"gradle-{setup.VERSION}/bin/gradle", "NOT_EXECUTED_TEST_LAUNCHER")
        return stream.getvalue()

    def test_transient_504_retries_then_checks_completed_bytes(self):
        payload = b"TEST_ONLY_DISTRIBUTION_BYTES"
        failure = urllib.error.HTTPError(setup.DOWNLOAD_URLS[0], 504, "Gateway Timeout", {}, None)
        with mock.patch.object(setup.urllib.request, "urlopen", side_effect=[failure, io.BytesIO(payload)]) as open_url, \
                mock.patch.object(setup.time, "sleep") as sleep, \
                mock.patch.object(setup, "EXPECTED_SHA256", hashlib.sha256(payload).hexdigest()):
            destination = self.root / "distribution.zip"
            setup.download_distribution(destination)
        self.assertEqual(destination.read_bytes(), payload)
        self.assertEqual(open_url.call_count, 2)
        self.assertEqual([call.args[0].full_url for call in open_url.call_args_list], list(setup.DOWNLOAD_URLS))
        sleep.assert_called_once_with(10)

    def test_exhausted_network_retries_leave_no_archive(self):
        with mock.patch.object(setup.urllib.request, "urlopen", side_effect=urllib.error.URLError("test outage")) as open_url, \
                mock.patch.object(setup.time, "sleep") as sleep:
            destination = self.root / "distribution.zip"
            with self.assertRaisesRegex(RuntimeError, "已尝试 6 次"):
                setup.download_distribution(destination)
        self.assertEqual(open_url.call_count, setup.DOWNLOAD_ATTEMPTS)
        self.assertEqual([call.args[0] for call in sleep.call_args_list], [10, 20, 30, 30, 30])
        self.assertFalse(destination.exists())

    def test_bad_checksum_never_executes_or_exports_gradle(self):
        with mock.patch.object(setup.urllib.request, "urlopen", return_value=io.BytesIO(b"corrupt")), \
                mock.patch.object(setup.subprocess, "run") as execute:
            with self.assertRaisesRegex(ValueError, "SHA256"):
                setup.main()
        execute.assert_not_called()
        self.assertEqual(self.path_file.read_text(), "")
        self.assertEqual(self.env_file.read_text(), "")
        self.assertFalse(list(self.root.glob("wsl-gradle-*")))

    def test_archive_traversal_and_links_are_rejected_before_extracting(self):
        for name, mode in ((f"gradle-{setup.VERSION}/../../outside", stat.S_IFREG),
                           (f"gradle-{setup.VERSION}/bin/gradle", stat.S_IFLNK)):
            with self.subTest(name=name):
                archive_file = self.root / "unsafe.zip"
                with zipfile.ZipFile(archive_file, "w") as archive:
                    entry = zipfile.ZipInfo(name)
                    entry.external_attr = (mode | 0o777) << 16
                    archive.writestr(entry, "/outside")
                with self.assertRaisesRegex(ValueError, "非法路径或文件类型"):
                    setup.extract_distribution(archive_file, self.root / "extract")
                self.assertFalse((self.root / "extract").exists())

    def test_verified_install_exports_persistent_home_and_executable(self):
        payload = self.archive_bytes()
        with mock.patch.object(setup.urllib.request, "urlopen", return_value=io.BytesIO(payload)), \
                mock.patch.object(setup, "EXPECTED_SHA256", hashlib.sha256(payload).hexdigest()), \
                mock.patch.object(setup.subprocess, "run", return_value=subprocess.CompletedProcess(
                    [], 0, stdout=f"Gradle {setup.VERSION}\n", stderr="")):
            setup.main()
        executable_directory = Path(self.path_file.read_text().strip())
        self.assertTrue((executable_directory / "gradle").is_file())
        self.assertTrue(os.access(executable_directory / "gradle", os.X_OK))
        self.assertEqual(self.env_file.read_text(), f"GRADLE_HOME={executable_directory.parent}\n")
        self.assertFalse(list(self.root.rglob("distribution.zip")))

    def test_wrong_runtime_version_never_exports_installation(self):
        payload = self.archive_bytes()
        with mock.patch.object(setup.urllib.request, "urlopen", return_value=io.BytesIO(payload)), \
                mock.patch.object(setup, "EXPECTED_SHA256", hashlib.sha256(payload).hexdigest()), \
                mock.patch.object(setup.subprocess, "run", return_value=subprocess.CompletedProcess(
                    [], 0, stdout="Gradle WRONG_VERSION\n", stderr="")):
            with self.assertRaisesRegex(ValueError, "锁定的 Gradle 版本"):
                setup.main()
        self.assertEqual(self.path_file.read_text(), "")
        self.assertEqual(self.env_file.read_text(), "")
        self.assertFalse(list(self.root.glob("wsl-gradle-*")))


if __name__ == "__main__":
    unittest.main()
