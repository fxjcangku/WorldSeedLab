package io.github.fxjcangku.worldseedlab.client.probe;

import io.github.fxjcangku.worldseedlab.client.scope.WorldScopeResolver;
import io.github.fxjcangku.worldseedlab.core.geometry.AutoDesertFootprintPlan;
import io.github.fxjcangku.worldseedlab.core.probe.BoundedChunkHistory;
import io.github.fxjcangku.worldseedlab.core.geometry.ClientVisibleDesertPyramidRaster;
import io.github.fxjcangku.worldseedlab.core.geometry.StructureFootprintInverter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.levelgen.Heightmap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * P3.6 一次开启后自动发现附近蓝色陶瓦、自动构造粗 ROI 并复用 P3.5 的完整外缘校验。
 *
 * <p>并非自动 Seed 破解。开启时还接入原有 P1/P2 的真实群系和地形取证、
 * P2.6 的材料候选；所有模型参数、生成器身份、建筑自然来源仍然 UNKNOWN。
 * 当前只处理普通客户端已收到的 FULL Chunk，不请求新区块，不读取服务端 NBT。
 *
 * <p>单 Tick 最多 256 个额外 BlockState。先在最多 2 Chunk 近邻内逐格寻找
 * 实际蓝色陶瓦，再围绕其坐标扫描完整29×29×32栅格；任何区块缺失、卸载、
 * 越界、文件写入失败，均不得输出已证实的 StructureStart。
 */
public final class AutoDesertFootprintController {
    private static final Logger LOGGER = LoggerFactory.getLogger("WorldSeedLab");
    private static final int BLOCKS_PER_TICK = 256;
    private static final int RADIUS_CHUNKS = 2;
    // 4096是去重历史的内存上限，不是自动取证会话的区块终点。
    private static final int RECENT_SCANNED_CACHE = 4096;
    private static final int MAX_OBSERVED_SITES = 64;
    private static final int MAX_DISTINCT_BLUE_HINTS = 256;
    private static final int MAX_DEFERRED_HINTS = 24;
    private static final int DEFERRED_TICKS = 1200;
    private static final int QUEUED_FILES = 8;

    private final WorldProbeController probe;
    private final String version;
    private final AtomicInteger filesPersisted = new AtomicInteger();
    private final AtomicReference<String> diskError = new AtomicReference<>();
    private final BoundedChunkHistory scannedChunks =
            new BoundedChunkHistory(RECENT_SCANNED_CACHE);
    private final Set<String> observedHints = new HashSet<>();
    private final Set<Long> queuedFootprints = new HashSet<>();
    private final ArrayDeque<Deferred> deferred = new ArrayDeque<>();

    private boolean running;
    private boolean ownsProbe, ownsSignatures;
    private ClientLevel boundLevel;
    private String source, worldFingerprint, dimension;
    private Path folder;
    private ChunkCursor search;
    private Capture capture;
    private ThreadPoolExecutor executor;
    private long ticks, attempts, verified, incomplete, completedChunks, interrupted, duplicates;
    private long droppedDeferred, invalidAnchorElevation;
    private String lastStatus = "P3.6 自动结构与地形联合取证未启用（默认关闭）";

    private record Deferred(AutoDesertFootprintPlan.BlueTerracottaAnchor anchor,
                            long firstTick) {}

    private static final class ChunkCursor {
        final LevelChunk chunk;
        final int x, z, buildMinY, buildMaxYExclusive;
        // 每列仅一次高度图查询；每Tick仍至多256次额外BlockState。
        final int[] bottomByColumn = new int[16 * 16];
        final boolean[] columnKnown = new boolean[16 * 16];
        int next;

        ChunkCursor(LevelChunk chunk, int x, int z, int buildMinY, int buildMaxYExclusive) {
            this.chunk = chunk;
            this.x = x;
            this.z = z;
            this.buildMinY = buildMinY;
            this.buildMaxYExclusive = buildMaxYExclusive;
        }

        int total() { return 16 * 16 * AutoDesertFootprintPlan.BLUE_SEARCH_HEIGHT; }

