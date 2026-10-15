#!/usr/bin/env python3
"""P4.2 纯Python生成器可辨识性、证据隔离及旧版高度纠错正反例。"""
import contextlib
import io
import json
from pathlib import Path
import tempfile
import unittest
from zipfile import ZipFile

from audit_generator_identifiability import (
    Audit, audit_result, compare_references, model_references, main,
)
from research_p37_custom_worldgen import Dataset, EvidenceRejected

HASH = "a" * 64
ROOT = "worlds/" + HASH + "/dimensions/" + "b"*24 + "/"


def biome(x, z, y=66, name="minecraft:plains", fp=HASH, version="26.2"):
    return dict(schema=1, world_fingerprint=fp, dimension="minecraft:overworld",
                client_version=version, kind="BIOME_OBSERVATION",
                origin="CLIENT_OBSERVATION", observed_identifier=name,
                position={"x":x,"y":y,"z":z})


def terrain(cx, cz, ymax=320):
    return dict(schema=2, type="terrain_chunk", world_fingerprint=HASH,
                dimension="minecraft:overworld", client_version="26.2",
                chunk_x=cx, chunk_z=cz, min_build_y=-64,
                max_build_y_exclusive=ymax, sea_level=63,
                heightmap="WORLD_SURFACE", sample_grid="4x4_quart_centers",
                surface_y=[68]*16)


def strata(cx, cz, mode="STRATA", floor=-65):
    return dict(schema=4, type="terrain_detail", world_fingerprint=HASH,
                dimension="minecraft:overworld", client_version="26.2",
                chunk_x=cx, chunk_z=cz, mode=mode,
                provenance="client_loaded_block_states",
                generation_origin_verified=False,
                surface=[dict(world_surface_y=69, ocean_floor_y=floor)],
                vertical=[dict(local_x=2, local_z=2, y=-62,
                               block="minecraft:bedrock")] if mode=="STRATA" else [])


def write_evidence(path, p1=None, p2=None, p22=None):
    if p1 is None:
        p1 = [biome(2+2048*i, 2+2048*i) for i in range(5)]
    if p2 is None:
        p2 = [terrain(128*i,128*i) for i in range(5)]
    if p22 is None:
        p22 = [strata(0,0)]
    with ZipFile(path,"w") as z:
        for fname, rows in (("evidence.jsonl",p1),("terrain.jsonl",p2),
                            ("terrain-detail.jsonl",p22)):
            z.writestr(ROOT+fname,"".join(json.dumps(r)+"\n" for r in rows))


