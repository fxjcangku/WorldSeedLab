package io.github.fxjcangku.worldseedlab.client.probe;

import io.github.fxjcangku.worldseedlab.client.scope.WorldScopeResolver;

import io.github.fxjcangku.worldseedlab.core.evidence.EvidenceKind;
import io.github.fxjcangku.worldseedlab.core.evidence.EvidenceOrigin;
import io.github.fxjcangku.worldseedlab.core.evidence.EvidenceRecord;
import io.github.fxjcangku.worldseedlab.core.generation.AdvertisedDimensions;
import io.github.fxjcangku.worldseedlab.core.generation.DimensionProfile;
import io.github.fxjcangku.worldseedlab.core.generation.GenerationDiagnostics;
import io.github.fxjcangku.worldseedlab.core.generation.SamplingCoverage;
import io.github.fxjcangku.worldseedlab.core.generation.CoverageReportAppendix;
import io.github.fxjcangku.worldseedlab.core.generation.DetailObservationStats;
import io.github.fxjcangku.worldseedlab.core.generation.DetailReportAppendix;
import io.github.fxjcangku.worldseedlab.core.generation.TerrainDetailSample;
import io.github.fxjcangku.worldseedlab.core.generation.ResearchReportStore;
import io.github.fxjcangku.worldseedlab.core.generation.TerrainChunkKey;
import io.github.fxjcangku.worldseedlab.core.generation.TerrainDetailKey;
import io.github.fxjcangku.worldseedlab.core.probe.DetailMode;
import io.github.fxjcangku.worldseedlab.core.generation.TerrainChunkSample;
import io.github.fxjcangku.worldseedlab.core.generation.WorldgenReport;
import io.github.fxjcangku.worldseedlab.core.privacy.LocalSecret;
import io.github.fxjcangku.worldseedlab.core.privacy.WorldFingerprint;
import io.github.fxjcangku.worldseedlab.core.privacy.WorldEpochStore;
import io.github.fxjcangku.worldseedlab.core.probe.AsyncEvidenceWriter;
import io.github.fxjcangku.worldseedlab.core.probe.ChunkKey;
import io.github.fxjcangku.worldseedlab.core.probe.ChunkSamplingCursor;
import io.github.fxjcangku.worldseedlab.core.probe.CompletedChunkStore;
import io.github.fxjcangku.worldseedlab.core.probe.ProbeConfigFile;
import io.github.fxjcangku.worldseedlab.core.probe.ProbeGate;
import io.github.fxjcangku.worldseedlab.core.probe.ProbeSettings;
import io.github.fxjcangku.worldseedlab.core.recognition.CandidateClusterIndex;
import io.github.fxjcangku.worldseedlab.core.recognition.StructureScanEvidence;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import org.slf4j.Logger;

/**
 * 客户端群系观测协调器。
 * 只在玩家主动授权后读取已加载区块；读取在游戏线程、写入在有界后台线程执行。
 * 发生维度切换或写入异常时立即停止，绝不对服务器请求额外区块。
 */
public final class WorldProbeController {
    private static final int MAX_WAITING_CHUNKS = 96;
    private final Logger logger;
    private final String gameVersion;
    private final Path configRoot;
    private final ProbeConfigFile preferences;
    private final ProbeGate gate = new ProbeGate();
    private final ArrayDeque<ChunkKey> pending = new ArrayDeque<>();
    private final Set<ChunkKey> enqueued = new HashSet<>();
    private final Set<ChunkKey> seen = new HashSet<>();
    // P2 使用独立区块索引：P1 旧数据不会阻止地形补采。
    private final Set<TerrainChunkKey> terrainSeen = new HashSet<>();
    /** 与 P1 群系及 P2 高度图分离，升级档位仍可补采旧区块。 */
    private final Set<TerrainDetailKey> detailSeen = new HashSet<>();
    private TerrainDetailCursor detailCursor;
    private DetailObservationStats detailStats;
    private long detailCompletedAtStart;
    private final Map<String, Long> markedStructureCounts = new HashMap<>();
    private final Map<String, Long> biomes = new HashMap<>();
    private ProbeSettings settings;
    /** 结构指纹扫描是独立手动授权，重启/换维度/退出后始终关闭。 */
    private boolean structureScanEnabled;
    private LoadedStructureScanCursor structureCursor;
    private final Set<Long> structureScanned = new HashSet<>();
    private CandidateClusterIndex structureClusters = new CandidateClusterIndex();
    private final Map<String, Long> detectedSignatureKinds = new HashMap<>();
    private long structureScanCompletedAtStart;
    private long acceptedStructureScans;
    private long structureScanInterrupted;
    private long structureQueueBackpressure;
    private DimensionProfile dimensionProfile;
    private AdvertisedDimensions advertisedDimensions;
    private GenerationDiagnostics generationDiagnostics;
    private SamplingCoverage samplingCoverage;
    private long terrainCompletedAtStart;
    private ClientLevel scopeLevel;
    private String scopeName;
    private String fingerprint;
    private String dimension;
    private AsyncEvidenceWriter writer;
    private CompletedChunkStore store;
    private ChunkSamplingCursor cursor;
    private ChunkKey chunk;
    private List<EvidenceRecord> samples;
    private int ticks;
    private long observedSamples;
    private long previouslyCompleted;
    private String status = "等待手动开始";

