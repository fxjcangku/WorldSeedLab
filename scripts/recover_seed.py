#!/usr/bin/env python3
"""WorldSeedLab P3.9 真正运行客户端几何 -> Z3 -> 高16条件匹配。

注意：客户端材料簇/地形本身不是天然 StructureStart；模型条件候选不是
未知服务器的真实Seed。所有证据本机只读，临时quart文件不会上传。
"""
from __future__ import annotations

import argparse
import json
from pathlib import Path
import re
import shutil
import subprocess
import sys
import tempfile
import zipfile

CLIENT_ARCHIVE = re.compile(r"(?:^|/)(?:client-auto-desert-|client-footprint-)[^/]{0,180}\.properties$")
SAFE_SCOPE = re.compile(r"^worlds/[a-f0-9]{64}/dimensions/[a-f0-9]{24}/evidence\.jsonl$")
P32_MAGIC = "WSL_P32_CLIENT_QUART_V1"
MAX_ZIP_ENTRIES = 100_000
MAX_BIOME_ROWS = 8_000_000
MAX_BIOME_ROW_CHARS = 8192
MIN_DISTANCE_QUART = 256


class EvidenceError(Exception):
    pass


def inspect(input_file: Path):
    if input_file.is_file():
        if input_file.is_symlink():
            raise EvidenceError("拒绝符号链接输入")
        with zipfile.ZipFile(input_file) as z:
            if len(z.infolist()) > MAX_ZIP_ENTRIES:
                raise EvidenceError("压缩包成员超过安全上限")
            footprints = sorted(n for n in z.namelist() if CLIENT_ARCHIVE.search(n))
            biome = [n for n in z.namelist() if SAFE_SCOPE.fullmatch(n)]
    elif input_file.is_dir():
        footprints = sorted(str(p) for p in input_file.rglob("*.properties")
                            if CLIENT_ARCHIVE.search(str(p).replace("\\", "/"))
                            and p.is_file() and not p.is_symlink())
        biome = sorted(str(p) for p in input_file.rglob("evidence.jsonl")
                       if p.is_file() and not p.is_symlink())
    else:
        raise EvidenceError("文件或取证目录不存在")
    if len(footprints) > 64:
        raise EvidenceError("完整轮廓记录数量超过64份安全上限")
    return footprints, biome


def _sample_rows(input_file: Path, version: str) -> tuple[str, int]:
    """转换为原有严格P3.2手动quart格式：不是生成器模型认证。"""
    if input_file.is_file():
        with zipfile.ZipFile(input_file) as z:
            matches = [name for name in z.namelist() if SAFE_SCOPE.fullmatch(name)]
            if len(matches) != 1:
                raise EvidenceError("高16群系文件必须恰好属于一个HMAC世界/维度")
            with z.open(matches[0]) as stream:
                return _collect_quarts(stream, version)
    names = list(input_file.rglob("evidence.jsonl"))
    if len(names) != 1 or names[0].is_symlink():
        raise EvidenceError("高16群系目录只能有一个安全的evidence.jsonl")
    with names[0].open("rb") as stream:
        return _collect_quarts(stream, version)


def _collect_quarts(stream, version: str) -> tuple[str, int]:
    if version not in ("26.1.2", "26.2"):
        raise EvidenceError("Minecraft版本不支持")
    scope = None
    rows = []
    custom = 0
    for number, raw in enumerate(stream, 1):
        if number > MAX_BIOME_ROWS or len(raw) > MAX_BIOME_ROW_CHARS:
            raise EvidenceError("原始证据超出有界行数或行长度")
        try:
            record = json.loads(raw)
        except (ValueError, UnicodeDecodeError) as bad:
            raise EvidenceError("JSONL证据截断或非法") from bad
        if record.get("schema") != 1 or record.get("kind") != "BIOME_OBSERVATION":
            continue
        if record.get("origin") != "CLIENT_OBSERVATION":
            raise EvidenceError("非真实CLIENT_OBSERVATION证据不可参与高16位候选")
        token = (record.get("world_fingerprint"), record.get("client_version"),
                 record.get("dimension"))
        if token[1] != version or not isinstance(token[0], str) or len(token[0]) != 64:
            raise EvidenceError("真实群系数据版本/指纹异常")
        if scope is None:
            scope = token
        elif scope != token:
            raise EvidenceError("跨世界/版本/维度的群系样本已拒绝")
        biome = record.get("observed_identifier")
        if not isinstance(biome, str) or ":" not in biome:
            raise EvidenceError("群系真实注册键非法")
        if not biome.startswith("minecraft:"):
            custom += 1
            continue
        pos = record.get("position")
        if not isinstance(pos, dict) or not all(type(pos.get(k)) is int for k in ("x", "y", "z")):
            raise EvidenceError("客户端原始群系坐标非法")
        x, y, z = pos["x"], pos["y"], pos["z"]
        if x % 4 != 2 or z % 4 != 2:
            raise EvidenceError("群系格点并非真实quart中心")
        qx, qy, qz = x // 4, y // 4, z // 4
        if len(rows) < 5 and all((qx-a)**2+(qz-b)**2
                                  >= MIN_DISTANCE_QUART**2 for a, _, b, _ in rows):
            rows.append((qx, qy, qz, biome))
    if scope is None:
        raise EvidenceError("没有任何可解析的真实P1客户端群系观测")
    if custom:
        raise EvidenceError("观测到非minecraft自定义群系：不能把未知生成器强行认定为原版模型")
    if len(rows) < 5:
        raise EvidenceError("高16至少需要5条相距1024方块的真实原版群系观测")
    world, game, dimension = scope
    header = (P32_MAGIC + "\nworldFingerprint=" + world + "\ngameVersion=" + game
              + "\ndimension=" + dimension
              + "\ngeneratorProfile=worldseedlab:unknown_worldgen"
              + "\nprovenance=CLIENT_OBSERVED"
              + "\nsampling=AUTOMATIC_FULL_CHUNK_OBSERVATION"
              + "\nquart_x,quart_y,quart_z,biome_id,split\n")
    text = header + "".join(
        f"{qx},{qy},{qz},{biome},{'FIT' if i < 3 else 'HOLDOUT'}\n"
        for i, (qx, qy, qz, biome) in enumerate(rows))
    return text, len(rows)


