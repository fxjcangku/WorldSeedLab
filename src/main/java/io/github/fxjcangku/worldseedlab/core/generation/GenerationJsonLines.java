package io.github.fxjcangku.worldseedlab.core.generation;

import java.util.Iterator;
import java.util.Objects;

/** P2 使用独立版本化 JSONL 格式，禁止把高度图伪装为群系证据。 */
public final class GenerationJsonLines {
    private GenerationJsonLines() {
    }

    public static String profile(DimensionProfile profile) {
        Objects.requireNonNull(profile, "profile");
        return "{"
                + "\"schema\":2,"
                + "\"type\":\"dimension_profile\","
                + "\"world_fingerprint\":" + quote(profile.worldFingerprint()) + ","
                + "\"dimension\":" + quote(profile.dimension()) + ","
                + "\"client_version\":" + quote(profile.clientVersion()) + ","
                + "\"min_build_y\":" + profile.minBuildY() + ","
                + "\"max_build_y_exclusive\":" + profile.maxBuildYExclusive() + ","
                + "\"sea_level\":" + profile.seaLevel() + ","
                + "\"skylight\":" + profile.skylight() + ","
                + "\"ceiling\":" + profile.ceiling() + ","
                + "\"ultra_warm\":" + (profile.ultraWarm() == null ? "null" : profile.ultraWarm()) + ","
                + "\"observed_at\":" + quote(profile.observedAt().toString()) + ","
                + "\"provenance\":\"client_level_metadata\""
                + "}";
    }

    /** 登录连接已公布的维度注册键，仅代表客户端获知，不代表进入或生成。 */
    public static String advertisedDimensions(AdvertisedDimensions sample) {
        Objects.requireNonNull(sample, "sample");
        StringBuilder out = new StringBuilder(350)
                .append("{\"schema\":2,\"type\":\"advertised_dimensions\",")
                .append("\"world_fingerprint\":").append(quote(sample.worldFingerprint())).append(',')
                .append("\"client_version\":").append(quote(sample.clientVersion())).append(',')
                .append("\"current_dimension\":").append(quote(sample.currentDimension())).append(',')
                .append("\"source\":").append(quote(sample.source().name())).append(',')
                .append("\"dimension_ids\":[");
        for (int i = 0; i < sample.dimensionIds().size(); i++) {
            if (i != 0) out.append(',');
            out.append(quote(sample.dimensionIds().get(i)));
        }
        return out.append("],\"observed_at\":")
                .append(quote(sample.observedAt().toString()))
                .append('}').toString();
    }

    public static String terrain(TerrainChunkSample terrain) {
        Objects.requireNonNull(terrain, "terrain");
        StringBuilder builder = new StringBuilder(380)
                .append("{\"schema\":2,\"type\":\"terrain_chunk\",")
                .append("\"world_fingerprint\":").append(quote(terrain.worldFingerprint())).append(',')
                .append("\"dimension\":").append(quote(terrain.dimension())).append(',')
                .append("\"client_version\":").append(quote(terrain.clientVersion())).append(',')
                .append("\"chunk_x\":").append(terrain.chunkX()).append(',')
                .append("\"chunk_z\":").append(terrain.chunkZ()).append(',')
                .append("\"min_build_y\":").append(terrain.minBuildY()).append(',')
                .append("\"max_build_y_exclusive\":").append(terrain.maxBuildYExclusive()).append(',')
                .append("\"sea_level\":").append(terrain.seaLevel()).append(',')
                .append("\"surface_y\":[");
        Iterator<Integer> heights = terrain.surfaceHeights().iterator();
        while (heights.hasNext()) {
            builder.append(heights.next());
            if (heights.hasNext()) {
                builder.append(',');
            }
        }
        return builder.append("],\"sample_grid\":\"4x4_quart_centers\",")
                .append("\"heightmap\":\"WORLD_SURFACE\",")
                .append("\"observed_at\":").append(quote(terrain.observedAt().toString()))
                .append('}').toString();
    }

    /** 只用于 JSON 字符串；避免将用户注释或外部标识直接拼成非法 JSON。 */
    private static String quote(String value) {
        StringBuilder out = new StringBuilder().append('"');
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
                        out.append(String.format(java.util.Locale.ROOT, "\\u%04x", (int) c));
                    } else {
                        out.append(c);
                    }
                }
            }
        }
        return out.append('"').toString();
    }
}