    public WorldProbeController(Path configRoot, String gameVersion, Logger logger) {
        this.configRoot = configRoot;
        this.gameVersion = gameVersion;
        this.logger = logger;
        preferences = new ProbeConfigFile(configRoot);
        settings = preferences.load();
    }

    public boolean active() { return gate.isActive(); }
    public String status() { return status; }
    public ProbeSettings settings() { return settings; }
    public int biomeTypes() { return biomes.size(); }
    public long observedSamples() { return observedSamples; }
    public long writtenSamples() { return writer == null ? 0 : writer.writtenSamples(); }
    public long writtenChunks() { return previouslyCompleted + (writer == null ? 0 : writer.writtenChunks()); }
    public int pendingWrites() { return writer == null ? 0 : writer.pending(); }
    public int queuedChunks() { return pending.size(); }

    public boolean structureScanEnabled() { return structureScanEnabled; }
    public int structureCandidateGroups() { return structureClusters.totalGroups(); }
    public long structureScansQueued() { return acceptedStructureScans; }
    public long persistedStructureScans() {
        return structureScanCompletedAtStart
                + (writer == null ? 0 : writer.writtenStructureScans());
    }
    public long structureCandidateRecordsWritten() {
        return writer == null ? 0 : writer.writtenStructureCandidates();
    }

    /**
     * P2.6 独立手动开关：普通群系/地形采集即使开启，结构扫描仍默认关闭。
     * 用户停用后会丢弃本次未扫描完成的临时游标，绝不写出伪造证据。
     */
    public String setStructureScanning(boolean enabled) {
        if (!active()) {
            status = "请先手动启动世界采集，随后才可开启结构候选扫描";
            return status;
        }
        structureScanEnabled = enabled;
        if (!enabled) structureCursor = null;
        status = enabled
                ? "P2.6 结构候选扫描已开启：只读已加载区块，矿石不参与判定"
                : "P2.6 结构候选扫描已关闭，已接受的写盘队列继续排空";
        return status;
    }
    public Path dataRoot() { return configRoot.resolve("observations"); }

    /** 游戏当前维度的客户端可见元数据，不能视为服务端完整生成器配置。 */
    public DimensionProfile dimensionProfile() {
        return dimensionProfile;
    }

    /** 仅代表连接时服务器向客户端公布过的维度键，不代表已经探索。 */
    public AdvertisedDimensions advertisedDimensions() {
        return advertisedDimensions;
    }

    /** 会话内、已接受队列的区块采样质量统计，非全图覆盖率。 */
    public SamplingCoverage.Snapshot coverageStats() {
        return samplingCoverage == null ? null : samplingCoverage.snapshot();
    }

    public GenerationDiagnostics.Snapshot terrainStats() {
        return generationDiagnostics == null ? null : generationDiagnostics.snapshot();
    }

    public long detailChunksWritten() {
        return detailCompletedAtStart + (writer == null ? 0 : writer.writtenDetailChunks());
    }

    public long markedStructureTypes() {
        return markedStructureCounts.size();
    }

    public long observedDetailBlockStates() {
        return detailCursor == null ? 0 : detailCursor.collectedBlockStates();
    }

    public long terrainChunksWritten() {
        return terrainCompletedAtStart + (writer == null ? 0 : writer.writtenTerrainChunks());
    }

