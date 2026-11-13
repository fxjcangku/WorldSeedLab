package io.github.fxjcangku.worldseedlab.core.map;

import java.nio.file.Path;
import java.util.Objects;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * 在有界后台线程读取本机地图。新请求替换尚未开始的请求，旧查询不得覆盖新视窗。
 * 此类不依赖 Minecraft，便于独立验证快速切层、刷新和关闭页面的并发时序。
 */
public final class ObservedMapLoader implements AutoCloseable {
    private static final System.Logger LOGGER = System.getLogger(ObservedMapLoader.class.getName());
    private final Reader reader;
    private final ThreadPoolExecutor pool = new ThreadPoolExecutor(
            1, 1, 0, TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(1), work -> {
                Thread worker = new Thread(work, "WorldSeedLab-ObservedMap");
                worker.setDaemon(true);
                return worker;
            }, new ThreadPoolExecutor.AbortPolicy());
    private volatile Result current = new Result(null, "等待加载本地地图", false);
    private long generation;
    private boolean closed;
    private Path requestedScope;

    public ObservedMapLoader() {
        this((scope, query) -> new ObservedMapRepository(scope).query(query));
    }

    /** 测试用读取边界：用可控阻塞复现真实异步顺序，不依赖磁盘碰巧快慢。 */
    ObservedMapLoader(Reader reader) {
        this.reader = Objects.requireNonNull(reader);
    }

    public Result result() {
        return current;
    }

    /**
     * 相同作用域、相同视窗的刷新保留上一张真实快照，避免定时刷新时整幅地图闪空。
     * 切层、平移或切世界则立即撤下旧视图，绝不把旧作用域画到新请求上。
     */
    public synchronized void request(Path scopedFolder, MapQuery query) {
        Objects.requireNonNull(scopedFolder);
        Objects.requireNonNull(query);
        if (closed) {
            current = new Result(null, "地图面板已关闭，停止读取", false);
            return;
        }
        Path scope = scopedFolder.toAbsolutePath().normalize();
        MapView previous = current.view();
        if (!scope.equals(requestedScope) || previous == null || !previous.query().equals(query)) {
            previous = null;
        }
        requestedScope = scope;
        long token = ++generation;
        current = new Result(previous, "正在后台读取已保存地图…", true);
        // 同一锁内最多留一个排队请求。关闭执行器必须明确拒绝，不能静默丢弃后永久显示加载中。
        pool.getQueue().clear();
        try {
            pool.execute(() -> load(scope, query, token));
        } catch (RejectedExecutionException rejected) {
            current = new Result(previous, "地图读取任务未能启动，请重新打开地图", false);
        }
    }

    private void load(Path scope, MapQuery query, long token) {
        synchronized (this) {
            if (closed || generation != token) return;
        }
        try {
            MapView map = reader.read(scope, query);
            synchronized (this) {
                if (!closed && generation == token) {
                    current = new Result(map, map.message(), false);
                }
            }
        } catch (Exception problem) {
            synchronized (this) {
                if (!closed && generation == token) {
                    // 日志只记录错误类型，不把私有作用域路径、服务器地址或坐标带入摘要。
                    LOGGER.log(System.Logger.Level.WARNING,
                            "本地地图读取失败：{0}；保留原始证据，当前视窗保持未知",
                            problem.getClass().getSimpleName());
                    current = new Result(null,
                            "地图证据读取失败：" + problem.getClass().getSimpleName()
                                    + "；请保留原始证据并检查本地索引", false);
                }
            }
        }
    }

    @Override
    public synchronized void close() {
        if (closed) return;
        closed = true;
        generation++;
        current = new Result(null, "地图面板已关闭，停止读取", false);
        pool.shutdownNow();
    }

    @FunctionalInterface
    interface Reader {
        MapView read(Path scope, MapQuery query) throws Exception;
    }

    /** view 始终是完整不可变快照；loading 为真时可以保留同一视窗的上一份快照。 */
    public record Result(MapView view, String message, boolean loading) { }
}
