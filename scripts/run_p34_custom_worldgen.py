#!/usr/bin/env python3
"""P3.4：真正启动 Minecraft 专用服务端，生成自定义平坦地形与原版沙漠神殿。

此脚本只在全新隔离 CI 的公开 Seed 测试世界运行；RCON/forceload/存档NBT
均为受控世界测试专用，不能包装成普通 Fabric 客户端拥有的服务端权限。
客户端真实轮廓取证另行验收；本脚本只生成可追溯的服务端真值。
"""
from __future__ import annotations

import argparse
import json
from pathlib import Path
import secrets
import subprocess
import threading
import time

from run_known_seed_server import (
    ROOT, KNOWN_SEED, POSITION, STRUCTURE, QUERY_POINTS, rcon, region_chunks,
    scan_structure_starts, scan_observed_biome_quart_samples,
)

VANILLA_DESERT_SALT = 14357617
MODIFIED_DESERT_SALT = 14357844
DATAPACK_NAME = "worldseedlab_p34_modified_salt"


def install_modified_salt_pack(world: Path, version: str) -> None:
    """只在全新隔离实验世界写入数据包；普通Fabric客户端无服务端写入功能。

    与目标Minecraft的数据包版本精确匹配。随后须由游戏自己确认启用，
    最终还要用真实已生成StructureStart的偏移核对，不能仅信任此文件。
    """
    formats = {"26.1.2": [101, 1], "26.2": [107, 1]}
    pack_version = formats[version]
    target = world / "datapacks" / DATAPACK_NAME
    location = target / "data" / "minecraft" / "worldgen" / "structure_set"
    location.mkdir(parents=True, exist_ok=False)
    (target / "pack.mcmeta").write_text(json.dumps({
        "pack": {
            "description": "WorldSeedLab P3.4 controlled modified-salt structure set",
            "min_format": pack_version,
            "max_format": pack_version,
        }
    }, indent=2) + "\n", encoding="utf-8")
    payload = {
        "structures": [{"structure": "minecraft:desert_pyramid", "weight": 1}],
        "placement": {
            "type": "minecraft:random_spread",
            "spacing": 32,
            "separation": 8,
            "salt": MODIFIED_DESERT_SALT,
        },
    }
    (location / "desert_pyramids.json").write_text(
        json.dumps(payload, indent=2) + "\n", encoding="utf-8")


GENERATOR = {
    "biome": "minecraft:desert",
    "features": False,
    "lakes": False,
    "layers": [
        {"block": "minecraft:bedrock", "height": 1},
        {"block": "minecraft:sandstone", "height": 124},
        {"block": "minecraft:sand", "height": 4},
    ],
    "structure_overrides": ["minecraft:desert_pyramids"],
}


