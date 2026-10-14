#!/usr/bin/env python3
"""P3.7 普通客户端证据的只读离线审计；不从未知生成器假设中制造Seed。"""
from __future__ import annotations

import argparse
from collections import Counter
from contextlib import contextmanager
import io
import json
from pathlib import Path
import re
import sys
import zipfile

SCOPE_PATH = re.compile(r"^worlds/[a-f0-9]{64}/dimensions/[a-f0-9]{24}/(evidence\.jsonl|terrain\.jsonl|terrain-detail\.jsonl)$")
FINGERPRINT = re.compile(r"^[a-f0-9]{64}$")
DIMENSION = re.compile(r"^[a-z0-9_.-]+:[a-z0-9_./-]+$")
ALLOWED_VERSIONS = {"26.1.2", "26.2"}
FILES = ("evidence.jsonl", "terrain.jsonl", "terrain-detail.jsonl")
MAX_FILE_BYTES = 2_147_483_648
MAX_ROW_BYTES = 65_536
MAX_RECORDS = 8_000_000
MAX_REGIONS = 16_384
MAX_TERRAIN_KEYS = 500_000
MAX_BIOMES = 4096
MAX_NAMESPACES = 128
MAX_ARCHIVE_MEMBERS = 150_000


class EvidenceRejected(ValueError):
    """证据混合、截断、伪造来源或不受支持的格式，拒绝进行推断。"""


class Dataset:
    """所有输入必须属于一个作用域；ZIP仅读取白名单JSONL，不解压、不写盘。"""

    def __init__(self, source: Path):
        if not source.exists():
            raise EvidenceRejected("离线观测文件不存在")
        self.source = source
        self.archive = None
        self.entries = {}
        self.expected_fp = None
        if source.is_file() and zipfile.is_zipfile(source):
            self.archive = zipfile.ZipFile(source)
            entries = self.archive.infolist()
            if len(entries) > MAX_ARCHIVE_MEMBERS:
                raise EvidenceRejected("ZIP文件成员过多，拒绝不受限归档")
            candidates = {}
            for entry in entries:
                name = entry.filename
                if ".." in Path(name).parts or name.startswith("/"):
                    raise EvidenceRejected("ZIP存在不安全的相对路径")
                m = SCOPE_PATH.fullmatch(name)
                if m:
                    if entry.file_size > MAX_FILE_BYTES:
                        raise EvidenceRejected("原始证据文件超过2GiB安全上限")
                    root = name.rsplit("/", 1)[0]
                    candidates.setdefault(root, {})[m.group(1)] = name
            if len(candidates) != 1:
                raise EvidenceRejected("ZIP必须仅包含一个HMAC世界/维度作用域")
            root, self.entries = next(iter(candidates.items()))
            self.expected_fp = root.split("/")[1]
        elif source.is_dir():
            candidates = []
            if any((source / f).is_file() for f in FILES):
                candidates = [source]
            else:
                candidates = sorted({p.parent for pattern in FILES
                                     for p in source.rglob(pattern)})
            if len(candidates) != 1:
                raise EvidenceRejected("目录必须明确且仅含一个世界/维度作用域")
            folder = candidates[0]
            if FINGERPRINT.fullmatch(folder.parent.parent.name):
                self.expected_fp = folder.parent.parent.name
            for name in FILES:
                path = folder / name
                if path.is_file():
                    if path.is_symlink() or path.stat().st_size > MAX_FILE_BYTES:
                        raise EvidenceRejected("拒绝软链接或超过上限的证据文件")
                    self.entries[name] = path
        else:
            raise EvidenceRejected("输入须为observations.zip或一个本机作用域目录")
        if not ("evidence.jsonl" in self.entries
                or "terrain.jsonl" in self.entries):
            raise EvidenceRejected("缺少实际客户端的群系或地形原始文件")

    @contextmanager
    def opened(self, name):
        source = self.entries.get(name)
        if source is None:
            yield None
        elif self.archive is not None:
            with self.archive.open(source, "r") as raw:
                with io.TextIOWrapper(raw, encoding="utf-8", errors="strict") as stream:
                    yield stream
        else:
            with open(source, "r", encoding="utf-8") as stream:
                yield stream

    def close(self):
        if self.archive is not None:
            self.archive.close()


