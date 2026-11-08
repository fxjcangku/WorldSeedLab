package io.github.fxjcangku.worldseedlab.core.solver;

import io.github.fxjcangku.worldseedlab.core.geometry.ClientVisibleDesertPyramidRaster;
import io.github.fxjcangku.worldseedlab.core.geometry.StructureFootprintInverter;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.LinkOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * 只读导入普通客户端完整轮廓，并构造独立 FIT/HOLDOUT 结构约束。
 *
 * <p>此层不启动求解、不读取系统属性、不输出原始作用域。几何零偏移校准
 * 来自 P3.5 两版受控游戏 FULL+StructureStart 对照（Actions 38563599534）；
 * 应用于未知服务器仍为模型假设，不认证天然来源、Salt 或生成历史。
 */
public final class ClientStructureEvidence {
    private ClientStructureEvidence() {}

    public static final String SET_ID = "minecraft:desert_pyramids";
    public static final String CALIBRATION_ID = "p35_real_mc_20261014";
    private static final int MAX_INPUT_ENTRIES = 100_000;
    private static final int MAX_ARCHIVES = 64;
    private static final int MAX_ARCHIVE_BYTES = 320_000;

    public record Site(ClientVisibleDesertPyramidRaster raster,
                       StructureFootprintInverter.BlockBounds outline,
                       int chunkX, int chunkZ) {}

    public record Observation(List<Site> sites, int filesSeen, String version,
                              String dimension, String worldFingerprint) {
        public Observation {
            sites = List.copyOf(sites);
            if (!"26.1.2".equals(version) && !"26.2".equals(version)) {
                throw new IllegalArgumentException("客户端轮廓版本不受支持");
            }
            if (filesSeen < sites.size() || filesSeen > MAX_ARCHIVES) {
                throw new IllegalArgumentException("客户端轮廓计数非法");
            }
            Set<String> unique = new HashSet<>();
            for (var site : sites) {
                var scope = site.raster().scope();
                var audit = site.raster().audit();
                if (!Objects.equals(worldFingerprint, scope.worldFingerprint())
                        || !Objects.equals(dimension, scope.dimension())
                        || !version.equals(scope.gameVersion())
                        || !audit.completeVisiblePerimeter() || audit.matchedFootprints() != 1
                        || !site.outline().equals(site.raster().discoveredFootprint())
                        || Math.floorMod(site.outline().minX(), 16) != 0
                        || Math.floorMod(site.outline().minZ(), 16) != 0
                        || site.chunkX() != Math.floorDiv(site.outline().minX(), 16)
                        || site.chunkZ() != Math.floorDiv(site.outline().minZ(), 16)
                        || !unique.add(site.chunkX() + ":" + site.chunkZ())) {
                    throw new IllegalArgumentException("客户端轮廓范围、作用域或独立性未通过复核");
                }
            }
        }
        public int independentRegions(int spacing) {
            return (int) sites.stream().map(s -> regionKey(s.chunkX(), s.chunkZ(), spacing))
                    .distinct().count();
        }
    }

    private static boolean archiveName(String entry) {
        String name = entry.substring(entry.lastIndexOf('/') + 1);
        return name.endsWith(".properties") && (
                name.startsWith("client-auto-desert-")
                || name.startsWith("client-footprint-"));
    }

