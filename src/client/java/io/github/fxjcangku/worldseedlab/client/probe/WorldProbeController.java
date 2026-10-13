package io.github.fxjcangku.worldseedlab.client.probe;

import io.github.fxjcangku.worldseedlab.client.scope.WorldScopeResolver;

import io.github.fxjcangku.worldseedlab.core.evidence.EvidenceKind;
import io.github.fxjcangku.worldseedlab.core.evidence.EvidenceOrigin;
import io.github.fxjcangku.worldseedlab.core.evidence.EvidenceRecord;
import io.github.fxjcangku.worldseedlab.core.privacy.LocalSecret;
import io.github.fxjcangku.worldseedlab.core.privacy.WorldFingerprint;
import io.github.fxjcangku.worldseedlab.core.probe.AsyncEvidenceWriter;
import io.github.fxjcangku.worldseedlab.core.probe.ChunkKey;
import io.github.fxjcangku.worldseedlab.core.probe.ChunkSamplingCursor;
import io.github.fxjcangku.worldseedlab.core.probe.CompletedChunkStore;
import io.github.fxjcangku.worldseedlab.core.probe.ProbeConfigFile;
import io.github.fxjcangku.worldseedlab.core.probe.ProbeGate;
import io.github.fxjcangku.worldseedlab.core.probe.ProbeSettings;
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
    private final Map<String, Long> biomes = new HashMap<>();
    private ProbeSettings settings;
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
    public Path dataRoot() { return configRoot.resolve("observations"); }

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

    private static final Set<String> MANUAL_STRUCTURES = Set.of(
            "minecraft:shipwreck", "minecraft:desert_pyramid", "minecraft:village_plains");

    public long writtenManualMarks() {
        return writer == null ? 0 : writer.writtenManualMarks();
    }

    /**
     * Saves the player's observation position, explicitly NOT the structure's
     * world-generation start chunk. Requires an active, unchanged world scope.
     */
    public void markStructure(Minecraft client, String structureId) {
        if (!MANUAL_STRUCTURES.contains(structureId)) {
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
        EvidenceRecord evidence = new EvidenceRecord(UUID.randomUUID(),
                fingerprint, dimension, gameVersion,
                EvidenceKind.STRUCTURE_OBSERVATION, structureId,
                position.getX(), position.getY(), position.getZ(),
                EvidenceOrigin.MANUAL_MARK, Instant.now(),
                "manual-player-position;unverified;NOT-structure-generation-origin");
        if (!gate.allows(evidence) || !writer.offerManual(evidence)) {
            status = "结构标记未加入写入队列，请检查磁盘或重试";
            return;
        }
        status = "已提交手动结构标记（位置不是生成起点）";
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
            String f = WorldFingerprint.fromLocalSecret(
                    LocalSecret.loadOrCreate(configRoot), source);
            String d = client.level.dimension().identifier().toString();
            CompletedChunkStore nextStore = new CompletedChunkStore(dataRoot());
            Set<ChunkKey> resume = nextStore.loadCompleted(f, d);
            pending.clear();
            enqueued.clear();
            seen.clear();
            seen.addAll(resume);
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
            gate.arm(f, d);
            status = "正在采集客户端已加载的群系数据";
        } catch (IOException | RuntimeException failure) {
            logger.error("Probe activation rejected", failure);
            stop("启动失败：" + failure.getMessage());
        }
    }

    public void stop(String reason) {
        gate.disarm();
        pending.clear();
        enqueued.clear();
        cursor = null;
        chunk = null;
        samples = null;
        scopeLevel = null;
        scopeName = null;
        if (writer != null) writer.close(); // drains accepted chunks asynchronously
        status = reason;
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
            if (++ticks % 12 == 1) findChunks(client);
            int budget = settings.samplesPerTick();
            while (budget > 0) {
                if (cursor == null && !beginChunk(client.level)) break;
                if (cursor.hasNext() && !isLoaded(client.level, chunk)) {
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
                // Keep the finished chunk in memory if the bounded disk queue is full.
                if (!writer.offer(chunk, samples)) {
                    status = "写入队列繁忙，暂时等待";
                    break;
                }
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