def scan_custom_terrain(world: Path):
    """从 FULL Anvil 区块 section 取证，而非只相信 server.properties 声明。"""
    regions = world / "dimensions/minecraft/overworld/region"
    if not regions.is_dir():
        raise AssertionError("26.x 主世界真实 region 目录缺失")
    checked = 0
    sandstone_sections = 0
    sand_sections = 0
    for file in sorted(regions.glob("r.*.*.mca")):
        for chunk in region_chunks(file):
            if chunk.get("Status") not in ("full", "minecraft:full"):
                continue
            checked += 1
            for section in chunk.get("sections", []):
                if not isinstance(section, dict):
                    continue
                palette = section.get("block_states", {}).get("palette", [])
                names = {v.get("Name") for v in palette if isinstance(v, dict)}
                if section.get("Y") == 3 and "minecraft:sandstone" in names:
                    sandstone_sections += 1
                if section.get("Y") == 4 and "minecraft:sand" in names:
                    sand_sections += 1
    if checked < 5 or sandstone_sections < 5 or sand_sections < 5:
        raise AssertionError(
            f"没有在真实FULL区块中确认自定义地层：FULL={checked},"
            f"sandstone={sandstone_sections}, sand={sand_sections}")
    return {"full_chunks": checked, "sandstone_sections": sandstone_sections,
            "sand_sections": sand_sections}


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--minecraft", choices=["26.1.2", "26.2"], required=True)
    parser.add_argument("--modified-salt", action="store_true",
                        help="仅受控实验：在全新世界真实替换神殿结构集Salt")
    args = parser.parse_args()
    version = args.minecraft
    root = ROOT / "run"
    root.mkdir(exist_ok=True)
    name = ("worldseedlab_p34_changed_salt_" if args.modified_salt
            else "worldseedlab_p34_custom_flat_") + version.replace(".", "_")
    if (root / name).exists():
        raise RuntimeError("旧世界目录已存在：拒绝复用带历史区块的存档")
    output = ROOT / ("build/p34-modified-salt" if args.modified_salt
                     else "build/p34-controlled") / version
    output.mkdir(parents=True, exist_ok=True)
    if args.modified_salt:
        install_modified_salt_pack(root / name, version)
    secret = secrets.token_hex(20)
    props = {
        "level-name": name,
        "level-seed": str(KNOWN_SEED),
        "level-type": "flat",
        "generator-settings": json.dumps(GENERATOR, separators=(",", ":")),
        "generate-structures": "true",
        "gamemode": "creative", "difficulty": "peaceful",
        "server-ip": "127.0.0.1", "server-port": "25565",
        "enable-rcon": "true", "rcon.port": "25575", "rcon.password": secret,
        "online-mode": "false", "spawn-monsters": "false",
        "spawn-animals": "false", "spawn-npcs": "false",
        "max-players": "1", "view-distance": "3",
        "simulation-distance": "3", "spawn-protection": "0",
        "max-tick-time": "-1", "motd": "WorldSeedLab controlled custom terrain",
    }
    (root / "eula.txt").write_text("eula=true\n", encoding="utf-8")
    (root / "server.properties").write_text(
        "".join(f"{k}={v}\n" for k, v in props.items()), encoding="utf-8")
    process = subprocess.Popen(
        ["gradle", "--no-daemon", "--console=plain",
         "-PmcVersion=" + version, "runServer", "--args=nogui"],
        cwd=ROOT, stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
        text=True, bufsize=1,
    )
    recent = []
    ready = threading.Event()

    def consume():
        for line in process.stdout:
            recent.append(line.rstrip())
            if len(recent) > 700:
                del recent[:200]
            if "Done (" in line and "For help" in line:
                ready.set()
            if any(key in line for key in ("Done (", "Exception", "ERROR",
                                            "Starting Minecraft server")):
                print("[P34-SERVER]", line.rstrip()[:400], flush=True)
    threading.Thread(target=consume, daemon=True).start()
    try:
        if not ready.wait(200):
            raise RuntimeError("真实Minecraft自定义服务端没有启动: "
                               + " | ".join(recent[-35:]))
        pack_verified = False
        if args.modified_salt:
            enabled = rcon("datapack list enabled", secret)
            print("[P34-SALT] Enabled packs:", enabled[:450], flush=True)
            if DATAPACK_NAME not in enabled:
                raise AssertionError("修改Salt的数据包未被真正的Minecraft服务端启用")
            pack_verified = True
        found = []
        for x, z in QUERY_POINTS:
            answer = rcon("execute in minecraft:overworld positioned "
                          + f"{x} 75 {z} run locate structure {STRUCTURE}", secret)
            print("[P34-LOCATE]", x, z, answer[:260], flush=True)
            match = POSITION.search(answer)
            if match is None:
                continue
            site = (int(match.group(1)), int(match.group(2)))
            if all(max(abs(site[0]-prior[0]), abs(site[1]-prior[1])) > 1024
                   for prior in found):
                found.append(site)
        if len(found) < 4:
            raise AssertionError("自定义平坦地形未找到>=4个真实沙漠神殿，"
                                 "不能宣称保留了原版结构生成："
                                 + str(found))
        for x, z in found:
            cx, cz = x // 16, z // 16
            result = rcon(f"forceload add {(cx-2)*16} {(cz-2)*16} "
                          f"{(cx+2)*16} {(cz+2)*16}", secret)
            print("[P34-FORCE]", result[:200], flush=True)
        time.sleep(26)
        rcon("save-all flush", secret)
        time.sleep(5)
        rcon("stop", secret)
        process.wait(timeout=110)
        if process.returncode != 0:
            raise RuntimeError(f"runServer退出码{process.returncode}")
        worlds = [x.parent for x in ROOT.rglob("level.dat") if x.parent.name == name]
        if len(worlds) != 1:
            raise RuntimeError("受控世界目录歧义/缺失: " + str(worlds))
        world = worlds[0]
        terrain = scan_custom_terrain(world)
        starts, scanned = scan_structure_starts(world, found)
        if len(starts) < 3:
            raise AssertionError("自定义世界真实Anvil StructureStart <3，"
                                 "仅/locate不构成实际生成")
        quart = scan_observed_biome_quart_samples(world)
        if len(quart) < 5:
            raise AssertionError("自定义世界独立FULL群系点不足5")
        if any(b != "minecraft:desert" for x, y, z, b in quart[:5]):
            raise AssertionError("自定义平坦生成器的沙漠群系并非实际FULL真值")
        (output / "real_structure_starts.csv").write_text(
            "chunk_x,chunk_z\n" + "".join(
                f"{s['start_chunk_x']},{s['start_chunk_z']}\n" for s in starts),
            encoding="utf-8")
        (output / "real_custom_quart.csv").write_text(
            "quart_x,quart_y,quart_z,biome_id\n"
            + "".join(f"{x},{y},{z},{b}\n" for x, y, z, b in quart[:5]),
            encoding="utf-8")
        summary = {
            "game_version": version, "world_seed": KNOWN_SEED,
            "generation_mode": "actual_minecraft_flat_generator_with_desert_pyramids",
            "generator_settings": GENERATOR,
            "structure_salt": (MODIFIED_DESERT_SALT if args.modified_salt
                               else VANILLA_DESERT_SALT),
            "modified_salt_datapack_enabled": pack_verified,
            "salt_test_kind": ("REAL_ENABLED_DATAPACK_MODIFIED_SALT"
                               if args.modified_salt else "VANILLA_STRUCTURE_SET_SALT"),
            "terrain_provenance": "actual_FULL_Anvil_block_states",
            "structure_provenance": "actual_saved_ServerSide_StructureStart_NBT",
            "quart_provenance": "actual_saved_ServerSide_FULL_Anvil_biomes",
            "not_client_observed": True,
            "not_unknown_custom_server": True,
            "custom_terrain": terrain,
            "located_count": len(found),
            "saved_structure_starts": len(starts),
            "real_biome_count": len(quart),
            "scanned_chunks": scanned,
        }
        (output / "controlled_summary.json").write_text(
            json.dumps(summary, ensure_ascii=False, indent=2), encoding="utf-8")
        print("[P34-SUCCESS]", json.dumps(summary, ensure_ascii=False), flush=True)
    except BaseException:
        (output / "server_log_tail.txt").write_text(
            "\n".join(recent[-260:]), encoding="utf-8")
        if process.poll() is None:
            try:
                rcon("stop", secret)
            except (OSError, RuntimeError):
                process.terminate()
            try:
                process.wait(timeout=15)
            except subprocess.TimeoutExpired:
                process.kill()
                process.wait(timeout=5)
        raise


if __name__ == "__main__":
    main()