    /**
     * 完全只读，ZIP逐条安全限额解压于临时文件，不提取原始用户目录。
     * 相同HMAC/维度/版本是硬门槛；不可信/不完整轮廓拒绝入求解。
     */
    public static Observation inspect(Path input, String expectedVersion) throws IOException {
        Objects.requireNonNull(input);
        if (!"26.1.2".equals(expectedVersion) && !"26.2".equals(expectedVersion)) {
            throw new IllegalArgumentException("必须指定Minecraft 26.1.2或26.2匹配版本");
        }
        List<ClientVisibleDesertPyramidRaster> records = new ArrayList<>();
        int seen = 0;
        if (Files.isSymbolicLink(input)) {
            throw new IOException("客户端证据入口不得是符号链接");
        }
        if (Files.isDirectory(input, LinkOption.NOFOLLOW_LINKS)) {
            try (var walk = Files.walk(input, 9)) {
                List<Path> paths = walk.filter(Files::isRegularFile)
                        .filter(path -> archiveName(path.getFileName().toString()))
                        .sorted().limit(MAX_ARCHIVES + 1L).toList();
                if (paths.size() > MAX_ARCHIVES) {
                    throw new IOException("自动取证轮廓超过64份安全上限");
                }
                for (Path path : paths) {
                    if (Files.isSymbolicLink(path) || Files.size(path) > MAX_ARCHIVE_BYTES) {
                        throw new IOException("客户端轮廓存档不是安全的普通文件");
                    }
                    seen++;
                    records.add(ClientVisibleDesertPyramidRaster.read(path));
                }
            }
        } else if (Files.isRegularFile(input) && !Files.isSymbolicLink(input)) {
            try (ZipFile zipped = new ZipFile(input.toFile())) {
                if (zipped.size() > MAX_INPUT_ENTRIES) {
                    throw new IOException("ZIP成员数超过取证安全上限");
                }
                var entries = zipped.stream().filter(e -> !e.isDirectory())
                        .filter(e -> archiveName(e.getName()))
                        .sorted(Comparator.comparing(ZipEntry::getName))
                        .limit(MAX_ARCHIVES + 1L).toList();
                if (entries.size() > MAX_ARCHIVES) {
                    throw new IOException("ZIP内完整轮廓超过64份上限，需按世界分组");
                }
                Set<String> entryNames = new HashSet<>();
                for (var entry : entries) {
                    if (!entryNames.add(entry.getName())) {
                        throw new IOException("ZIP包含重名轮廓成员，拒绝不明确的证据来源");
                    }
                    if (entry.getName().startsWith("/") || entry.getName().contains("../")
                            || entry.getSize() > MAX_ARCHIVE_BYTES) {
                        throw new IOException("ZIP轮廓路径或数据长度不安全");
                    }
                    Path tmp = Files.createTempFile("wsl-p39-client-raster-", ".properties");
                    try {
                        byte[] bytes;
                        try (InputStream in = zipped.getInputStream(entry)) {
                            bytes = in.readNBytes(MAX_ARCHIVE_BYTES + 1);
                        }
                        if (bytes.length > MAX_ARCHIVE_BYTES) {
                            throw new IOException("解压轮廓超出单文件安全上限");
                        }
                        Files.write(tmp, bytes);
                        seen++;
                        records.add(ClientVisibleDesertPyramidRaster.read(tmp));
                    } finally {
                        Files.deleteIfExists(tmp);
                    }
                }
            }
        } else {
            throw new IOException("请选择存在的客户端observations.zip或世界取证目录");
        }

        String world = null, dimension = null;
        List<Site> accepted = new ArrayList<>();
        Set<String> uniqueSites = new HashSet<>();
        for (var raster : records) {
            var scope = raster.scope();
            if (!expectedVersion.equals(scope.gameVersion())) {
                throw new IOException("客户端轮廓与当前Minecraft映射版本不一致，拒绝混合");
            }
            if (world == null) { world = scope.worldFingerprint(); dimension = scope.dimension(); }
            if (!world.equals(scope.worldFingerprint()) || !dimension.equals(scope.dimension())) {
                throw new IOException("世界HMAC或维度不一致，拒绝跨世界混合结构求解");
            }
            var audit = raster.audit();
            if (!audit.completeVisiblePerimeter() || audit.matchedFootprints() != 1) {
                throw new IOException("存在不完整/歧义的客户端神殿轮廓，拒绝当作结构起点");
            }
            var outline = raster.discoveredFootprint();
            if (Math.floorMod(outline.minX(), 16) != 0
                    || Math.floorMod(outline.minZ(), 16) != 0) {
                // 真实受控样本零偏移；未知生成器如果偏离，此模型不能强行取整。
                throw new IOException("完整轮廓与受控零偏移模板不一致，请校准新的生成器");
            }
            int cx = Math.floorDiv(outline.minX(), 16);
            int cz = Math.floorDiv(outline.minZ(), 16);
            if (uniqueSites.add(cx + ":" + cz)) {
                accepted.add(new Site(raster, outline, cx, cz));
            }
        }
        accepted.sort(Comparator.comparingInt(Site::chunkX).thenComparingInt(Site::chunkZ));
        return new Observation(accepted, seen, expectedVersion, dimension, world);
    }

