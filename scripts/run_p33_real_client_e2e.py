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

from run_known_seed_server import rcon, region_chunks
from run_p34_custom_worldgen import GENERATOR
import json

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


def client_display_environments(base_env):
    """Fabric Loom's runClient uses nested xvfb-run, potentially DISPLAY=:99.

    The actual game JVM's DISPLAY/XAUTHORITY are authoritative; the outer Gradle
    task and our script can be on a different virtual desktop. Never choose
    a window on the wrong display to fake a successful client interaction.
    """
    yield base_env
    found = {(base_env.get("DISPLAY"), base_env.get("XAUTHORITY"))}
    for item in Path("/proc").glob("[0-9]*/cmdline"):
        try:
            command = item.read_bytes().replace(b"\x00", b" ").decode("utf-8", "replace")
            if not any(part in command for part in (
                    "net.fabricmc.devlaunchinjector", "net.fabricmc.loader.impl.launch",
                    "net.minecraft.client.main.Main")):
                continue
            values = dict(field.split("=", 1)
                for field in (chunk.decode("utf-8", "replace")
                    for chunk in (item.parent / "environ").read_bytes().split(b"\x00"))
                if "=" in field and field.split("=",1)[0] in
                    ("DISPLAY", "XAUTHORITY", "XDG_SESSION_TYPE"))
            display = values.get("DISPLAY")
            if not display:
                continue
            marker = (display, values.get("XAUTHORITY"))
            if marker in found:
                continue
            found.add(marker)
            local = dict(base_env)
            local.update(values)
            yield local
        except (OSError, PermissionError, UnicodeError, ValueError):
            continue


def visible_minecraft_window(base_env):
    # 只选择当前实际游戏JVM X11 display上的已映射窗口。
    for env in client_display_environments(base_env):
        for mode in (("--name", "Minecraft"), ("--class", "minecraft")):
            found = subprocess.run(["xdotool","search","--onlyvisible",*mode],
                                   env=env,text=True,capture_output=True,timeout=10)
            if found.returncode == 0 and found.stdout.strip():
                return found.stdout.strip().splitlines()[-1], env
    return None


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


def export_custom_world_full_truth(world_name: str, out: Path, positions):
    """只在受控服务器完成保存后，独立从真实FULL区块解出指定quart观测。

    自定义flat世界群系palette必须是唯一的minecraft:desert；不会对普通服
    读取服务端存档，也不能伪造客户端CLIENT_OBSERVED字段。
    """
    levels = [p.parent for p in ROOT.rglob("level.dat") if p.parent.name == world_name]
    if len(levels) != 1:
        raise RuntimeError("cannot uniquely locate controlled Minecraft world save")
    folder = levels[0] / "dimensions/minecraft/overworld/region"
    if not folder.is_dir():
        raise RuntimeError("26.x controlled FULL Anvil overworld region missing")
    verified = []
    for qx, qy, qz in positions:
        cx, cz = qx // 4, qz // 4
        file = folder / f"r.{cx // 32}.{cz // 32}.mca"
        if not file.is_file():
            raise RuntimeError("controlled FULL region missing for specified client sample")
        matched = [chunk for chunk in region_chunks(file)
                   if chunk.get("xPos") == cx and chunk.get("zPos") == cz]
        if len(matched) != 1 or matched[0].get("Status") not in ("full","minecraft:full"):
            raise RuntimeError("client-observed chunk not independently saved as FULL")
        section_y = (qy * 4) // 16
        sections = [s for s in matched[0].get("sections",[])
                    if isinstance(s,dict) and s.get("Y") == section_y]
        if len(sections) != 1:
            raise RuntimeError("independent FULL chunk contains no sample-height section")
        biomes = sections[0].get("biomes", {})
        palette = biomes.get("palette") if isinstance(biomes,dict) else None
        if palette != ["minecraft:desert"]:
            raise RuntimeError("custom-flat controlled world is not single desert biome "
                               + repr(palette)[:120])
        verified.append((qx, qy, qz, palette[0]))
    if len(verified) != 5 or len(set(verified)) != 5:
        raise AssertionError("P3.4 controlled independent full chunk samples incomplete")
    (out / "server-full-quart.csv").write_text(
        "quart_x,quart_y,quart_z,biome_id\n"
        + "".join(f"{x},{y},{z},{b}\n" for x,y,z,b in verified),
        encoding="utf-8")
    print("[P34] Independently inspected 5 saved FULL Anvil quart biomes: all "
          "minecraft:desert; source=CONTROL_WORLD_OBSERVED, not client", flush=True)


