#!/usr/bin/env python3
"""P4.2 自定义生成器可辨识性。对P1/P2/STRATA完整只读审计，绝不声称恢复Seed。

拒绝跨世界/版本/维度，不载入受限第三方数据包或未经许可的生成器实现。
原始HMAC、XYZ、服务器地址仅存在本机内存；所有可分享输出严格匿名。
"""
from __future__ import annotations

import argparse
from collections import Counter
import json
from pathlib import Path
import re
import sys

from research_p37_custom_worldgen import (
    Dataset, EvidenceRejected, FINGERPRINT, DIMENSION, ALLOWED_VERSIONS,
    MAX_RECORDS, MAX_ROW_BYTES,
)

BOUND = 1 << 25
MAX_POINTS = 2_500_000
MAX_CHUNKS = 500_000
MAX_REGIONS = 16_384
BIOME_ID = re.compile(r"^[a-z0-9_.-]+:[a-z0-9_./-]+$")


class Audit:
    """不保存用户路径、世界HMAC、服务器IP至摘要的有界流式审计。"""

    def __init__(self):
        self.scope = None
        self.quarts = {}
        self.codes = {}
        self.conflicting = set()
        self.biome_rows = 0
        self.namespace_rows = Counter()
        self.p1_regions = set()
        self.p2_regions = set()
        self.strata_regions = set()
        self.terrain = {}
        self.terrain_changed = set()
        self.terrain_rows = 0
        self.profile = None
        self.profile_conflicts = 0
        self.legacy_height_rows = 0
        self.exclusive_height_rows = 0
        self.detail_rows = 0
        self.strata_chunks = set()
        self.vertical_cells = 0
        self.ocean_columns = 0
        self.invalid_ocean_columns = 0

    @staticmethod
    def require(ok, why):
        if not ok:
            raise EvidenceRejected(why)

    @staticmethod
    def pack(x, y, z):
        """三维坐标注入式65位整数编码，避免hash碰撞掩盖群系矛盾。"""
        if not (-BOUND <= x < BOUND and -BOUND <= z < BOUND
                and -4096 <= y < 4096):
            raise EvidenceRejected("坐标超过世界审计安全界限")
        return ((x + BOUND) << 39) | ((z + BOUND) << 13) | (y + 4096)

    def add_region(self, target, x, z):
        key = (x // 512, z // 512)
        if key not in target:
            self.require(len(target) < MAX_REGIONS, "宏区域超限")
            target.add(key)

    def rows(self, source, filename):
        with source.opened(filename) as stream:
            if stream is None:
                return
            for number, line in enumerate(stream, 1):
                self.require(number <= MAX_RECORDS and len(line) <= MAX_ROW_BYTES,
                             "原始JSONL容量或单行长度超限")
                try:
                    row = json.loads(line)
                except (ValueError, UnicodeError) as exc:
                    raise EvidenceRejected("原始JSONL截断或损坏") from exc
                self.require(isinstance(row, dict), "原始JSONL必须包含对象")
                fp, dim, version = (row.get("world_fingerprint"), row.get("dimension"),
                                    row.get("client_version"))
                self.require(isinstance(fp, str) and FINGERPRINT.fullmatch(fp)
                             and isinstance(dim, str) and DIMENSION.fullmatch(dim)
                             and version in ALLOWED_VERSIONS, "非法证据作用域")
                key = (fp, dim, version)
                if self.scope is None:
                    self.scope = key
                self.require(self.scope == key and (source.expected_fp is None
                             or fp == source.expected_fp), "跨世界/维度/版本混合")
                yield row

    def scan(self, source):
        for row in self.rows(source, "evidence.jsonl"):
            self.require(row.get("schema") == 1, "P1格式非法")
            if row.get("kind") != "BIOME_OBSERVATION":
                continue
            self.require(row.get("origin") == "CLIENT_OBSERVATION",
                         "原版客户端群系来源被伪造")
            pos = row.get("position")
            self.require(isinstance(pos, dict) and all(
                type(pos.get(a)) is int for a in ("x", "y", "z")), "P1缺少XYZ")
            x, y, z = (pos["x"], pos["y"], pos["z"])
            self.require(x % 4 == 2 and y % 4 == 2 and z % 4 == 2,
                         "原始位置不是quart中心")
            biome = row.get("observed_identifier")
            self.require(isinstance(biome, str) and len(biome) <= 256
                         and BIOME_ID.fullmatch(biome), "非法群系注册ID")
            code = self.codes.setdefault(biome, len(self.codes))
            self.require(len(self.codes) <= 4096, "群系注册ID数量超限")
            packed = self.pack(x, y, z)
            first = self.quarts.get(packed)
            if first is None:
                self.require(len(self.quarts) < MAX_POINTS, "独立quart超250万，需分段审计")
                self.quarts[packed] = code
            elif first != code:
                self.require(len(self.conflicting) < 200_000, "矛盾quart位置超限")
                self.conflicting.add(packed)
            self.add_region(self.p1_regions, x, z)
            self.biome_rows += 1
            self.namespace_rows[biome.split(":", 1)[0]] += 1

        for row in self.rows(source, "terrain.jsonl"):
            self.require(row.get("schema") == 2
                         and row.get("type") == "terrain_chunk"
                         and row.get("heightmap") == "WORLD_SURFACE"
                         and row.get("sample_grid") == "4x4_quart_centers",
                         "P2高度图类型/来源非法")
            cx, cz, lower, upper, sea = (
                row.get("chunk_x"), row.get("chunk_z"), row.get("min_build_y"),
                row.get("max_build_y_exclusive"), row.get("sea_level"))
            heights = row.get("surface_y")
            self.require(all(type(v) is int for v in (cx, cz, lower, upper, sea))
                         and -4096 <= lower < upper <= 4096
                         and isinstance(heights, list) and len(heights) == 16
                         and all(type(v) is int and lower-1 <= v <= upper+1
                                 for v in heights), "P2高度/世界配置数据不合法")
            # 旧客户端把getMaxY()含上界误标成maxBuildYExclusive；按16格节对齐
            # 严格归一化，绝不重写原始证据文件。
            span = upper - lower
            if span % 16 == 15:
                upper += 1
                self.legacy_height_rows += 1
            elif span % 16 == 0:
                self.exclusive_height_rows += 1
            else:
                raise EvidenceRejected("无法解释的世界高度范围，拒绝自行猜测")
            key_profile = (lower, upper, sea)
            if self.profile is None:
                self.profile = key_profile
            elif self.profile != key_profile:
                self.profile_conflicts += 1
            key = (cx, cz)
            height_values = tuple(heights)
            if key in self.terrain:
                if self.terrain[key] != height_values:
                    self.terrain_changed.add(key)
            else:
                self.require(len(self.terrain) < MAX_CHUNKS, "P2区块索引超限")
                self.terrain[key] = height_values
            self.add_region(self.p2_regions, cx*16, cz*16)
            self.terrain_rows += 1

        for row in self.rows(source, "terrain-detail.jsonl"):
            self.require(row.get("schema") == 4
                         and row.get("type") == "terrain_detail"
                         and row.get("provenance") == "client_loaded_block_states"
                         and row.get("generation_origin_verified") is False,
                         "P2.2原始BlockState来源不合法")
            cx, cz, mode = (row.get("chunk_x"), row.get("chunk_z"), row.get("mode"))
            surface, vertical = row.get("surface"), row.get("vertical")
            self.require(type(cx) is int and type(cz) is int
                         and mode in ("SURFACE", "STRATA")
                         and isinstance(surface, list) and isinstance(vertical, list),
                         "P2.2行结构非法")
            self.require(mode != "SURFACE" or not vertical,
                         "SURFACE档位不能伪造地下数据")
            self.detail_rows += 1
            self.add_region(self.strata_regions, cx*16, cz*16)
            if mode == "STRATA":
                self.strata_chunks.add((cx, cz))
                self.vertical_cells += len(vertical)
            for cell in surface:
                self.require(isinstance(cell, dict), "P2.2列必须是BlockState对象")
                floor, top = cell.get("ocean_floor_y"), cell.get("world_surface_y")
                if (type(floor) is not int or type(top) is not int
                        or (self.profile is not None and
                            (floor < self.profile[0] or floor > top))):
                    self.invalid_ocean_columns += 1
                self.ocean_columns += 1

        self.require(self.scope is not None and self.biome_rows > 0,
                     "缺少真实P1客户端quart数据")
        return self

    @staticmethod
    def independent_macroregions(regions):
        """保守512方块宏区域间隔>=3，即跨区域边缘至少约1024方块。"""
        selected = []
        for x, z in sorted(regions):
            if all(max(abs(x-xx), abs(z-zz)) >= 3 for xx, zz in selected):
                selected.append((x, z))
                if len(selected) >= 32:
                    break
        return len(selected)

    def summary(self):
        p12 = self.p1_regions & self.p2_regions
        p123 = p12 & self.strata_regions
        return {
            "schema": 1, "project": "WorldSeedLab",
            "phase": "P4.2_GENERATOR_IDENTIFIABILITY",
            "privacy": "AGGREGATE_ONLY_NO_COORDINATES_NO_HMAC_NO_SERVER_ID",
            "minecraft_version": self.scope[2],
            "dimension_class": ("overworld" if self.scope[1] == "minecraft:overworld"
                                else "non_overworld"),
            "dimension_profile": (dict(min_build_y=self.profile[0],
                max_build_y_exclusive=self.profile[1], sea_level=self.profile[2])
                if self.profile else None),
            "evidence": {
                "biome_rows": self.biome_rows,
                "unique_quart_xyz": len(self.quarts),
                "duplicate_quart_rows": self.biome_rows - len(self.quarts),
                "conflicting_quart_xyz": len(self.conflicting),
                "observed_biome_ids": len(self.codes),
                "nonminecraft_biome_ids": sum(
                    1 for name in self.codes if not name.startswith("minecraft:")),
                "observed_namespace_count": len(self.namespace_rows),
                "terrain_rows": self.terrain_rows,
                "unique_terrain_chunks": len(self.terrain),
                "terrain_reobserved_changes": len(self.terrain_changed),
                "dimension_profile_conflicts": self.profile_conflicts,
                "legacy_inclusive_height_rows": self.legacy_height_rows,
                "canonical_exclusive_height_rows": self.exclusive_height_rows,
                "terrain_detail_rows": self.detail_rows,
                "strata_chunks": len(self.strata_chunks),
                "strata_vertical_cells": self.vertical_cells,
                "ocean_floor_columns": self.ocean_columns,
                "invalid_ocean_floor_columns": self.invalid_ocean_columns,
            },
            "independence": {
                "p1_macroregions": len(self.p1_regions),
                "p1_p2_overlap_macroregions": len(p12),
                "p1_p2_strata_overlap_macroregions": len(p123),
                "conservatively_independent_p1_p2_strata_up_to_32":
                    self.independent_macroregions(p123),
                "global_world_coverage_percent": None,
            },
            "identifiability": {
                "generator": "UNKNOWN", "noise_router": "UNKNOWN",
                "generator_version": "UNKNOWN", "structure_salt": "UNKNOWN",
                "natural_structure_origin": "UNKNOWN",
                "low48": "NOT_DERIVED",
                "full64": "UNKNOWN_CUSTOM_WORLDGEN",
                "model_signature_match_does_not_establish_generator_identity": True,
            },
        }


def model_references(path):
    if not path.is_file() or path.is_symlink() or path.stat().st_size > 1024*1024:
        raise EvidenceRejected("候选生成器参考文件不安全")
    try:
        root = json.loads(path.read_text(encoding="utf-8"))
    except (ValueError, UnicodeError, OSError) as exc:
        raise EvidenceRejected("参考模型JSON非法") from exc
    refs = root.get("reference_models") if isinstance(root, dict) and root.get("schema")==1 else None
    if not isinstance(refs, list) or not 1 <= len(refs) <= 32:
        raise EvidenceRejected("候选模型数量无效")
    names = set()
    for ref in refs:
        if not isinstance(ref,dict) or not isinstance(ref.get("model_id"),str) \
                or not BIOME_ID.fullmatch(ref["model_id"]) or ref["model_id"] in names:
            raise EvidenceRejected("参考模型名称非法或重复")
        names.add(ref["model_id"])
        if ref.get("provenance") != "USER_SUPPLIED_UNVERIFIED" \
                or ref.get("game_version") not in ALLOWED_VERSIONS \
                or not isinstance(ref.get("dimension"),str) \
                or not DIMENSION.fullmatch(ref["dimension"]) \
                or type(ref.get("biome_catalog_complete")) is not bool:
            raise EvidenceRejected("模型不允许自行声称已经通过目标服务器认证")
        ids = ref.get("possible_biomes")
        if not isinstance(ids,list) or not 1 <= len(ids) <= 4096 \
                or len(set(ids)) != len(ids) or not all(
                isinstance(b,str) and len(b)<=256 and BIOME_ID.fullmatch(b) for b in ids):
            raise EvidenceRejected("模型参考群系列表非法")
        profile = ref.get("dimension_profile")
        if profile is not None and (not isinstance(profile,dict) or
                set(profile) != {"min_build_y","max_build_y_exclusive","sea_level"} or
                any(type(v) is not int for v in profile.values()) or not
                -4096 <= profile["min_build_y"] < profile["max_build_y_exclusive"] <= 4096):
            raise EvidenceRejected("模型参考维度高度非法")
    return refs


def compare_references(audit, references):
    out=[]
    for ref in references:
        mismatches=[]
        if ref["game_version"] != audit.scope[2] or ref["dimension"] != audit.scope[1]:
            mismatches.append("VERSION_OR_DIMENSION_MISMATCH")
        expected=ref.get("dimension_profile")
        if expected is not None and audit.profile is not None and any(
                expected[k] != value for k,value in zip(
                ("min_build_y","max_build_y_exclusive","sea_level"), audit.profile)):
            mismatches.append("EXACT_DIMENSION_PROFILE_MISMATCH")
        missing=(set(audit.codes)-set(ref["possible_biomes"])) if ref["biome_catalog_complete"] else None
        if missing:
            mismatches.append("COMPLETE_BIOME_CATALOG_MISSING_OBSERVED_IDS")
        out.append({"model_id":ref["model_id"],
                    "status":("MODEL_HYPOTHESIS_CONTRADICTED" if mismatches
                              else "NOT_FALSIFIED_IDENTITY_UNVERIFIED"),
                    "reasons":mismatches,
                    "unexplained_observed_biome_count":len(missing) if missing is not None else None})
    return out


def audit_result(audit, refs=None):
    result=audit.summary()
    reasons=[]
    if audit.scope[1] == "minecraft:overworld":
        if audit.profile is not None and audit.profile != (-64, 320, 63):
            reasons.append("EXACT_VANILLA_DIMENSION_PROFILE_MISMATCH")
        if any(not b.startswith("minecraft:") for b in audit.codes):
            reasons.append("VANILLA_ONLY_BIOME_REGISTRY_MISMATCH")
    result["exact_unmodified_vanilla_overworld"] = {
        "status":("EXACT_VANILLA_PROFILE_CONTRADICTED" if reasons else
                  "NOT_FALSIFIED_IDENTITY_UNVERIFIED"),
        "reasons":reasons,
        "does_not_disprove_partial_vanilla_noise_router_reuse":True,
    }
    if refs is not None:
        result["reference_models"] = compare_references(audit, refs)
    return result


def main(argv=None):
    parser=argparse.ArgumentParser(description="WorldSeedLab P4.2 客户端生成器可辨识性审计")
    parser.add_argument("--input",type=Path,required=True)
    parser.add_argument("--reference-models",type=Path)
    parser.add_argument("--json-out",type=Path)
    args=parser.parse_args(argv)
    try:
        refs=model_references(args.reference_models) if args.reference_models else None
        source=Dataset(args.input)
        try:
            audited=Audit().scan(source)
        finally:
            source.close()
        result=audit_result(audited,refs)
        d=result["evidence"]
        print("WorldSeedLab P4.2 客户端生成器可辨识性审计")
        print("P1行数={biome_rows} 独立quart={unique_quart_xyz} 坐标冲突={conflicting_quart_xyz}".format(**d))
        print("P2唯一地形区块={unique_terrain_chunks} STRATA区块={strata_chunks}".format(**d))
        print("P1/P2/STRATA共同区域=",
              result["independence"]["p1_p2_strata_overlap_macroregions"])
        print("EXACT_VANILLA=",result["exact_unmodified_vanilla_overworld"]["status"])
        if refs:
            for ref in result["reference_models"]:
                print("MODEL=",ref["model_id"],"VERDICT=",ref["status"])
        print("GENERATOR=UNKNOWN NOISE_ROUTER=UNKNOWN LOW48=NOT_DERIVED FULL64=UNKNOWN")
        if args.json_out is not None:
            destination=args.json_out
            if destination.suffix.lower()!=".json" or destination.is_symlink() \
                    or destination.exists() or destination.resolve()==args.input.resolve() \
                    or destination.name.lower() in ("identity.key","evidence.jsonl",
                                                     "terrain.jsonl","terrain-detail.jsonl"):
                raise EvidenceRejected("不能覆盖证据或现有文件")
            destination.parent.mkdir(parents=True,exist_ok=True)
            with destination.open("x",encoding="utf-8") as stream:
                json.dump(result,stream,ensure_ascii=False,indent=2)
                stream.write("\n")
            print("本机仅保存不包含服务器IP/HMAC/坐标的匿名统计")
        return 0
    except (EvidenceRejected,OSError,ValueError,TypeError):
        print("P4.2_FAIL_CLOSED (private paths and HMAC redacted)",file=sys.stderr)
        return 2


if __name__=="__main__":
    raise SystemExit(main())
