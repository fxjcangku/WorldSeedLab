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
                        int matchedFootprints) {}

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
     * 用玩家授权的大范围3D栅格内的实际方块寻找尺寸为21x21的神殿外沿。
     * 21x21只来自目标版本已独立校准的沙漠神殿受控模板，不代表后台Seed。
     *
     * <p>必须在原始栅格内部独立检验四侧“建筑方块对外侧实际空气”，
     * 而不是直接把手动框的边缘或RCON导航位置作为建筑真实外边界。
     * 多于一个相符矩形时保持歧义并拒绝生成起点，不凭最近玩家位置猜。
     */
    public record VisibleDetection(StructureFootprintInverter.BlockBounds blocks, Audit audit) {}

    private List<VisibleDetection> matchingBoundaries() {
        var result = new java.util.ArrayList<VisibleDetection>();
        if (bounds.maxX() - bounds.minX() + 1 < 21
                || bounds.maxZ() - bounds.minZ() + 1 < 21) {
            return List.of();
        }
        // 人工授权范围最大40x40，对21x21窗口最多400个有限测量假设。
        for (int left = bounds.minX(); left <= bounds.maxX() - 20; left++) {
            for (int north = bounds.minZ(); north <= bounds.maxZ() - 20; north++) {
                var proposed = new StructureFootprintInverter.BlockBounds(
                        left, north, left + 20, north + 20);
                var resultForCandidate = auditRectangle(proposed);
                if (resultForCandidate.completeVisiblePerimeter()) {
                    result.add(new VisibleDetection(proposed, resultForCandidate));
                    if (result.size() > 32) {
                        // 过多包络误报不能绕过后续硬上限。
                        return List.copyOf(result);
                    }
                }
            }
        }
        return List.copyOf(result);
    }

    /** 审计此栅格中有多少个完全通过可见外轮廓门槛的潜在矩形。 */
    public Audit audit() {
        var candidates = matchingBoundaries();
        if (candidates.size() == 1) {
            var pass = candidates.getFirst().audit();
            return new Audit(true,
                    "已从客户端方块独立定位21x21可见外沿；自然来源仍然UNKNOWN",
                    pass.orangeBlocks(), pass.blueBlocks(), pass.carvedBlocks(),
                    pass.westEdge(), pass.eastEdge(), pass.northEdge(), pass.southEdge(),1);
        }
        return new Audit(false,
                candidates.isEmpty()
                        ? "未从实际方块找到完整21x21可见外沿（可能遮挡/损坏/误选区域）"
                        : "真实可见外沿有" + candidates.size()
                          + "种重叠解释，必须拒绝不唯一的建筑起点",
                0,0,0,0,0,0,0,candidates.size());
    }

    public StructureFootprintInverter.BlockBounds discoveredFootprint() {
        var candidates = matchingBoundaries();
        if (candidates.size() != 1) {
            throw new IllegalArgumentException(
                    "P3.5 客户端真实方块不能唯一确定完整建筑外沿，候选数=" + candidates.size());
        }
        return candidates.getFirst().blocks();
    }

    /** 只读取CLIENT_OBSERVED三维方块；人工ROI可大于最后发现的真实轮廓。 */
    private Audit auditRectangle(StructureFootprintInverter.BlockBounds proposed) {
        int orange = 0, blue = 0, carved = 0;
        for (int x = proposed.minX(); x <= proposed.maxX(); x++) {
            for (int z = proposed.minZ(); z <= proposed.maxZ(); z++) {
                for (int y = minY; y <= maxY; y++) {
                    byte cell = at(x, y, z);
                    if (cell == ORANGE_TERRACOTTA) orange++;
                    if (cell == BLUE_TERRACOTTA) blue++;
                    if (cell == CARVED_OR_CUT) carved++;
                }
            }
        }
        int west = edge(proposed.minX(), proposed.minZ(), proposed.maxZ(), -1, 0, true);
        int east = edge(proposed.maxX(), proposed.minZ(), proposed.maxZ(), 1, 0, true);
        int north = edge(proposed.minZ(), proposed.minX(), proposed.maxX(), 0, -1, false);
        int south = edge(proposed.maxZ(), proposed.minX(), proposed.maxX(), 0, 1, false);
        int w = proposed.maxX() - proposed.minX() + 1;
        int d = proposed.maxZ() - proposed.minZ() + 1;
        // P3.5 受控21x21模板的完整外侧边界必须逐列闭合；
        // 缺失哪怕一列也不得悄悄把破损形态提升为“完整”。
        boolean edges = west == d && east == d && north == w && south == w;
        boolean decoration = orange >= 4 && blue >= 1 && carved >= 3;
        return new Audit(edges && decoration,
                !decoration ? "缺少受控神殿装饰指纹"
                        : !edges ? "四侧真实方块/空气外缘不完整"
                        : "可见外轮廓成立（非自然来源认证）",
                orange,blue,carved,west,east,north,south,0);
    }

    private int edge(int fixed, int first, int last, int offX, int offZ,
                     boolean fixedX) {
        int hits = 0;
        for (int n = first; n <= last; n++) {
            int x = fixedX ? fixed : n;
            int z = fixedX ? n : fixed;
            boolean buildingOnEdge = false;
            boolean exteriorHasAir = false;
            boolean exteriorHasBuilding = false;
            for (int y = minY; y <= maxY; y++) {
                byte inside = at(x, y, z);
                byte outside = at(x + offX, y, z + offZ);
                if (inside >= SANDSTONE && inside <= BLUE_TERRACOTTA) {
                    buildingOnEdge = true;
                }
                if (outside >= SANDSTONE && outside <= BLUE_TERRACOTTA) {
                    exteriorHasBuilding = true;
                }
                if (outside == AIR) exteriorHasAir = true;
            }
            // 真实神殿底部紧靠沙子，同高度外侧可能是沙方块而非空气。
            // 但外侧整列必须不存在结构材料且确实有露天空气；地形掩埋、
            // 结构延伸越过候选框、整列遮挡均不能当完整外沿。
            if (buildingOnEdge && !exteriorHasBuilding && exteriorHasAir) hits++;
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
        var discovered = discoveredFootprint();
        var observed = new StructureFootprintInverter.ObservedFootprint(
                scope.worldFingerprint(), scope.gameVersion(), scope.dimension(),
                "minecraft:desert_pyramids", "minecraft:desert_pyramid",
                discovered, StructureFootprintInverter.FootprintQuality.COMPLETE_PERIMETER);
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
