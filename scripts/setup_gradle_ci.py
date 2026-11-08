#!/usr/bin/env python3
"""仅在 CI 中安装固定且经官方 SHA256 校验的 Gradle，不改变缓存或令牌权限。"""
from __future__ import annotations

import hashlib
import http.client
import os
from pathlib import Path, PurePosixPath
import shutil
import stat
import subprocess
import sys
import tempfile
import time
import urllib.error
import urllib.request
import zipfile


VERSION = "9.9.0"
GITHUB_ASSET_API_URL = "https://api.github.com/repos/gradle/gradle-distributions/releases/assets/717698303"
DOWNLOAD_URLS = (
    GITHUB_ASSET_API_URL,
    f"https://github.com/gradle/gradle-distributions/releases/download/v{VERSION}/gradle-{VERSION}-bin.zip",
    f"https://downloads.gradle.org/distributions/gradle-{VERSION}-bin.zip",
    f"https://services.gradle.org/distributions/gradle-{VERSION}-bin.zip",
)
# 2026-11-08 核对两个官方.sha256及GitHub发行资产digest，并完整下载发行ZIP复核。
EXPECTED_SHA256 = "0b00b0ffe07c4d4789c12d4935a44a7057d55774b7540bd4a3f76f336deb177a"
MAX_DOWNLOAD_BYTES = 256 * 1024 * 1024
MAX_EXTRACTED_BYTES = 512 * 1024 * 1024
RETRY_DELAYS_SECONDS = (10, 20, 30, 30, 30)
DOWNLOAD_ATTEMPTS = len(RETRY_DELAYS_SECONDS) + 1
DOWNLOAD_TIMEOUT_SECONDS = 30
DOWNLOAD_DEADLINE_SECONDS = 180


def download_distribution(destination: Path) -> None:
    """网络故障有界重试；摘要错误立即失败，不执行未经验证的归档。"""
    deadline = time.monotonic() + DOWNLOAD_DEADLINE_SECONDS
    for attempt in range(1, DOWNLOAD_ATTEMPTS + 1):
        url = DOWNLOAD_URLS[(attempt - 1) % len(DOWNLOAD_URLS)]
        digest = hashlib.sha256()
        size = 0
        try:
            remaining = deadline - time.monotonic()
            if remaining <= 0:
                raise TimeoutError("Gradle 下载总时间预算已耗尽")
            headers = {"User-Agent": "WorldSeedLab-CI"}
            if url == GITHUB_ASSET_API_URL:
                # 公开发行资产API须显式请求ZIP；不读取或发送GitHub令牌。
                headers.update({"Accept": "application/octet-stream", "X-GitHub-Api-Version": "2022-11-28"})
            request = urllib.request.Request(url, headers=headers)
            with urllib.request.urlopen(request, timeout=min(DOWNLOAD_TIMEOUT_SECONDS, remaining)) as response, \
                    destination.open("wb") as output:
                while True:
                    if time.monotonic() >= deadline:
                        raise TimeoutError("Gradle 下载总时间预算已耗尽")
                    chunk = response.read(1024 * 1024)
                    if not chunk:
                        break
                    size += len(chunk)
                    if size > MAX_DOWNLOAD_BYTES:
                        raise ValueError("Gradle ZIP 超出固定下载大小上限")
                    digest.update(chunk)
                    output.write(chunk)
            actual = digest.hexdigest()
            if actual != EXPECTED_SHA256:
                raise ValueError(f"Gradle {VERSION} SHA256 不符：expected={EXPECTED_SHA256}, actual={actual}")
            print(f"Gradle {VERSION} 官方 ZIP 已校验：bytes={size} sha256={actual} source={url}", flush=True)
            return
        except (urllib.error.URLError, TimeoutError, ConnectionError, http.client.HTTPException) as error:
            destination.unlink(missing_ok=True)
            remaining = deadline - time.monotonic()
            if attempt == DOWNLOAD_ATTEMPTS or remaining <= 0:
                raise RuntimeError(f"Gradle 官方下载失败，已尝试 {attempt} 次：{error}") from error
            delay = min(RETRY_DELAYS_SECONDS[attempt - 1], remaining)
            print(f"Gradle 官方下载第 {attempt} 次失败（{url}）：{error}；{delay:g} 秒后重试", flush=True)
            time.sleep(delay)
        except Exception:
            destination.unlink(missing_ok=True)
            raise


def extract_distribution(archive: Path, destination: Path) -> Path:
    """固定顶层目录；不接受归档链接、越界路径或解压膨胀。"""
    root_name = f"gradle-{VERSION}"
    with zipfile.ZipFile(archive) as bundle:
        total_size = 0
        for entry in bundle.infolist():
            path = PurePosixPath(entry.filename)
            mode = entry.external_attr >> 16
            file_type = stat.S_IFMT(mode)
            if path.is_absolute() or not path.parts or path.parts[0] != root_name \
                    or ".." in path.parts or "\\" in entry.filename \
                    or file_type not in (0, stat.S_IFREG, stat.S_IFDIR):
                raise ValueError(f"Gradle ZIP 含非法路径或文件类型：{entry.filename!r}")
            total_size += entry.file_size
            if total_size > MAX_EXTRACTED_BYTES:
                raise ValueError("Gradle ZIP 超出固定解压大小上限")
        bundle.extractall(destination)
    home = destination / root_name
    launcher = home / "bin" / "gradle"
    if not launcher.is_file() or launcher.is_symlink():
        raise ValueError("Gradle ZIP 缺少真实启动器")
    launcher.chmod(0o755)
    return home


def required_path(name: str) -> Path:
    value = os.environ.get(name, "")
    if not value or "\n" in value or "\r" in value:
        raise ValueError(f"必须由 CI 提供有效的 {name}")
    return Path(value).resolve(strict=True)


def main() -> None:
    runner_temp = required_path("RUNNER_TEMP")
    github_path = required_path("GITHUB_PATH")
    github_env = required_path("GITHUB_ENV")
    if not runner_temp.is_dir() or not github_path.is_file() or not github_env.is_file():
        raise ValueError("RUNNER_TEMP/GITHUB_PATH/GITHUB_ENV 类型无效")
    installation = Path(tempfile.mkdtemp(prefix="wsl-gradle-", dir=runner_temp))
    try:
        archive = installation / "distribution.zip"
        download_distribution(archive)
        home = extract_distribution(archive, installation)
        result = subprocess.run([str(home / "bin" / "gradle"), "--version"], check=True,
                                text=True, capture_output=True, timeout=60)
        if f"Gradle {VERSION}" not in result.stdout.splitlines():
            raise ValueError("启动器未报告锁定的 Gradle 版本")
        archive.unlink()
        # 只有下载、摘要、归档与真实版本检查均成功后，才对后续步骤公布安装路径。
        with github_path.open("a", encoding="utf-8") as output:
            output.write(str(home / "bin") + "\n")
        with github_env.open("a", encoding="utf-8") as output:
            output.write(f"GRADLE_HOME={home}\n")
        print(f"Gradle {VERSION} 已安装并验证：{home}", flush=True)
    except Exception:
        shutil.rmtree(installation)
        raise


if __name__ == "__main__":
    try:
        main()
    except (OSError, ValueError, RuntimeError, zipfile.BadZipFile, subprocess.SubprocessError) as error:
        print(f"Gradle CI 安装失败：{error}", file=sys.stderr)
        raise SystemExit(1) from error