    private static String regionKey(int cx, int cz, int spacing) {
        return Math.floorDiv(cx, spacing) + ":" + Math.floorDiv(cz, spacing);
    }

    /**
     * 只允许独立RandomSpread region进入FIT/HOLDOUT。来自 P35真MC受控
     * 轮廓的零偏移模板作为待验证的自然生成假设，而非目标服真值证书。
     */
    public static SolverResearchInput constraints(Observation observed, int salt) {
        return constraints(observed, salt, 0, CandidateSeedSearch.TOTAL_48);
    }

    /** 受控CI可检查公开已知有限窗口；实际研究默认全2^48位域。 */
    public static SolverResearchInput constraints(Observation observed, int salt,
                                                   long startInclusive, long endExclusive) {
        Objects.requireNonNull(observed);
        if (observed.sites().isEmpty() || observed.worldFingerprint() == null
                || !"minecraft:overworld".equals(observed.dimension())) {
            throw new IllegalArgumentException("缺少主世界已验证完整轮廓，无法提出低48约束");
        }
        var model = new RandomSpreadSpec(SET_ID, 32, 8,
                RandomSpreadSpec.Distribution.LINEAR, salt);
        var calibration = new StructureFootprintInverter.Calibration(
                CALIBRATION_ID, observed.version(), observed.dimension(),
                SET_ID, "minecraft:desert_pyramid", "controlled_21x21_geometry",
                0, 0, 20, 20, 0);
        List<Site> independent = new ArrayList<>();
        Set<String> regions = new HashSet<>();
        for (var site : observed.sites()) {
            String key = regionKey(site.chunkX(), site.chunkZ(), model.spacing());
            if (regions.add(key)) independent.add(site);
        }
        if (independent.size() < 3) {
            throw new IllegalArgumentException("需要至少三个独立RandomSpread区域的完整沙漠神殿；"
                    + "当前只有" + independent.size() + "个；未运行假种子求解");
        }
        List<StartPlacementConstraint> constraints = new ArrayList<>();
        for (int i = 0; i < independent.size() && i < 32; i++) {
            var site = independent.get(i);
            var raster = site.raster();
            var scope = raster.scope();
            var observedOutline = new StructureFootprintInverter.ObservedFootprint(
                    scope.worldFingerprint(), scope.gameVersion(), scope.dimension(),
                    SET_ID, "minecraft:desert_pyramid", site.outline(),
                    StructureFootprintInverter.FootprintQuality.COMPLETE_PERIMETER);
            var inferred = StructureFootprintInverter.infer(
                    observedOutline, List.of(calibration));
            constraints.add(inferred.toConstraint(observedOutline,
                    i == independent.size() - 1 || i == 31
                            ? StartPlacementConstraint.Split.HOLDOUT
                            : StartPlacementConstraint.Split.FIT));
        }
        var input = new SolverResearchInput(Map.of(SET_ID, model), constraints,
                new CandidateSeedSearch.SearchWindow(
                        startInclusive, endExclusive, 1_000_000, 32, 90_000));
        SymbolicRandomSpread.validateEvidence(input);
        return input;
    }

}