    /**
     * 仅用户打开地图时解析当前本机世界身份和维度。
     * 停止采集后仍可读取历史地图；同服地图重置纪元必须保持独立。
     */
    public Optional<MapAccess> mapAccess(Minecraft client) {
        String source = WorldScopeResolver.discriminator(client);
        if (source == null || client.level == null || client.player == null) {
            status = "请先进入可识别的世界再打开观测地图";
            return Optional.empty();
        }
        try {
            byte[] secret = LocalSecret.loadOrCreate(configRoot);
            String base = WorldFingerprint.fromLocalSecret(secret, source);
            String epoch = WorldEpochStore.load(configRoot, base);
            String localWorld = WorldEpochStore.effectiveFingerprint(secret, source, epoch);
            String dim = client.level.dimension().identifier().toString();
            Path scope = new CompletedChunkStore(dataRoot()).scopedFolder(localWorld, dim);
            var location = client.player.blockPosition();
            return Optional.of(new MapAccess(scope, dim, location.getX(), location.getZ(),
                    location.getY(), client.level.getMinY(), client.level.getMaxY()));
        } catch (IOException | RuntimeException problem) {
            status = "本地地图作用域读取失败：" + problem.getMessage();
            logger.error("Cannot open observed map scope", problem);
            return Optional.empty();
        }
    }

    /** 保留精确世界坐标仅用于本机地图窗口，绝不写入公开摘要。 */
    public record MapAccess(Path folder, String dimension, int x, int z, int y,
                            int minY, int maxYExclusive) { }

    public String exportReport() {
        if (dimensionProfile == null || generationDiagnostics == null) {
            status = "尚未进入采集会话，没有可导出的维度诊断信息";
            return status;
        }
        try {
            String report = WorldgenReport.render(
                    dimensionProfile, generationDiagnostics.snapshot(), Map.copyOf(biomes),
                    observedSamples, writtenSamples(), writtenManualMarks(),
                    pendingWrites(), active(), advertisedDimensions);
            StringBuilder extended = new StringBuilder(report)
                    .append("\n【扩展地形证据】\n")
                    .append("当前档位: ").append(settings.detailMode().chineseLabel()).append('\n')
                    .append("本次及历史已完成详情区块: ").append(detailChunksWritten()).append('\n')
                    .append("本次标记建筑类型数: ").append(markedStructureCounts.size()).append('\n');
            markedStructureCounts.entrySet().stream()
                    .sorted(Map.Entry.comparingByKey())
                    .forEach(e -> extended.append("  ").append(e.getKey())
                            .append(" : ").append(e.getValue()).append("\n"));
            extended.append("说明：方块状态可能经过玩家/服务器修改，不是原始生成器输入。\n")
                    .append("地下剖面为分层抽样，不能据此判断未采样区域无矿物或洞穴。\n");
            report = extended.toString()
                    + DetailReportAppendix.render(
                            detailStats == null ? null : detailStats.snapshot())
                    + CoverageReportAppendix.render(coverageStats())
                    + structureResearchAppendix();
            Path path = new ResearchReportStore(dataRoot()).export(dimensionProfile, report);
            status = "P2 研究日志已导出：" + path.toAbsolutePath();
            return status;
        } catch (IOException | RuntimeException error) {
            status = "研究日志导出失败：" + error.getMessage();
            logger.error("P2 report export failed", error);
            return status;
        }
    }


    /**
     * 摘要只有本次会话候选统计，不包含精确区块坐标；
     * 不对假矿、完整世界种子或任何候选结构起始区块作出推测。
     */
    private String structureResearchAppendix() {
        StringBuilder out = new StringBuilder("\n【P2.6 自动结构材料候选】\n");
        out.append("扫描模式: ").append(structureScanEnabled ? "启用" : "关闭").append('\n');
        out.append("历史及本次已完成稀疏扫描区块: ")
                .append(persistedStructureScans()).append('\n');
        out.append("本次提交写盘的扫描区块: ").append(acceptedStructureScans).append('\n');
        out.append("本次已写入候选记录: ").append(structureCandidateRecordsWritten()).append('\n');
        out.append("本次候选近邻聚合组: ").append(structureCandidateGroups()).append('\n');
        out.append("中途卸载/停止导致未完成: ").append(structureScanInterrupted).append('\n');
        out.append("结构写盘队列背压: ").append(structureQueueBackpressure).append('\n');
        detectedSignatureKinds.entrySet().stream().sorted(Map.Entry.comparingByKey())
                .forEach(e -> out.append("  疑似 ").append(e.getKey())
                        .append(" 材料证据批次: ").append(e.getValue()).append('\n'));
        out.append("注意：材料相似≠服务器自然生成；无命中≠没有结构。\n");
        out.append("假矿与矿石分布不参与此识别，任何记录都不能当作真正的结构起点。\n");
        out.append("精确证据位于结构候选 JSONL，请勿未经脱敏公开。\n");
        return out.toString();
    }

