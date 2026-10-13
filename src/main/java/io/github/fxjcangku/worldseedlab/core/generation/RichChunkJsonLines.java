package io.github.fxjcangku.worldseedlab.core.generation;

import java.util.List;
import java.util.Objects;

/**
 * 精细观测 JSONL v3，每行只描述一个已加载区块的 16 个稀疏列与 16 个地下点。
 *
 * <p>绝不序列化方块实体数据或玩家信息。由于包含区块坐标，原件不能直接公开。
 */
public final class RichChunkJsonLines {
    private RichChunkJsonLines() {
    }

    public static String serialize(RichChunkSample sample) {
        Objects.requireNonNull(sample);
        StringBuilder json = new StringBuilder(3900);
        json.append("{\"schema\":3,\"type\":\"rich_chunk\",")
                .append("\"world_fingerprint\":").append(quote(sample.worldFingerprint()))
                .append(",\"dimension\":").append(quote(sample.dimension()))
                .append(",\"client_version\":").append(quote(sample.clientVersion()))
                .append(",\"chunk_x\":").append(sample.chunkX())
                .append(",\"chunk_z\":").append(sample.chunkZ())
                .append(",\"min_y\":").append(sample.minBuildY())
                .append(",\"max_y_exclusive\":").append(sample.maxBuildYExclusive());
        ints(json, "world_surface_y", sample.surfaceY());
        ints(json, "ocean_floor_y", sample.oceanFloorY());
        ints(json, "motion_blocking_y", sample.motionBlockingY());
        strings(json, "surface_blocks", sample.surfaceBlockIds());
        strings(json, "ocean_floor_blocks", sample.oceanFloorBlockIds());
        strings(json, "surface_biomes", sample.surfaceBiomeIds());
        strings(json, "surface_fluids", sample.surfaceFluidIds());
        json.append(",\"deep_layers\":[");
        for (int i = 0; i < sample.deepLayers().size(); i++) {
            if (i != 0) json.append(',');
            RichChunkSample.DeepLayer layer = sample.deepLayers().get(i);
            json.append("{\"x\":").append(layer.localX())
                    .append(",\"z\":").append(layer.localZ())
                    .append(",\"y\":").append(layer.y())
                    .append(",\"block\":").append(quote(layer.blockId()))
                    .append('}');
        }
        return json.append("],\"observation_only\":true,\"observed_at\":")
                .append(quote(sample.observedAt().toString()))
                .append('}').toString();
    }

    private static void ints(StringBuilder out, String key, List<Integer> values) {
        out.append(',').append(quote(key)).append(":[");
        for (int i = 0; i < values.size(); i++) {
            if (i != 0) out.append(',');
            out.append(values.get(i));
        }
        out.append(']');
    }

    private static void strings(StringBuilder out, String key, List<String> values) {
        out.append(',').append(quote(key)).append(":[");
        for (int i = 0; i < values.size(); i++) {
            if (i != 0) out.append(',');
            out.append(quote(values.get(i)));
        }
        out.append(']');
    }

    private static String quote(String value) {
        StringBuilder s = new StringBuilder(value.length() + 4).append('"');
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"' -> s.append("\\\"");
                case '\\' -> s.append("\\\\");
                case '\n' -> s.append("\\n");
                case '\r' -> s.append("\\r");
                case '\t' -> s.append("\\t");
                default -> {
                    if (c < 0x20) s.append(String.format(java.util.Locale.ROOT, "\\u%04x", (int) c));
                    else s.append(c);
                }
            }
        }
        return s.append('"').toString();
    }
}
