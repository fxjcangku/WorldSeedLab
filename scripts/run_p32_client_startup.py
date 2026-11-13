#!/usr/bin/env python3
"""在隔离 CI 桌面验证真实客户端窗口和渲染，不以模组初始化代替 GUI 成功。"""
from __future__ import annotations

import argparse
import hashlib
import json
from pathlib import Path
import re
import time
import subprocess

from PIL import Image, ImageStat

from minecraft_versions import SUPPORTED_MINECRAFT_VERSIONS
from run_p33_real_client_e2e import (
    ROOT, ManagedProcess, controlled_gui_environment,
    visible_minecraft_window, wait_until, xcmd,
)


FATAL_CLIENT = re.compile(
    r'Exception in thread "Render thread"|ModLoadingException|'
    r'WorldSeedLab bootstrap rejected|---- Minecraft Crash Report ----'
)


def verify_client(client: ManagedProcess):
    """任何真实进程退出或渲染线程崩溃均使验收失败。"""
    if client.process.poll() is not None:
        raise RuntimeError("真实 Fabric 客户端提前退出: " + client.tail())
    text = client.log.read_text(encoding="utf-8", errors="replace")
    if FATAL_CLIENT.search(text):
        raise RuntimeError("真实 Fabric 客户端崩溃: " + client.tail())
    return text


def frame_details(path: Path):
    """检查游戏窗口中央内容，排除空白窗口和仅窗口管理器边框。"""
    with Image.open(path) as source:
        frame = source.convert("RGB")
        width, height = frame.size
        if width < 640 or height < 360:
            return None
        center = frame.crop((width // 10, height // 10,
                             width * 9 // 10, height * 9 // 10))
        sample = center.resize((128, 72))
        colors = len(set(sample.getdata()))
        variance = max(ImageStat.Stat(sample).var)
        if colors < 16 or variance < 10:
            return None
    return {"width": width, "height": height, "sample_colors": colors,
            "sample_variance": round(variance, 3),
            "sha256": hashlib.sha256(path.read_bytes()).hexdigest()}


def run(version: str):
    out = ROOT / "build" / "p32-client-startup" / version
    out.mkdir(parents=True, exist_ok=True)
    options = ROOT / "run" / "options.txt"
    options.parent.mkdir(exist_ok=True)
    # 仅新建的 CI 客户端跳过首次无障碍欢迎页；已有用户配置必须保留。
    with options.open("x", encoding="utf-8") as handle:
        handle.write("onboardAccessibility:false\nnarrator:0\npauseOnLostFocus:false\n")
    env = controlled_gui_environment(version)
    display = ManagedProcess(
        ["Xvfb", env["DISPLAY"], "-screen", "0", "1280x720x24", "-nolisten", "tcp"],
        out / "xvfb.log", env)
    wm = game_wm = client = None
    try:
        wait_until(lambda: subprocess.run(
            ["xdpyinfo"], env=env, stdout=subprocess.DEVNULL,
            stderr=subprocess.DEVNULL, timeout=10).returncode == 0,
            15, "隔离 X11 桌面未就绪", display)
        wm = ManagedProcess(["openbox", "--sm-disable"], out / "openbox.log", env)
        client = ManagedProcess(
            ["gradle", "--no-daemon", "--console=plain", "-PmcVersion=" + version,
             "runClient", "--args=--width 1024 --height 680"], out / "client.log", env)

        ready = "WorldSeedLab P1 ready for Minecraft " + version

        def graphics_ready():
            log = verify_client(client)
            loaded = ready in log and "biome capture disabled until manual activation" in log
            atlas = any("Created: " in line and "-atlas" in line
                        and "Render thread/INFO" in line for line in log.splitlines())
            native_window = version != "26.3" or "Created window using SDL video driver: x11" in log
            return loaded and atlas and native_window and visible_minecraft_window(env) is not None

        wait_until(graphics_ready, 240,
                   "实际游戏窗口、图形后端与纹理 atlas 尚未全部就绪", client, display, wm)
        window, actual_env = visible_minecraft_window(env)
        if actual_env["DISPLAY"] != env["DISPLAY"]:
            game_wm = ManagedProcess(["openbox", "--sm-disable"],
                                     out / "game-openbox.log", actual_env)
            time.sleep(2)
        xcmd("windowraise", window, env=actual_env)
        xcmd("windowfocus", "--sync", window, env=actual_env)

        proof = None
        for attempt in range(8):
            time.sleep(2)
            verify_client(client)
            observed = visible_minecraft_window(env)
            if observed is None or observed[0] != window:
                raise RuntimeError("已初始化的 Minecraft 图形窗口已消失")
            if xcmd("getwindowfocus", env=actual_env) != window:
                raise RuntimeError("截图焦点不是实际 Minecraft 窗口")
            frame = out / ("rendered-window-" + str(attempt) + ".png")
            subprocess.run(["scrot", "--focused", str(frame)], env=actual_env,
                           check=True, timeout=15)
            proof = frame_details(frame)
            if proof is not None:
                proof["file"] = frame.name
                break
        if proof is None:
            raise RuntimeError("实际 Minecraft 窗口未出现可辨识渲染内容")
        verify_client(client)
        report = {"game_version": version, "client_initialized": True,
                  "capture_disabled": True, "window_mapped": True,
                  "atlas_created": True, "rendered_frame": proof}
        (out / "startup.json").write_text(json.dumps(report, indent=2) + "\n",
                                           encoding="utf-8")
        print("[P32] Real Fabric window, texture atlas and rendered frame verified for "
              + version + "; collection remains disabled.", flush=True)
        print("[P32] " + json.dumps(report, sort_keys=True), flush=True)
        print("[P32] This startup check does not establish world sampling or seed recovery.",
              flush=True)
    finally:
        if client is not None:
            print("[P32] ACTUAL CLIENT LOG TAIL:\n" + client.tail(), flush=True)
        for process in (client, game_wm, wm, display):
            if process is not None:
                process.stop()


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--minecraft", choices=SUPPORTED_MINECRAFT_VERSIONS, required=True)
    run(parser.parse_args().minecraft)