    public List<Map.Entry<String, Long>> topBiomes(int count) {
        return biomes.entrySet().stream()
                .sorted(Map.Entry.<String, Long>comparingByValue(Comparator.reverseOrder()))
                .limit(count).map(e -> Map.entry(e.getKey(), e.getValue())).toList();
    }

    public void changeSettings(ProbeSettings next) {
        if (active()) { status = "请先停止采集再修改参数"; return; }
        try {
            preferences.save(next);
            settings = next;
            status = "采样设置已保存；采集仍为关闭状态";
        } catch (IOException error) {
            status = "设置保存失败：" + error.getMessage();
            logger.error("Failed to save P1 preferences", error);
        }
    }

    /** 可记录的建筑类别。全部仅为“人工观察候选”，不是已识别的生成起点。 */
    private static final Map<String, String> STRUCTURE_CATALOG = Map.ofEntries(
            Map.entry("shipwreck", "minecraft:shipwreck"),
            Map.entry("desert_pyramid", "minecraft:desert_pyramid"),
            Map.entry("jungle_temple", "minecraft:jungle_temple"),
            Map.entry("igloo", "minecraft:igloo"),
            Map.entry("swamp_hut", "minecraft:swamp_hut"),
            Map.entry("ocean_monument", "minecraft:monument"),
            Map.entry("pillager_outpost", "minecraft:pillager_outpost"),
            Map.entry("ruined_portal", "minecraft:ruined_portal"),
            Map.entry("buried_treasure", "minecraft:buried_treasure"),
            Map.entry("village", "worldseedlab:village_unclassified"),
            Map.entry("stronghold", "minecraft:stronghold"),
            Map.entry("ancient_city", "minecraft:ancient_city"),
            Map.entry("mineshaft", "minecraft:mineshaft"),
            Map.entry("nether_fortress", "minecraft:fortress"),
            Map.entry("bastion", "minecraft:bastion_remnant"),
            Map.entry("end_city", "minecraft:end_city"),
            Map.entry("trial_chambers", "minecraft:trial_chambers")
    );

    public static Map<String, String> structureCatalog() {
        return STRUCTURE_CATALOG;
    }

    public void markStructureByAlias(Minecraft client, String alias) {
        String id = STRUCTURE_CATALOG.get(alias);
        if (id == null) {
            status = "未知建筑类型：" + alias;
            return;
        }
        markStructure(client, id);
    }

    public long writtenManualMarks() {
        return writer == null ? 0 : writer.writtenManualMarks();
    }

    /**
     * 用户显式标记的是“发现建筑时的玩家脚下坐标”，并非结构生成起始区块。
     * 记录只属于当前世界、当前维度；禁止把此数据冒充已证明的随机结构位置。
     */
    public void markStructure(Minecraft client, String structureId) {
        if (!STRUCTURE_CATALOG.containsValue(structureId)) {
            status = "不支持的结构类型，拒绝标记";
            return;
        }
        if (!active() || client.player == null || client.level != scopeLevel
                || !scopeName.equals(WorldScopeResolver.discriminator(client))
                || !dimension.equals(client.level.dimension().identifier().toString())) {
            status = "请先在当前世界开启采集，再标记结构";
            return;
        }
        var position = client.player.blockPosition();
        String localBiome = client.level.getBiome(position).unwrapKey()
                .map(key -> key.identifier().toString()).orElse("unknown");
        EvidenceRecord evidence = new EvidenceRecord(UUID.randomUUID(),
                fingerprint, dimension, gameVersion,
                EvidenceKind.STRUCTURE_OBSERVATION, structureId,
                position.getX(), position.getY(), position.getZ(),
                EvidenceOrigin.MANUAL_MARK, Instant.now(),
                "manual-player-position;observed-biome=" + localBiome
                        + ";natural-origin-unknown;NOT-structure-generation-origin");
        if (!gate.allows(evidence) || !writer.offerManual(evidence)) {
            status = "结构标记未加入写入队列，请检查磁盘或重试";
            return;
        }
        markedStructureCounts.merge(structureId, 1L, Long::sum);
        status = "已提交手动结构标记（并非真实生成起点）";
    }

