#!/usr/bin/env python3
"""P3.6 自动建筑与地形证据：真正Fabric GUI一次手动授权后自动完成建筑发现及原始栅格保存。

测试环境只使用公开 Seed=2731 和回环受控服务器。RCON /locate、
/tp、/forceload 仅用于布景和受控客户端导航；客户端取证代码不含
服务端 Seed、结构 NBT 或服务器管理权限。测试结束后的独立 Anvil
StructureStart 严格用于 posthoc 真值，不向客户端命令注入结构起点。
"""
from __future__ import annotations

import argparse
import json
import os
from pathlib import Path
import re
import secrets
import signal
import subprocess
import time

from run_known_seed_server import (
    ROOT, KNOWN_SEED, POSITION, QUERY_POINTS, STRUCTURE,
    rcon, scan_structure_starts, region_chunks,
)
from run_p34_custom_worldgen import GENERATOR, scan_custom_terrain
from run_p33_real_client_e2e import (
    ManagedProcess, wait_until, visible_minecraft_window, xcmd, chat, screenshot,
)

TARGET_COUNT = 4
SCAN_SECONDS = 37
PORT = "25565"


def run(version: str):
    out = ROOT / "build" / "p36-real-client-footprint" / version
    out.mkdir(parents=True, exist_ok=True)
    world_name = "wsl_p36_automatic_footprint_" + version.replace(".", "_")
    world = ROOT / "run" / world_name
    if world.exists() or list(ROOT.glob("run/**/client-auto-desert-*.properties")):
        raise RuntimeError("P3.6 真实测试必须新世界，不能复用旧的客户端取证")
    rundir = ROOT / "run"
    rundir.mkdir(exist_ok=True)
    secret = secrets.token_hex(24)
    props = {
        "level-name": world_name,
        "level-seed": str(KNOWN_SEED),
        "level-type": "flat",
        "generator-settings": json.dumps(GENERATOR,separators=(",",":")),
        "generate-structures": "true",
        "online-mode": "false",
        "server-ip": "127.0.0.1",
        "server-port": PORT,
        "enable-rcon": "true",
        "rcon.port": "25575",
        "rcon.password": secret,
        "gamemode": "spectator",
        "difficulty": "peaceful",
        "spawn-animals": "false",
        "spawn-monsters": "false",
        "spawn-npcs": "false",
        "spawn-protection": "0",
        "view-distance": "5",
        "simulation-distance": "4",
        "max-tick-time": "-1",
        "max-players": "1",
    }
    (rundir/"server.properties").write_text(
        "".join(k+"="+v+"\n" for k,v in props.items()),encoding="utf-8")
    (rundir/"eula.txt").write_text("eula=true\n",encoding="utf-8")
    options = rundir / "options.txt"
    if options.exists():
        raise RuntimeError("旧客户端选项存在，不能冒充首次真实测试")
    options.write_text(
        "onboardAccessibility:false\nnarrator:0\npauseOnLostFocus:false\n",
        encoding="utf-8")
    env = os.environ.copy()
    env.update({"DISPLAY":":97","XDG_SESSION_TYPE":"x11","LIBGL_ALWAYS_SOFTWARE":"1"})
    env.pop("WAYLAND_DISPLAY",None)
    env.pop("WAYLAND_SOCKET",None)
    display = subprocess.Popen(
        ["Xvfb",":97","-screen","0","1280x720x24","-nolisten","tcp"],
        cwd=ROOT,stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL,
        start_new_session=True)
    wm = None
    game_wm = None
    client = None
    server = None
    actual_env = env
    records = []
    try:
        time.sleep(2)
        if display.poll() is not None:
            raise RuntimeError("真实游戏X11显示器未启动")
        wm = subprocess.Popen(
            ["openbox","--sm-disable"],cwd=ROOT,env=env,
            stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL,
            start_new_session=True)
        time.sleep(1.5)
        if wm.poll() is not None:
            raise RuntimeError("真实游戏窗口管理器启动失败")
        server = ManagedProcess(
            ["gradle","--no-daemon","--console=plain","-PmcVersion="+version,
             "runServer","--args=nogui"],out/"server.log")
        wait_until(lambda: server.saw("Done (") and server.saw("For help"),
                   210,"真实自定义沙漠Minecraft服务端未启动",server)
        client = ManagedProcess(
            ["gradle","--no-daemon","--console=plain","-PmcVersion="+version,
             "runClient","--args=--quickPlayMultiplayer 127.0.0.1:"+PORT
             +" --width 1024 --height 680"],out/"client.log",env)
        wait_until(lambda: client.saw("WorldSeedLab P1 ready for Minecraft "+version),
                   210,"Fabric client模组未真正加载",client,server)
        wait_until(lambda: visible_minecraft_window(env) is not None,
                   160,"Fabric GUI实际窗口不存在",client,server)
        window, actual_env = visible_minecraft_window(env)
        if actual_env["DISPLAY"] != env["DISPLAY"]:
            game_wm = subprocess.Popen(
                ["openbox","--sm-disable"],cwd=ROOT,env=actual_env,
                stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL,
                start_new_session=True)
            time.sleep(2)
        xcmd("windowfocus","--sync",window,env=actual_env)
        xcmd("mousemove","--window",window,"512","636",env=actual_env)
        xcmd("click","1",env=actual_env)
        time.sleep(0.9)
        xcmd("key","--clearmodifiers","Return",env=actual_env)
        time.sleep(4)
        screenshot(out/"actual-client-welcome.png",actual_env)

        def actual_player():
            try:
                response = rcon("list",secret)
                match = re.search(r"players online:\s*([A-Za-z0-9_]+)",response)
                return match.group(1) if match else None
            except (OSError,RuntimeError,TimeoutError,ConnectionError):
                return None

        wait_until(lambda: actual_player() is not None,115,
                   "真实Fabric GUI未完成正常多人协议连接",client,server)
        player = actual_player()
        print("[P36] ACTUAL Fabric GUI client joined 127.0.0.1; "
              "controlled custom desert, original client commands",flush=True)

        # 真GUI受控玩家为spectator。不同版本初次连服偶发遮挡聊天的
        # Advancements / Friends List 弹窗：26.1.2 的真实失败截图出现
        # Advancements(Done)；26.2出现 Friends List(Turn OFF)。
        # 只在X11当前真实游戏窗口上点可见按钮，未出现弹窗则点击不会改世界。
        def dismiss_intro_screens():
            xcmd("windowfocus","--sync",window,env=actual_env)
            for local_x,local_y in ((512,665),(665,442),(512,665)):
                xcmd("mousemove","--window",window,
                     str(local_x),str(local_y),env=actual_env)
                xcmd("click","1",env=actual_env)
                time.sleep(0.25)

        # locate仅导航到受控已知世界的建筑附近，不把NBT结构起点传给客户端。
        located = []
        for from_x,from_z in QUERY_POINTS:
            answer = rcon(
                f"execute in minecraft:overworld positioned {from_x} 75 {from_z} "
                f"run locate structure {STRUCTURE}",secret)
            match = POSITION.search(answer)
            print("[P36] CONTROLLED navigation locate:",answer[:220],flush=True)
            if match is None:
                continue
            hit = (int(match.group(1)),int(match.group(2)))
            if all(max(abs(hit[0]-p[0]),abs(hit[1]-p[1]))>1024 for p in located):
                located.append(hit)
            if len(located)==TARGET_COUNT:
                break
        if len(located)<TARGET_COUNT:
            raise RuntimeError("隔离自建世界不足4座不同Region的真实神殿")

        # 先且仅一次由真实游戏聊天框手动授权：此后无需两个角或逐个export。
        # 自动取证同时带起原有 P1/P2 群系、地貌观测；不会取得服务端Salt。
        # “聊天尝试次数”和“实际被客户端执行的授权命令”必须区分：
        # 若UI拦截导致首轮输入根本没有进入聊天框，先从真实截图排查并
        # 关闭挡住的GUI，再重试键盘；只允许且硬验证一次真正成功的授权。
        time.sleep(2)
        for attempt in range(3):
            screenshot(out/f"auto-intro-preflight-{attempt}.png",actual_env)
            dismiss_intro_screens()
            if client.saw("P3.6 started:"):
                break
            chat(window, "/wsl auto on", actual_env)
            if client.saw("P3.6 started:"):
                break
            screenshot(out/f"auto-keyboard-blocked-{attempt}.png",actual_env)
            time.sleep(0.9)
        actual_starts = sum("P3.6 started:" in line for line in client.lines)
        if actual_starts != 1:
            raise RuntimeError("真实Fabric GUI必须恰好实际执行一次/wsl auto on，"
                               f"而非被进度/社交界面拦截；启动日志次数={actual_starts}")
        print("[P36] GUI opt-in verified: exactly one accepted /wsl auto on",
              flush=True)
        time.sleep(3)
        for ordinal,(x,z) in enumerate(located):
            # RCON只负责带玩家到附近，真正的蓝陶瓦位置/29x29轮廓完全由
            # 客户端已接收BlockState发现，绝不给客户端StructureStart真值。
            ax = (x//16)*16
            az = (z//16)*16
            forceload = rcon(
                f"forceload add {(x//16-2)*16} {(z//16-2)*16} "
                f"{(x//16+3)*16} {(z//16+3)*16}",secret)
            if any(bad in forceload.lower() for bad in
                   ("too many","cannot","failed")):
                raise RuntimeError("受控服务端未能生成自动观察所需FULL区块")
            # 完整神殿在chunk左上角往内约10格，玩家仅靠近建筑；
            # 绝不人工标记建筑两角或指定真实结构起点。
            rcon(f"tp {player} {ax+10} 75 {az+10}",secret)
            deadline = time.monotonic()+72
            target = []
            while time.monotonic()<deadline:
                target = list(rundir.rglob("client-auto-desert-*.properties"))
                if len(target)>=ordinal+1: break
                if client.process.poll() is not None:
                    raise RuntimeError("真实Fabric客户端在自动扫描途中退出")
                time.sleep(2)
            if len(target)!=ordinal+1:
                chat(window, "/wsl auto status", actual_env)
                screenshot(out/f"auto-not-exported-{ordinal}.png",actual_env)
                raise RuntimeError(f"未在真实普通Fabric客户端完成第{ordinal+1}/4座神殿"
                                   f"的自动存证：已发现{len(target)}份文件")
            newest = max(target,key=lambda p:p.stat().st_mtime_ns)
            result = out/f"client-footprint-{ordinal}.properties"
            result.write_bytes(newest.read_bytes())
            records.append((ordinal,x,z,ax+10,az+10))
            print(f"[P36] REAL AUTO GUI {ordinal+1}/{TARGET_COUNT}: "
                  f"zero per-structure manual commands, genuine CLIENT_OBSERVED "
                  f"auto file {result.name}",flush=True)
        chat(window, "/wsl auto status",actual_env)
        screenshot(out/"actual-client-last-automatic.png",actual_env)
        chat(window, "/wsl auto off",actual_env)
        time.sleep(5)
        # 自动模式必须真正联动P1群系+P2地形，而不只是保存建筑。
        # 不复制原始经纬坐标到公共Artifact，仅输出匿名数量统计。
        matches = list(rundir.rglob("client-auto-desert-*.properties"))
        if len(matches)!=TARGET_COUNT:
            raise RuntimeError("P3.6在停止时客户端自动结构原始文件数量改变")
        scoped_folder = matches[0].parent.parent
        if any(file.parent.parent!=scoped_folder for file in matches):
            raise RuntimeError("P3.6自动证据跨越世界指纹或维度，禁止合并")
        biome_evidence = scoped_folder/"evidence.jsonl"
        terrain_evidence = scoped_folder/"terrain.jsonl"
        if not biome_evidence.is_file() or not terrain_evidence.is_file():
            raise RuntimeError("自动模式未真实保存群系及地貌证据，仅结构成功不算完整P3.6")
        biome_count = len(biome_evidence.read_text(encoding="utf-8").splitlines())
        terrain_count = len(terrain_evidence.read_text(encoding="utf-8").splitlines())
        if biome_count < 1 or terrain_count < 1:
            raise RuntimeError("一键自动模式未产生真实非空客户端群系或地貌记录")
        structure_index = scoped_folder/"structure-scanned-v1.csv"
        completed_p26 = len(structure_index.read_text(encoding="utf-8").splitlines()) \
            if structure_index.is_file() else 0
        (out/"client-coupled-evidence-summary.json").write_text(
            json.dumps({
                "phase":"P3.6","version":version,
                "source":"ACTUAL_CLIENT_P1_P2_OBSERVATIONS",
                "manual_auto_on_commands":1,
                "manual_per_structure_corner_commands":0,
                "manual_per_structure_export_commands":0,
                "auto_structure_archives":TARGET_COUNT,
                "persisted_real_client_biome_records":biome_count,
                "persisted_real_client_terrain_chunks":terrain_count,
                "completed_p26_material_scans":completed_p26,
                "generator_identity":"UNKNOWN",
                "natural_structure_origin":"UNKNOWN",
                "raw_private_coordinates_uploaded":False
            },ensure_ascii=False,indent=2)+"\n",encoding="utf-8")
        print("[P36] REAL CLIENT JOINED P1/P2: "
              f"biome records={biome_count}, terrain chunks={terrain_count}, "
              f"P2.6 completed={completed_p26}; no raw world records exported",flush=True)
        (out/"navigation.csv").write_text(
            "order,locate_x,locate_z,controlled_nearby_x,controlled_nearby_z\n"
            +"".join(f"{i},{x},{z},{near_x},{near_z}\n"
                     for i,x,z,near_x,near_z in records),
            encoding="utf-8")
        # 所有客户端文件已经落盘；现在才从受控存档读取独立NBT起点真值。
        rcon("save-all flush",secret)
        time.sleep(6)
        rcon("stop",secret)
        wait_until(lambda:server.process.poll() is not None,
                   95,"隔离服务端未正常保存停机",server)
        worlds = [p.parent for p in ROOT.rglob("level.dat")
                  if p.parent.name==world_name]
        if len(worlds)!=1:
            raise RuntimeError("公开受控FULL世界存档不能唯一定位")
        real,scanned = scan_structure_starts(worlds[0],located)
        if scanned<50 or len(real)<TARGET_COUNT:
            raise RuntimeError("真值应来自真实FULL区块与StructureStart NBT，数量不足")
        # posthoc才读服务端FULL区块，不在客户端Controller中引入任何真值。
        # 强制检查本次四个可见建筑对应的独立 StructureStart 真的已保存为FULL，
        # 其余区域的已保存StructureStart不用于增加客户端样本量。
        expected = {(x//16,z//16) for x,z in located}
        observed_truth = [s for s in real
                          if (s["start_chunk_x"],s["start_chunk_z"]) in expected]
        if len(expected)!=TARGET_COUNT or len(observed_truth)!=TARGET_COUNT:
            raise RuntimeError("所测四座建筑必须各有一条独立服务端StructureStart")
        if any(len(s["piece_orientations"])!=1 for s in observed_truth):
            raise RuntimeError("受控结构朝向NBT缺失或出现歧义")
        directory=worlds[0]/"dimensions/minecraft/overworld/region"
        found_full=set()
        for region_x,region_z in sorted({(cx//32,cz//32) for cx,cz in expected}):
            file=directory/f"r.{region_x}.{region_z}.mca"
            if not file.is_file():
                raise RuntimeError("受控独立StructureStart所在Region缺失")
            for chunk in region_chunks(file):
                key=(chunk.get("xPos"),chunk.get("zPos"))
                if key not in expected:
                    continue
                if key in found_full:
                    raise RuntimeError("重复FULL区块，不准冒充独立StructureStart")
                if chunk.get("Status") not in ("full","minecraft:full"):
                    raise RuntimeError("StructureStart虽存在但所在区块不是真正FULL")
                found_full.add(key)
        if found_full!=expected:
            raise RuntimeError("客户端四座神殿的服务端独立FULL区块有缺失")
        terrain=scan_custom_terrain(worlds[0])
        (out/"posthoc-real-structure-starts.csv").write_text(
            "chunk_x,chunk_z,orientation,full_status\n"
            +"".join(f"{s['start_chunk_x']},{s['start_chunk_z']},"
                     f"{s['piece_orientations'][0]},minecraft:full\n"
                     for s in observed_truth),encoding="utf-8")
        (out/"posthoc-control-summary.json").write_text(
            json.dumps({
                "phase":"P3.6", "version":version, "controlled_seed":KNOWN_SEED,
                "source":"CONTROL_WORLD_POSTHOC_FULL_ANVIL_STRUCTURE_START",
                "not_client_observed":True, "visible_client_samples":TARGET_COUNT,
                "saved_structure_starts_total":len(real),
                "selected_true_starts_with_full_chunks":len(found_full),
                "scanned_actual_chunks":scanned,
                "custom_flat_terrain":terrain,
            },ensure_ascii=False,indent=2)+"\n",encoding="utf-8")
        print(f"[P36] POSTHOC ONLY: {len(found_full)}/{TARGET_COUNT} "
              f"independent saved StructureStart chunks verified FULL, "
              f"saved structures total={len(real)}, custom flat terrain="
              f"{terrain['full_chunks']} FULL chunks; not CLIENT_OBSERVED",
              flush=True)
    except BaseException:
        if client is not None:
            print("[P36] REAL CLIENT LOG TAIL:\n"+client.tail(),flush=True)
        if server is not None:
            print("[P36] REAL SERVER LOG TAIL:\n"+server.tail(),flush=True)
        screenshot(out/"actual-client-failure.png",actual_env)
        raise
    finally:
        if client is not None:
            client.stop()
        if server is not None:
            if server.process.poll() is None:
                try: rcon("stop",secret)
                except (OSError,RuntimeError,TimeoutError): pass
            server.stop()
        for proc in (game_wm,wm,display):
            if proc is not None and proc.poll() is None:
                os.killpg(proc.pid,signal.SIGTERM)
                try: proc.wait(timeout=5)
                except subprocess.TimeoutExpired:
                    os.killpg(proc.pid,signal.SIGKILL)
                    proc.wait(timeout=5)


if __name__ == "__main__":
    parser=argparse.ArgumentParser()
    parser.add_argument("--minecraft",required=True,choices=("26.1.2","26.2"))
    args=parser.parse_args()
    run(args.minecraft)
