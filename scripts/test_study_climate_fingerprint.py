#!/usr/bin/env python3
"""P4.3 普通客户端P1证据提取：空间独立、匿名输出、错误来源拒绝正反例。

本文件只校验证据安全。真正Minecraft六轴噪声由双版本Java/FULL真值验证。
"""
import contextlib
import io
import json
from pathlib import Path
import tempfile
from types import SimpleNamespace
from unittest import TestCase, main, mock
from zipfile import ZipFile

from research_p37_custom_worldgen import Dataset, EvidenceRejected
from study_climate_fingerprint import (
    choose_regions, scan_coverage, sample_regions, encode, main as run_cli,
    MAX_PER_REGION, _rank,
)

WORLD = "a" * 64
SCOPE = "worlds/" + WORLD + "/dimensions/" + "b"*24 + "/"

def observed(region, n, *, version="26.2", origin="CLIENT_OBSERVATION",
             world=WORLD, biome=None):
    qx = region*6*128 + 2 + n % 24
    qz = region*7*128 + 3 + n // 24
    qy = 18
    return {"schema": 1, "world_fingerprint": world, "client_version": version,
            "dimension": "minecraft:overworld", "kind": "BIOME_OBSERVATION",
            "origin": origin, "observed_identifier": biome or (
                "terralith:controlled_test" if region % 2 else "minecraft:forest"),
            "position": {"x": 4*qx+2, "y": 4*qy+2, "z": 4*qz+2}}

def write_zip(path, rows):
    with ZipFile(path, "w") as archive:
        archive.writestr(SCOPE + "evidence.jsonl",
                         "".join(json.dumps(r)+"\n" for r in rows))


