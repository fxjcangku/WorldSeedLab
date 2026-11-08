package io.github.fxjcangku.worldseedlab.core.solver;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * P4.3：原始客户端P1的空间分组气候观测。比P3.2独立单点quart多保留了
 * 每个FIT/HOLDOUT区域内的稀疏观测，以便测试未知自定义群系标签与
 * Minecraft原生气候场是否存在跨区域可泛化的映射。
 *
 * <p>客户端来源与未知生成器指纹必须保持原样。此格式是仅在本机临时生成的
 * 研究输入，不提供服务端NoiseRouter、真实Seed或世界生成来源认证。
 */
public final class P43SpatialClimateEvidence {
    private P43SpatialClimateEvidence() {}
    public static final String MAGIC = "WSL_P43_SPATIAL_CLIMATE_V1";
    public static final int MACRO_REGION_QUARTS = 128; // 32个16方块区块=512方块
    private static final int MAX_BYTES = 512 * 1024;
    private static final int MAX_POINTS = 768;
    private static final Pattern BIOME = Pattern.compile("[a-z0-9_.-]+:[a-z0-9_./-]+");

    public enum Split { FIT, HOLDOUT }

    public record Point(int quartX, int quartY, int quartZ, String biomeId, Split split) {
        public Point {
            if (quartX < -8_000_000 || quartX > 8_000_000
                    || quartZ < -8_000_000 || quartZ > 8_000_000
                    || quartY < -1024 || quartY >= 2048
                    || biomeId == null || biomeId.length() > 256
                    || !BIOME.matcher(biomeId).matches() || split == null) {
                throw new IllegalArgumentException("不可信的客户端quart坐标/群系/留出角色");
            }
        }
        public int regionX() { return Math.floorDiv(quartX, MACRO_REGION_QUARTS); }
        public int regionZ() { return Math.floorDiv(quartZ, MACRO_REGION_QUARTS); }
        public String region() { return regionX() + ":" + regionZ(); }
    }

    public record Archive(Full64BiomeCandidateSearch.Scope scope, List<Point> points,
                          int fitRegions, int holdoutRegions, int distinctBiomes) {
        public Archive {
            Objects.requireNonNull(scope);
            if (!ClientQuartEvidenceArchive.UNVERIFIED_PROFILE.equals(scope.generatorProfile())
                    || !"minecraft:overworld".equals(scope.dimension())
                    || points == null || points.size() < 5 || points.size() > MAX_POINTS) {
                throw new IllegalArgumentException("必须是有界CLIENT_OBSERVED主世界真实群系");
            }
            points = List.copyOf(points);
            Map<String, Split> regions = new HashMap<>();
            Set<String> positions = new HashSet<>();
            Set<String> labels = new HashSet<>();
            for (var point : points) {
                if (point == null || !positions.add(point.quartX() + ":" + point.quartY()
                        + ":" + point.quartZ())) {
                    throw new IllegalArgumentException("重复quart不能伪造独立训练/留出");
                }
                Split previous = regions.putIfAbsent(point.region(), point.split());
                if (previous != null && previous != point.split()) {
                    throw new IllegalArgumentException("同一宏区域不能同时属于FIT和HOLDOUT");
                }
                labels.add(point.biomeId());
            }
            List<Point> representatives = new ArrayList<>();
            Set<String> seen = new HashSet<>();
            for (var point : points) {
                if (seen.add(point.region())) representatives.add(point);
            }
            for (int i = 0; i < representatives.size(); i++) {
                for (int j = 0; j < i; j++) {
                    var a = representatives.get(i);
                    var b = representatives.get(j);
                    if (Math.max(Math.abs(a.regionX() - b.regionX()),
                            Math.abs(a.regionZ() - b.regionZ())) < 3) {
                        throw new IllegalArgumentException("不同取样区域相隔不足保守1024方块");
                    }
                }
            }
            if (regions.size() > 12 || regions.values().stream().filter(
                    role -> role == Split.FIT).count() < 3
                    || regions.values().stream().filter(role -> role == Split.HOLDOUT).count() < 2) {
                throw new IllegalArgumentException("至少3FIT区域、2HOLDOUT区域，且最多12区域");
            }
            fitRegions = (int) regions.values().stream()
                    .filter(role -> role == Split.FIT).count();
            holdoutRegions = regions.size() - fitRegions;
            distinctBiomes = labels.size();
        }
    }

    public static Archive read(Path file) throws IOException {
        if (file == null || !Files.isRegularFile(file) || Files.isSymbolicLink(file)
                || Files.size(file) > MAX_BYTES) {
            throw new IOException("P4.3本机临时证据文件不存在或超出容量上限");
        }
        String text = Files.readString(file, StandardCharsets.UTF_8);
        if (text.indexOf('\r') >= 0 || !text.endsWith("\n")) {
            throw new IOException("P4.3客户端证据需要标准UTF-8换行");
        }
        String[] rows = text.split("\n");
        if (rows.length < 13 || !MAGIC.equals(rows[0])
                || !rows[1].startsWith("worldFingerprint=")
                || !rows[2].startsWith("gameVersion=")
                || !rows[3].equals("dimension=minecraft:overworld")
                || !rows[4].equals("generatorProfile=" + ClientQuartEvidenceArchive.UNVERIFIED_PROFILE)
                || !rows[5].equals("provenance=CLIENT_OBSERVED")
                || !rows[6].equals("sampling=AUTOMATIC_FULL_CHUNK_OBSERVATION")
                || !rows[7].equals("quart_x,quart_y,quart_z,biome_id,split")) {
            throw new IOException("P4.3证据结构或来源非法");
        }
        try {
            var scope = new Full64BiomeCandidateSearch.Scope(
                    rows[1].substring("worldFingerprint=".length()),
                    rows[2].substring("gameVersion=".length()),
                    "minecraft:overworld", ClientQuartEvidenceArchive.UNVERIFIED_PROFILE);
            List<Point> points = new ArrayList<>();
            for (int i = 8; i < rows.length; i++) {
                if (points.size() >= MAX_POINTS) throw new IllegalArgumentException("观测容量超限");
                String[] cols = rows[i].split(",", -1);
                if (cols.length != 5) throw new IllegalArgumentException("证据行列数不正确");
                points.add(new Point(Integer.parseInt(cols[0]), Integer.parseInt(cols[1]),
                        Integer.parseInt(cols[2]), cols[3], Split.valueOf(cols[4])));
            }
            return new Archive(scope, points, 0, 0, 0);
        } catch (RuntimeException error) {
            throw new IOException("P4.3客户端空间群系证据格式/隔离失败", error);
        }
    }
}
