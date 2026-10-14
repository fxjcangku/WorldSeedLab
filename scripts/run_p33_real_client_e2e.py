#!/usr/bin/env python3
"""P3.3 真正的 Fabric 客户端与隔离原版服务器 GUI 联机取证实验。

两进程都是真实 Minecraft；通过虚拟显示器对真实聊天框输入客户端命令。
不调用服务器 Seed/NBT 接口作为群系样本，也不在模组中植入作弊测试钩子。
本程序只在专用 CI 机器、公开受控 Seed=2731 下运行，绝不连接私人服务器。
"""
from __future__ import annotations

import argparse
import collections
import os
from pathlib import Path
import re
import secrets
import signal
import subprocess
import threading
import time

from run_known_seed_server import rcon

ROOT = Path(__file__).resolve().parents[1]
SEED = 2731
POSITIONS = (
    (-4678, 18, -4190),
    (-3150, 18, 2982),
    (-482, 18, -438),
    (2, 18, 2),
    (3706, 18, 3150),
)
MIN_DIST = 256


class ManagedProcess:
    def __init__(self, command: list[str], log: Path, env: dict | None = None):
        log.parent.mkdir(parents=True, exist_ok=True)
        self.command = command
        self.log = log
        self.lines = collections.deque(maxlen=2000)
        self.logfile = log.open("w", encoding="utf-8")
        self.process = subprocess.Popen(
            command, cwd=ROOT, env=env, text=True, bufsize=1,
            stdout=subprocess.PIPE, stderr=subprocess.STDOUT, start_new_session=True)
        self.reader = threading.Thread(target=self._read, daemon=True)
        self.reader.start()

    def _read(self):
        try:
            for line in self.process.stdout:
                self.lines.append(line.strip())
                self.logfile.write(line)
                self.logfile.flush()
        finally:
            self.logfile.flush()

    def saw(self, phrase: str) -> bool:
        return any(phrase in line for line in self.lines)

    def tail(self) -> str:
        return "\n".join(list(self.lines)[-25:])

    def stop(self):
        if self.process.poll() is None:
            os.killpg(self.process.pid, signal.SIGINT)
            try:
                self.process.wait(timeout=15)
            except subprocess.TimeoutExpired:
                os.killpg(self.process.pid, signal.SIGKILL)
                self.process.wait(timeout=10)
        self.logfile.flush()
        self.logfile.close()


def wait_until(check, limit_s: float, description: str, *procs: ManagedProcess):
    until = time.monotonic() + limit_s
    while time.monotonic() < until:
        if check():
            return
        if any(proc.process.poll() is not None for proc in procs):
            raise RuntimeError(description + " (Minecraft process exited): "
                               + " | ".join(proc.tail()[-1600:] for proc in procs))
        time.sleep(1.5)
    raise RuntimeError(description + " (timeout): "
                       + " | ".join(proc.tail()[-1600:] for proc in procs))


def xcmd(*args, env):
    return subprocess.check_output(["xdotool", *args], env=env,
                                   text=True, timeout=15).strip()


def visible_minecraft_window(env):
    found = subprocess.run(["xdotool","search","--onlyvisible","--name","Minecraft"],
                           env=env, text=True, capture_output=True, timeout=10)
    return found.stdout.strip().splitlines()[-1] if found.returncode == 0         and found.stdout.strip() else None


def chat(window: str, command: str, env: dict):
    # 真实 GUI 交互：先聚焦游戏，再用 T 打开聊天栏，输入 client-only /wsl。
    xcmd("windowfocus", "--sync", window, env=env)
    xcmd("key", "--clearmodifiers", "t", env=env)
    time.sleep(0.6)
    xcmd("type", "--clearmodifiers", "--delay", "25", command, env=env)
    xcmd("key", "--clearmodifiers", "Return", env=env)
    time.sleep(1.4)


def screenshot(dst: Path, env: dict):
    try:
        subprocess.run(["scrot", str(dst)], env=env, timeout=10,
                       check=False, stdout=subprocess.DEVNULL,
                       stderr=subprocess.DEVNULL)
    except (OSError, subprocess.TimeoutExpired):
        pass