def run(version: str, custom_worldgen: bool = False):
    out = ROOT / "build" / ("p34-real-custom-client" if custom_worldgen
                            else "p33-real-client") / version
    out.mkdir(parents=True, exist_ok=True)
    world_name = ("wsl_p34_custom_client_" if custom_worldgen
                  else "wsl_p33_client_e2e_") + version.replace(".", "_")
    world = ROOT / "run" / world_name
    if world.exists() or list(out.glob("**/client-quart-*.csv")):
        raise RuntimeError("Controlled GUI E2E must start with an isolated world and no stale samples")
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
    if custom_worldgen:
        properties["level-type"] = "flat"
        properties["generator-settings"] = json.dumps(GENERATOR, separators=(",", ":"))
    (server_dir / "server.properties").write_text(
        "".join(k + "=" + v + "\n" for k, v in properties.items()), encoding="utf-8")
    (server_dir / "eula.txt").write_text("eula=true\n", encoding="utf-8")
    # Minecraft 自带首启偏好；全新 CI 必须跳过 Narrator/Accessibility 欢迎弹窗。
    # 这不会更改完整世界 Seed、生成器、客户端可见群系或取证数据。
    # 用户正式使用模组时不改变其已有游戏设置。
    client_options = server_dir / "options.txt"
    if client_options.exists():
        raise RuntimeError("new controlled P3.3 CI must not reuse old client options")
    client_options.write_text(
        "onboardAccessibility:false\nnarrator:0\npauseOnLostFocus:false\n",
        encoding="utf-8")
    env = os.environ.copy()
    env["DISPLAY"] = ":97"
    env["XDG_SESSION_TYPE"] = "x11"
    env.pop("WAYLAND_DISPLAY", None)
    env.pop("WAYLAND_SOCKET", None)
    env["LIBGL_ALWAYS_SOFTWARE"] = "1"
    display = subprocess.Popen(
        ["Xvfb",":97","-screen","0","1280x720x24","-nolisten","tcp"],
        cwd=ROOT, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL,
        start_new_session=True)
    server = None
    client = None
    wm = None
    game_wm = None
    input_env = env
    try:
        time.sleep(2)
        if display.poll() is not None:
            raise RuntimeError("Virtual X11 display exited before launching Minecraft")
        wm = subprocess.Popen(["openbox", "--sm-disable"], cwd=ROOT, env=env,
                              stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL,
                              start_new_session=True)
        time.sleep(1.5)
        if wm.poll() is not None:
            raise RuntimeError("Virtual display Openbox window manager did not start")
        server = ManagedProcess(
            ["gradle","--no-daemon","--console=plain","-PmcVersion="+version,
             "runServer","--args=nogui"], out / "server.log")
        wait_until(lambda:server.saw("Done (") and server.saw("For help"),
                   180, "controlled Minecraft server did not boot", server)
        print("[P34]" if custom_worldgen else "[P33]",
              "True controlled Minecraft server listening on loopback; public Seed 2731",
              flush=True)

        client = ManagedProcess(
            ["gradle","--no-daemon","--console=plain","-PmcVersion="+version,
             "runClient","--args=--quickPlayMultiplayer 127.0.0.1:25565 --width 1024 --height 680"],
            out / "client.log",env=env)
        wait_until(lambda:client.saw("WorldSeedLab P1 ready for Minecraft "+version),
                   180, "actual Fabric client mod did not initialize", server, client)
        # 软件GL在共享GitHub Runner上可能长时间编译shader/加载资源；
        # 必须等待游戏映射实际窗口，不能用仅有Mod初始化日志替代成功。
        wait_until(lambda:visible_minecraft_window(env) is not None,
                   140, "Minecraft window not created", server, client)
        window, input_env = visible_minecraft_window(env)
        if input_env["DISPLAY"] != env["DISPLAY"]:
            # Loom devLauncher is wrapped in its own Xvfb. Use its XAUTHORITY,
            # not the script's empty outer display where xdotool finds nothing.
            game_wm = subprocess.Popen(["openbox", "--sm-disable"],cwd=ROOT,
                       env=input_env,stdout=subprocess.DEVNULL,
                       stderr=subprocess.DEVNULL,start_new_session=True)
            time.sleep(2)
        print("[P33] Actual Fabric runClient X11 window is on "
              + input_env["DISPLAY"] + " (nested Loom display discovered)",flush=True)

        # 全新Minecraft客户端先显示Accessibility首次启动欢迎屏幕。
        # 真实用户必须按Continue才允许Quick Play继续；这里由X11模仿点击，
        # 禁止通过服务器后台或模组测试钩子伪造客户端操作。
        xcmd("windowfocus", "--sync", window, env=input_env)
        xcmd("mousemove", "--window", window, "512", "636", env=input_env)
        xcmd("click", "1", env=input_env)
        # X11中第一次鼠标操作可能只完成窗口聚焦，鼠标悬停不能视作已点按钮。
        # Welcome 页面底部 Continue 由 Enter 键真正确认，随后真实 Quick Play 开始连服。
        time.sleep(0.9)
        xcmd("key", "--clearmodifiers", "Return", env=input_env)
        time.sleep(4)
        screenshot(out / "controlled-client-after-welcome.png", input_env)
        print("[P33] Clicked then pressed Enter on actual Minecraft accessibility Continue",flush=True)

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
        match = visible_minecraft_window(env)
        if not match:
            raise RuntimeError("Minecraft game window vanished after login")
        window, input_env = match

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
            chat(window,"/wsl quart sample",input_env)
            print(f"[P33] Typed real CLIENT command {i+1}/5, "
                  f"at controlled block quart ({qx},{qy},{qz})",flush=True)

        chat(window,"/wsl quart export",input_env)
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
            print("[P34]" if custom_worldgen else "[P33]",
                  "Real /wsl quart export generated 1 HMAC-scoped CSV; "
                  "copying only PUBLIC controlled fixture for CI validation",flush=True)
            if custom_worldgen:
                # 先通过游戏聊天框产生CLIENT_OBSERVED，之后才从隔离服务器的
                # 实际FULL Anvil区块取得独立真值。两条来源从不互相生成。
                rcon("save-all flush", password)
                time.sleep(3)
                export_custom_world_full_truth(world_name, out, POSITIONS)
        finally:
            screenshot(out/"controlled-client-final.png",input_env)
    except Exception as e:
        if client:
            print("[P33] CLIENT LOG TAIL\n"+client.tail(),flush=True)
        if server:
            print("[P33] SERVER LOG TAIL\n"+server.tail(),flush=True)
        try:
            listing = subprocess.run(["xwininfo","-root","-tree"],env=env,
                    text=True,capture_output=True,timeout=8)
            print("[P33] X11 visible-window inventory:\n"+listing.stdout[-3500:],flush=True)
        except (OSError,subprocess.TimeoutExpired):
            pass
        # 最终定位显示隔离与渲染卡点；只打印JAVA子进程实际DISPLAY，
        # 不读取私人服务器记录、不打印GitHub secrets或其它进程环境。
        for candidate in Path("/proc").glob("[0-9]*/cmdline"):
            try:
                cmd = candidate.read_bytes().replace(b"\x00",b" ").decode("utf-8","replace")
                if ("net.fabricmc.devlaunchinjector" not in cmd
                        and "net.fabricmc.loader.impl.launch" not in cmd
                        and "net.minecraft.client.main.Main" not in cmd):
                    continue
                pid = int(candidate.parent.name)
                raw_env = (candidate.parent/"environ").read_bytes()
                display_vars = [item.decode("utf-8","replace")
                        for item in raw_env.split(b"\x00")
                        if item.startswith((b"DISPLAY=",b"WAYLAND_DISPLAY=",
                                            b"XDG_SESSION_TYPE="))]
                print("[P33] Client JVM display namespace: pid=" + str(pid)
                      + " " + repr(display_vars),flush=True)
                result = subprocess.run(["jcmd",str(pid),"Thread.print"],
                        capture_output=True,text=True,timeout=12)
                dump = result.stdout.splitlines()
                index = next((i for i,line in enumerate(dump)
                          if '"Render thread"' in line),-1)
                if index >= 0:
                    print("[P33] Render thread limited stack:\n"
                          + "\n".join(dump[index:index+13]),flush=True)
            except (OSError,PermissionError,ValueError,subprocess.TimeoutExpired):
                pass
        screenshot(out/"controlled-client-failure.png",input_env)
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
        if game_wm is not None and game_wm.poll() is None:
            os.killpg(game_wm.pid, signal.SIGTERM)
            try:
                game_wm.wait(timeout=4)
            except subprocess.TimeoutExpired:
                os.killpg(game_wm.pid, signal.SIGKILL)
                game_wm.wait(timeout=4)
        if wm is not None and wm.poll() is None:
            os.killpg(wm.pid, signal.SIGTERM)
            try:
                wm.wait(timeout=4)
            except subprocess.TimeoutExpired:
                os.killpg(wm.pid, signal.SIGKILL)
                wm.wait(timeout=4)
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
    ap.add_argument("--custom-worldgen", action="store_true",
                    help="P3.4 CI only: real Fabric client joins controlled custom flat world")
    parsed = ap.parse_args()
    run(parsed.minecraft, parsed.custom_worldgen)
