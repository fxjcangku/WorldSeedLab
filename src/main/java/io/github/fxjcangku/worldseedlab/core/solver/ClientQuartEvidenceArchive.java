package io.github.fxjcangku.worldseedlab.core.solver;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * P3.2 普通客户端真实可见群系的独立、受作用域约束的离线取证格式。
 *
 * <p>客户端并不知道服务器真实生成器，归档始终使用 UNVERIFIED_PROFILE。
 * 导入后只有用户在离线实验中**明确声明**原版 Overworld 假设，才能将
 * 证据映射到验证过的 VanillaOverworldBiomeModel；此转换不证明服务器
 * 使用了原版生成器。任何原始坐标都只保存到用户主动导出的本地文件。
 */
public final class ClientQuartEvidenceArchive {
    public static final String UNVERIFIED_PROFILE = "worldseedlab:unknown_worldgen";
    public static final int MAX_SAMPLES = 64;
    public static final int MIN_HORIZONTAL_QUART_DISTANCE = 256; // 1024方块
    private static final String MAGIC = "WSL_P32_CLIENT_QUART_V1";
    private static final String HEADER = "quart_x,quart_y,quart_z,biome_id,split";
    // P3.9只读P1已完成FULL区块的自动证据转换，来源不能冒充用户手动quart。
    private static final long MAX_FILE_BYTES = 32 * 1024;

    private ClientQuartEvidenceArchive() {}

    public record Archive(Full64BiomeCandidateSearch.Scope scope,
                          List<Full64BiomeCandidateSearch.BiomeSample> samples) {
        public Archive {
            Objects.requireNonNull(scope);
            if (!UNVERIFIED_PROFILE.equals(scope.generatorProfile())) {
                throw new IllegalArgumentException("客户端证据不能自行声明服务器使用原版生成器");
            }
            samples = List.copyOf(Objects.requireNonNull(samples));
            if (samples.size() < 5 || samples.size() > MAX_SAMPLES) {
                throw new IllegalArgumentException("至少5条独立quart观测才能导出");
            }
            validateSamples(scope, samples);
        }

        /** 明确声明原版模型假设仅影响本次离线研究，不会改写客户端原始证据。 */
        public List<Full64BiomeCandidateSearch.BiomeSample> forVanillaHypothesis() {
            Full64BiomeCandidateSearch.Scope assumed =
                    new Full64BiomeCandidateSearch.Scope(
                            scope.worldFingerprint(), scope.gameVersion(),
                            scope.dimension(), VanillaOverworldBiomeModel.GENERATOR_PROFILE);
            return samples.stream().map(item ->
                    new Full64BiomeCandidateSearch.BiomeSample(
                            assumed, item.quartX(), item.quartY(), item.quartZ(),
                            item.observedBiome(), item.split(), item.origin())).toList();
        }
    }

    /** 每次采样必须由用户显式触发；此类没有世界访问权限，也不会自动写盘。 */
    public static final class Session {
        private final Full64BiomeCandidateSearch.Scope scope;
        private final List<Full64BiomeCandidateSearch.BiomeSample> points = new ArrayList<>();

        public Session(String fingerprint, String version, String dimension) {
            scope = new Full64BiomeCandidateSearch.Scope(
                    fingerprint, version, dimension, UNVERIFIED_PROFILE);
        }

        public Full64BiomeCandidateSearch.Scope scope() { return scope; }
        public int size() { return points.size(); }
        public boolean ready() { return points.size() >= 5; }
        public void clear() { points.clear(); }

        /** 使用 Minecraft 自己的 quart 坐标口径：负方块向负无穷取整。 */
        public void addObservedBlock(int blockX, int blockY, int blockZ, String biomeId) {
            addQuart(Math.floorDiv(blockX, 4), Math.floorDiv(blockY, 4),
                    Math.floorDiv(blockZ, 4), biomeId);
        }

        public void addQuart(int qx, int qy, int qz, String biomeId) {
            if (points.size() >= MAX_SAMPLES) {
                throw new IllegalArgumentException("最多64条观测，必须先导出/清空");
            }
            for (var item : points) {
                long dx = (long) qx - item.quartX();
                long dz = (long) qz - item.quartZ();
                if (Math.abs(dx) < MIN_HORIZONTAL_QUART_DISTANCE
                        && Math.abs(dz) < MIN_HORIZONTAL_QUART_DISTANCE
                        && dx * dx + dz * dz < (long) MIN_HORIZONTAL_QUART_DISTANCE
                                * MIN_HORIZONTAL_QUART_DISTANCE) {
                    throw new IllegalArgumentException(
                            "采样点相隔不足1024方块，不能重复作为独立FIT/HOLDOUT");
                }
            }
            var item = new Full64BiomeCandidateSearch.BiomeSample(
                    scope, qx, qy, qz, biomeId,
                    points.size() < 3 ? Full64BiomeCandidateSearch.Split.FIT
                            : Full64BiomeCandidateSearch.Split.HOLDOUT,
                    Full64BiomeCandidateSearch.Origin.CLIENT_OBSERVED);
            points.add(item);
        }

        public Archive snapshot() { return new Archive(scope, points); }
    }