class P43CliTests(TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix="wsl-p43-test-")
        self.path = Path(self.temp.name)/"observations.zip"
        self.rows = [observed(region, n) for region in range(6) for n in range(90)]
        write_zip(self.path, self.rows)

    def tearDown(self):
        self.temp.cleanup()

    def read(self):
        source = Dataset(self.path)
        try:
            return scan_coverage(source, "26.2")
        finally:
            source.close()

    def test_true_scope_and_spatial_separation(self):
        scope, regions, rows, kinds, namespaces = self.read()
        self.assertEqual(rows, 540)
        self.assertEqual(len(regions), 6)
        self.assertEqual(kinds, 2)
        self.assertEqual(namespaces, 2)
        self.assertEqual(scope[1:],("26.2","minecraft:overworld"))
        for i, a in enumerate(regions):
            for b in regions[:i]:
                self.assertGreaterEqual(max(abs(a[0]-b[0]), abs(a[1]-b[1])),3)

    def test_private_reservoir_and_3fit_2holdout(self):
        source=Dataset(self.path)
        try:
            scope, regions, *_ = scan_coverage(source,"26.2")
            groups=sample_regions(source,regions)
        finally:
            source.close()
        self.assertTrue(all(len(v)==MAX_PER_REGION for v in groups.values()))
        text, count=encode(scope,regions,groups)
        self.assertEqual(count,4)
        self.assertEqual(text.count(",FIT\n"), 4*MAX_PER_REGION)
        self.assertEqual(text.count(",HOLDOUT\n"), 2*MAX_PER_REGION)
        self.assertIn("sampling=AUTOMATIC_FULL_CHUNK_OBSERVATION",text)
        self.assertNotIn("CONTROL_WORLD_OBSERVED",text)

    def test_conflict_after_reservoir_eviction_is_rejected(self):
        # 特意复测区域中未进入前64名的quart；旧版只追踪heap活动点会漏掉冲突。
        source = Dataset(self.path)
        try:
            scope, regions, *_ = scan_coverage(source, "26.2")
        finally:
            source.close()
        original = [r for r in self.rows if (r["position"]["x"] // 4 // 128,
                    r["position"]["z"] // 4 // 128) == regions[0]]
        self.assertGreater(len(original), MAX_PER_REGION)
        # _rank越大越可能被reservoir剔除；选择最大者保证能覆盖此路径。
        victim = max(original, key=lambda r: _rank((
            r["position"]["x"] // 4, r["position"]["y"] // 4,
            r["position"]["z"] // 4)))
        changed = dict(victim)
        changed["observed_identifier"] = "worldseedlab:contradictory_biome"
        write_zip(self.path, self.rows + [changed])
        source = Dataset(self.path)
        try:
            with self.assertRaisesRegex(EvidenceRejected, "互相矛盾"):
                sample_regions(source, regions)
        finally:
            source.close()

    def test_repeated_same_quart_not_counted_twice(self):
        source = Dataset(self.path)
        try:
            _, regions, *_ = scan_coverage(source, "26.2")
        finally:
            source.close()
        write_zip(self.path, self.rows + [self.rows[0]] * 5)
        source = Dataset(self.path)
        try:
            points = sample_regions(source, regions)
        finally:
            source.close()
        self.assertEqual(sum(len(p) for p in points.values()),
                         len(regions) * MAX_PER_REGION)
        for rows in points.values():
            self.assertEqual(len({coords for coords, _ in rows}), len(rows))

    def test_insufficient_region_coverage(self):
        with self.assertRaises(EvidenceRejected):
            choose_regions([(0,0),(0,1),(0,2),(1,2),(2,1)])

    def test_unknown_generator_without_seed(self):
        output=io.StringIO()
        with contextlib.redirect_stdout(output):
            code=run_cli(["--input",str(self.path),"--minecraft","26.2"])
        self.assertEqual(code,0)
        self.assertIn("FULL64=UNKNOWN_NO_SEED_HYPOTHESES",output.getvalue())
        self.assertNotIn(WORLD,output.getvalue())

    def test_one_control_enables_temporary_local_java_only(self):
        seen={}
        def fake_gradle(cmd, **kwargs):
            self.assertIn("p43ClimateFingerprint",cmd)
            self.assertIn("-PmcVersion=26.2",cmd)
            self.assertIn("-Pp43Seeds=2731,2732",cmd)
            path=Path(next(v.split("=",1)[1] for v in cmd
                           if v.startswith("-Pp43Samples=")))
            self.assertTrue(path.exists())
            contents=path.read_text()
            self.assertEqual(contents.count(",FIT\n"),4*MAX_PER_REGION)
            self.assertEqual(contents.count(",HOLDOUT\n"),2*MAX_PER_REGION)
            seen["temp"]=path
            return SimpleNamespace(returncode=0)
        with mock.patch("study_climate_fingerprint.shutil.which",return_value="/usr/bin/gradle"),\
             mock.patch("study_climate_fingerprint.subprocess.run",side_effect=fake_gradle):
            output=io.StringIO()
            with contextlib.redirect_stdout(output):
                code=run_cli(["--input",str(self.path),"--minecraft","26.2",
                              "--seeds","2731,2732","--assume-vanilla-noise-router"])
        self.assertEqual(code,0)
        self.assertFalse(seen["temp"].exists())
        self.assertNotIn(WORLD,output.getvalue())

    def test_fail_closed_without_explicit_hypothesis(self):
        argv=["--input",str(self.path),"--minecraft","26.2"]
        self.assertEqual(run_cli(argv+["--seeds","2731"]),2)
        self.assertEqual(run_cli(argv+["--assume-vanilla-noise-router"]),2)
        self.assertEqual(run_cli(argv+["--seeds","0x1,garbage",
                                      "--assume-vanilla-noise-router"]),2)

    def test_fake_provenance_and_cross_world_rejected(self):
        write_zip(self.path, [observed(0,0,origin="CONTROL_WORLD_OBSERVED")]
                  + self.rows[1:])
        source=Dataset(self.path)
        try:
            with self.assertRaises(EvidenceRejected):
                scan_coverage(source,"26.2")
        finally:
            source.close()
        write_zip(self.path,[observed(0,0,world="f"*64)]+self.rows[1:])
        source=Dataset(self.path)
        try:
            with self.assertRaises(EvidenceRejected):
                scan_coverage(source,"26.2")
        finally:
            source.close()

    def test_wrong_game_version_rejected(self):
        self.assertEqual(run_cli(["--input",str(self.path),"--minecraft","26.1.2"]),2)


if __name__=="__main__":
    main()