def main(argv=None):
    parser = argparse.ArgumentParser(
        description="WorldSeedLab真实客户端世界种子反演，非管理员/Seed直读")
    parser.add_argument("--input", required=True, type=Path,
                        help="原始observations.zip、P3.5/P3.6受控档案ZIP或单一证据目录")
    parser.add_argument("--minecraft", required=True, choices=["26.1.2", "26.2"])
    parser.add_argument("--salts", default="14357617",
                        help="最多四个明确研究假设Salt；默认原版沙漠神殿14357617")
    parser.add_argument("--vanilla-high16", action="store_true",
                        help="仅显式原版Overworld假设；自定义群系出现时安全拒绝")
    parser.add_argument("--window-end", type=int, default=1 << 48,
                        help="低48搜索上界；仅受控实验可用4096，默认完整48位")
    parser.add_argument("--dry-run", action="store_true",
                        help="只检查是否存在足够完整客户端结构轮廓，不启动Z3")
    args = parser.parse_args(argv)
    try:
        footprints, biomes = inspect(args.input)
        print("WorldSeedLab · 实际客户端 Seed 反演")
        print(f"可用轮廓存档文件: {len(footprints)}（需至少3个独立RandomSpread区域）")
        if len(footprints) < 3:
            print("RESULT=INSUFFICIENT_GEOMETRY_EVIDENCE")
            print("尚无足够完整结构轮廓：不能仅凭大量自定义群系、高度图或材料候选破解低48。")
            print("请继续使用最新P3.8一次自动取证，在自然建筑区域收集完整轮廓；"
                  "无法认证天然来源时只能给出条件模型候选。")
            return 2
        if args.dry_run:
            print("RESULT=STRUCTURE_FILES_PRESENT_UNVERIFIED")
            return 0
        if not (1 <= args.window_end <= 1 << 48):
            raise EvidenceError("低48窗口必须在1..2^48范围")
        for value in args.salts.split(","):
            if not re.fullmatch(r"-?\d{1,11}", value.strip()):
                raise EvidenceError("非法结构Salt，只允许整数研究假设")
        if not shutil.which("gradle") or not shutil.which("z3"):
            raise EvidenceError("缺少本机Java25/Gradle9.6.1或Z3命令，无法实际运行反演")

        root = Path(__file__).resolve().parents[1]
        cmd = ["gradle", "--no-daemon", "--console=plain",
               "-PmcVersion="+args.minecraft,
               "-PrecoveryEvidence="+str(args.input.resolve()),
               "-PrecoverySalts="+args.salts,
               "-PrecoveryEnd="+str(args.window_end)]
        with tempfile.TemporaryDirectory(prefix="wsl-p39-quart-") as d:
            if args.vanilla_high16:
                text, count = _sample_rows(args.input, args.minecraft)
                quart = Path(d)/"client-quart.csv"
                quart.write_text(text, encoding="utf-8")
                cmd += ["-PrecoveryQuart="+str(quart),
                        "-PrecoveryBiomeMode=EXPLICIT_VANILLA_OVERWORLD_HYPOTHESIS"]
                print(f"实际筛选了{count}条空间独立群系，原版生成器身份仍未证明")
            cmd += ["p39RealSeedRecovery"]
            print("运行本机原版Java RNG/Z3真求解；结果为模型候选，Salt/自然来源未确认")
            result = subprocess.run(cmd, cwd=root, check=False)
        return result.returncode
    except (EvidenceError, OSError, zipfile.BadZipFile) as exc:
        print("RESULT=BLOCKED: " + str(exc), file=sys.stderr)
        return 2


if __name__ == "__main__":
    raise SystemExit(main())