    /**
     * 本机世界纪元切换：只在采集停止且后台 I/O 已排空时允许，
     * 为服务器同地址换地图提供显式隔离，不假称自动检测世界重置。
     */
    public String newWorldEpoch(Minecraft client) {
        if (active() || (writer != null && writer.pending() != 0)) {
            status = "请先停止采集并等待后台写入清空，再切换世界纪元";
            return status;
        }
        String source = WorldScopeResolver.discriminator(client);
        if (client.level == null || client.player == null || source == null) {
            status = "只有已进入的世界可以创建新的数据纪元";
            return status;
        }
        try {
            byte[] secret = LocalSecret.loadOrCreate(configRoot);
            String base = WorldFingerprint.fromLocalSecret(secret, source);
            String next = WorldEpochStore.newEpoch(configRoot, base);
            dimensionProfile = null;
            advertisedDimensions = null;
            generationDiagnostics = null;
            samplingCoverage = null;
            detailStats = null;
            status = "已创建独立世界纪元 " + next.substring(0, 8)
                    + "；旧证据保留。请重新启动采集";
        } catch (IOException | RuntimeException error) {
            status = "创建世界纪元失败：" + error.getMessage();
            logger.error("World epoch rotation was rejected", error);
        }
        return status;
    }

    /** 本机世界纪元标签不暴露原始服务器地址。 */
    public String worldEpochStatus(Minecraft client) {
        String source = WorldScopeResolver.discriminator(client);
        if (source == null) return "尚未进入可识别的世界";
        try {
            byte[] secret = LocalSecret.loadOrCreate(configRoot);
            String base = WorldFingerprint.fromLocalSecret(secret, source);
            String epoch = WorldEpochStore.load(configRoot, base);
            return "当前世界数据纪元: " + (WorldEpochStore.LEGACY.equals(epoch)
                    ? "legacy（保留历史目录）" : epoch.substring(0, 8));
        } catch (IOException | RuntimeException failure) {
            return "无法读取世界纪元，已拒绝推断: " + failure.getMessage();
        }
    }

    public void start(Minecraft client) {
        if (active()) return;
        if (writer != null && writer.pending() > 0) {
            status = "上一批磁盘写入尚未完成，请稍候";
            return;
        }
        if (client.level == null || client.player == null) {
            status = "请先进入世界再启动采集"; return;
        }
        String source = WorldScopeResolver.discriminator(client);
        if (source == null) {
            status = "世界身份无法确认，拒绝启动"; return;
        }
        try {
            byte[] secret = LocalSecret.loadOrCreate(configRoot);
            String baseFingerprint = WorldFingerprint.fromLocalSecret(secret, source);
            String epoch = WorldEpochStore.load(configRoot, baseFingerprint);
            String f = WorldEpochStore.effectiveFingerprint(secret, source, epoch);
            String d = client.level.dimension().identifier().toString();
            CompletedChunkStore nextStore = new CompletedChunkStore(dataRoot());
            Set<ChunkKey> resume = nextStore.loadCompleted(f, d);
            Set<TerrainChunkKey> terrainResume = nextStore.loadTerrainCompleted(f, d);
            Set<TerrainDetailKey> detailsResume = nextStore.loadDetailCompleted(f, d);
            Set<Long> structureResume = nextStore.loadStructureScanned(f, d);
            DimensionProfile nextProfile =
                    TerrainObserver.dimensionProfile(client.level, f, gameVersion);
            AdvertisedDimensions registry = inspectAvailableDimensions(client, f, d);
            pending.clear();
            enqueued.clear();
            seen.clear();
            seen.addAll(resume);
            terrainSeen.clear();
            terrainSeen.addAll(terrainResume);
            detailSeen.clear();
            detailSeen.addAll(detailsResume);
            detailCursor = null;
            detailStats = new DetailObservationStats(f, d);
            structureScanEnabled = false;
            structureCursor = null;
            structureScanned.clear();
            structureScanned.addAll(structureResume);
            structureClusters = new CandidateClusterIndex();
            detectedSignatureKinds.clear();
            structureScanCompletedAtStart = structureResume.size();
            acceptedStructureScans = 0;
            structureScanInterrupted = 0;
            structureQueueBackpressure = 0;
            markedStructureCounts.clear();
            detailCompletedAtStart = (int) detailsResume.stream()
                    .filter(k -> k.mode() == settings.detailMode()).count();
            dimensionProfile = nextProfile;
            advertisedDimensions = registry;
            generationDiagnostics = new GenerationDiagnostics(nextProfile);
            samplingCoverage = new SamplingCoverage(f, d);
            terrainCompletedAtStart = terrainResume.size();
            biomes.clear();
            previouslyCompleted = resume.size();
            observedSamples = 0;
            ticks = 0;
            cursor = null;
            chunk = null;
            samples = null;
            scopeLevel = client.level;
            scopeName = source;
            fingerprint = f;
            dimension = d;
            store = nextStore;
            writer = new AsyncEvidenceWriter(store, f, d);
            if (!writer.offerDimension(nextProfile)
                    || !writer.offerAdvertisedDimensions(registry)) {
                throw new IOException("维度档案或维度清单无法进入写盘队列，拒绝采集");
            }
            gate.arm(f, d);
            status = "正在采集客户端已加载的群系数据";
        } catch (IOException | RuntimeException failure) {
            logger.error("Probe activation rejected", failure);
            stop("启动失败：" + failure.getMessage());
        }
    }