    public static String encode(Archive archive) {
        Objects.requireNonNull(archive);
        StringBuilder out = new StringBuilder(2500);
        out.append(MAGIC).append('\n')
                .append("worldFingerprint=").append(archive.scope().worldFingerprint()).append('\n')
                .append("gameVersion=").append(archive.scope().gameVersion()).append('\n')
                .append("dimension=").append(archive.scope().dimension()).append('\n')
                .append("generatorProfile=").append(UNVERIFIED_PROFILE).append('\n')
                .append("provenance=CLIENT_OBSERVED\n")
                .append("sampling=MANUAL_ALREADY_LOADED_FULL\n")
                .append(HEADER).append('\n');
        for (var item : archive.samples()) {
            out.append(item.quartX()).append(',')
                    .append(item.quartY()).append(',')
                    .append(item.quartZ()).append(',')
                    .append(item.observedBiome()).append(',')
                    .append(item.split()).append('\n');
        }
        return out.toString();
    }

    public static Archive read(Path path) throws IOException {
        if (path == null || !Files.isRegularFile(path) || Files.isSymbolicLink(path)
                || Files.size(path) > MAX_FILE_BYTES) {
            throw new IOException("客户端群系证据不存在、是链接或超出32KiB");
        }
        return decode(Files.readString(path, StandardCharsets.UTF_8));
    }

    /** 防止换世界/维度时悄悄使用另一文件；作用域必须来自独立可信会话。 */
    public static Archive decodeForScope(
            String text, Full64BiomeCandidateSearch.Scope expected
    ) {
        Archive archive = decode(text);
        if (!archive.scope().equals(Objects.requireNonNull(expected))) {
            throw new IllegalArgumentException("导出的客户端证据与实际会话/版本/维度作用域不一致");
        }
        return archive;
    }

    public static Archive decode(String text) {
        Objects.requireNonNull(text);
        if (text.length() > MAX_FILE_BYTES || text.indexOf('\r') >= 0) {
            throw new IllegalArgumentException("客户端证据过大或换行格式异常");
        }
        String[] lines = text.split("\n", -1);
        if (lines.length < 14 || !MAGIC.equals(lines[0])
                || !lines[1].startsWith("worldFingerprint=")
                || !lines[2].startsWith("gameVersion=")
                || !lines[3].startsWith("dimension=")
                || !lines[4].equals("generatorProfile=" + UNVERIFIED_PROFILE)
                || !lines[5].equals("provenance=CLIENT_OBSERVED")
                || !(lines[6].equals("sampling=MANUAL_ALREADY_LOADED_FULL")
                        || lines[6].equals("sampling=AUTOMATIC_FULL_CHUNK_OBSERVATION"))
                || !HEADER.equals(lines[7]) || !lines[lines.length - 1].isEmpty()) {
            throw new IllegalArgumentException("非法的P3.2 CLIENT_OBSERVED取证格式");
        }
        var scope = new Full64BiomeCandidateSearch.Scope(
                lines[1].substring("worldFingerprint=".length()),
                lines[2].substring("gameVersion=".length()),
                lines[3].substring("dimension=".length()), UNVERIFIED_PROFILE);
        List<Full64BiomeCandidateSearch.BiomeSample> points = new ArrayList<>();
        for (int i = 8; i < lines.length - 1; i++) {
            String[] fields = lines[i].split(",", -1);
            if (fields.length != 5 || points.size() >= MAX_SAMPLES) {
                throw new IllegalArgumentException("群系数据列数或行数超限");
            }
            try {
                points.add(new Full64BiomeCandidateSearch.BiomeSample(
                        scope, Integer.parseInt(fields[0]), Integer.parseInt(fields[1]),
                        Integer.parseInt(fields[2]), fields[3],
                        Full64BiomeCandidateSearch.Split.valueOf(fields[4]),
                        Full64BiomeCandidateSearch.Origin.CLIENT_OBSERVED));
            } catch (RuntimeException failure) {
                throw new IllegalArgumentException("群系取证行非法: " + i, failure);
            }
        }
        return new Archive(scope, points);
    }

    private static void validateSamples(Full64BiomeCandidateSearch.Scope scope,
            List<Full64BiomeCandidateSearch.BiomeSample> points) {
        Set<String> coordinates = new HashSet<>();
        for (int i = 0; i < points.size(); i++) {
            var point = Objects.requireNonNull(points.get(i));
            if (!scope.equals(point.scope())
                    || point.origin() != Full64BiomeCandidateSearch.Origin.CLIENT_OBSERVED
                    || point.split() != (i < 3 ? Full64BiomeCandidateSearch.Split.FIT
                            : Full64BiomeCandidateSearch.Split.HOLDOUT)
                    || !coordinates.add(point.quartX() + ":" + point.quartY() + ":"
                            + point.quartZ())) {
                throw new IllegalArgumentException("跨世界、伪造来源、重复或不独立的群系样本");
            }
            for (int j = 0; j < i; j++) {
                var other = points.get(j);
                long dx = (long) point.quartX() - other.quartX();
                long dz = (long) point.quartZ() - other.quartZ();
                if (Math.abs(dx) < MIN_HORIZONTAL_QUART_DISTANCE
                        && Math.abs(dz) < MIN_HORIZONTAL_QUART_DISTANCE
                        && dx * dx + dz * dz < (long) MIN_HORIZONTAL_QUART_DISTANCE
                                * MIN_HORIZONTAL_QUART_DISTANCE) {
                    throw new IllegalArgumentException("群系候选空间距离不足");
                }
            }
        }
    }
}
