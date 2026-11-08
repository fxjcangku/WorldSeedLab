#!/usr/bin/env python3
"""WorldSeedLab P3.9 真正运行客户端几何 -> Z3 -> 高16条件匹配。

参数校验、只读证据准备与求解执行分离；客户端材料簇/地形本身不是天然
StructureStart。模型条件候选不是未知服务器真实Seed，也不是矿物预测认证。
"""
from __future__ import annotations

import argparse
from dataclasses import dataclass
import json
from pathlib import Path
import re
import shutil
import subprocess
import sys
import tempfile
import zipfile

from audit_custom_climate import ID, encoded_quart, sample_evidence
from minecraft_versions import SUPPORTED_MINECRAFT_VERSIONS
from research_p37_custom_worldgen import Dataset, EvidenceRejected

CLIENT_ARCHIVE = re.compile(r"(?:^|/)(?:client-auto-desert-|client-footprint-)[^/]{0,180}\.properties$")
SAFE_SCOPE = re.compile(r"^worlds/[a-f0-9]{64}/dimensions/[a-f0-9]{24}/evidence\.jsonl$")
MAX_ZIP_ENTRIES = 100_000
RESUME_DIGEST = re.compile(r"wsl-p44-v1:[a-f0-9]{64}")
CLIMATE_AXES = ("temperature", "humidity", "continentalness", "erosion", "depth", "weirdness")


class EvidenceError(Exception):
    """可直接向用户显示且不含原始路径或坐标的预检查失败。"""


class ModelRegistryConflict(EvidenceError):
    def __init__(self, missing_count: int):
        super().__init__("自定义模型不能解释全部真实P1群系")
        self.missing_count = missing_count


@dataclass(frozen=True)
class PreparedQuart:
    """仅在内存/本机临时文件传递的真实样本，不提升生成器可信等级。"""

    contents: str
    selected_count: int
    record_count: int


def inspect(input_file: Path):
    if input_file.is_symlink():
        raise EvidenceError("拒绝符号链接输入")
    if input_file.is_file():
        with zipfile.ZipFile(input_file) as z:
            if len(z.infolist()) > MAX_ZIP_ENTRIES:
                raise EvidenceError("压缩包成员超过安全上限")
            names = z.namelist()
            if any(".." in Path(name).parts or name.startswith("/") for name in names):
                raise EvidenceError("压缩包包含不安全的相对路径")
            footprints = sorted(n for n in names if CLIENT_ARCHIVE.search(n))
            biome = [n for n in names if SAFE_SCOPE.fullmatch(n)]
            if len(footprints) != len(set(footprints)) or len(biome) != len(set(biome)):
                raise EvidenceError("压缩包包含重复的结构或群系证据成员")
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


def parse_args(argv=None):
    parser = argparse.ArgumentParser(
        description="WorldSeedLab真实客户端世界种子反演，非管理员/Seed直读")
    parser.add_argument("--input", required=True, type=Path,
                        help="原始observations.zip、P3.5/P3.6受控档案ZIP或单一证据目录")
    parser.add_argument("--minecraft", required=True, choices=SUPPORTED_MINECRAFT_VERSIONS)
    parser.add_argument("--salts", default="14357617",
                        help="最多四个不重复的int32研究假设Salt；默认原版沙漠神殿14357617")
    parser.add_argument("--vanilla-high16", action="store_true",
                        help="仅显式原版Overworld假设；自定义群系出现时安全拒绝")
    parser.add_argument("--custom-model", type=Path,
                        help="P4.1 用户独立取得的自定义MultiNoise参数文件，未认证目标服务器生成器")
    parser.add_argument("--high16-shard", type=int,
                        help="从0开始的高16连续分片索引，默认0")
    parser.add_argument("--high16-shards", type=int,
                        help="高16总分片数，默认16")
    parser.add_argument("--high16-evaluations", type=int,
                        help="本次至多计算的完整64位模型次数，默认128")
    parser.add_argument("--high16-resume", type=int,
                        help="上次打印的NEXT_INDEX；需匹配完整证据摘要且low48集合稳定")
    parser.add_argument("--high16-expected-digest",
                        help="续算时前次输出的RECOVERY_RESUME_TOKEN（wsl-p44-v1:SHA256）；旧裸摘要不可复用")
    parser.add_argument("--window-end", type=int, default=1 << 48,
                        help="低48搜索上界；仅受控实验可用4096，默认完整48位")
    parser.add_argument("--report", type=Path,
                        help="可选本机新JSON文件；仅实际Java求解写入，不能覆盖现有文件")
    parser.add_argument("--dry-run", action="store_true",
                        help="校验参数及所选高16证据，检查结构文件数量；不启动Z3、不写求解报告")
    return parser.parse_args(argv)


