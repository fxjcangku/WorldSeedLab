package io.github.fxjcangku.worldseedlab.core.geometry;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.List;
import java.util.Objects;
import java.util.Properties;
import java.util.regex.Pattern;

/**
 * P3.5 普通客户端真实 BlockState 的有界三维栅格。只包含“看到的方块”，
 * 不含 StructureStart、生成器参数、Seed 或服务端结构元数据。
 *
 * <p>两位人工标定的建筑水平对角点只是待验证区域，不会自动提升为真实起点。
 * 栅格必须覆盖外侧一格和全部指定 Y 层；缺失或中断的样本绝不能补空气。
 * 露天砂岩围墙与特殊陶瓦等特征只证明“可见形态一致”，
 * 无法排除人工复制、修复、混合世代或服务器更改自然建筑。
 */
public final class ClientVisibleDesertPyramidRaster {
    public static final int SCHEMA = 1;
    public static final byte AIR = 0;
    public static final byte SANDSTONE = 1;
    public static final byte CARVED_OR_CUT = 2;
    public static final byte ORANGE_TERRACOTTA = 3;
    public static final byte BLUE_TERRACOTTA = 4;
    public static final byte OTHER = 5;
    private static final Pattern WORLD = Pattern.compile("[a-f0-9]{64}");
    private static final Pattern ID = Pattern.compile("[a-z0-9_.-]+:[a-z0-9_./-]+");
    private static final int MAX_ARCHIVE_BYTES = 320_000;

    public record Scope(String worldFingerprint, String gameVersion, String dimension) {
        public Scope {
            if (worldFingerprint == null || !WORLD.matcher(worldFingerprint).matches()
                    || (!"26.1.2".equals(gameVersion) && !"26.2".equals(gameVersion))
                    || dimension == null || !ID.matcher(dimension).matches()) {
                throw new IllegalArgumentException("P3.5 世界指纹、版本或维度非法");
            }
        }
    }

    /** VERIFIED_VISIBLE 仅表示栅格里的完整可见边界；自然生成仍然 UNKNOWN。 */
    public record Audit(boolean completeVisiblePerimeter, String reason,
                        int orangeBlocks, int blueBlocks, int carvedBlocks,
                        int westEdge, int eastEdge, int northEdge, int southEdge,
                        int possibleOrigins) {}

    private final Scope scope;
    private final StructureFootprintInverter.BlockBounds bounds;
    private final int minY, maxY;
    private final byte[] raster;
    private final int outerWidth, outerDepth, height;

    public ClientVisibleDesertPyramidRaster(Scope scope,
            StructureFootprintInverter.BlockBounds bounds, int minY, int maxY, byte[] raster) {
        this.scope = Objects.requireNonNull(scope);
        this.bounds = Objects.requireNonNull(bounds);
        int width = bounds.maxX() - bounds.minX() + 1;
        int depth = bounds.maxZ() - bounds.minZ() + 1;
        if (width < 16 || depth < 16 || width > 40 || depth > 40
                || minY > maxY || (long) maxY - minY + 1 < 16
                || (long) maxY - minY + 1 > 40
                || bounds.minX() == Integer.MIN_VALUE || bounds.maxX() == Integer.MAX_VALUE
                || bounds.minZ() == Integer.MIN_VALUE || bounds.maxZ() == Integer.MAX_VALUE) {
            throw new IllegalArgumentException("P3.5 建筑区域必须16..40宽、16..40高且包含外侧环带");
        }
        this.minY = minY;
        this.maxY = maxY;
        this.outerWidth = width + 2;
        this.outerDepth = depth + 2;
        this.height = maxY - minY + 1;
        if (raster == null || raster.length != outerWidth * outerDepth * height) {
            throw new IllegalArgumentException("P3.5 栅格缺失，不能使用部分轮廓");
        }
        this.raster = raster.clone();
        for (byte value : this.raster) {
            if (value < AIR || value > OTHER) {
                throw new IllegalArgumentException("P3.5 栅格中含有未知/未完成的方块");
            }
        }
    }

