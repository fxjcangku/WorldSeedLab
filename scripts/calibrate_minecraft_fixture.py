#!/usr/bin/env python3
"""从官方原版服务器的已保存 FULL 世界生成独立版本夹具。

仅供隔离受控 CI：固定公开 Seed=2731，不读取玩家存档或第三方服务器。
先按 Mojang 官方元数据验证服务器，再调用真实服务器和原有 NBT 读取器；
不从待验证的群系前向模型合成样本，也不把旧版本 CSV 重新标记。
"""
from __future__ import annotations

import argparse
import csv
import hashlib
import io
import itertools
import json
import os
from pathlib import Path
import subprocess
import sys
import urllib.request

from minecraft_versions import SUPPORTED_MINECRAFT_VERSIONS

ROOT = Path(__file__).resolve().parents[1]
MANIFEST_URL = "https://piston-meta.mojang.com/mc/game/version_manifest_v2.json"


def download(url: str, limit: int) -> bytes:
    if not url.startswith("https://"):
        raise ValueError("official download must use HTTPS")
    with urllib.request.urlopen(url, timeout=120) as response:
        data = response.read(limit + 1)
    if len(data) > limit:
        raise ValueError("official download exceeds declared size limit")
    return data


def checked_download(metadata: dict, limit: int) -> bytes:
    data = download(metadata["url"], limit)
    if hashlib.sha1(data).hexdigest() != metadata["sha1"]:
        raise ValueError("official Mojang SHA1 does not match downloaded bytes")
    if "size" in metadata and len(data) != metadata["size"]:
        raise ValueError("official Mojang size does not match downloaded bytes")
    return data


def select_quart_fixture(source: Path) -> str:
    with source.open(encoding="utf-8", newline="") as stream:
        reader = csv.DictReader(stream)
        if reader.fieldnames != ["quart_x", "quart_y", "quart_z", "biome_id"]:
            raise ValueError("unexpected saved FULL quart schema")
        rows = list(reader)
    # 排序和选点只依赖真实观测坐标及群系多样性，不咨询前向模型预测。
    rows.sort(key=lambda row: (int(row["quart_x"]), int(row["quart_z"])))
    for group in itertools.combinations(rows, 5):
        if len({row["biome_id"] for row in group}) < 3:
            continue
        if any(max(abs(int(a["quart_x"]) - int(b["quart_x"])),
                   abs(int(a["quart_z"]) - int(b["quart_z"]))) < 256
               for a, b in itertools.combinations(group, 2)):
            continue
        stream = io.StringIO(newline="")
        writer = csv.DictWriter(stream,
                                fieldnames=["quart_x", "quart_y", "quart_z", "biome_id"],
                                lineterminator="\n")
        writer.writeheader()
        writer.writerows(group)
        return stream.getvalue()
    raise ValueError("need exactly five FULL samples >=1024 blocks apart and >=3 biomes")


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--minecraft", choices=SUPPORTED_MINECRAFT_VERSIONS, required=True)
    args = parser.parse_args()
    minecraft = args.minecraft
    run_id, repository = os.environ.get("GITHUB_RUN_ID"), os.environ.get("GITHUB_REPOSITORY")
    if not run_id or not repository:
        raise ValueError("controlled calibration requires a traceable GitHub Actions run")
    run_url = "https://github.com/" + repository + "/actions/runs/" + run_id
    output = ROOT / "build" / "p45-calibration" / minecraft
    output.mkdir(parents=True, exist_ok=False)
    manifest = json.loads(download(MANIFEST_URL, 8_000_000))
    versions = [entry for entry in manifest["versions"] if entry["id"] == minecraft]
    if len(versions) != 1 or versions[0]["type"] != "release":
        raise ValueError("requested Minecraft version must be one official release")
    version_data = checked_download(versions[0], 8_000_000)
    version = json.loads(version_data)
    if version["id"] != minecraft or version["javaVersion"]["majorVersion"] != 25:
        raise ValueError("unexpected official Minecraft version or Java requirement")
    server = version["downloads"]["server"]
    server_data = checked_download(server, 150_000_000)
    jar = ROOT / "build" / ("official-server-" + minecraft + ".jar")
    jar.write_bytes(server_data)
    print("[P45-CALIBRATION] official server verified", minecraft, server["sha1"], flush=True)
    subprocess.run([sys.executable, "-u", str(ROOT / "scripts" / "run_known_seed_server.py"),
                    "--minecraft", minecraft, "--vanilla-server-jar", str(jar)],
                   cwd=ROOT, check=True)
    source = ROOT / "build" / "p29-realworld" / minecraft
    truth = json.loads((source / "real_structure_starts.json").read_text(encoding="utf-8"))
    if truth["game_version"] != minecraft or truth["known_seed"] != 2731:
        raise ValueError("controlled saved world scope mismatch")
    starts = truth["real_structure_starts"]
    if len(starts) < 4 or len({(row["start_chunk_x"] // 32, row["start_chunk_z"] // 32)
                              for row in starts}) < 4:
        raise ValueError("need four independent true StructureStart regions for 3FIT+1HOLDOUT")
    prefix = "vanilla_" + minecraft + "_seed2731"
    quart_name = prefix + "_quart.csv"
    starts_name = prefix + "_structures.csv"
    quart_bytes = select_quart_fixture(source / "real_biome_quart_samples.csv").encode("utf-8")
    starts_bytes = (source / "real_structure_starts.csv").read_bytes()
    (output / quart_name).write_bytes(quart_bytes)
    (output / starts_name).write_bytes(starts_bytes)
    entry = {
        "file": quart_name,
        "sha256": hashlib.sha256(quart_bytes).hexdigest(),
        "provenance": "REAL_MINECRAFT_FULL_CHUNK",
        "known_seed": "2731",
        "sample_count": 5,
        "source_run_url": run_url,
        "structure_starts": {
            "file": starts_name,
            "sha256": hashlib.sha256(starts_bytes).hexdigest(),
            "provenance": "REAL_MINECRAFT_STRUCTURE_START",
            "sample_count": len(starts),
            "source_run_url": run_url,
        },
    }
    provenance = {
        "schema": 1, "minecraft": minecraft, "known_seed": "2731",
        "source_run_url": run_url, "source_commit": os.environ.get("GITHUB_SHA"),
        "official_version_url": versions[0]["url"],
        "official_version_sha1": versions[0]["sha1"],
        "official_server_url": server["url"], "official_server_sha1": server["sha1"],
        "source": "saved_vanilla_FULL_chunk_NBT",
        "quart_split": "first 3 FIT, last 2 HOLDOUT",
        "manifest_entry": entry,
        "note": "Public controlled server truth; not CLIENT_OBSERVED or an unknown server seed.",
    }
    for name in ("real_structure_starts.json", "real_structure_geometry.csv"):
        (output / name).write_bytes((source / name).read_bytes())
    (output / "provenance.json").write_text(json.dumps(provenance, indent=2) + "\n", encoding="utf-8")
    # 公开受控小文件同时输出到日志，便于核对下载工件的精确字节与来源。
    for name in (quart_name, starts_name, "real_structure_geometry.csv", "provenance.json"):
        content = (output / name).read_text(encoding="utf-8")
        print("P45_FILE_BEGIN " + name, flush=True)
        print(content, end="" if content.endswith("\n") else "\n", flush=True)
        print("P45_FILE_END " + name, flush=True)


if __name__ == "__main__":
    main()
