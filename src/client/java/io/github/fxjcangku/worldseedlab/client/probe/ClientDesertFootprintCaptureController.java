package io.github.fxjcangku.worldseedlab.client.probe;

import io.github.fxjcangku.worldseedlab.client.scope.WorldScopeResolver;
import io.github.fxjcangku.worldseedlab.core.geometry.ClientVisibleDesertPyramidRaster;
import io.github.fxjcangku.worldseedlab.core.geometry.StructureFootprintInverter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.Arrays;
import java.util.Objects;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.status.ChunkStatus;

/**
 * P3.5 客户端两角手动取证：只在命令触发后扫描已接收到的 FULL 区块。
 * 整个边界+外侧一格的 3D 方块栅格必须全部读取；每 Tick 至多 256 次
 * BlockState 读取。严禁通过 World.getChunk 触发新加载，也不读取 StructureStart。
 *
 * <p>玩家所标区域尚未经天然来源验证，即便四侧外缘与纹饰吻合，
 * 也只能保存 CLIENT_OBSERVED_BLOCK_STATES，不可升级为真正生成起点。
 */
public final class ClientDesertFootprintCaptureController {
    private static final int TICK_BUDGET = 256;
    private final WorldProbeController probe;
    private final String gameVersion;
    private BlockPos first;
    private ClientLevel level;
    private String source;
    private String worldFingerprint, dimension;
    private Path folder;
    private Session session;
    private String status = "P3.5 尚未开始：先到建筑两处水平外角分别执行 first / second";

    private static final class Session {
        final StructureFootprintInverter.BlockBounds bounds;
        final ClientVisibleDesertPyramidRaster.Scope scope;
        final int lowY, highY;
        final int firstChunkX, firstChunkZ;
        final LevelChunk[][] loaded;
        final byte[] raw;
        int next;
        ClientVisibleDesertPyramidRaster finished;

        Session(StructureFootprintInverter.BlockBounds bounds,
                ClientVisibleDesertPyramidRaster.Scope scope, int lowY, int highY,
                int firstChunkX, int firstChunkZ, LevelChunk[][] loaded) {
            this.bounds = bounds;
            this.scope = scope;
            this.lowY = lowY;
            this.highY = highY;
            this.firstChunkX = firstChunkX;
            this.firstChunkZ = firstChunkZ;
            this.loaded = loaded;
            int count = (bounds.maxX() - bounds.minX() + 3)
                    * (bounds.maxZ() - bounds.minZ() + 3) * (highY - lowY + 1);
            this.raw = new byte[count];
            Arrays.fill(raw, (byte) -1);
        }
    }

    public ClientDesertFootprintCaptureController(WorldProbeController probe, String version) {
        this.probe = Objects.requireNonNull(probe);
        this.gameVersion = Objects.requireNonNull(version);
    }

    public String status() {
        if (session == null) return status;
        return status + " | 实际读取 " + session.next + "/" + session.raw.length
                + " 方块 | 默认不会生成 Seed 候选";
    }

    /** 对象身份/世界源/维度变更即丢弃；若区块中途卸载则拒绝部分栅格。 */
    public void tick(Minecraft minecraft) {
        if (first == null && session == null) return;
        if (!sameWorld(minecraft)) {
            clear("断开、换服或换维度：已清空未导出的建筑取证");
            return;
        }
        if (session == null || session.finished != null) return;
        Session s = session;
        for (int x = 0; x < s.loaded.length; x++) {
            for (int z = 0; z < s.loaded[x].length; z++) {
                if (minecraft.level.getChunkSource().getChunk(
                        s.firstChunkX + x, s.firstChunkZ + z,
                        ChunkStatus.FULL, false) != s.loaded[x][z]) {
                    clear("扫描中 FULL 区块卸载或替换：未产生完整轮廓证据");
                    return;
                }
            }
        }
        try {
            int depth = s.bounds.maxZ() - s.bounds.minZ() + 3;
            int height = s.highY - s.lowY + 1;
            for (int i = 0; i < TICK_BUDGET && s.next < s.raw.length; i++) {
                int n = s.next;
                int y = s.lowY + n % height;
                n /= height;
                int z = s.bounds.minZ() - 1 + n % depth;
                int x = s.bounds.minX() - 1 + n / depth;
                LevelChunk chunk = s.loaded[Math.floorDiv(x,16)-s.firstChunkX]
                        [Math.floorDiv(z,16)-s.firstChunkZ];
                String id = BuiltInRegistries.BLOCK.getKey(
                        chunk.getBlockState(new BlockPos(x,y,z)).getBlock()).toString();
                s.raw[s.next++] = ClientVisibleDesertPyramidRaster.classify(id);
            }
            if (s.next == s.raw.length) {
                // 所有栅格都来自原始已加载 chunk 对象；用户还需单独执行 export。
                s.finished = new ClientVisibleDesertPyramidRaster(
                        s.scope, s.bounds, s.lowY, s.highY, s.raw);
                var audit = s.finished.audit();
                status = "客户端真实方块读取完毕：" + audit.reason()
                        + "；边界通过=" + audit.completeVisiblePerimeter()
                        + "；自然生成来源 UNKNOWN；请手动 /wsl footprint export";
            }
        } catch (RuntimeException failure) {
            clear("区块取样异常 " + failure.getClass().getSimpleName()
                    + "：失败关闭，不保留部分轮廓");
        }
    }

