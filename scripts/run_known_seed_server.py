#!/usr/bin/env python3
"""启动真实 Minecraft 的已知 Seed 离线世界，读取已保存的 StructureStart NBT。

该工具只在 GitHub Actions 上生成自建受控世界，不连接第三方服务器。
真实 NBT 起点只证明世界生成；不是客户端可见建筑轮廓。
"""
from __future__ import annotations

import argparse
import gzip
import json
from pathlib import Path
import re
import secrets
import socket
import struct
import subprocess
import threading
import time
import zlib

ROOT = Path(__file__).resolve().parents[1]
KNOWN_SEED = 2731
STRUCTURE = "minecraft:desert_pyramid"
POSITION = re.compile(r"\[\s*(-?\d+)\s*,\s*(?:~|-?\d+)\s*,\s*(-?\d+)\s*\]")
QUERY_POINTS = [(-14000, -14000), (0, 0), (14000, 14000),
                (-14000, 14000), (14000, -14000)]


class NbtReader:
    """只读有界 big-endian NBT，避免对实验输入信任任意长度。"""
    def __init__(self, payload: bytes):
        if len(payload) > 8_000_000:
            raise ValueError("NBT too large")
        self.data = memoryview(payload)
        self.offset = 0

    def read(self, n: int) -> bytes:
        if n < 0 or self.offset + n > len(self.data):
            raise ValueError("invalid NBT length")
        i = self.offset
        self.offset += n
        return self.data[i:self.offset].tobytes()

    def num(self, fmt: str):
        return struct.unpack(">" + fmt, self.read(struct.calcsize(">" + fmt)))[0]

    def string(self):
        n = self.num("H")
        if n > 16384:
            raise ValueError("oversized NBT key")
        return self.read(n).decode("utf-8")

    def tag(self, typ: int, depth: int = 0):
        if depth > 48:
            raise ValueError("NBT nesting limit")
        scalar = {1:"b", 2:"h", 3:"i", 4:"q", 5:"f", 6:"d"}
        if typ in scalar:
            return self.num(scalar[typ])
        if typ == 8:
            return self.string()
        if typ == 7:
            n = self.num("i")
            if n < 0 or n > 8_000_000:
                raise ValueError("invalid byte array")
            return self.read(n)
        if typ == 9:
            child, count = self.num("B"), self.num("i")
            if count < 0 or count > 250_000:
                raise ValueError("invalid NBT list")
            return [self.tag(child, depth + 1) for _ in range(count)]
        if typ == 10:
            result = {}
            while True:
                child = self.num("B")
                if child == 0:
                    return result
                if len(result) >= 50_000:
                    raise ValueError("oversized NBT compound")
                # Python 先求赋值右侧，再求下标表达式；必须显式先读取 NBT 名称，
                # 否则会把 payload 当作名称解析，导致读指针错位。
                name = self.string()
                value = self.tag(child, depth + 1)
                result[name] = value
        if typ in (11, 12):
            count = self.num("i")
            unit = 4 if typ == 11 else 8
            if count < 0 or count * unit > 8_000_000:
                raise ValueError("invalid NBT array")
            return list(struct.unpack(">" + ("i" if typ == 11 else "q") * count,
                                      self.read(count * unit)))
        raise ValueError("unknown NBT type: " + str(typ))

    def document(self):
        if self.num("B") != 10:
            raise ValueError("expected compound document")
        self.string()
        return self.tag(10)


def region_chunks(path: Path):
    data = path.read_bytes()
    # Mojang 的 region storage 可能先创建零字节占位文件；
    # 尚未写入任何区块的空文件不等于损坏的已生成区块。
    if not data:
        return
    if len(data) < 8192:
        raise ValueError("short region header")
    for slot in range(1024):
        word = int.from_bytes(data[slot*4:slot*4+4], "big")
        start = (word >> 8) * 4096
        if not start:
            continue
        if start + 5 > len(data):
            raise ValueError("invalid region sector")
        n = int.from_bytes(data[start:start+4], "big")
        if n < 2 or n > 8_000_000 or start + n + 4 > len(data):
            raise ValueError("invalid region chunk")
        mode = data[start+4]
        compressed = data[start+5:start+4+n]
        if mode == 1:
            raw = gzip.decompress(compressed)
        elif mode == 2:
            raw = zlib.decompress(compressed)
        elif mode == 3:
            raw = compressed
        else:
            raise ValueError("unsupported chunk compression: " + str(mode))
        yield NbtReader(raw).document()


