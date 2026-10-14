package io.github.fxjcangku.worldseedlab.core.solver;

import io.github.fxjcangku.worldseedlab.core.geometry.ClientVisibleDesertPyramidRaster;
import io.github.fxjcangku.worldseedlab.core.geometry.StructureFootprintInverter;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
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
 * P3.9 首个实际客户端证据 -> 低48 SMT -> 完整64位条件模型的离线求解入口。
 *
 * <p>直接读取P3.6真实客户端自动存档或P3.5完整轮廓（ZIP或本机目录），
 * 不再依赖手填StructureStart和结构约束。默认对真实观测执行原版
 * desert_pyramids RandomSpread 的显式研究假设。不能证明建筑天然来源、
 * 目标服Salt、生成器算法，或从普通客户端获得后台 /seed 权限。
 *
 * <p>本研究的零偏移几何校准来自两版实际 Minecraft 受控 world:
 * GitHub Actions P3.5 run 38563599534，分别4/4 FULL NBT真起点与
 * 普通Fabric可见完整21x21轮廓，四个实测相对偏移均为(0,0,20,20)。
 * 对未知自定义地形服务器应用该校准仍是一项假设，必须独立验证。
 */
public final class RealClientSeedRecoveryCli {
    private RealClientSeedRecoveryCli() {}

    public static final String SET_ID = "minecraft:desert_pyramids";
    private static final int MAX_INPUT_ENTRIES = 100_000;
    private static final int MAX_ARCHIVES = 64;
    private static final int MAX_ARCHIVE_BYTES = 320_000;

    public record Site(ClientVisibleDesertPyramidRaster raster,
                       StructureFootprintInverter.BlockBounds outline,
                       int chunkX, int chunkZ) {}

    public record Observation(List<Site> sites, int filesSeen, String version,
                              String dimension, String worldFingerprint) {
        public Observation { sites = List.copyOf(sites); }
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
        if (Files.isDirectory(input)) {
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
                for (var entry : entries) {
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
                "p35_real_mc_20261014", observed.version(), observed.dimension(),
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

    private static List<Integer> salts(String argument) {
        String[] tokens = argument.split(",", -1);
        if (tokens.length == 0 || tokens.length > 4) {
            throw new IllegalArgumentException("每次最多四个明确声明的结构Salt假设");
        }
        List<Integer> out = new ArrayList<>();
        for (String token : tokens) {
            int value = Integer.parseInt(token.strip());
            if (out.contains(value)) throw new IllegalArgumentException("不能重复相同Salt假设");
            out.add(value);
        }
        return List.copyOf(out);
    }

    public static void main(String[] args) throws Exception {
        if (args.length < 1 || args.length > 4) {
            throw new IllegalArgumentException(
                    "用法：p39RealSeedRecovery -PrecoveryEvidence=observations.zip "
                    + "[-PrecoverySalts=14357617] "
                    + "[-PrecoveryQuart=/path/client-quart.csv "
                    + "-PrecoveryBiomeMode=EXPLICIT_VANILLA_OVERWORLD_HYPOTHESIS]");
        }
        String version = System.getProperty("mcVersion", "26.2");
        Observation observed = inspect(Path.of(args[0]), version);
        System.out.println("WorldSeedLab P3.9 · 真实客户端证据离线种子反演");
        System.out.println("游戏版本=" + version + " | 完整轮廓文件=" + observed.filesSeen()
                + " | 可用不重复轮廓=" + observed.sites().size()
                + " | 独立结构Region=" + observed.independentRegions(32));
        System.out.println("来源=CLIENT_OBSERVED，校准=两版实际P3.5游戏控制世界FULL+StructureStart");
        System.out.println("自然来源=UNKNOWN | 生成器=UNKNOWN | 结构Salt未验证");
        if (observed.independentRegions(32) < 3) {
            System.out.println("RESULT=INSUFFICIENT_GEOMETRY_EVIDENCE");
            System.out.println("目标服务器需要≥3个不同RandomSpread区域的完整自然建筑轮廓，"
                    + "或者需要额外独立可验证的世界生成模型。");
            System.out.println("无法从材料候选、地形高度图或普通quart记录伪造StructureStart。");
            return;
        }

        List<Integer> hypotheses = salts(args.length >= 2 ? args[1] : "14357617");
        if ((args.length == 3) || (args.length == 4
                && !"EXPLICIT_VANILLA_OVERWORLD_HYPOTHESIS".equals(args[3]))) {
            throw new IllegalArgumentException("高16位必须明确提供quart CSV和原版生成器假设");
        }
        long startInclusive = Long.parseLong(System.getProperty("recoveryStart", "0"));
        long endExclusive = Long.parseLong(System.getProperty("recoveryEnd",
                Long.toString(CandidateSeedSearch.TOTAL_48)));
        for (int salt : hypotheses) {
            var input = constraints(observed, salt, startInclusive, endExclusive);
            System.out.println("结构模型假设：spacing=32 separation=8 LINEAR Salt=" + salt
                    + "；不会将材料线索当作天然起点");
            var low = LocalSmtSeedSolver.search(input,
                    new SymbolicRandomSpread.Options(3, 90_000, 64), "z3", 16);
            System.out.println("Z3低48状态=" + low.status()
                    + " | 候选=" + low.candidates().size()
                    + " | 留出拒绝=" + low.rejectedByHoldout()
                    + " | Java精确拒绝=" + low.rejectedByExactModel());
            for (var candidate : low.candidates()) {
                System.out.println("LOW48_MODEL_CANDIDATE=0x" + candidate.low48Hex()
                        + " | FIT=" + candidate.fitMatches()
                        + " | HOLDOUT=" + candidate.holdoutMatches()
                        + " | NATURAL_ORIGIN=UNKNOWN");
            }
            if (args.length == 4 && !low.candidates().isEmpty()) {
                var archive = ClientQuartEvidenceArchive.read(Path.of(args[2]));
                if (!observed.worldFingerprint().equals(archive.scope().worldFingerprint())
                        || !observed.version().equals(archive.scope().gameVersion())
                        || !observed.dimension().equals(archive.scope().dimension())) {
                    throw new IOException("真实quart群系与真实轮廓HMAC/维度/版本不一致");
                }
                var scope = new Full64BiomeCandidateSearch.Scope(
                        observed.worldFingerprint(), observed.version(), observed.dimension(),
                        VanillaOverworldBiomeModel.GENERATOR_PROFILE);
                var model = VanillaOverworldBiomeModel.create(scope);
                long domain = (long) low.candidates().size() * (1 << 16);
                var budget = new Full64BiomeCandidateSearch.SearchBudget(
                        0, domain, Math.min(domain, 1_000_000), 120_000, 64);
                var full = Full64BiomeCandidateSearch.search(
                        low.candidates(), archive.forVanillaHypothesis(), model,
                        budget, Thread.currentThread()::isInterrupted);
                System.out.println("FULL64_仅原版假设=" + full.completion()
                        + " | 实际高位检查=" + full.evaluations()
                        + "/" + full.domainSize());
                for (var candidate : full.candidates()) {
                    System.out.println("FULL64_MODEL_CANDIDATE=0x" + candidate.hex16()
                            + " | signed=" + candidate.signedDecimal()
                            + " | MODEL_NOT_VERIFIED_ON_UNKNOWN_SERVER");
                }
            } else if (args.length < 4) {
                System.out.println("FULL64=UNKNOWN：未提供可验证的真实生成器映射及独立高16证据");
            }
        }
        System.out.println("本研究实际运行SMT与FIT/HOLDOUT；模型一致仍不证明未知目标服真实Seed。");
    }
}