        int bottomAt(int localX, int localZ) {
            int columnIndex = localX * 16 + localZ;
            if (!columnKnown[columnIndex]) {
                int firstFreeY = chunk.getHeight(Heightmap.Types.WORLD_SURFACE,
                        localX, localZ);
                bottomByColumn[columnIndex] = AutoDesertFootprintPlan.aroundObservedSurface(
                        firstFreeY, buildMinY, buildMaxYExclusive).minY();
                columnKnown[columnIndex] = true;
            }
            return bottomByColumn[columnIndex];
        }
    }

    private static final class Capture {
        final AutoDesertFootprintPlan.BlueTerracottaAnchor anchor;
        final AutoDesertFootprintPlan.Survey survey;
        final ClientVisibleDesertPyramidRaster.Scope scope;
        final int firstChunkX, firstChunkZ;
        final LevelChunk[][] fullChunks;
        final byte[] voxels;
        int next;
        long footprintKey;
        ClientVisibleDesertPyramidRaster completed;

        Capture(AutoDesertFootprintPlan.BlueTerracottaAnchor anchor,
                AutoDesertFootprintPlan.Survey survey,
                ClientVisibleDesertPyramidRaster.Scope scope,
                int firstChunkX, int firstChunkZ, LevelChunk[][] fullChunks) {
            this.anchor = anchor;
            this.survey = survey;
            this.scope = scope;
            this.firstChunkX = firstChunkX;
            this.firstChunkZ = firstChunkZ;
            this.fullChunks = fullChunks;
            voxels = new byte[31 * 31 * 32];
            Arrays.fill(voxels, (byte) -1);
        }
    }

    public AutoDesertFootprintController(WorldProbeController probe, String version) {
        this.probe = Objects.requireNonNull(probe);
        this.version = Objects.requireNonNull(version);
    }

    public boolean running() { return running; }

    public String status() {
        String progress = capture != null
                ? " | 当前完整轮廓 " + capture.next + "/" + capture.voxels.length + " 方块"
                : search != null
                    ? " | 材料探测 " + search.next + "/" + search.total()
                    : "";
        int pendingFiles = executor == null ? 0
                : executor.getQueue().size() + executor.getActiveCount();
        return lastStatus + " | 自动取证 " + (running ? "开启" : "关闭")
                + " | 材料线索 " + attempts + " | 完整可见轮廓 " + verified
                + " | 已落盘 " + filesPersisted.get()
                + " | 文件待写 " + pendingFiles
                + " | 扫描完成批次 " + completedChunks
                + " | 近期缓存 " + scannedChunks.retained() + "/" + RECENT_SCANNED_CACHE
                + " | 历史淘汰 " + scannedChunks.evictions()
                + " | 区块卸载中断 " + interrupted
                + " | 轮廓不合格/歧义 " + incomplete
                + " | 等待FULL超时/丢弃 " + droppedDeferred
                + " | 高度非法 " + invalidAnchorElevation
                + " | 同座去重 " + duplicates
                + " | 等待FULL线索 " + deferred.size()
                + progress + " | 材料高度=实际地表"
                + " | 生成器/Salt/自然来源=UNKNOWN";
    }

