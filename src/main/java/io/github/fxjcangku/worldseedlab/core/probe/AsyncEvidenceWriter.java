package io.github.fxjcangku.worldseedlab.core.probe;

import io.github.fxjcangku.worldseedlab.core.evidence.EvidenceRecord;
import java.io.IOException;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/** Single-writer, bounded I/O queue: no disk writes on the game tick. */
public final class AsyncEvidenceWriter implements AutoCloseable {
    private final CompletedChunkStore store;
    private final String fingerprint;
    private final String dimension;
    private final ThreadPoolExecutor io;
    private final AtomicReference<String> failure = new AtomicReference<>();
    private final AtomicLong writtenChunks = new AtomicLong();
    private final AtomicLong writtenSamples = new AtomicLong();
    private final AtomicLong writtenManualMarks = new AtomicLong();

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

    @Override
    public void close() {
        // Ordered shutdown drains previously accepted chunk batches asynchronously.
        io.shutdown();
    }
}