    public String first(Minecraft minecraft) {
        clear("重置了上次未导出的 P3.5 会话");
        var access = probe.mapAccess(minecraft);
        String discriminator = WorldScopeResolver.discriminator(minecraft);
        if (access.isEmpty() || discriminator == null || minecraft.level == null
                || minecraft.player == null) {
            return status = "当前世界身份不可验证，拒绝设置建筑第一外角";
        }
        var current = access.get();
        first = minecraft.player.blockPosition();
        level = minecraft.level;
        source = discriminator;
        worldFingerprint = current.worldFingerprint();
        dimension = current.dimension();
        folder = current.folder();
        return status = "第一水平外角已暂存；到同一建筑的对角外缘执行 /wsl footprint second";
    }

    public String second(Minecraft minecraft) {
        if (first == null || !sameWorld(minecraft)) {
            clear("第一外角缺失或已换世界，拒绝取证");
            return status;
        }
        var access = probe.mapAccess(minecraft);
        if (access.isEmpty() || !matches(access.get())) {
            clear("世界纪元身份变更，拒绝复用旧标记");
            return status;
        }
        BlockPos second = minecraft.player.blockPosition();
        StructureFootprintInverter.BlockBounds bounds;
        try {
            bounds = new StructureFootprintInverter.BlockBounds(
                    Math.min(first.getX(), second.getX()),
                    Math.min(first.getZ(), second.getZ()),
                    Math.max(first.getX(), second.getX()),
                    Math.max(first.getZ(), second.getZ()));
            int low = Math.subtractExact(Math.min(first.getY(), second.getY()), 12);
            int high = Math.addExact(Math.max(first.getY(), second.getY()), 19);
            if (low < level.getMinY() || high >= level.getMaxY()) {
                throw new IllegalArgumentException("高度取证窗口超出世界边界");
            }
            var scope = new ClientVisibleDesertPyramidRaster.Scope(
                    worldFingerprint, gameVersion, dimension);
            // 验证尺寸与体积；空占位全部为-1，绝不能当作空气。
            int width = bounds.maxX() - bounds.minX() + 3;
            int depth = bounds.maxZ() - bounds.minZ() + 3;
            new ClientVisibleDesertPyramidRaster(scope, bounds, low, high,
                    new byte[width * depth * (high - low + 1)]);
            int cx0 = Math.floorDiv(bounds.minX()-1, 16);
            int cz0 = Math.floorDiv(bounds.minZ()-1, 16);
            int cx1 = Math.floorDiv(bounds.maxX()+1, 16);
            int cz1 = Math.floorDiv(bounds.maxZ()+1, 16);
            LevelChunk[][] existing = new LevelChunk[cx1-cx0+1][cz1-cz0+1];
            for (int ix = 0; ix < existing.length; ix++) {
                for (int iz = 0; iz < existing[ix].length; iz++) {
                    // false 是关键：没有任何新 chunk 请求；缺一格即不启动。
                    existing[ix][iz] = level.getChunkSource().getChunk(
                            cx0+ix, cz0+iz, ChunkStatus.FULL, false);
                    if (existing[ix][iz] == null) {
                        clear("外缘或建筑内部存在未接收 FULL 区块：拒绝开始扫描");
                        return status;
                    }
                }
            }
            session = new Session(bounds, scope, low, high, cx0, cz0, existing);
            return status = "已授权本次手动沙漠神殿三维轮廓取证；每Tick最多读取"
                    + TICK_BUDGET + "个已加载方块；未验证自然来源";
        } catch (IllegalArgumentException | ArithmeticException invalid) {
            clear("取证框尺寸、高度或坐标不合法，拒绝扫描");
            return status;
        }
    }

    /** 用户显式导出，写到当前世界 HMAC/维度隔离目录；不自动上传。 */
    public String export(Minecraft minecraft) {
        tick(minecraft);
        if (session == null || session.finished == null) {
            return status = "三维轮廓仍未完整读取；不能导出半成品";
        }
        var access = probe.mapAccess(minecraft);
        if (access.isEmpty() || !matches(access.get())) {
            clear("本地世界纪元改变，拒绝跨纪元导出");
            return status;
        }
        Path temp = null;
        try {
            Path dir = folder.resolve("p35-manual-footprint");
            Files.createDirectories(dir);
            temp = Files.createTempFile(dir, ".p35-raster-", ".tmp");
            Files.writeString(temp, session.finished.encode(), StandardCharsets.UTF_8);
            Path path = dir.resolve("client-visible-desert-"
                    + Instant.now().toEpochMilli() + ".properties");
            Files.move(temp, path, StandardCopyOption.ATOMIC_MOVE);
            temp = null;
            var audit = session.finished.audit();
            clear("P3.5 CLIENT_OBSERVED 栅格已手动保存在：" + path.toAbsolutePath()
                    + "；可见四侧通过=" + audit.completeVisiblePerimeter()
                    + "；" + audit.reason() + "；未证明生成起点");
            return status;
        } catch (IOException | RuntimeException problem) {
            return status = "本地P3.5导出失败，内存证据未丢弃："
                    + problem.getClass().getSimpleName();
        } finally {
            if (temp != null) {
                try { Files.deleteIfExists(temp); }
                catch (IOException ignored) { /* 前面已向用户报告导出失败 */ }
            }
        }
    }

    private boolean sameWorld(Minecraft minecraft) {
        return minecraft.level != null && minecraft.player != null
                && minecraft.level == level
                && Objects.equals(source, WorldScopeResolver.discriminator(minecraft))
                && Objects.equals(dimension,
                        minecraft.level.dimension().identifier().toString());
    }

    private boolean matches(WorldProbeController.MapAccess access) {
        return worldFingerprint.equals(access.worldFingerprint())
                && dimension.equals(access.dimension())
                && folder.equals(access.folder());
    }

    public void clear(String message) {
        first = null;
        level = null;
        source = null;
        worldFingerprint = null;
        dimension = null;
        folder = null;
        session = null;
        status = message;
    }
}