def _read_model_biomes(path: Path) -> frozenset[str]:
    """先校验全部模型参数；Java前向模型仍须独立复核同一份本机文件。"""
    if path.is_symlink() or not path.is_file() or path.stat().st_size > 1024 * 1024:
        raise EvidenceError("本机自定义生成器模型必须是1MiB以内的普通JSON文件")
    try:
        model = json.loads(path.read_text(encoding="utf-8"))
    except (ValueError, UnicodeError) as exc:
        raise EvidenceError("自定义模型JSON损坏或编码非法") from exc
    if not isinstance(model, dict) or type(model.get("schema")) is not int \
            or model["schema"] != 1 \
            or model.get("provenance") != "USER_SUPPLIED_UNVERIFIED" \
            or model.get("climate_sampler") != "minecraft:vanilla_overworld_noise_router":
        raise EvidenceError("自定义生成器模型结构不符；只支持显式原版噪声路由器假设")
    profile = model.get("generator_profile")
    if not isinstance(profile, str) or not profile.startswith("worldseedlab:") \
            or not ID.fullmatch(profile):
        raise EvidenceError("自定义模型缺失有效worldseedlab生成器假设ID")
    entries = model.get("entries")
    if not isinstance(entries, list) or not 1 <= len(entries) <= 4096:
        raise EvidenceError("自定义气候参数表必须有1..4096条气候区域")
    possible = set()
    for entry in entries:
        if not isinstance(entry, dict):
            raise EvidenceError("自定义模型气候区域必须为JSON对象")
        biome = entry.get("biome")
        if not isinstance(biome, str) or len(biome) > 256 or not ID.fullmatch(biome):
            raise EvidenceError("自定义模型缺失有效群系映射")
        for axis in CLIMATE_AXES:
            span = entry.get(axis)
            if not isinstance(span, list) or len(span) != 2 \
                    or any(type(value) not in (int, float) for value in span) \
                    or not -4 <= span[0] <= span[1] <= 4:
                raise EvidenceError("自定义模型六维气候参数必须是[-4,4]内的有限有序区间")
        offset = entry.get("offset")
        if type(offset) not in (int, float) or not 0 <= offset <= 4:
            raise EvidenceError("自定义模型气候偏移量必须在0..4之间且为有限数值")
        possible.add(biome)
    return frozenset(possible)