    public void stop(String reason) {
        gate.disarm();
        structureScanEnabled = false;
        if (structureCursor != null) structureScanInterrupted++;
        structureCursor = null;
        pending.clear();
        enqueued.clear();
        cursor = null;
        chunk = null;
        samples = null;
        detailCursor = null;
        scopeLevel = null;
        scopeName = null;
        if (writer != null) writer.close(); // drains accepted chunks asynchronously
        status = reason;
    }

    /**
     * 客户端正常退出时，最多等待 2.5 秒完成已接受的后台 I/O，
     * 随后自动生成中文汇总文件。异常强制结束进程不保证此回调运行。
     */
    public void onClientStopping() {
        if (active()) {
            stop("客户端正在退出，终止采样");
        }
        if (writer != null && !writer.closeAndAwait(2500)) {
            logger.warn("P2 evidence queue did not finish within 2500ms at shutdown");
        }
        if (dimensionProfile != null) {
            exportReport();
        }
    }

    public void tick(Minecraft client) {
        if (!active()) return;
        try {
            if (client.player == null || client.level == null || client.level != scopeLevel
                    || !scopeName.equals(WorldScopeResolver.discriminator(client))
                    || !dimension.equals(client.level.dimension().identifier().toString())) {
                stop("世界或维度已变化，自动停止采集");
                return;
            }
            if (writer.failure() != null) {
                String error = writer.failure();
                stop("磁盘写入失败，安全停机");
                logger.error("P1 disk writer failed: {}", error);
                return;
            }
            if (++ticks % 12 == 1) {
                // 地形每 12 Tick 最多取证两个已加载区块，避免鞘翅跑图时卡帧。
                observeNearbyTerrain(client);
                findChunks(client);
            }
            // 附加方块扫描每 Tick 最多 24 次 BlockState 读取，独立于群系预算。
            advanceDetail(client, 24);
            if (structureScanEnabled) advanceStructureScan(client);
            int budget = settings.samplesPerTick();
            while (budget > 0) {
                if (cursor == null && !beginChunk(client.level)) break;
                if (cursor.hasNext() && !isLoaded(client.level, chunk)) {
                    samplingCoverage.abandonedUnloadedChunk();
                    enqueued.remove(chunk); cursor = null; chunk = null; samples = null;
                    continue;
                }
                while (cursor.hasNext() && budget > 0) {
                    var pos = cursor.next();
                    String key = client.level.getBiome(new BlockPos(pos.x(), pos.y(), pos.z()))
                            .unwrapKey().map(value -> value.identifier().toString()).orElse(null);
                    if (key == null) { stop("未能识别群系注册键，已停止"); return; }
                    samples.add(new EvidenceRecord(UUID.randomUUID(), fingerprint, dimension,
                            gameVersion, EvidenceKind.BIOME_OBSERVATION, key,
                            pos.x(), pos.y(), pos.z(), EvidenceOrigin.CLIENT_OBSERVATION,
                            Instant.now(), "quart-grid;vertical-stride=" + settings.verticalStride()
                            + ";loaded-only;generation-history-unknown"));
                    budget--;
                }
                if (cursor.hasNext()) break;
                // 磁盘写入队列满时保留已完成的采样数据，等待后续 Tick 重试。
                if (!writer.offer(chunk, samples)) {
                    samplingCoverage.diskBackpressure();
                    status = "写入队列繁忙，暂时等待";
                    break;
                }
                // 仅在完整样本被后台写入队列接受后，才计入本次有效观测足迹。
                samplingCoverage.accepted(chunk, samples);
                for (EvidenceRecord record : samples) {
                    biomes.merge(record.observedIdentifier(), 1L, Long::sum);
                }
                observedSamples += samples.size();
                seen.add(chunk); enqueued.remove(chunk);
                cursor = null; chunk = null; samples = null;
                status = "正在记录群系观测证据";
            }
        } catch (RuntimeException failure) {
            logger.error("P1 sampler stopped safely", failure);
            stop("扫描异常，安全停止：" + failure.getClass().getSimpleName());
        }
    }