def scan_structure_starts(world: Path, located):
    result = {}
    read_chunks = 0
    targets = {(x//16, z//16) for x,z in located}
    showed_target = set()
    # Minecraft Java 26.1+ 官方世界存档布局：全部维度位于 dimensions/<namespace>/<name>。
    # 原版主世界不再位于 world/region；使用旧路径会伪报“没有结构”。
    overworld_regions = world / "dimensions" / "minecraft" / "overworld" / "region"
    if not overworld_regions.is_dir():
        raise RuntimeError("Minecraft 26.1+/26.2 overworld region directory missing")
    for region in sorted(overworld_regions.glob("r.*.*.mca")):
        for chunk in region_chunks(region):
            read_chunks += 1
            cxz = (chunk.get("xPos"),chunk.get("zPos"))
            if cxz in targets and cxz not in showed_target:
                showed_target.add(cxz)
                print("[P29-NBT] expected start-chunk", cxz,
                      "root keys", sorted(chunk.keys())[:35], flush=True)
                print("[P29-NBT] raw structure map (truncated):",
                      repr(chunk.get("structures", chunk.get("Structures", {})))[:1800],
                      flush=True)
            structures = chunk.get("structures", chunk.get("Structures", {}))
            if not isinstance(structures, dict):
                continue
            starts = structures.get("starts", structures.get("Starts", {}))
            if not isinstance(starts, dict):
                continue
            for key, start in starts.items():
                if not isinstance(start, dict):
                    continue
                sid = start.get("id", start.get("Id", ""))
                if (sid != STRUCTURE and key != STRUCTURE) or sid in ("", "INVALID"):
                    continue
                cx, cz = start.get("ChunkX"), start.get("ChunkZ")
                pieces = start.get("Children")
                if not (isinstance(cx, int) and isinstance(cz, int)
                        and isinstance(pieces, list) and len(pieces) > 0):
                    continue
                bb = start.get("BB")
                bb_source = "structure_start"
                if not (isinstance(bb, list) and len(bb) == 6):
                    # 26.1+/26.2: StructureStart NBT may omit its aggregate BB.
                    # Full generated piece extents are in Children[].BB, not at the root.
                    piece_boxes = [piece.get("BB") for piece in pieces
                                   if isinstance(piece, dict)]
                    if len(piece_boxes) != len(pieces) or any(
                            not isinstance(box, list) or len(box) != 6
                            or not all(isinstance(value, int) for value in box)
                            for box in piece_boxes):
                        continue
                    bb = [min(box[0] for box in piece_boxes),
                          min(box[1] for box in piece_boxes),
                          min(box[2] for box in piece_boxes),
                          max(box[3] for box in piece_boxes),
                          max(box[4] for box in piece_boxes),
                          max(box[5] for box in piece_boxes)]
                    bb_source = "union_of_serialized_structure_pieces"
                # 只有本区块保存的真实 start 才可成为权威起点。
                if chunk.get("xPos") != cx or chunk.get("zPos") != cz:
                    continue
                result[(cx, cz)] = {
                    "structure_id": str(sid),
                    "start_chunk_x": cx,
                    "start_chunk_z": cz,
                    "generated_structure_box": bb,
                    "generated_box_source": bb_source,
                    "piece_orientations": sorted(set(
                        int(piece["O"]) for piece in pieces
                        if isinstance(piece, dict) and isinstance(piece.get("O"), int))),
                    "piece_count": len(pieces),
                    "provenance": "saved_vanilla_structure_start_nbt",
                }
    return list(result.values()), read_chunks


def read_exact(sock, n):
    data = bytearray()
    while len(data) < n:
        fragment = sock.recv(n - len(data))
        if not fragment:
            raise ConnectionError("RCON disconnected")
        data.extend(fragment)
    return bytes(data)


def rcon_packet(sock):
    n = struct.unpack("<i", read_exact(sock, 4))[0]
    if n < 10 or n > 100_000:
        raise ValueError("RCON response length invalid")
    frame = read_exact(sock, n)
    request_id, packet_type = struct.unpack("<ii", frame[:8])
    return request_id, packet_type, frame[8:-2].decode("utf-8", "replace")


def rcon(command: str, password: str):
    with socket.create_connection(("127.0.0.1", 25575), timeout=15) as sock:
        sock.settimeout(90)
        def send(request_id, kind, text):
            data = struct.pack("<ii", request_id, kind) + text.encode() + b"\x00\x00"
            sock.sendall(struct.pack("<i", len(data)) + data)
        send(71, 3, password)
        for _ in range(4):
            ident, _, _ = rcon_packet(sock)
            if ident == 71:
                break
            if ident == -1:
                raise RuntimeError("RCON authentication failed")
        else:
            raise RuntimeError("RCON authentication response missing")
        send(72, 2, command)
        while True:
            ident, _, answer = rcon_packet(sock)
            if ident == 72:
                return re.sub(r"§[0-9a-fk-or]", "", answer)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--minecraft", choices=["26.1.2","26.2"], required=True)
    mc = parser.parse_args().minecraft
    rundir = ROOT / "run"
    rundir.mkdir(exist_ok=True)
    world_name = "worldseedlab_known_seed_" + mc.replace(".", "_")
    world = rundir / world_name
    if world.exists():
        raise RuntimeError("existing world cannot be reused for provenance test")
    password = secrets.token_hex(22)
    settings = {
        "level-name":world_name, "level-seed":str(KNOWN_SEED),
        "generate-structures":"true", "level-type":"minecraft:normal",
        "gamemode":"creative", "difficulty":"peaceful",
        "server-ip":"127.0.0.1", "server-port":"25565",
        "enable-rcon":"true", "rcon.port":"25575", "rcon.password":password,
        "online-mode":"false", "spawn-monsters":"false", "spawn-animals":"false",
        "spawn-npcs":"false", "max-players":"1", "view-distance":"3",
        "simulation-distance":"3", "spawn-protection":"0", "max-tick-time":"-1",
        "motd":"WorldSeedLab private known-seed test",
    }
    (rundir / "eula.txt").write_text("eula=true\n", encoding="utf-8")
    (rundir / "server.properties").write_text(
        "".join(k + "=" + v + "\n" for k,v in settings.items()), encoding="utf-8")
    output = ROOT / "build" / "p29-realworld" / mc
    output.mkdir(parents=True, exist_ok=True)
    process = subprocess.Popen([
        "gradle", "--no-daemon", "--console=plain", "-PmcVersion=" + mc,
        "runServer", "--args=nogui",
    ], cwd=ROOT, stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
       text=True, bufsize=1)
    logs = []
    ready = threading.Event()
    def receive_lines():
        for line in process.stdout:
            logs.append(line.rstrip())
            if len(logs) > 3000:
                del logs[:1000]
            if "Done (" in line and "For help" in line:
                ready.set()
            if any(w in line for w in ("Done (", "ERROR", "Exception",
                                      "Failed", "Starting Minecraft server",
                                      "Stopping server")):
                print("[minecraft]", line.rstrip()[:500], flush=True)
    threading.Thread(target=receive_lines, daemon=True).start()
    try:
        if not ready.wait(180):
            raise RuntimeError("server never reached Done: " + " | ".join(logs[-25:]))
        print("[P29-REAL] Actual Minecraft server started; controlled seed = 2731",
              flush=True)
        located = []
        for x,z in QUERY_POINTS:
            answer = rcon(
                "execute in minecraft:overworld positioned " + str(x)
                + " 80 " + str(z) + " run locate structure " + STRUCTURE,
                password)
            print("[P29-REAL] locate from", x,z, answer[:320], flush=True)
            hit = POSITION.search(answer)
            if not hit:
                continue
            point = (int(hit.group(1)),int(hit.group(2)))
            if all(max(abs(point[0]-prior[0]),abs(point[1]-prior[1])) > 512
                   for prior in located):
                located.append(point)
            if len(located) >= 4:
                break
        if len(located) < 3:
            raise RuntimeError("less than three distinct generated locate results: "
                               + repr(located))
        for x,z in located:
            cx, cz = x//16, z//16
            answer = rcon("forceload add "
                          + str((cx-2)*16) + " " + str((cz-2)*16) + " "
                          + str((cx+2)*16) + " " + str((cz+2)*16), password)
            print("[P29-REAL] force:", answer[:250], flush=True)
        time.sleep(24)
        print("[P29-REAL] save:", rcon("save-all flush",password)[:250],flush=True)
        time.sleep(5)
        rcon("stop",password)
        process.wait(timeout=90)
        if process.returncode != 0:
            raise RuntimeError("Minecraft runServer exit code: " + str(process.returncode))
        # Loom 的运行工作目录可能不同于脚本的项目目录；
        # 只能选本轮新建的离线世界，先实际核实 level.dat 和 region 位置。
        saved_worlds = sorted(ROOT.rglob("level.dat"))
        print("[P29-REAL] saved levels:", [
            str(item.parent.relative_to(ROOT)) for item in saved_worlds], flush=True)
        matching = [p.parent for p in saved_worlds if p.parent.name == world_name]
        if len(matching) != 1:
            # CI 全新 checkout，正常只有一个世界；多份同名则明确拒绝混合。
            if matching:
                raise RuntimeError("ambiguous controlled world save")
            if len(saved_worlds) != 1:
                raise RuntimeError("cannot identify the actual offline world folder")
            world = saved_worlds[0].parent
        else:
            world = matching[0]
        inventory = sorted(p for p in world.rglob("*") if p.is_file())
        print("[P29-REAL] world save file inventory:", [
            (str(p.relative_to(world)),p.stat().st_size)
            for p in inventory[:25]], flush=True)
        region_files = sorted(
            (world / "dimensions" / "minecraft" / "overworld" / "region").glob("*"))
        print("[P29-REAL] Minecraft 26.1+ overworld dimension layout checked")
        print("[P29-REAL] world directory:", str(world.relative_to(ROOT)),
              "region files:", [(p.name, p.stat().st_size) for p in region_files[:12]],
              flush=True)
        starts, chunks = scan_structure_starts(world, located)
        if len(starts) < 3:
            # 只上传本机公开种子测试世界的一份小型生成区域，用于解析器调试；
            # 不包含任何私人服务器信息，也不上传整张世界。
            sample_cx,sample_cz = located[1][0]//16, located[1][1]//16
            sample_region = (world / "dimensions" / "minecraft" / "overworld"
                             / "region" / ("r." + str(sample_cx//32) + "."
                                           + str(sample_cz//32) + ".mca"))
            if sample_region.is_file() and 8192 <= sample_region.stat().st_size < 2_000_000:
                (output / "controlled_debug_region.mca").write_bytes(
                    sample_region.read_bytes())
        fixture = {
            "game_version":mc, "known_seed":KNOWN_SEED,
            "structure_id":STRUCTURE,
            "source":"locally_generated_real_Minecraft_world",
            "located_positions":located,
            "saved_chunk_count":chunks, "real_structure_starts":starts,
            "note":"Server-side StructureStart, NOT client-observed building perimeter",
        }
        (output / "real_structure_starts.json").write_text(
            json.dumps(fixture,indent=2,ensure_ascii=False),encoding="utf-8")
        (output / "real_structure_starts.csv").write_text(
            "chunk_x,chunk_z\n" + "".join(
                str(s["start_chunk_x"]) + "," + str(s["start_chunk_z"]) + "\n"
                for s in starts),encoding="utf-8")
        print("[P29-REAL] Saved chunk count:", chunks, flush=True)
        print("[P29-REAL] Actual StructureStart count:", len(starts), flush=True)
        if len(starts) < 3:
            raise AssertionError("Need >=3 true naturally generated structure starts; "
                                 "got " + str(len(starts)) + ". Check region files.")
    except BaseException:
        if process.poll() is None:
            try:
                rcon("stop",password)
            except Exception:
                process.terminate()
            try:
                process.wait(timeout=15)
            except subprocess.TimeoutExpired:
                process.kill()
                process.wait(timeout=5)
        (output/"server_log_tail.txt").write_text(
            "\n".join(logs[-250:]),encoding="utf-8")
        raise


if __name__ == "__main__":
    main()
