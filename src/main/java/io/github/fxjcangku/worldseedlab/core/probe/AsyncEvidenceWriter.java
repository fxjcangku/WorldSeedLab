package io.github.fxjcangku.worldseedlab.core.probe;

import io.github.fxjcangku.worldseedlab.core.evidence.EvidenceRecord;
import io.github.fxjcangku.worldseedlab.core.generation.DimensionProfile;
import io.github.fxjcangku.worldseedlab.core.generation.TerrainChunkSample;
import java.io.IOException;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 仅一个后台磁盘写入线程、有界队列；游戏 Tick 线程只做非阻塞提交。
 * 数据异常时保留故障状态，通知 Controller 主动停机。
 */
public final class AsyncEvidenceWriter implements AutoCloseable {
    private final CompletedChunkStore store;
    private final String fingerprint;
    private final String dimension;
    private final ThreadPoolExecutor io;
    private final AtomicReference<String> failure = new AtomicReference<>();
    private final AtomicLong writtenChunks = new AtomicLong();
    private final AtomicLong writtenSamples = new AtomicLong();
    private final AtomicLong writtenManualMarks = new AtomicLong();
    private final AtomicLong writtenTerrainChunks = new AtomicLong();
    private final AtomicLong writtenDetailChunks = new AtomicLong();
    private final AtomicLong writtenRichChunks = new AtomicLong();