    /**
     * P2 额外读取 WORLD_SURFACE：仅从已加载区块取 16 个稀疏点。
     * 严格上限两个区块/12 tick；队列满时本次跳过，将来再重试。
     * 并不生成区块、不请求服务器更多地图数据。
     */
    private void observeNearbyTerrain(Minecraft client) {
        if (generationDiagnostics == null || dimensionProfile == null || writer.pending() >= 12) {
            return;
        }
        int centerX = client.player.blockPosition().getX() >> 4;
        int centerZ = client.player.blockPosition().getZ() >> 4;
        int accepted = 0;
        int radius = settings.radiusChunks();
        for (int ring = 0; ring <= radius && accepted < 2; ring++) {
            for (int dx = -ring; dx <= ring && accepted < 2; dx++) {
                for (int dz = -ring; dz <= ring && accepted < 2; dz++) {
                    if (Math.max(Math.abs(dx), Math.abs(dz)) != ring) continue;
                    TerrainChunkKey key = new TerrainChunkKey(centerX + dx, centerZ + dz);
                    if (terrainSeen.contains(key)) continue;
                    TerrainChunkSample terrain = TerrainObserver.observe(
                            client.level, dimensionProfile, key);
                    if (terrain == null) continue;
                    if (!writer.offerTerrain(terrain)) return;
                    terrainSeen.add(key);
                    generationDiagnostics.observe(terrain);
                    accepted++;
                }
            }
        }
    }

    /**
     * 扩展证据只针对已加载区块。每 Tick 固定预算，防止鞘翅高速跑图时扫完整块卡帧。
     *
     * <p>完成后才提交异步队列；磁盘队列满则持有已完成游标，等待下次 Tick，
     * 不会静默遗失或伪报为已写盘。
     */
    private void advanceDetail(Minecraft client, int budget) {
        if (settings.detailMode() == DetailMode.OFF || writer.pending() >= 12) return;
        if (detailCursor == null) {
            if (ticks % 8 != 1) return;
            int centerX = client.player.blockPosition().getX() >> 4;
            int centerZ = client.player.blockPosition().getZ() >> 4;
            int radius = settings.radiusChunks();
            outer:
            for (int ring = 0; ring <= radius; ring++) {
                for (int dx = -ring; dx <= ring; dx++) {
                    for (int dz = -ring; dz <= ring; dz++) {
                        if (Math.max(Math.abs(dx), Math.abs(dz)) != ring) continue;
                        TerrainDetailKey key = new TerrainDetailKey(
                                centerX + dx, centerZ + dz, settings.detailMode());
                        if (detailSeen.contains(key)) continue;
                        if (client.level.getChunkSource().getChunk(key.chunkX(),
                                key.chunkZ(), ChunkStatus.FULL, false) == null) continue;
                        detailCursor = new TerrainDetailCursor(client.level, dimensionProfile, key);
                        break outer;
                    }
                }
            }
            if (detailCursor == null) return;
        }
        if (!detailCursor.isStillLoaded(client.level)) {
            samplingCoverage.abandonedDetailChunk();
            detailCursor = null; // 未完成证据全部撤销，可在下次访问区块时重试。
            return;
        }
        if (!detailCursor.isComplete()) detailCursor.advance(client.level, budget);
        if (detailCursor.isComplete()) {
            TerrainDetailSample detailed = detailCursor.result();
            if (!writer.offerDetail(detailed)) {
                samplingCoverage.diskBackpressure();
                status = "扩展地形写盘队列繁忙，暂停本次取证";
                return;
            }
            detailStats.observe(detailed);
            detailSeen.add(detailCursor.key());
            detailCursor = null;
        }
    }


