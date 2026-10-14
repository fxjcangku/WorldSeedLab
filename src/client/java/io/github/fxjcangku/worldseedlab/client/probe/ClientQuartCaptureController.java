package io.github.fxjcangku.worldseedlab.client.probe;

import io.github.fxjcangku.worldseedlab.client.scope.WorldScopeResolver;
import io.github.fxjcangku.worldseedlab.core.solver.ClientQuartEvidenceArchive;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.Objects;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.chunk.status.ChunkStatus;

/**
 * P3.2 普通 Fabric 客户端的手动已加载 FULL 区块群系证据采集。
 *
 * <p>无自动加载、无额外请求、无隐式启动、不使用服务端 StructureStart/Seed。
 * 不接受跨连接、跨世界纪元、跨维度续采；玩家需要主动在相隔>=1024方块的
 * 不同地点执行 /wsl quart sample，显式 /wsl quart export 才写入本地。
 * 原始私服坐标文件不得公开；这里导出的所有数据均不能自动成为生成器真值。
 */
public final class ClientQuartCaptureController {
    private final WorldProbeController probe;
    private final String gameVersion;
    private ClientQuartEvidenceArchive.Session session;
    private ClientLevel boundLevel;
    private String boundSource;
    private Path scopedFolder;
    private String dimension;
    private String lastStatus = "P3.2 离线群系手动证据尚未开始";

    public ClientQuartCaptureController(WorldProbeController probe, String gameVersion) {
        this.probe = Objects.requireNonNull(probe);
        this.gameVersion = Objects.requireNonNull(gameVersion);
    }

    public String status() {
        return lastStatus + " | 已收集 " + (session == null ? 0 : session.size())
                + "/5 个最低独立点（前3 FIT、其余 HOLDOUT）";
    }

    /** 每 tick 仅检查对象/连接身份，不扫描世界，不产生任何文件。 */
    public void tick(Minecraft client) {
        if (session == null) return;
        if (client.level == null || client.player == null
                || client.level != boundLevel
                || !Objects.equals(boundSource, WorldScopeResolver.discriminator(client))
                || !Objects.equals(dimension,
                        client.level.dimension().identifier().toString())) {
            clear("切服、断开或换维度：已丢弃未导出的群系证据");
        }
    }

    public void clear(String message) {
        session = null;
        boundLevel = null;
        boundSource = null;
        scopedFolder = null;
        dimension = null;
        lastStatus = message;
    }

    /** 玩家主动发起，仅检查玩家所在、已经接收到的 FULL 区块中心。 */
    public String sample(Minecraft client) {
        tick(client);
        if (client.level == null || client.player == null) {
            return lastStatus = "未进入世界，拒绝群系取样";
        }
        String source = WorldScopeResolver.discriminator(client);
        if (source == null) return lastStatus = "当前世界身份未知，拒绝采集";
        var access = probe.mapAccess(client);
        if (access.isEmpty()) return lastStatus = "本地世界指纹无法验证，拒绝取证";
        var current = access.get();
        if (session != null
                && (!session.scope().worldFingerprint().equals(current.worldFingerprint())
                || !session.scope().gameVersion().equals(gameVersion)
                || !session.scope().dimension().equals(current.dimension())
                || !Objects.equals(scopedFolder, current.folder()))) {
            clear("世界纪元/维度已变更，旧样本已清空；请重新采集");
            return lastStatus;
        }

        int chunkX = Math.floorDiv(current.x(), 16);
        int chunkZ = Math.floorDiv(current.z(), 16);
        final int blockX, blockY, blockZ;
        final String biome;
        try {
            if (client.level.getChunkSource().getChunk(
                    chunkX, chunkZ, ChunkStatus.FULL, false) == null) {
                return lastStatus = "当前区块未完整加载；不请求新区块";
            }
            blockX = Math.addExact(Math.multiplyExact(chunkX, 16), 8);
            blockZ = Math.addExact(Math.multiplyExact(chunkZ, 16), 8);
            blockY = Math.max(current.minY(), Math.min(current.y(),
                    current.maxYExclusive() - 1));
            biome = client.level.getBiome(new BlockPos(blockX, blockY, blockZ))
                    .unwrapKey().map(k -> k.identifier().toString()).orElse(null);
        } catch (RuntimeException invalid) {
            clear("客户端区块/群系状态异常，未导出的证据已清空");
            return lastStatus;
        }
        if (biome == null) return lastStatus = "群系注册键未知，不记为证据";

        if (session == null) {
            session = new ClientQuartEvidenceArchive.Session(
                    current.worldFingerprint(), gameVersion, current.dimension());
            boundLevel = client.level;
            boundSource = source;
            scopedFolder = current.folder();
            dimension = current.dimension();
        }
        try {
            session.addObservedBlock(blockX, blockY, blockZ, biome);
            return lastStatus = "已记录实际 FULL 区块 quart 群系 " + biome
                    + "；第 " + session.size()
                    + " 条（需不同地点，且水平至少间隔1024方块）";
        } catch (IllegalArgumentException rejected) {
            return lastStatus = "样本未接收：" + rejected.getMessage();
        } catch (RuntimeException failure) {
            clear("客户端群系识别异常，采集已清空");
            return lastStatus;
        }
    }

    /** 用户明确命令导出；只写当前 HMAC 世界/维度目录，不覆盖其它历史会话。 */
    public String export(Minecraft client) {
        tick(client);
        if (session == null || !session.ready()) {
            return lastStatus = "证据不足：至少需要3个FIT和2个HOLDOUT，水平各隔1024方块";
        }
        var access = probe.mapAccess(client);
        if (access.isEmpty() || client.level != boundLevel
                || !session.scope().worldFingerprint().equals(access.get().worldFingerprint())
                || !session.scope().dimension().equals(access.get().dimension())
                || !scopedFolder.equals(access.get().folder())) {
            clear("世界或世界纪元已经变化：拒绝将旧坐标写入新世界");
            return lastStatus;
        }
        Path temp = null;
        try {
            Path targetDirectory = scopedFolder.resolve("p32-manual-quart");
            Files.createDirectories(targetDirectory);
            temp = Files.createTempFile(targetDirectory, ".p32-quart-", ".tmp");
            Files.writeString(temp, ClientQuartEvidenceArchive.encode(session.snapshot()),
                    StandardCharsets.UTF_8);
            String filename = "client-quart-" + Instant.now().toEpochMilli() + ".csv";
            Path target = targetDirectory.resolve(filename);
            Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE);
            temp = null;
            clear("群系证据已显式写入本地：" + target.toAbsolutePath()
                    + "；含私人世界原始坐标，分享前必须人工脱敏");
            return lastStatus;
        } catch (IOException | RuntimeException failure) {
            lastStatus = "本地导出失败，数据仍保留在内存：" + failure.getClass().getSimpleName();
            return lastStatus;
        } finally {
            if (temp != null) {
                try { Files.deleteIfExists(temp); } catch (IOException ignored) { /* 导出失败已提示 */ }
            }
        }
    }
}
