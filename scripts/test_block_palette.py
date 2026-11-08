#!/usr/bin/env python3
"""官方26.x方块编码形态的解析回归；模拟section只测门禁，不冒充FULL世界。"""
from copy import deepcopy
from pathlib import Path
import tempfile
import unittest
from unittest import mock

from run_known_seed_server import block_palette_ids
from run_p34_custom_worldgen import scan_custom_terrain


class SavedBlockPaletteTest(unittest.TestCase):
    def test_legacy_name_and_properties(self):
        palette = [{"Name": "minecraft:sand"}, {"Name": "minecraft:sandstone"},
                   {"Name": "minecraft:oak_log", "Properties": {"axis": "x"}}]
        self.assertEqual(block_palette_ids(palette),
                         {"minecraft:sand", "minecraft:sandstone", "minecraft:oak_log"})

    def test_26_3_homogeneous_default_state_strings(self):
        # 26.3官方BlockState.CODEC + PalettedContainer实际序列化得到此形态。
        self.assertEqual(block_palette_ids(["minecraft:sand", "minecraft:sandstone"]),
                         {"minecraft:sand", "minecraft:sandstone"})

    def test_26_3_mixed_binary_list_wraps_only_the_string_entries(self):
        # 官方ListTag.write后的真实二进制经本项目NbtReader读取，字符串保留空键包装。
        palette = [{"": "minecraft:sand"},
                   {"id": "minecraft:oak_log", "properties": {"axis": "x"}},
                   {"": "minecraft:sandstone"}]
        self.assertEqual(block_palette_ids(palette),
                         {"minecraft:sand", "minecraft:oak_log", "minecraft:sandstone"})

    def test_26_3_boolean_and_integer_properties_are_saved_as_strings(self):
        # 官方编码后再次读取二进制：BooleanProperty/IntegerProperty仍是StringTag。
        palette = [{"id": "minecraft:oak_fence", "properties": {
            "east": "true", "north": "false", "south": "false",
            "waterlogged": "false", "west": "false"}},
            {"id": "minecraft:wheat", "properties": {"age": "3"}}]
        self.assertEqual(block_palette_ids(palette), {"minecraft:oak_fence", "minecraft:wheat"})

    def test_unknown_ambiguous_and_malformed_entries_fail_closed(self):
        bad_entries = [None, True, 7, [], {}, "sand", "Minecraft:sand", "minecraft:",
                       "minecraft:sand\n", {"Name": 7}, {"id": None},
                       {"Name": "minecraft:sand", "id": "minecraft:stone"},
                       {"": {"id": "minecraft:sand"}},
                       {"": "minecraft:sand", "extra": "minecraft:stone"},
                       {"Name": "minecraft:sand", "properties": {}},
                       {"id": "minecraft:sand", "Properties": {}},
                       {"id": "minecraft:oak_log", "properties": {"axis": 1}},
                       {"id": "minecraft:oak_log", "properties": []}]
        for entry in bad_entries:
            with self.subTest(entry=entry), self.assertRaises(ValueError):
                block_palette_ids([entry])
        for palette in (None, {}, "minecraft:sand", ["minecraft:sand"] * 4097):
            with self.subTest(palette_type=type(palette).__name__), self.assertRaises(ValueError):
                block_palette_ids(palette)
        self.assertEqual(block_palette_ids([]), set(), "缺少palette不贡献地层真值")


class ControlledTerrainGateTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory(prefix="wsl-terrain-parser-test-")
        self.world = Path(self.temporary.name)
        regions = self.world / "dimensions/minecraft/overworld/region"
        regions.mkdir(parents=True)
        (regions / "r.0.0.mca").touch()

    def tearDown(self):
        self.temporary.cleanup()

    @staticmethod
    def section(y, palette):
        return {"Y": y, "block_states": {"palette": palette}}

    def scan(self, chunks):
        with mock.patch("run_p34_custom_worldgen.region_chunks", return_value=iter(chunks)):
            return scan_custom_terrain(self.world)

    def test_legacy_and_new_palettes_keep_same_full_and_height_gate(self):
        for sandstone, sand in (([{"Name": "minecraft:sandstone"}], [{"Name": "minecraft:sand"}]),
                                (["minecraft:sandstone"], ["minecraft:sand"]),
                                ([{"": "minecraft:sandstone"}, {"id": "minecraft:oak_log",
                                   "properties": {"axis": "x"}}], [{"": "minecraft:sand"}])):
            with self.subTest(sandstone=sandstone):
                chunk = {"Status": "minecraft:full", "sections": [
                    self.section(3, sandstone), self.section(4, sand)]}
                self.assertEqual(self.scan([deepcopy(chunk) for _ in range(5)]),
                                 {"full_chunks": 5, "sandstone_sections": 5, "sand_sections": 5})

    def test_missing_full_wrong_height_and_insufficient_layers_still_fail(self):
        chunk = {"Status": "minecraft:full", "sections": [
            self.section(3, ["minecraft:sandstone"]), self.section(4, ["minecraft:sand"])]}
        for variant in ("not_full", "wrong_height", "too_few_full", "too_few_sand", "empty_palette"):
            chunks = [deepcopy(chunk) for _ in range(5)]
            if variant == "not_full":
                chunks[0]["Status"] = "minecraft:surface"
            elif variant == "wrong_height":
                for row in chunks:
                    row["sections"][0]["Y"] = 2
            elif variant == "too_few_full":
                chunks.pop()
            elif variant == "too_few_sand":
                chunks[0]["sections"].pop()
            elif variant == "empty_palette":
                chunks[0]["sections"][1]["block_states"]["palette"] = []
            with self.subTest(variant=variant), self.assertRaises(AssertionError):
                self.scan(chunks)


if __name__ == "__main__":
    unittest.main()