    /**
     * 结构自动候选独立于 P1 群系扫描。每 Tick 最多读取 64 个已缓存 BlockState，
     * 并且最多保留一个区块的工作游标，避免鞘翅快速飞行累积无限待办。
     */
    private void advanceStructureScan(Minecraft client) {
        if (writer == null || writer.pending() >= 12) return;
        if (structureCursor == null) {
            if (ticks % 8 != 1) return;
            int cx = Math.floorDiv(client.player.blockPosition().getX(), 16);
            int cz = Math.floorDiv(client.player.blockPosition().getZ(), 16);
            int radius = Math.min(settings.radiusChunks(), 2);
            outer:
            for (int ring = 0; ring <= radius; ring++) {
                for (int dx = -ring; dx <= ring; dx++) {
                    for (int dz = -ring; dz <= ring; dz++) {
                        if (Math.max(Math.abs(dx), Math.abs(dz)) != ring) continue;
                        int x = cx + dx, z = cz + dz;
                        if (structureScanned.contains(packStructureChunk(x, z))) continue;
                        if (client.level.getChunkSource().getChunk(
                                x, z, ChunkStatus.FULL, false) == null) continue;
                        structureCursor = new LoadedStructureScanCursor(
                                client.level, x, z, fingerprint, dimension, gameVersion);
                        break outer;
                    }
                }
            }
            if (structureCursor == null) return;
        }
        if (!structureCursor.isLoaded(client.level)) {
            structureScanInterrupted++;
            structureCursor = null;
            return;
        }
        if (!structureCursor.finished()) {
            structureCursor.advance(client.level, 64);
        }
        if (!structureCursor.finished()) return;
        StructureScanEvidence evidence = structureCursor.finishEvidence();
        if (!writer.offerStructureScan(evidence)) {
            structureQueueBackpressure++;
            return; // 保留完整游标，下一 Tick 重试写盘
        }
        structureScanned.add(packStructureChunk(evidence.chunkX(), evidence.chunkZ()));
        acceptedStructureScans++;
        for (var candidate : evidence.candidates()) {
            structureClusters.add(candidate.structureId(),
                    evidence.chunkX(), evidence.chunkZ());
            detectedSignatureKinds.merge(candidate.structureId(), 1L, Long::sum);
        }
        if (!evidence.candidates().isEmpty()) {
            status = "发现 " + evidence.candidates().size()
                    + " 条疑似结构材料线索（未经自然生成验证）";
        }
        structureCursor = null;
    }

    private static long packStructureChunk(int x, int z) {
        return ((long) x << 32) | (z & 0xffffffffL);
    }

    /**
     * 仅读取已接收的维度注册键；连接对象缺失时记录 CURRENT_ONLY，
     * 不可把此类 ID 清单误当成已访问维度或服务端维度生成器配置。
     */
    private AdvertisedDimensions inspectAvailableDimensions(
            Minecraft client, String localWorld, String currentDimension
    ) {
        try {
            var connection = client.getConnection();
            if (connection != null) {
                List<String> ids = connection.levels().stream()
                        .map(levelKey -> levelKey.identifier().toString()).toList();
                if (!ids.isEmpty()) {
                    List<String> combined = new ArrayList<>(ids);
                    if (!combined.contains(currentDimension)) {
                        combined.add(currentDimension);
                    }
                    return new AdvertisedDimensions(localWorld, gameVersion,
                            currentDimension, AdvertisedDimensions.Source.ADVERTISED,
                            combined, Instant.now());
                }
            }
        } catch (RuntimeException unavailable) {
            logger.warn("Unable to inspect client-advertised dimensions; using current dimension only",
                    unavailable);
        }
        return new AdvertisedDimensions(localWorld, gameVersion, currentDimension,
                AdvertisedDimensions.Source.CURRENT_ONLY,
                List.of(currentDimension), Instant.now());
    }

    private void findChunks(Minecraft client) {
        int centerX = client.player.blockPosition().getX() >> 4;
        int centerZ = client.player.blockPosition().getZ() >> 4;
        for (int ring = 0; ring <= settings.radiusChunks() && pending.size() < MAX_WAITING_CHUNKS; ring++) {
            for (int dx = -ring; dx <= ring && pending.size() < MAX_WAITING_CHUNKS; dx++) {
                for (int dz = -ring; dz <= ring && pending.size() < MAX_WAITING_CHUNKS; dz++) {
                    if (Math.max(Math.abs(dx), Math.abs(dz)) != ring) continue;
                    ChunkKey next = new ChunkKey(centerX + dx, centerZ + dz,
                            settings.verticalStride());
                    if (!seen.contains(next) && !enqueued.contains(next)
                            && isLoaded(client.level, next)) {
                        pending.addLast(next);
                        enqueued.add(next);
                    }
                }
            }
        }
    }

    private boolean beginChunk(ClientLevel level) {
        while (!pending.isEmpty()) {
            ChunkKey next = pending.removeFirst();
            if (!isLoaded(level, next)) { enqueued.remove(next); continue; }
            cursor = new ChunkSamplingCursor(next, level.getMinY(), level.getMaxY());
            chunk = next;
            samples = new ArrayList<>(512);
            return true;
        }
        return false;
    }

    private static boolean isLoaded(ClientLevel level, ChunkKey key) {
        return level.getChunkSource().getChunk(key.chunkX(), key.chunkZ(),
                ChunkStatus.FULL, false) != null;
    }
}