def run(version: str):
    out = ROOT / "build" / "p33-real-client" / version
    out.mkdir(parents=True, exist_ok=True)
    world_name = "wsl_p33_client_e2e_" + version.replace(".", "_")
    world = ROOT / "run" / world_name
    if world.exists() or list(out.glob("**/client-quart-*.csv")):
        raise RuntimeError("P3.3 CI must start with a new, isolated world and no stale samples")
    server_dir = ROOT / "run"
    server_dir.mkdir(exist_ok=True)
    password = secrets.token_hex(24)
    properties = {
        "level-name": world_name,
        "level-seed": str(SEED),
        "level-type": "minecraft:normal",
        "generate-structures": "true",
        "online-mode": "false",
        "server-ip": "127.0.0.1",
        "server-port": "25565",
        "enable-rcon": "true",
        "rcon.port": "25575",
        "rcon.password": password,
        "gamemode": "spectator",
        "difficulty": "peaceful",
        "spawn-monsters": "false",
        "spawn-animals": "false",
        "spawn-npcs": "false",
        "spawn-protection": "0",
        "max-players": "1",
        "view-distance": "3",
        "simulation-distance": "3",
        "max-tick-time": "-1",
    }
    (server_dir / "server.properties").write_text(
        "".join(k + "=" + v + "\n" for k, v in properties.items()), encoding="utf-8")
    (server_dir / "eula.txt").write_text("eula=true\n", encoding="utf-8")
    env = os.environ.copy()
    env["DISPLAY"] = ":97"
    env["LIBGL_ALWAYS_SOFTWARE"] = "1"
    display = subprocess.Popen(
        ["Xvfb",":97","-screen","0","1280x720x24","-nolisten","tcp"],
        cwd=ROOT, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL,
        start_new_session=True)
    server = None
    client = None
    try:
        time.sleep(2)
        if display.poll() is not None:
            raise RuntimeError("Virtual X11 display exited before launching Minecraft")
        server = ManagedProcess(
            ["gradle","--no-daemon","--console=plain","-PmcVersion="+version,
             "runServer","--args=nogui"], out / "server.log")
        wait_until(lambda:server.saw("Done (") and server.saw("For help"),
                   180, "controlled Minecraft server did not boot", server)
        print("[P33] True vanilla server listening on 127.0.0.1, public Seed 2731",
              flush=True)

        client = ManagedProcess(
            ["gradle","--no-daemon","--console=plain","-PmcVersion="+version,
             "runClient","--args=--server 127.0.0.1 --port 25565"],
            out / "client.log",env=env)
        wait_until(lambda:client.saw("WorldSeedLab P1 ready for Minecraft "+version),
                   180, "actual Fabric client mod did not initialize", server, client)
        wait_until(lambda:visible_minecraft_window(env) is not None,
                   40, "Minecraft window not created", server, client)
        print("[P33] Actual Fabric runClient initialized + X11 game window exists",
              flush=True)

        def player_name():
            try:
                response = rcon("list", password)
                match = re.search(r"players online:\s*([A-Za-z0-9_]+)", response)
                return match.group(1) if match else None
            except (TimeoutError,ConnectionError,OSError,RuntimeError):
                return None

        wait_until(lambda:player_name() is not None, 100,
                   "Fabric GUI client did not autojoin controlled server",
                   server,client)
        player = player_name()
        print("[P33] Actual Minecraft client connected over server protocol", flush=True)
        window = visible_minecraft_window(env)
        if not window:
            raise RuntimeError("Minecraft game window vanished after login")

        for i, (qx,qy,qz) in enumerate(POSITIONS):
            for ex,_,ez in POSITIONS[:i]:
                dx, dz = qx-ex, qz-ez
                if dx*dx+dz*dz < MIN_DIST*MIN_DIST:
                    raise AssertionError("controlled quart observations overlap")
            bx, by, bz = qx*4, qy*4, qz*4
            loaded = rcon(f"forceload add {bx} {bz}",password)
            if any(x in loaded.lower() for x in ["too many","failed","cannot"]):
                raise RuntimeError("Controlled forceload rejected: " + loaded[:160])
            teleported = rcon(f"tp {player} {bx} {by} {bz}",password)
            if "No entity was found" in teleported:
                raise RuntimeError("Player teleport failed")
            time.sleep(9)
            chat(window,"/wsl quart sample",env)
            print(f"[P33] Typed real CLIENT command {i+1}/5, "
                  f"at controlled block quart ({qx},{qy},{qz})",flush=True)

        chat(window,"/wsl quart export",env)
        try:
            match = []
            until = time.monotonic() + 18
            while time.monotonic() < until:
                match = list((ROOT / "run").rglob("client-quart-*.csv"))
                if match:
                    break
                time.sleep(1)
            if len(match) != 1:
                raise RuntimeError("Expected exactly one actual client-exported CSV, found "
                                   + str(len(match)))
            actual = match[0]
            if "p32-manual-quart" not in actual.parts:
                raise RuntimeError("Client export not in expected private scope")
            destination = out / "controlled-client-quart.csv"
            destination.write_bytes(actual.read_bytes())
            print("[P33] Real /wsl quart export generated 1 HMAC-scoped CSV; "
                  "copying only PUBLIC controlled fixture for CI validation",flush=True)
        finally:
            screenshot(out/"controlled-client-final.png",env)
    except Exception as e:
        if client:
            print("[P33] CLIENT LOG TAIL\n"+client.tail(),flush=True)
        if server:
            print("[P33] SERVER LOG TAIL\n"+server.tail(),flush=True)
        screenshot(out/"controlled-client-failure.png",env)
        raise
    finally:
        if client:
            client.stop()
        if server:
            try:
                rcon("stop",password)
            except (OSError,RuntimeError,TimeoutError):
                pass
            server.stop()
        if display.poll() is None:
            os.killpg(display.pid, signal.SIGTERM)
            try:
                display.wait(timeout=5)
            except subprocess.TimeoutExpired:
                os.killpg(display.pid, signal.SIGKILL)
                display.wait(timeout=5)


if __name__ == "__main__":
    ap=argparse.ArgumentParser()
    ap.add_argument("--minecraft", required=True, choices=("26.1.2","26.2"))
    run(ap.parse_args().minecraft)
