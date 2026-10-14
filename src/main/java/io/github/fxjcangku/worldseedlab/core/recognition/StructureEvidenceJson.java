package io.github.fxjcangku.worldseedlab.core.recognition;

import java.util.Map;

/**
 * 每个完成区块一行，仅包含已观察的候选材料计数。
 * 缺席结构不是原版结构生成的负证据，尤其自定义地形/旧版区块。
 */
public final class StructureEvidenceJson {
    private StructureEvidenceJson() {}

    public static String line(StructureScanEvidence sample) {
        StringBuilder s = new StringBuilder(500);
        s.append("{\"schema\":2,\"kind\":\"AUTOMATED_MATERIAL_CANDIDATES\",")
                .append("\"model_version\":").append(StructureScanEvidence.MODEL_VERSION)
                .append(",\"world_fingerprint\":").append(q(sample.worldFingerprint()))
                .append(",\"dimension\":").append(q(sample.dimension()))
                .append(",\"client_version\":").append(q(sample.clientVersion()))
                .append(",\"chunk_x\":").append(sample.chunkX())
                .append(",\"chunk_z\":").append(sample.chunkZ())
                .append(",\"observed_block_cells\":").append(sample.scannedCells())
                .append(",\"min_observed_y\":").append(sample.minObservedY())
                .append(",\"max_observed_y\":").append(sample.maxObservedY())
                .append(",\"natural_origin_verified\":false,")
                .append("\"structure_start_known\":false,")
                .append("\"uses_ore_seed_evidence\":false,")
                .append("\"candidate_structures\":[");
        for (int i = 0; i < sample.candidates().size(); i++) {
            StructureCandidate candidate = sample.candidates().get(i);
            if (i != 0) s.append(',');
            s.append("{\"structure\":").append(q(candidate.structureId()))
                    .append(",\"grade\":").append(q(candidate.grade().name()))
                    .append(",\"characteristic_blocks\":").append(candidate.characteristicBlocks())
                    .append(",\"note\":").append(q(candidate.explanation()))
                    .append(",\"witnesses\":{");
            int count = 0;
            for (var e : candidate.evidenceBlocks().entrySet().stream()
                    .sorted(Map.Entry.comparingByKey()).toList()) {
                if (count++ != 0) s.append(',');
                s.append(q(e.getKey())).append(':').append(e.getValue());
            }
            s.append("}}");
        }
        s.append("],\"observed_at\":").append(q(sample.scannedAt().toString())).append('}');
        return s.toString();
    }

    private static String q(String input) {
        StringBuilder out = new StringBuilder(input.length() + 8).append('"');
        for (int i = 0; i < input.length(); i++) {
            char c = input.charAt(i);
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> {
                    if (c < 0x20) out.append(String.format(java.util.Locale.ROOT, "\\u%04x", (int)c));
                    else out.append(c);
                }
            }
        }
        return out.append('"').toString();
    }
}