    /** 一次手动开启完整联合证据。P1/P2 原有地形和群系仍独立保存，绝不自动启动Z3。 */
    public String start(Minecraft client) {
        if (running) return status();
        // 上一轮自动写盘已接受的文件必须真正排空才能建立新一轮作用域。
        // 禁止重新启动后清空上一轮错误和计数、造成证据写入状态误报。
        if (executor != null && !executor.isTerminated()) {
            return lastStatus = "上一轮客户端自动证据仍在落盘，请先确认写入排空";
        }
        if (diskError.get() != null) {
            return lastStatus = "上一轮自动证据持久化失败：" + diskError.get()
                    + "；需要重新启动客户端后才可再次采集";
        }
        if (client.player == null || client.level == null
                || WorldScopeResolver.discriminator(client) == null) {
            return lastStatus = "未进入可识别的世界，拒绝启动自动取证";
        }
        boolean startedProbe = !probe.active();
        if (startedProbe) probe.start(client);
        if (!probe.active()) return lastStatus = "地形采集未能启动：" + probe.status();
        boolean startedSignatures = !probe.structureScanEnabled();
        if (startedSignatures) probe.setStructureScanning(true);
        if (!probe.structureScanEnabled()) {
            if (startedProbe) probe.stop("P3.6 自动取证启动失败，停止联动取证");
            return lastStatus = "P2.6 材料指纹索引未通过自检：" + probe.status();
        }
        var access = probe.mapAccess(client);
        if (access.isEmpty()) {
            if (startedSignatures && !startedProbe) probe.setStructureScanning(false);
            if (startedProbe) probe.stop("P3.6 世界身份拒绝");
            return lastStatus = "HMAC 世界与维度隔离不可验证，禁止自动取证";
        }
        var scope = access.get();
        ownsProbe = startedProbe;
        ownsSignatures = startedSignatures && !startedProbe;
        boundLevel = client.level;
        source = WorldScopeResolver.discriminator(client);
        worldFingerprint = scope.worldFingerprint();
        dimension = scope.dimension();
        folder = scope.folder();
        ticks = attempts = verified = incomplete = completedChunks = interrupted = duplicates = 0;
        droppedDeferred = invalidAnchorElevation = 0;
        filesPersisted.set(0);
        diskError.set(null);
        scannedChunks.clear();
        observedHints.clear();
        queuedFootprints.clear();
        deferred.clear();
        search = null;
        capture = null;
        executor = new ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(QUEUED_FILES), work -> {
                    Thread thread = new Thread(work, "WorldSeedLab-P36-LocalArchive");
                    thread.setDaemon(true);
                    return thread;
                }, new ThreadPoolExecutor.AbortPolicy());
        running = true;
        lastStatus = "P3.6 已明确授权：自动取证已加载建筑，同时保留真实群系和地形；不会求解或上传 Seed";
        LOGGER.info("P3.6 started: surface-heightmap search, bounded client-loaded evidence; version={}", version);
        return status();
    }

    public String stop(String reason) {
        if (running) {
            LOGGER.info("P3.6 stopped: {} | completeScanPasses={} recentCache={} evicted={} unloads={} blueHints={} acceptedSites={} savedSites={} pendingFiles={} rejected={} deferredLost={} duplicates={}",
                    reason, completedChunks, scannedChunks.retained(), scannedChunks.evictions(),
                    interrupted, attempts, verified, filesPersisted.get(),
                    executor == null ? 0 : executor.getActiveCount() + executor.getQueue().size(),
                    incomplete, droppedDeferred, duplicates);
            running = false;
            search = null;
            capture = null;
            deferred.clear();
            scannedChunks.clear();
            observedHints.clear();
            queuedFootprints.clear();
            if (ownsProbe && probe.active()) {
                probe.stop("P3.6 自动取证停止：已关闭本次联动的地形/群系扫描");
            } else if (ownsSignatures && probe.structureScanEnabled()) {
                probe.setStructureScanning(false);
            }
        }
        ownsProbe = false;
        ownsSignatures = false;
        boundLevel = null;
        source = null;
        worldFingerprint = null;
        dimension = null;
        folder = null;
        if (executor != null) {
            executor.shutdown(); // 已接受证据继续排空；保留引用供退出时限时等待。
        }
        lastStatus = reason;
        return status();
    }

    /** 客户端退出时只等候有限时间排空已接受的自动取证文件。 */
    public void onClientStopping() {
        ThreadPoolExecutor writer = executor;
        stop("客户端退出，停止自动结构取证");
        if (writer != null) {
            try {
                if (!writer.awaitTermination(2500, TimeUnit.MILLISECONDS)) {
                    lastStatus = "客户端退出：仍有取证文件正在落盘，不能声称全部保存";
                }
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                lastStatus = "关闭中断：自动取证文件写入状态未知";
            }
        }
    }

    /** 世界生命周期与文件错误优先校验；同一Tick绝不同时扫稀疏+稠密两套方块。 */
    public void tick(Minecraft client) {
        if (!running) return;
        if (!sameWorld(client) || !probe.active() || !probe.structureScanEnabled()) {
            stop("断线、切服、换维度或上游采集停止：已撤销P3.6自动授权");
            return;
        }
        if (diskError.get() != null) {
            stop("自动取证写盘失败：" + diskError.get() + "；后续取证已停止");
            return;
        }
        ticks++;
        if (ticks % 1200 == 0) {
            LOGGER.info("P3.6 progress: completeScanPasses={} recentCache={} evicted={} unloads={} blueHints={} acceptedSites={} savedSites={} rejected={} deferredLost={}",
                    completedChunks, scannedChunks.retained(), scannedChunks.evictions(),
                    interrupted, attempts, verified, filesPersisted.get(),
                    incomplete, droppedDeferred);
        }
        if (ticks % 100 == 0) {
            var access = probe.mapAccess(client);
            if (access.isEmpty() || !matches(access.get())) {
                stop("本机世界纪元/作用域改变，拒绝将不同世界证据混合");
                return;
            }
        }
        try {
            if (capture != null) {
                advanceCapture(client.level);
            } else {
                if (ticks % 20 == 1) retryDeferred(client.level);
                if (capture == null) advanceBlueSearch(client);
            }
            if (verified >= MAX_OBSERVED_SITES) {
                stop("P3.6 本轮已接受64份完整轮廓，达到单会话证据安全上限；请等待写盘排空后人工确认是否继续");
            }
        } catch (RuntimeException failure) {
            LOGGER.error("P3.6 automatic scan failed; partial evidence refused", failure);
            stop("P3.6 遇到异常" + failure.getClass().getSimpleName()
                    + "，未完成的建筑取证已失败关闭");
        }
    }

    private void advanceBlueSearch(Minecraft client) {
        if (search == null) {
            if (ticks % 5 != 1) return;
            int centerX = Math.floorDiv(client.player.blockPosition().getX(), 16);
            int centerZ = Math.floorDiv(client.player.blockPosition().getZ(), 16);
            int buildMinY = client.level.getMinY();
            int buildMaxYExclusive = client.level.getMaxY();
            if ((long) buildMaxYExclusive - buildMinY
                    < AutoDesertFootprintPlan.BLUE_SEARCH_HEIGHT) {
                stop("P3.6 当前维度高度不足32格，拒绝不完整材料扫描");
                return;
            }
            outer:
            for (int ring = 0; ring <= RADIUS_CHUNKS; ring++) {
                for (int dx = -ring; dx <= ring; dx++) {
                    for (int dz = -ring; dz <= ring; dz++) {
                        if (Math.max(Math.abs(dx), Math.abs(dz)) != ring) continue;
                        int cx = centerX + dx, cz = centerZ + dz;
                        if (scannedChunks.contains(pack(cx, cz))) continue;
                        LevelChunk loaded = client.level.getChunkSource().getChunk(
                                cx, cz, ChunkStatus.FULL, false);
                        if (loaded == null) continue;
                        search = new ChunkCursor(loaded, cx, cz,
                                buildMinY, buildMaxYExclusive);
                        break outer;
                    }
                }
            }
            if (search == null) return;
        }
        ChunkCursor cursor = search;
        if (client.level.getChunkSource().getChunk(cursor.x, cursor.z,
                ChunkStatus.FULL, false) != cursor.chunk) {
            search = null;
            interrupted++;
            return;
        }
        for (int i = 0; i < BLOCKS_PER_TICK && cursor.next < cursor.total(); i++) {
            int n = cursor.next++;
            int offsetY = n % AutoDesertFootprintPlan.BLUE_SEARCH_HEIGHT;
            n /= AutoDesertFootprintPlan.BLUE_SEARCH_HEIGHT;
            int localZ = n % 16;
            int localX = n / 16;
            int y = cursor.bottomAt(localX, localZ) + offsetY;
            int z = cursor.z * 16 + localZ;
            int x = cursor.x * 16 + localX;
            // 26.1.2/26.2 的 Blocks 静态字段存在差异；复用已验证注册表键取证。
            String actualId = BuiltInRegistries.BLOCK.getKey(
                    cursor.chunk.getBlockState(new BlockPos(x, y, z)).getBlock()).toString();
            if (!"minecraft:blue_terracotta".equals(actualId)) continue;
            var blue = new AutoDesertFootprintPlan.BlueTerracottaAnchor(x, y, z);
            String key = hintKey(blue);
            if (observedHints.contains(key)) continue;
            // 创造/自定义世界可能存在大量蓝陶瓦，逐格字符串集合必须硬封顶。
            // 不允许仅靠4096个已扫区块误以为候选线索内存有界。
            if (observedHints.size() >= MAX_DISTINCT_BLUE_HINTS) {
                stop("本次蓝陶瓦线索超过256个安全上限，避免内存无限累积");
                return;
            }
            observedHints.add(key);
            attempts++;
            if (!tryCapture(blue, client.level)) {
                if (deferred.size() == MAX_DEFERRED_HINTS) {
                    deferred.removeFirst();
                    droppedDeferred++;
                }
                deferred.addLast(new Deferred(blue, ticks));
                lastStatus = "发现蓝陶瓦材料线索，但完整ROI尚未全部加载；只等待普通客户端正常接收区块";
            }
            break; // 已观察到一处线索，本次 tick 不再做另一处方块扫描。
        }
        if (cursor.next >= cursor.total()) {
            if (scannedChunks.remember(pack(cursor.x, cursor.z))) completedChunks++;
            search = null;
        }
    }

    private void retryDeferred(ClientLevel level) {
        int count = deferred.size();
        for (int i = 0; i < count && capture == null; i++) {
            Deferred item = deferred.removeFirst();
            if (ticks - item.firstTick() > DEFERRED_TICKS) {
                droppedDeferred++;
                continue;
            }
            if (!tryCapture(item.anchor(), level)) {
                deferred.addLast(item);
            }
        }
    }

    private boolean tryCapture(AutoDesertFootprintPlan.BlueTerracottaAnchor blue,
                               ClientLevel level) {
        final AutoDesertFootprintPlan.Survey survey;
        try {
            survey = AutoDesertFootprintPlan.fromObservedBlue(
                    blue, level.getMinY(), level.getMaxY());
        } catch (IllegalArgumentException badElevation) {
            invalidAnchorElevation++;
            return true; // 没有合法Y窗口，无意义重试。
        }
        var bounds = survey.roughBounds();
        int cx0 = Math.floorDiv(bounds.minX() - 1, 16);
        int cx1 = Math.floorDiv(bounds.maxX() + 1, 16);
        int cz0 = Math.floorDiv(bounds.minZ() - 1, 16);
        int cz1 = Math.floorDiv(bounds.maxZ() + 1, 16);
        LevelChunk[][] chunks = new LevelChunk[cx1-cx0+1][cz1-cz0+1];
        for (int ix = 0; ix < chunks.length; ix++) {
            for (int iz = 0; iz < chunks[ix].length; iz++) {
                LevelChunk full = level.getChunkSource().getChunk(
                        cx0+ix, cz0+iz, ChunkStatus.FULL, false);
                if (full == null) return false;
                chunks[ix][iz] = full;
            }
        }
        var scope = new ClientVisibleDesertPyramidRaster.Scope(
                worldFingerprint, version, dimension);
        capture = new Capture(blue, survey, scope, cx0, cz0, chunks);
        lastStatus = "客户端观察到蓝陶瓦，完整ROI已加载；正在自动测量建筑形状";
        return true;
    }

    private void advanceCapture(ClientLevel level) {
        Capture c = capture;
        for (int ix = 0; ix < c.fullChunks.length; ix++) {
            for (int iz = 0; iz < c.fullChunks[ix].length; iz++) {
                if (level.getChunkSource().getChunk(
                        c.firstChunkX + ix, c.firstChunkZ + iz,
                        ChunkStatus.FULL, false) != c.fullChunks[ix][iz]) {
                    capture = null;
                    interrupted++;
                    lastStatus = "ROI中途区块卸载/替换，未输出部分建筑证据";
                    return;
                }
            }
        }
        if (c.completed == null) {
            var b = c.survey.roughBounds();
            for (int i = 0; i < BLOCKS_PER_TICK && c.next < c.voxels.length; i++) {
                int n = c.next;
                int y = c.survey.minY() + n % 32;
                n /= 32;
                int z = b.minZ() - 1 + n % 31;
                int x = b.minX() - 1 + n / 31;
                LevelChunk loaded = c.fullChunks[Math.floorDiv(x, 16)-c.firstChunkX]
                        [Math.floorDiv(z, 16)-c.firstChunkZ];
                String blockId = BuiltInRegistries.BLOCK.getKey(
                        loaded.getBlockState(new BlockPos(x, y, z)).getBlock()).toString();
                c.voxels[c.next++] = ClientVisibleDesertPyramidRaster.classify(blockId);
            }
            if (c.next == c.voxels.length) {
                c.completed = new ClientVisibleDesertPyramidRaster(
                        c.scope, b, c.survey.minY(), c.survey.maxY(), c.voxels);
                var audit = c.completed.audit();
                if (!audit.completeVisiblePerimeter() || audit.matchedFootprints() != 1) {
                    incomplete++;
                    lastStatus = "材料线索不足以确认完整沙漠神殿：" + audit.reason();
                    capture = null;
                    return;
                }
                // 不同蓝陶瓦锚点可能归属同一座建筑。仅在严格21x21外缘确认后去重。
                var footprint = c.completed.discoveredFootprint();
                c.footprintKey = pack(footprint.minX(), footprint.minZ());
                if (queuedFootprints.contains(c.footprintKey)) {
                    duplicates++;
                    capture = null;
                    lastStatus = "跳过本会话已接受的相同完整轮廓；自然来源仍UNKNOWN";
                    return;
                }
            }
        }
        if (c.completed != null) {
            if (!queueArchive(c.completed)) {
                // 队列背压时保留整个完整证据，不标记成功、不继续扫描。
                lastStatus = "本地自动证据写入队列已满，暂停扫描等待落盘";
                return;
            }
            queuedFootprints.add(c.footprintKey);
            verified++;
            capture = null;
            lastStatus = "自动发现第 " + verified
                    + " 座完整可见神殿轮廓，客户端证据已进入后台写盘队列；"
                    + "自然来源和生成器仍 UNKNOWN";
        }
    }

    private boolean queueArchive(ClientVisibleDesertPyramidRaster raster) {
        ThreadPoolExecutor writer = executor;
        if (writer == null || writer.isShutdown()
                || writer.getQueue().remainingCapacity() == 0) return false;
        // 在主线程生成不可变文本快照；后台不再访问世界对象。
        String snapshot = raster.encode()
                + "captureMode=P36_AUTOMATIC_EXPLICIT_OPT_IN\n"
                + "generatorProfile=UNKNOWN\n"
                + "structureStartKnown=false\n";
        Path destination = folder.resolve("p36-automatic-footprint")
                .resolve("client-auto-desert-" + Instant.now().toEpochMilli()
                        + "-" + UUID.randomUUID() + ".properties");
        try {
            writer.execute(() -> {
                Path temp = null;
                try {
                    Files.createDirectories(destination.getParent());
                    temp = Files.createTempFile(destination.getParent(),
                            ".p36-automatic-", ".tmp");
                    Files.writeString(temp, snapshot, StandardCharsets.UTF_8);
                    Files.move(temp, destination, StandardCopyOption.ATOMIC_MOVE);
                    filesPersisted.incrementAndGet();
                } catch (IOException | RuntimeException failure) {
                    LOGGER.error("P3.6 local evidence archive failed", failure);
                    diskError.compareAndSet(null,
                            "IOException/文件系统拒绝自动证据持久化");
                } finally {
                    if (temp != null) {
                        try { Files.deleteIfExists(temp); }
                        catch (IOException cleanup) {
                            diskError.compareAndSet(null, "自动证据临时文件清理失败");
                        }
                    }
                }
            });
            return true;
        } catch (RuntimeException queueFailure) {
            diskError.compareAndSet(null, "文件队列提交异常");
            return false;
        }
    }

    private boolean sameWorld(Minecraft client) {
        return client.player != null && client.level == boundLevel
                && Objects.equals(source, WorldScopeResolver.discriminator(client))
                && Objects.equals(dimension,
                    client.level.dimension().identifier().toString());
    }

    private boolean matches(WorldProbeController.MapAccess access) {
        return worldFingerprint.equals(access.worldFingerprint())
                && dimension.equals(access.dimension())
                && folder.equals(access.folder());
    }

    private static long pack(int x, int z) {
        return ((long) x << 32) | (z & 0xffff_ffffL);
    }

    private static String hintKey(AutoDesertFootprintPlan.BlueTerracottaAnchor blue) {
        return blue.x() + ":" + blue.y() + ":" + blue.z();
    }
}