def validate_options(args) -> frozenset[str] | None:
    """所有模式（包括dry-run）先校验，避免忽略无效或无效力的参数。"""
    if args.custom_model is not None and args.vanilla_high16:
        raise EvidenceError("原版高16与自定义模型条件高16不能同时执行")
    if not 1 <= args.window_end <= 1 << 48:
        raise EvidenceError("低48窗口必须在1..2^48范围")
    tokens = args.salts.split(",")
    if not 1 <= len(tokens) <= 4:
        raise EvidenceError("每次必须提供1..4个不重复的结构Salt假设")
    salts = []
    for token in tokens:
        if not re.fullmatch(r"-?[0-9]{1,10}", token.strip()):
            raise EvidenceError("非法结构Salt，只允许int32整数研究假设")
        salt = int(token.strip())
        if not -(1 << 31) <= salt < 1 << 31:
            raise EvidenceError("结构Salt必须在Java int32范围内")
        if salt in salts:
            raise EvidenceError("不能重复相同Salt假设")
        salts.append(salt)
    args.salts = ",".join(str(salt) for salt in salts)
    if args.high16_resume is not None and len(salts) != 1:
        raise EvidenceError("断点续算仅支持单个结构Salt假设；多个Salt请分别运行并使用各自续算凭据")

    if args.custom_model is None and any(value is not None for value in (
            args.high16_resume, args.high16_expected_digest, args.high16_shard,
            args.high16_shards, args.high16_evaluations)):
        raise EvidenceError("高16分片/预算/断点参数只能用于显式自定义模型")
    args.high16_shard = 0 if args.high16_shard is None else args.high16_shard
    args.high16_shards = 16 if args.high16_shards is None else args.high16_shards
    args.high16_evaluations = 128 if args.high16_evaluations is None else args.high16_evaluations
    if not 1 <= args.high16_shards <= 256 or not 0 <= args.high16_shard < args.high16_shards:
        raise EvidenceError("高16分片数量或索引非法")
    if not 1 <= args.high16_evaluations <= 1_000_000:
        raise EvidenceError("高16实际搜索预算必须在1..1000000")
    if args.high16_expected_digest is not None and re.fullmatch(
            r"[a-f0-9]{64}", args.high16_expected_digest):
        raise EvidenceError("旧版裸断点摘要不能复用；请从本分片起点重新开始，获取RECOVERY_RESUME_TOKEN")
    if args.high16_resume is not None and (
            not 0 <= args.high16_resume < 1 << 63
            or args.high16_expected_digest is None
            or not RESUME_DIGEST.fullmatch(args.high16_expected_digest)):
        raise EvidenceError("断点续算必须提供有效非负NEXT_INDEX及上一轮RECOVERY_RESUME_TOKEN")
    if args.high16_expected_digest is not None and args.high16_resume is None:
        raise EvidenceError("指定输入摘要但没有NEXT_INDEX")

    if args.report is not None:
        if args.report.exists() or any(path.is_symlink() for path in
                                      (args.report, *args.report.parents)):
            raise EvidenceError("求解报告目标已存在或为符号链接，拒绝覆盖")
        if not args.report.parent.is_dir():
            raise EvidenceError("求解报告父目录不存在或不是目录")
        args.report = args.report.resolve()
    return _read_model_biomes(args.custom_model) if args.custom_model is not None else None


def prepare_quart(args, possible_biomes: frozenset[str] | None) -> PreparedQuart | None:
    """原版和自定义路径复用严格P1校验；路径HMAC、XYZ中心与完整作用域一致。"""
    if not args.vanilla_high16 and args.custom_model is None:
        return None
    dataset = Dataset(args.input)
    try:
        scope, selected, total, observed_ids, _ = sample_evidence(dataset, args.minecraft)
    finally:
        dataset.close()
    if args.vanilla_high16 and any(not biome.startswith("minecraft:")
                                  for biome in observed_ids):
        raise EvidenceError("观测到非minecraft自定义群系：不能把未知生成器强行认定为原版模型")
    if possible_biomes is not None and not observed_ids.issubset(possible_biomes):
        raise ModelRegistryConflict(len(observed_ids - possible_biomes))
    return PreparedQuart(encoded_quart(scope, selected), len(selected), total)


