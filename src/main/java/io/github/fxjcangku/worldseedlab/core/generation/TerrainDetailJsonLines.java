package io.github.fxjcangku.worldseedlab.core.generation;

/** 版本化、可逐行解析的扩展地形证据格式；原始采样含精确区块位置。 */
public final class TerrainDetailJsonLines {
    private TerrainDetailJsonLines() {
    }

    public static String serialize(TerrainDetailSample sample) {
        StringBuilder out = new StringBuilder(8500)
                .append("{\"schema\":3,\"type\":\"terrain_detail\",")
                .append("\"world_fingerprint\":").append(q(sample.worldFingerprint())).append(',')
                .append("\"dimension\":").append(q(sample.dimension())).append(',')
                .append("\"client_version\":").append(q(sample.clientVersion())).append(',')
                .append("\"chunk_x\":").append(sample.chunkX()).append(',')
                .append("\"chunk_z\":").append(sample.chunkZ()).append(',')
                .append("\"mode\":").append(q(sample.mode().name())).append(',')
                .append("\"vertical_stride_blocks\":").append(sample.verticalStrideBlocks()).append(',')
                .append("\"provenance\":\"client_loaded_block_states\",")
                .append("\"generation_origin_verified\":false,")
                .append("\"surface\":[");
        for (int i = 0; i < sample.surface().size(); i++) {
            var cell = sample.surface().get(i);
            if (i != 0) out.append(',');
            out.append("{\"local_x\":").append(cell.localX())
                    .append(",\"local_z\":").append(cell.localZ())
                    .append(",\"y\":").append(cell.observedY())
                    .append(",\"block\":").append(q(cell.blockId()))
                    .append(",\"block_state\":").append(q(cell.blockState()))
                    .append(",\"fluid\":").append(q(cell.fluidId()))
                    .append(",\"biome\":").append(q(cell.biomeId())).append('}');
        }
        out.append("],\"vertical\":[");
        for (int i = 0; i < sample.vertical().size(); i++) {
            var cell = sample.vertical().get(i);
            if (i != 0) out.append(',');
            out.append("{\"local_x\":").append(cell.localX())
                    .append(",\"local_z\":").append(cell.localZ())
                    .append(",\"y\":").append(cell.observedY())
                    .append(",\"block\":").append(q(cell.blockId()))
                    .append(",\"fluid\":").append(q(cell.fluidId()))
                    .append(",\"biome\":").append(q(cell.biomeId())).append('}');
        }
        return out.append("],\"observed_at\":")
                .append(q(sample.observedAt().toString())).append('}').toString();
    }

    /** 特殊字符转义；不允许方块状态描述破坏 JSONL 单行约束。 */
    private static String q(String value) {
        StringBuilder out = new StringBuilder(value.length() + 2).append('"');
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> {
                    if (c < 0x20) {
                        out.append(String.format(java.util.Locale.ROOT, "\\u%04x", (int)c));
                    } else {
                        out.append(c);
                    }
                }
            }
        }
        return out.append('"').toString();
    }
}