    public Scope scope() { return scope; }
    public StructureFootprintInverter.BlockBounds bounds() { return bounds; }
    public int minY() { return minY; }
    public int maxY() { return maxY; }
    public int rasterSize() { return raster.length; }

    /** 固定顺序：Y 最快，接着 Z，最后 X；所有索引均严格有界。 */
    public static int index(int x, int y, int z,
                            StructureFootprintInverter.BlockBounds b,
                            int lowY, int highY) {
        int w = b.maxX() - b.minX() + 3;
        int d = b.maxZ() - b.minZ() + 3;
        int h = highY - lowY + 1;
        int dx = x - (b.minX() - 1), dz = z - (b.minZ() - 1), dy = y - lowY;
        if (dx < 0 || dx >= w || dz < 0 || dz >= d || dy < 0 || dy >= h) {
            throw new IndexOutOfBoundsException("P3.5 读取坐标不在手动授权的栅格内");
        }
        return (dx * d + dz) * h + dy;
    }

    public byte at(int x, int y, int z) {
        return raster[index(x, y, z, bounds, minY, maxY)];
    }

    /** 只对 P2.6 沙漠神殿材料进行分类；石英、矿石、人工材料不能提升资格。 */
    public static byte classify(String blockId) {
        return switch (Objects.requireNonNull(blockId)) {
            case "minecraft:air", "minecraft:cave_air", "minecraft:void_air" -> AIR;
            case "minecraft:sandstone", "minecraft:smooth_sandstone",
                 "minecraft:sandstone_stairs", "minecraft:sandstone_slab",
                 "minecraft:sandstone_wall", "minecraft:smooth_sandstone_stairs",
                 "minecraft:smooth_sandstone_slab" -> SANDSTONE;
            case "minecraft:chiseled_sandstone", "minecraft:cut_sandstone",
                 "minecraft:cut_sandstone_slab" -> CARVED_OR_CUT;
            case "minecraft:orange_terracotta" -> ORANGE_TERRACOTTA;
            case "minecraft:blue_terracotta" -> BLUE_TERRACOTTA;
            default -> OTHER;
        };
    }

    /**
     * 对四条边逐列验证：同一高度上建筑侧真实砂岩与外侧一格真实空气相邻。
     * 这比材料数量或包络大小更严格，但不是“自然生成”的证明。
     */
    public Audit audit() {
        int orange = 0, blue = 0, carved = 0;
        for (int x = bounds.minX(); x <= bounds.maxX(); x++) {
            for (int z = bounds.minZ(); z <= bounds.maxZ(); z++) {
                for (int y = minY; y <= maxY; y++) {
                    byte cell = at(x, y, z);
                    if (cell == ORANGE_TERRACOTTA) orange++;
                    if (cell == BLUE_TERRACOTTA) blue++;
                    if (cell == CARVED_OR_CUT) carved++;
                }
            }
        }
        int west = edge(bounds.minX(), bounds.minZ(), bounds.maxZ(), -1, 0, true);
        int east = edge(bounds.maxX(), bounds.minZ(), bounds.maxZ(), 1, 0, true);
        int north = edge(bounds.minZ(), bounds.minX(), bounds.maxX(), 0, -1, false);
        int south = edge(bounds.maxZ(), bounds.minX(), bounds.maxX(), 0, 1, false);
        int w = bounds.maxX() - bounds.minX() + 1;
        int d = bounds.maxZ() - bounds.minZ() + 1;
        // 不完整或几何可疑时严格拒绝：不将缺口想象成完整围墙。
        boolean edges = west >= Math.ceil(d * 0.8) && east >= Math.ceil(d * 0.8)
                && north >= Math.ceil(w * 0.8) && south >= Math.ceil(w * 0.8);
        boolean decoration = orange >= 4 && blue >= 1 && carved >= 3;
        String message = !decoration ? "缺少沙漠神殿可见装饰指纹"
                : !edges ? "边界不完整/遮挡/破损或选区未贴合真实外沿"
                : "四侧可见边界完整；人工复制和自然来源仍然未知";
        return new Audit(edges && decoration, message, orange, blue, carved,
                west, east, north, south, 0);
    }

