package io.github.fxjcangku.worldseedlab.client.ui;

import io.github.fxjcangku.worldseedlab.core.map.MapQuery;
import io.github.fxjcangku.worldseedlab.core.map.MapView;
import io.github.fxjcangku.worldseedlab.core.map.ObservedMapRepository;
import java.nio.file.Path;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 本地地图索引读取只在后台线程进行。每次平移/切层更新 generation，
 * 过期的查询不得覆盖较新的地图画面；队列最多保留一项查询。
 */
final class ObservedMapLoader implements AutoCloseable {
    private final AtomicLong generation = new AtomicLong();
    private final ThreadPoolExecutor pool = new ThreadPoolExecutor(
            1, 1, 0, TimeUnit.MILLISECONDS,
            new ArrayBlockingQueue<>(1),
            work -> {
                Thread worker = new Thread(work, "WorldSeedLab-ObservedMap");
                worker.setDaemon(true);
                return worker;
            },
            new ThreadPoolExecutor.DiscardOldestPolicy()
    );
    private volatile Result current = new Result(null, "等待加载本地地图", false);

    Result result() { return current; }

    void request(Path scopedFolder, MapQuery query) {
        long token = generation.incrementAndGet();
        current = new Result(null, "正在后台读取已探索区块地图索引…", true);
        try {
            pool.execute(() -> {
                try {
                    MapView map = new ObservedMapRepository(scopedFolder).query(query);
                    if (generation.get() == token) {
                        current = new Result(map, map.message(), false);
                    }
                } catch (Exception problem) {
                    if (generation.get() == token) {
                        current = new Result(null,
                                "地图证据读取失败：" + problem.getClass().getSimpleName()
                                        + "（请检查本地地图索引）", false);
                    }
                }
            });
        } catch (RejectedExecutionException closing) {
            current = new Result(null, "地图面板已关闭，停止读取", false);
        }
    }

    @Override
    public void close() {
        generation.incrementAndGet();
        pool.shutdownNow();
    }

    record Result(MapView view, String message, boolean loading) {}
}