def build_command(args, quart: Path | None = None) -> list[str]:
    """只组装参数；不把候选资格、生成器可信等级或矿物就绪状态写成成功。"""
    command = ["gradle", "--no-daemon", "--console=plain",
               "-PmcVersion=" + args.minecraft,
               "-PrecoveryEvidence=" + str(args.input.resolve()),
               "-PrecoverySalts=" + args.salts,
               "-PrecoveryEnd=" + str(args.window_end)]
    if args.report is not None:
        command.append("-PrecoveryReport=" + str(args.report))
    if quart is not None:
        command.append("-PrecoveryQuart=" + str(quart))
        if args.vanilla_high16:
            command.append("-PrecoveryBiomeMode=EXPLICIT_VANILLA_OVERWORLD_HYPOTHESIS")
        else:
            command += ["-PrecoveryBiomeMode=EXPLICIT_CUSTOM_CLIMATE_HYPOTHESIS",
                        "-PrecoveryCustomModel=" + str(args.custom_model.resolve()),
                        "-PrecoveryShardIndex=" + str(args.high16_shard),
                        "-PrecoveryShardCount=" + str(args.high16_shards),
                        "-PrecoveryHigh16MaxEvaluations=" + str(args.high16_evaluations)]
            if args.high16_resume is not None:
                command += ["-PrecoveryHigh16Resume=" + str(args.high16_resume),
                            "-PrecoveryHigh16Digest=" + args.high16_expected_digest]
    return command + ["p39RealSeedRecovery"]


def run_recovery(args, prepared: PreparedQuart | None) -> int:
    """显式运行本机求解器；含私有quart的临时目录无论成功失败都会销毁。"""
    if not shutil.which("gradle") or not shutil.which("z3"):
        raise EvidenceError("缺少本机Java25/Gradle9.9.0或Z3命令，无法实际运行反演")
    root = Path(__file__).resolve().parents[1]
    with tempfile.TemporaryDirectory(prefix="wsl-p39-quart-") as directory:
        quart = None
        if prepared is not None:
            quart = Path(directory) / "client-quart.csv"
            quart.write_text(prepared.contents, encoding="utf-8")
        command = build_command(args, quart)
        print("运行本机原版Java RNG/Z3真求解；结果为模型候选，Salt/自然来源未确认")
        return subprocess.run(command, cwd=root, check=False).returncode


def main(argv=None):
    args = parse_args(argv)
    executed = False
    try:
        possible_biomes = validate_options(args)
        footprints, _ = inspect(args.input)
        print("WorldSeedLab · 实际客户端 Seed 反演")
        print(f"可用轮廓存档文件: {len(footprints)}（需至少3个独立RandomSpread区域）")
        if len(footprints) < 3:
            print("RESULT=INSUFFICIENT_GEOMETRY_EVIDENCE")
            print("尚无足够完整结构轮廓：不能仅凭大量自定义群系、高度图或材料候选破解低48。")
            print("请继续使用最新P3.8一次自动取证，在自然建筑区域收集完整轮廓；"
                  "无法认证天然来源时只能给出条件模型候选。")
            return 2
        prepared = prepare_quart(args, possible_biomes)
        if prepared is not None:
            if args.vanilla_high16:
                print(f"实际筛选了{prepared.selected_count}条空间独立群系，原版生成器身份仍未证明")
            else:
                print("P4.1：客户端真实群系=" + str(prepared.record_count)
                      + "；独立3FIT+2HOLDOUT就绪；噪声参数与服务端身份未认证")
        if args.dry_run:
            print("RESULT=STRUCTURE_FILES_PRESENT_UNVERIFIED")
            return 0
        exit_code = run_recovery(args, prepared)
        executed = True
        return exit_code
    except ModelRegistryConflict as exc:
        print("P4.1_MODEL_REGISTRY_INCOMPATIBLE: 在真实P1证据中"
              f"有{exc.missing_count}种未被模型解释的群系")
        print("FULL64=UNKNOWN_CUSTOM_WORLDGEN；"
              "停止不可信的高16位假设搜索，不能说目标服Seed不存在")
        return 2
    except (EvidenceError, EvidenceRejected) as exc:
        print("RESULT=BLOCKED: " + str(exc), file=sys.stderr)
        return 2
    except (OSError, zipfile.BadZipFile, ValueError, TypeError) as exc:
        print("RESULT=BLOCKED: " + type(exc).__name__
              + "（不输出原始私服路径或坐标）", file=sys.stderr)
        return 2
    finally:
        if args.report is not None and not executed:
            print("REPORT=NOT_CREATED；本次预检查未执行求解，未生成求解报告")


if __name__ == "__main__":
    raise SystemExit(main())