    public AsyncEvidenceWriter(CompletedChunkStore store, String fingerprint, String dimension) {
        this.store = store;
        this.fingerprint = fingerprint;
        this.dimension = dimension;
        this.io = new ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(16), job -> {
                    Thread worker = new Thread(job, "WorldSeedLab-EvidenceIO");
                    worker.setDaemon(true);
                    return worker;
                });
    }

    public boolean offer(ChunkKey key, List<EvidenceRecord> records) {
        if (io.isShutdown() || failure.get() != null || records.isEmpty()) {
            return false;
        }
        List<EvidenceRecord> frozen = List.copyOf(records);
        try {
            io.execute(() -> {
                try {
                    store.appendChunk(fingerprint, dimension, key, frozen);
                    writtenChunks.incrementAndGet();
                    writtenSamples.addAndGet(frozen.size());
                } catch (IOException | RuntimeException ex) {
                    failure.compareAndSet(null, "证据文件写入失败，请停止采集并查看日志: " + ex.getMessage());
                }
            });
            return true;
        } catch (RejectedExecutionException full) {
            return false;
        }
    }

    /** Explicit user action; serialized with the same single disk writer. */
    public boolean offerManual(io.github.fxjcangku.worldseedlab.core.evidence.EvidenceRecord record) {
        if (io.isShutdown() || failure.get() != null) return false;
        try {
            io.execute(() -> {
                try {
                    if (!fingerprint.equals(record.worldFingerprint())
                            || !dimension.equals(record.dimension())) {
                        throw new IOException("manual observation crossed a world scope");
                    }
                    store.appendManual(record);
                    writtenManualMarks.incrementAndGet();
                } catch (IOException | RuntimeException ex) {
                    failure.compareAndSet(null, "手动标记写入失败：" + ex.getMessage());
                }
            });
            return true;
        } catch (RejectedExecutionException full) {
            return false;
        }
    }

    /**
     * 写入维度元数据。与 P1/P2 证据使用同一个单线程队列，
     * 从而保证维度说明不会被写到其它世界的目录。
     */
    public boolean offerDimension(DimensionProfile profile) {
        if (io.isShutdown() || failure.get() != null) return false;
        if (!fingerprint.equals(profile.worldFingerprint())
                || !dimension.equals(profile.dimension())) {
            return false;
        }
        try {
            io.execute(() -> {
                try {
                    store.appendDimensionProfile(profile);
                } catch (IOException | RuntimeException failureCause) {
                    failure.compareAndSet(null,
                            "维度信息写入失败：" + failureCause.getMessage());
                }
            });
            return true;
        } catch (RejectedExecutionException full) {
            return false;
        }
    }

    /** 按当前世界范围异步写入客户端已获知的维度列表，不触发额外网络请求。 */
    public boolean offerAdvertisedDimensions(
            io.github.fxjcangku.worldseedlab.core.generation.AdvertisedDimensions snapshot
    ) {
        if (io.isShutdown() || failure.get() != null
                || !fingerprint.equals(snapshot.worldFingerprint())
                || !dimension.equals(snapshot.currentDimension())) {
            return false;
        }
        try {
            io.execute(() -> {
                try {
                    store.appendAdvertisedDimensions(snapshot);
                } catch (IOException | RuntimeException ex) {
                    failure.compareAndSet(null, "维度注册信息写入失败：" + ex.getMessage());
                }
            });
            return true;
        } catch (RejectedExecutionException queueFull) {
            return false;
        }
    }

    /**
     * 地形记录不完成 P1 群系区块索引；维持独立去重，兼容早期 P1 数据。
     */
    public boolean offerTerrain(TerrainChunkSample terrain) {
        if (io.isShutdown() || failure.get() != null) return false;
        if (!fingerprint.equals(terrain.worldFingerprint())
                || !dimension.equals(terrain.dimension())) {
            return false;
        }
        try {
            io.execute(() -> {
                try {
                    store.appendTerrain(terrain);
                    writtenTerrainChunks.incrementAndGet();
                } catch (IOException | RuntimeException failureCause) {
                    failure.compareAndSet(null,
                            "地形信息写入失败：" + failureCause.getMessage());
                }
            });
            return true;
        } catch (RejectedExecutionException full) {
            return false;
        }
    }

    /** 有界单线程写入，主线程不执行文件操作；按世界维度严格鉴权。 */
    public boolean offerDetail(
            io.github.fxjcangku.worldseedlab.core.generation.TerrainDetailSample detail
    ) {
        if (io.isShutdown() || failure.get() != null
                || !fingerprint.equals(detail.worldFingerprint())
                || !dimension.equals(detail.dimension())) return false;
        try {
            io.execute(() -> {
                try {
                    store.appendDetail(detail);
                    writtenDetailChunks.incrementAndGet();
                } catch (IOException | RuntimeException problem) {
                    failure.compareAndSet(null, "扩展地形详情写入失败：" + problem.getMessage());
                }
            });
            return true;
        } catch (RejectedExecutionException queueFull) {
            return false;
        }
    }

    public long writtenDetailChunks() {
        return writtenDetailChunks.get();
    }

    /**
     * 精细观测进入同一个有界后台队列；磁盘慢时主线程拒绝新任务并稍后重试。
     */
    public boolean offerRich(
            io.github.fxjcangku.worldseedlab.core.generation.RichChunkSample sample
    ) {
        if (io.isShutdown() || failure.get() != null
                || !fingerprint.equals(sample.worldFingerprint())
                || !dimension.equals(sample.dimension())) {
            return false;
        }
        try {
            io.execute(() -> {
                try {
                    store.appendRich(sample);
                    writtenRichChunks.incrementAndGet();
                } catch (IOException | RuntimeException error) {
                    failure.compareAndSet(null, "精细取证写盘失败：" + error.getMessage());
                }
            });
            return true;
        } catch (RejectedExecutionException queueFull) {
            return false;
        }
    }

    public long writtenRichChunks() {
        return writtenRichChunks.get();
    }

    public long writtenTerrainChunks() {
        return writtenTerrainChunks.get();
    }

    public long writtenManualMarks() {
        return writtenManualMarks.get();
    }

    public int pending() {
        return io.getQueue().size() + io.getActiveCount();
    }

    public String failure() {
        return failure.get();
    }

    public long writtenChunks() {
        return writtenChunks.get();
    }

    public long writtenSamples() {
        return writtenSamples.get();
    }

    /**
     * 只在客户端正常关闭时调用，最多等待有限时间让已接收的数据落盘。
     * 普通停止按钮仍采用非阻塞 close()，不会让 UI 等磁盘。
     */
    public boolean closeAndAwait(long maximumMillis) {
        io.shutdown();
        try {
            return io.awaitTermination(Math.max(1, maximumMillis), TimeUnit.MILLISECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    @Override
    public void close() {
        // 普通停止不阻塞 GUI；队列任务按提交顺序完成。
        io.shutdown();
    }
}