class P42AuditTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.root = Path(self.temp.name)
        self.input = self.root/"observations.zip"
        write_evidence(self.input)

    def tearDown(self):
        self.temp.cleanup()

    def inspect(self):
        data = Dataset(self.input)
        try:
            return Audit().scan(data)
        finally:
            data.close()

    def test_true_client_evidence_and_tri_source_regions(self):
        r = audit_result(self.inspect())
        self.assertEqual(r["evidence"]["biome_rows"],5)
        self.assertEqual(r["evidence"]["unique_quart_xyz"],5)
        self.assertEqual(r["evidence"]["terrain_detail_rows"],1)
        self.assertEqual(r["evidence"]["strata_chunks"],1)
        self.assertEqual(r["evidence"]["invalid_ocean_floor_columns"],1)
        self.assertEqual(r["independence"]["p1_p2_strata_overlap_macroregions"],1)
        self.assertEqual(r["identifiability"]["low48"],"NOT_DERIVED")
        self.assertNotIn(HASH,json.dumps(r))

    def test_duplicate_identical_is_not_second_constraint(self):
        write_evidence(self.input,p1=[biome(2,2),biome(2,2),biome(2050,2050)])
        r=self.inspect().summary()["evidence"]
        self.assertEqual(r["unique_quart_xyz"],2)
        self.assertEqual(r["duplicate_quart_rows"],1)
        self.assertEqual(r["conflicting_quart_xyz"],0)

    def test_mixed_reobserved_biomes_do_not_prove_seed(self):
        write_evidence(self.input,p1=[biome(2,2),biome(2,2,name="terralith:test")])
        result=audit_result(self.inspect())
        self.assertEqual(result["evidence"]["conflicting_quart_xyz"],1)
        self.assertEqual(result["identifiability"]["full64"],"UNKNOWN_CUSTOM_WORLDGEN")

    def test_negative_coordinates_do_not_collide(self):
        keys=[Audit.pack(-30_000_000,-62,2), Audit.pack(30_000_000,-62,2),
              Audit.pack(-30_000_000,-58,2), Audit.pack(-30_000_000,-62,6)]
        self.assertEqual(len(keys),len(set(keys)))

    def test_new_and_legacy_mojmap_height_semantics(self):
        write_evidence(self.input,p2=[terrain(0,0,639),terrain(128,128,640)])
        report=audit_result(self.inspect())
        self.assertEqual(report["dimension_profile"]["max_build_y_exclusive"],640)
        self.assertEqual(report["evidence"]["legacy_inclusive_height_rows"],1)
        self.assertEqual(report["evidence"]["canonical_exclusive_height_rows"],1)
        self.assertEqual(report["evidence"]["dimension_profile_conflicts"],0)
        self.assertEqual(report["exact_unmodified_vanilla_overworld"]["status"],
                         "EXACT_VANILLA_PROFILE_CONTRADICTED")
        self.assertTrue(report["exact_unmodified_vanilla_overworld"]
                        ["does_not_disprove_partial_vanilla_noise_router_reuse"])

    def test_custom_registry_contradicts_unmodified_vanilla_not_vanilla_rng(self):
        write_evidence(self.input,p1=[biome(2,2,name="terralith:test")])
        result=audit_result(self.inspect())
        self.assertIn("VANILLA_ONLY_BIOME_REGISTRY_MISMATCH",
                      result["exact_unmodified_vanilla_overworld"]["reasons"])
        self.assertEqual(result["identifiability"]["noise_router"],"UNKNOWN")

    def test_surface_only_does_not_claim_strata_overlap(self):
        write_evidence(self.input,p22=[strata(0,0,mode="SURFACE")])
        report=self.inspect().summary()
        self.assertEqual(report["evidence"]["strata_chunks"],0)
        self.assertEqual(report["independence"]["p1_p2_strata_overlap_macroregions"],0)

    def test_complete_reference_rejects_and_partial_remains_ambiguous(self):
        ref=dict(model_id="worldseedlab:hypothesis",provenance="USER_SUPPLIED_UNVERIFIED",
                 game_version="26.2",dimension="minecraft:overworld",
                 biome_catalog_complete=True,possible_biomes=["minecraft:desert"])
        a=self.inspect()
        rejected=compare_references(a,[ref])[0]
        self.assertEqual(rejected["status"],"MODEL_HYPOTHESIS_CONTRADICTED")
        self.assertEqual(rejected["unexplained_observed_biome_count"],1)
        ref["biome_catalog_complete"]=False
        inconclusive=compare_references(a,[ref])[0]
        self.assertEqual(inconclusive["status"],"NOT_FALSIFIED_IDENTITY_UNVERIFIED")
        self.assertIsNone(inconclusive["unexplained_observed_biome_count"])

    def test_reference_manifest_validation(self):
        ref=dict(model_id="worldseedlab:a",provenance="USER_SUPPLIED_UNVERIFIED",
                 game_version="26.2",dimension="minecraft:overworld",
                 biome_catalog_complete=True,possible_biomes=["minecraft:plains"])
        target=self.root/"model.json"
        target.write_text(json.dumps(dict(schema=1,reference_models=[ref])))
        self.assertEqual(len(model_references(target)),1)
        ref["provenance"]="CONFIRMED_TARGET_SEED"
        target.write_text(json.dumps(dict(schema=1,reference_models=[ref])))
        with self.assertRaises(EvidenceRejected):
            model_references(target)
        ref["provenance"]="USER_SUPPLIED_UNVERIFIED"
        target.write_text(json.dumps(dict(schema=1,reference_models=[ref,ref])))
        with self.assertRaises(EvidenceRejected):
            model_references(target)

    def test_cross_world_version_and_false_origin_rejected(self):
        write_evidence(self.input,p1=[biome(2,2),biome(2050,2050,fp="f"*64)])
        with self.assertRaises(EvidenceRejected):
            self.inspect()
        fake=biome(2,2)
        fake["origin"]="CONTROL_WORLD_OBSERVED"
        write_evidence(self.input,p1=[fake])
        with self.assertRaises(EvidenceRejected):
            self.inspect()
        write_evidence(self.input,p1=[biome(3,2)])
        with self.assertRaises(EvidenceRejected):
            self.inspect()

    def test_anonymous_output_and_no_overwrite(self):
        output=self.root/"result.json"
        captured=io.StringIO()
        with contextlib.redirect_stdout(captured):
            self.assertEqual(main(["--input",str(self.input),
                                   "--json-out",str(output)]),0)
        self.assertTrue(output.exists())
        self.assertNotIn(HASH,output.read_text())
        self.assertNotIn(HASH,captured.getvalue())
        self.assertEqual(main(["--input",str(self.input),
                               "--json-out",str(output)]),2)


if __name__=="__main__":
    unittest.main()