class P37Audit:
    """有界内存聚合，坐标仅用于本机宏区域和重复检测，永不出现在结果中。"""

    def __init__(self):
        self.scope = None
        self.biome_rows = 0
        self.terrain_rows = 0
        self.detail_rows = 0
        self.detail_vertical_rows = 0
        self.skipped_manual_rows = 0
        self.namespaces = Counter()
        self.biomes = set()
        self.biome_regions = set()
        self.terrain_regions = set()
        self.terrain_keys = {}
        self.duplicate_terrain_rows = 0
        self.terrain_reobserved_conflicts = 0
        self.min_surface = None
        self.max_surface = None
        self.max_relief = 0
        self.surface_relief_sum = 0
        self.detail_modes = Counter()
        self.terrain_has_metadata_conflict = False

    @staticmethod
    def _require(condition, reason):
        if not condition:
            raise EvidenceRejected(reason)

    def _scope(self, row):
        fp, dim, version = (row.get("world_fingerprint"), row.get("dimension"),
                            row.get("client_version"))
        self._require(isinstance(fp, str) and FINGERPRINT.fullmatch(fp)
                      and isinstance(dim, str) and DIMENSION.fullmatch(dim)
                      and version in ALLOWED_VERSIONS, "世界/维度/版本作用域非法")
        next_scope = (fp, dim, version)
        if self.scope is None:
            self.scope = next_scope
        self._require(self.scope == next_scope, "跨世界/维度/游戏版本混合证据")

    @staticmethod
    def _region(chunk_x, chunk_z):
        return (chunk_x // 32, chunk_z // 32)

    @staticmethod
    def _spatially_separated_regions(regions):
        """32区块宏区域宽512方块；间隔至少3个宏区域才保证边缘间≥1024。
        本步骤是保守候选筛选，并没有真正执行模型FIT/HOLDOUT。
        """
        chosen = []
        for point in sorted(regions):
            if all(max(abs(point[0] - old[0]), abs(point[1] - old[1])) >= 3
                   for old in chosen):
                chosen.append(point)
                if len(chosen) == 5:
                    break
        return len(chosen)

    def _add_region(self, target, key):
        if key not in target:
            self._require(len(target) < MAX_REGIONS,
                          "独立宏区域超出有界内存上限，需分段离线审计")
            target.add(key)

    def _read(self, stream, name, expected_fp=None):
        if stream is None:
            return
        for i, line in enumerate(stream, 1):
            self._require(i <= MAX_RECORDS, "证据行数超过单次审计上限")
            self._require(len(line) <= MAX_ROW_BYTES, "单条证据过大")
            try:
                row = json.loads(line)
            except (ValueError, UnicodeError) as failure:
                raise EvidenceRejected("无效或截断JSONL，文件类型=" + name
                                       + "，行号=" + str(i)) from failure
            self._require(isinstance(row, dict), "JSONL必须为对象")
            self._scope(row)
            if expected_fp is not None and self.scope[0] != expected_fp:
                raise EvidenceRejected("归档目录HMAC与原始记录的世界身份不一致")
            if name == "evidence.jsonl":
                self._biome(row)
            elif name == "terrain.jsonl":
                self._terrain(row)
            else:
                self._detail(row)

    def _biome(self, row):
        self._require(row.get("schema") == 1, "P1群系原始格式不兼容")
        if row.get("kind") != "BIOME_OBSERVATION":
            self.skipped_manual_rows += 1
            return
        self._require(row.get("origin") == "CLIENT_OBSERVATION",
                      "非CLIENT_OBSERVATION不能冒充远程世界群系")
        biome = row.get("observed_identifier")
        self._require(isinstance(biome, str) and DIMENSION.fullmatch(biome),
                      "群系注册表ID不合法")
        if biome not in self.biomes:
            self._require(len(self.biomes) < MAX_BIOMES, "群系种类超过安全上限")
            self.biomes.add(biome)
        namespace = biome.split(":", 1)[0]
        self._require(len(self.namespaces) < MAX_NAMESPACES or namespace in self.namespaces,
                      "群系命名空间种类超过安全上限")
        self.namespaces[namespace] += 1
        pos = row.get("position")
        self._require(isinstance(pos, dict)
                      and all(type(pos.get(axis)) is int for axis in ("x", "y", "z")),
                      "P1群系XYZ缺失")
        x, z = pos["x"], pos["z"]
        self._require(x % 4 == 2 and z % 4 == 2, "P1必须是已观测quart中心点")
        self._add_region(self.biome_regions, self._region(x // 16, z // 16))
        self.biome_rows += 1

    def _terrain(self, row):
        self._require(row.get("schema") == 2
                      and row.get("type") == "terrain_chunk"
                      and row.get("heightmap") == "WORLD_SURFACE"
                      and row.get("sample_grid") == "4x4_quart_centers",
                      "地形必须是真实WORLD_SURFACE采样，不能接受虚构高度")
        cx, cz = row.get("chunk_x"), row.get("chunk_z")
        heights = row.get("surface_y")
        lower, upper = row.get("min_build_y"), row.get("max_build_y_exclusive")
        self._require(type(cx) is int and type(cz) is int
                      and type(lower) is int and type(upper) is int
                      and lower < upper and isinstance(heights, list)
                      and len(heights) == 16
                      and all(type(y) is int and lower-1 <= y <= upper+1
                              for y in heights), "真实地形16列高度数据不完整或越界")
        key = (cx, cz)
        heights_tuple = tuple(heights)
        old = self.terrain_keys.get(key)
        if old is not None:
            self.duplicate_terrain_rows += 1
            if old != heights_tuple:
                self.terrain_reobserved_conflicts += 1
        else:
            self._require(len(self.terrain_keys) < MAX_TERRAIN_KEYS,
                          "地形去重索引超过安全上限")
            self.terrain_keys[key] = heights_tuple
        self._add_region(self.terrain_regions, self._region(cx, cz))
        lo, hi = min(heights), max(heights)
        self.min_surface = lo if self.min_surface is None else min(lo, self.min_surface)
        self.max_surface = hi if self.max_surface is None else max(hi, self.max_surface)
        self.max_relief = max(self.max_relief, hi - lo)
        self.surface_relief_sum += hi - lo
        self.terrain_rows += 1

    def _detail(self, row):
        self._require(row.get("schema") == 4 and row.get("type") == "terrain_detail"
                      and row.get("provenance") == "client_loaded_block_states"
                      and row.get("generation_origin_verified") is False,
                      "详细地形不能伪装生成器或服务端真值")
        mode = row.get("mode")
        self._require(mode in ("SURFACE", "STRATA"), "非法详细地形档位")
        self._require(type(row.get("chunk_x")) is int
                      and type(row.get("chunk_z")) is int
                      and isinstance(row.get("surface"), list)
                      and isinstance(row.get("vertical"), list),
                      "详细地形结构不完整")
        vertical = row["vertical"]
        if mode == "SURFACE" and vertical:
            raise EvidenceRejected("SURFACE档位不能声称存在地下剖面")
        self.detail_modes[mode] += 1
        self.detail_vertical_rows += len(vertical)
        self.detail_rows += 1

    def analyze(self, dataset: Dataset, salt_hypotheses=()):
        for name in FILES:
            with dataset.opened(name) as stream:
                self._read(stream, name, dataset.expected_fp)
        self._require(self.scope is not None, "没有可用作用域")
        custom = {ns: count for ns, count in self.namespaces.items()
                  if ns != "minecraft"}
        namespaces = {name: self.namespaces[name]
                      for name in sorted(self.namespaces)}
        independent_regions = self.biome_regions & self.terrain_regions
        independent = len(independent_regions)
        spaced = self._spatially_separated_regions(independent_regions)
        self._require(not salt_hypotheses or
                      (len(salt_hypotheses) <= 32
                       and len(salt_hypotheses) == len(set(salt_hypotheses))),
                      "未知Salt研究假设数量不得超过32且不能重复")
        analysis = {
            "schema": 1,
            "project": "WorldSeedLab",
            "phase": "P3.7",
            "game_version": self.scope[2],
            "dimension_kind": "overworld" if self.scope[1] == "minecraft:overworld"
                              else "non_overworld",
            "privacy": "AGGREGATE_ONLY_NO_COORDINATES_NO_HMAC_NO_SERVER_ID",
            "sources": {
                "actual_client_biome_samples": self.biome_rows,
                "actual_client_terrain_chunks": self.terrain_rows,
                "unique_terrain_chunks": len(self.terrain_keys),
                "terrain_detail_chunks": self.detail_rows,
                "strata_vertical_cells": self.detail_vertical_rows,
                "skipped_manual_rows": self.skipped_manual_rows,
            },
            "independence": {
                "biome_macroregions": len(self.biome_regions),
                "terrain_macroregions": len(self.terrain_regions),
                "regions_with_both_biome_and_terrain": independent,
                "conservatively_spaced_1024_block_regions_up_to_5": spaced,
                "possible_future_structure_2fit_1holdout_regions": spaced >= 3,
                "possible_future_biome_3fit_2holdout_regions": spaced >= 5,
                "actual_model_fit_or_holdout_run": False,
                "global_world_coverage_percent": None,
            },
            "biome": {
                "distinct_observed_ids": len(self.biomes),
                "namespace_observation_counts": namespaces,
                "custom_namespace_count": len(custom),
                "unmodified_vanilla_biome_model": (
                    "REGISTRY_MISMATCH_WITH_OBSERVED_CUSTOM_BIOMES"
                    if custom else "NOT_VERIFIED_BY_REGISTRY_ALONE"),
            },
            "terrain": {
                "min_surface_y": self.min_surface,
                "max_surface_y": self.max_surface,
                "max_within_chunk_relief": self.max_relief,
                "mean_within_chunk_relief": (
                    round(self.surface_relief_sum / self.terrain_rows, 3)
                    if self.terrain_rows else None),
                "duplicate_rows": self.duplicate_terrain_rows,
                "changed_reobservations": self.terrain_reobserved_conflicts,
            },
            "worldgen_hypotheses": {
                "generator": "UNKNOWN",
                "epoch_history": ("SUSPECTED_MIXED_OR_MUTATED_EVIDENCE"
                                  if self.terrain_reobserved_conflicts else "UNKNOWN"),
                "salt": "UNKNOWN",
                "salt_hypotheses_supplied": len(salt_hypotheses),
                "salt_hypotheses_evaluated": 0,
                "natural_structure_origin": "UNKNOWN",
                "structure_low48": "NOT_DERIVED",
                "full64": "NOT_IDENTIFIABLE_WITHOUT_VERIFIED_GENERATOR_AND_HOLDOUT",
            },
            "warnings": [
                "客户端现存方块/群系不保证是该区块最初自然生成状态",
                "非minecraft群系注册表ID不等于已识别Terralith或其它生成器配置",
                "独立宏区域只说明可留出验证，尚未拟合或测试任何Seed",
                "结构Salt、结构自然来源和跨年代世界生成规则仍未知",
                "不输出原始坐标、世界HMAC、本机密钥、地址或Seed候选",
            ],
        }
        return analysis


def format_chinese(report):
    sources = report["sources"]
    geo = report["independence"]
    biome = report["biome"]
    h = report["worldgen_hypotheses"]
    return (
        "WorldSeedLab P3.7 自定义地形客户端离线证据审计\n"
        f"版本：{report['game_version']}，维度类别：{report['dimension_kind']}\n"
        f"P1真实群系：{sources['actual_client_biome_samples']}条；"
        f"P2地形：{sources['actual_client_terrain_chunks']}条；"
        f"详细地形：{sources['terrain_detail_chunks']}条；"
        f"地下剖面格点：{sources['strata_vertical_cells']}\n"
        f"双链宏区域：{geo['regions_with_both_biome_and_terrain']}；"
        f"≥1024方块保守间隔的候选区：{geo['conservatively_spaced_1024_block_regions_up_to_5']}；"
        f"未来结构2FIT+1HOLDOUT：{'可划分' if geo['possible_future_structure_2fit_1holdout_regions'] else '不足'}；"
        f"未来群系3FIT+2HOLDOUT：{'可划分' if geo['possible_future_biome_3fit_2holdout_regions'] else '不足'}"
        "（均未实际拟合）\n"
        f"群系ID种类：{biome['distinct_observed_ids']}；"
        f"非minecraft命名空间数：{biome['custom_namespace_count']}；"
        f"未修改原版群系模型：{biome['unmodified_vanilla_biome_model']}\n"
        f"地表观测范围：{report['terrain']['min_surface_y']}～"
        f"{report['terrain']['max_surface_y']}；"
        f"同区块高度冲突复测：{report['terrain']['changed_reobservations']}\n"
        f"生成器={h['generator']}，区块历史={h['epoch_history']}，"
        f"结构Salt={h['salt']}，低48={h['structure_low48']}，完整64位={h['full64']}\n"
        "声明：仅客户端可见真证据的匿名统计；模型兼容性不等于已破解世界种子。"
    )


def main(argv=None):
    parser = argparse.ArgumentParser(description="WorldSeedLab P3.7: read-only offline client evidence")
    parser.add_argument("--input", required=True, type=Path,
                        help="本机observations.zip或单一世界维度的原始证据目录")
    parser.add_argument("--json-out", type=Path, help="显式保存不含坐标/HMAC的匿名JSON摘要")
    parser.add_argument("--salt-hypotheses", default="", help="最多32个逗号分隔的十进制Salt假设；仅登记、绝不暗中验证")
    args = parser.parse_args(argv)
    try:
        salts = tuple(int(x) for x in args.salt_hypotheses.split(",")
                      if x.strip())
        if any(not (-2**31 <= s < 2**31) for s in salts):
            raise EvidenceRejected("Salt超出Java int范围")
        data = Dataset(args.input)
        try:
            report = P37Audit().analyze(data, salts)
        finally:
            data.close()
        print(format_chinese(report))
        if args.json_out is not None:
            args.json_out.parent.mkdir(parents=True, exist_ok=True)
            args.json_out.write_text(
                json.dumps(report, ensure_ascii=False, indent=2) + "\n",
                encoding="utf-8")
            print("匿名报告已保存至用户指定的本地文件（不打印目录/世界身份）")
        return 0
    except (EvidenceRejected, OSError, zipfile.BadZipFile) as exc:
        print("P3.7 证据不合格，拒绝推导：" + str(exc), file=sys.stderr)
        return 2


if __name__ == "__main__":
    raise SystemExit(main())
