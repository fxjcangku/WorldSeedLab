package io.github.fxjcangku.worldseedlab.core.probe;

/**
 * 记录本次会话的采集机会及不完整样本，不估算服务器全图覆盖率。
 *
 * <p>“已排队”与“已接收写盘”不等于“已成功持久化”。实际落盘数量
 * 必须读取异步 Writer 的成功计数；高速鞘翅导致的遗漏无法全部观测到。
 * 所有方法只在客户端主线程调用，不参与任何生成器推测。
 */
public final class ProbeCoverage {
    private long queuedChunks;
    private long acceptedChunks;
    private long unloadedBeforeSampling;
    private long abandonedPartialChunks;
    private long writerBackpressure;
    private long abandonedDetailScans;

    public void queued() {
        queuedChunks++;
    }

    public void accepted() {
        acceptedChunks++;
    }

    public void unloadedBeforeSampling() {
        unloadedBeforeSampling++;
    }

    public void abandonedPartialChunk() {
        abandonedPartialChunks++;
    }

    public void writerBackpressure() {
        writerBackpressure++;
    }

    public void abandonedDetailScan() {
        abandonedDetailScans++;
    }

    public Snapshot snapshot() {
        return new Snapshot(queuedChunks, acceptedChunks, unloadedBeforeSampling,
                abandonedPartialChunks, writerBackpressure, abandonedDetailScans);
    }

    public record Snapshot(
            long queuedChunks,
            long acceptedChunks,
            long unloadedBeforeSampling,
            long abandonedPartialChunks,
            long writerBackpressureEvents,
            long abandonedDetailScans
    ) {
        /** 不得推导全服务器空间覆盖率，更不得把此处比值当作种子可信度。 */
        public String chineseSummary() {
            return "本次排队区块: " + queuedChunks
                    + "\n已接收写入的完整群系区块: " + acceptedChunks
                    + "\n排队后卸载（未采样）: " + unloadedBeforeSampling
                    + "\n部分采样后卸载: " + abandonedPartialChunks
                    + "\n异步队列背压事件: " + writerBackpressureEvents
                    + "\n扩展剖面中断: " + abandonedDetailScans
                    + "\n全服覆盖率: UNKNOWN（无法仅凭客户端观察推导）\n";
        }
    }
}