    private int edge(int fixed, int first, int last, int offX, int offZ,
                     boolean fixedX) {
        int hits = 0;
        for (int n = first; n <= last; n++) {
            int x = fixedX ? fixed : n;
            int z = fixedX ? n : fixed;
            boolean witnessed = false;
            for (int y = minY; y <= maxY; y++) {
                byte cell = at(x, y, z);
                if ((cell == SANDSTONE || cell == CARVED_OR_CUT)
                        && at(x + offX, y, z + offZ) == AIR) {
                    witnessed = true;
                    break;
                }
            }
            if (witnessed) hits++;
        }
        return hits;
    }

    /**
     * 此桥接仍只产出 GEOMETRY_ESTIMATE 所需的离散朝向并集；
     * 仅在现场可见外沿证据完整且另有同版受控校准时允许调用。
     * 未经独立证明的自然来源不在本对象中假设为真。
     */
    public StructureFootprintInverter.Inference inferCalibratedHypotheses(
            List<StructureFootprintInverter.Calibration> controlled) {
        if (!audit().completeVisiblePerimeter()) {
            throw new IllegalArgumentException("P3.5 不完整客户端轮廓不得进入 P2.9");
        }
        var observed = new StructureFootprintInverter.ObservedFootprint(
                scope.worldFingerprint(), scope.gameVersion(), scope.dimension(),
                "minecraft:desert_pyramids", "minecraft:desert_pyramid",
                bounds, StructureFootprintInverter.FootprintQuality.COMPLETE_PERIMETER);
        return StructureFootprintInverter.infer(observed, controlled);
    }

    /** 原始坐标只在玩家明确 export 时写入 HMAC 隔离的本地私有文件。 */
    public String encode() {
        return "schema=" + SCHEMA + "\norigin=CLIENT_OBSERVED_BLOCK_STATES\n"
                + "naturalOrigin=UNKNOWN\nstructure=minecraft:desert_pyramid\n"
                + "worldFingerprint=" + scope.worldFingerprint() + "\n"
                + "gameVersion=" + scope.gameVersion() + "\n"
                + "dimension=" + scope.dimension() + "\n"
                + "minX=" + bounds.minX() + "\nmaxX=" + bounds.maxX() + "\n"
                + "minZ=" + bounds.minZ() + "\nmaxZ=" + bounds.maxZ() + "\n"
                + "minY=" + minY + "\nmaxY=" + maxY + "\n"
                + "raster=" + Base64.getEncoder().encodeToString(raster) + "\n";
    }

    public static ClientVisibleDesertPyramidRaster read(Path path) throws IOException {
        if (Files.size(path) > MAX_ARCHIVE_BYTES) {
            throw new IOException("P3.5 栅格超过本地离线回放大小上限");
        }
        var props = new Properties();
        try (var reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            props.load(reader);
        }
        try {
            if (!"1".equals(props.getProperty("schema"))
                    || !"CLIENT_OBSERVED_BLOCK_STATES".equals(props.getProperty("origin"))
                    || !"UNKNOWN".equals(props.getProperty("naturalOrigin"))
                    || !"minecraft:desert_pyramid".equals(props.getProperty("structure"))) {
                throw new IllegalArgumentException("P3.5 证据来源或格式不受信任");
            }
            var scope = new Scope(props.getProperty("worldFingerprint"),
                    props.getProperty("gameVersion"), props.getProperty("dimension"));
            var bounds = new StructureFootprintInverter.BlockBounds(
                    Integer.parseInt(props.getProperty("minX")),
                    Integer.parseInt(props.getProperty("minZ")),
                    Integer.parseInt(props.getProperty("maxX")),
                    Integer.parseInt(props.getProperty("maxZ")));
            return new ClientVisibleDesertPyramidRaster(scope, bounds,
                    Integer.parseInt(props.getProperty("minY")),
                    Integer.parseInt(props.getProperty("maxY")),
                    Base64.getDecoder().decode(props.getProperty("raster")));
        } catch (IllegalArgumentException | NullPointerException problem) {
            throw new IOException("P3.5 无法接受不完整或篡改后的客户端栅格", problem);
        }
    }
}
