package io.github.fxjcangku.worldseedlab.core.map;

import java.io.IOException;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/** 用可控阻塞验证真实队列及页面关闭时序；不靠文件读取快慢碰运气。 */
public final class ObservedMapLoaderRegression {
    private static final Path FIRST_SCOPE = Path.of("controlled-map-scope-a");
    private static final Path SECOND_SCOPE = Path.of("controlled-map-scope-b");
    private static final MapQuery QUERY = new MapQuery(0, 0, 4, 4, MapLayer.SURFACE_BIOME, 64);

    private ObservedMapLoaderRegression() { }

    public static void run() throws Exception {
        latestQueuedRequestWins();
        refreshPreservesOnlySameScope();
        closedLoaderCannotRemainLoading();
        failureCanBeRetried();
    }

    private static void latestQueuedRequestWins() throws Exception {
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        AtomicInteger reads = new AtomicInteger();
        try (var loader = new ObservedMapLoader((scope, query) -> {
            if (reads.incrementAndGet() == 1) {
                entered.countDown();
                await(release, "release initial query");
                // 过期查询即使失败，也不得覆盖较新的请求状态。
                throw new IOException("controlled stale failure");
            }
            return MapView.empty(query, "latest query");
        })) {
            loader.request(FIRST_SCOPE, QUERY);
            await(entered, "first query must start");
            loader.request(FIRST_SCOPE, QUERY.moved(16, 0));
            MapQuery latest = QUERY.moved(32, 0);
            loader.request(FIRST_SCOPE, latest);
            release.countDown();
            MapView view = completed(loader).view();
            check(view != null && view.query().equals(latest), "stale result must not win");
            check(reads.get() == 2, "superseded queued query must never read disk");
        } finally {
            release.countDown();
        }
    }

    private static void refreshPreservesOnlySameScope() throws Exception {
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        AtomicInteger reads = new AtomicInteger();
        try (var loader = new ObservedMapLoader((scope, query) -> {
            if (reads.incrementAndGet() == 2) {
                entered.countDown();
                await(release, "release refresh query");
            }
            return MapView.empty(query, "controlled saved snapshot");
        })) {
            loader.request(FIRST_SCOPE, QUERY);
            MapView initial = completed(loader).view();
            loader.request(FIRST_SCOPE, QUERY);
            await(entered, "refresh must start");
            check(loader.result().loading() && loader.result().view() == initial,
                    "refresh should retain same-scope saved view while loading");
            loader.request(SECOND_SCOPE, QUERY);
            check(loader.result().loading() && loader.result().view() == null,
                    "a new scope may never display the previous world's snapshot");
            release.countDown();
            check(completed(loader).view() != null, "new scope should complete normally");
        } finally {
            release.countDown();
        }
    }

    private static void closedLoaderCannotRemainLoading() throws Exception {
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        CountDownLatch stopped = new CountDownLatch(1);
        try (var loader = new ObservedMapLoader((scope, query) -> {
            entered.countDown();
            try {
                await(release, "release closing query");
                return MapView.empty(query, "late result");
            } finally {
                stopped.countDown();
            }
        })) {
            loader.request(FIRST_SCOPE, QUERY);
            await(entered, "closing query must start");
            loader.close();
            loader.request(FIRST_SCOPE, QUERY);
            check(!loader.result().loading() && loader.result().view() == null,
                    "request after close must not silently disappear into permanent loading");
            release.countDown();
            await(stopped, "closed worker must stop");
            check(!loader.result().loading() && loader.result().view() == null,
                    "late worker completion may not revive a removed page");
        } finally {
            release.countDown();
        }
    }

    private static void failureCanBeRetried() throws Exception {
        AtomicInteger reads = new AtomicInteger();
        try (var loader = new ObservedMapLoader((scope, query) -> {
            if (reads.incrementAndGet() == 1) throw new IOException("controlled read failure");
            return MapView.empty(query, "retry completed");
        })) {
            loader.request(FIRST_SCOPE, QUERY);
            var failed = completed(loader);
            check(failed.view() == null && failed.message().contains("IOException"),
                    "active read error must produce a visible terminal state");
            loader.request(FIRST_SCOPE, QUERY);
            check(completed(loader).view() != null, "failure must not disable explicit retry");
        }
    }

    private static ObservedMapLoader.Result completed(ObservedMapLoader loader) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (loader.result().loading() && System.nanoTime() < deadline) Thread.sleep(5);
        var result = loader.result();
        check(!result.loading(), "query must reach a terminal state within test budget");
        return result;
    }

    private static void await(CountDownLatch latch, String message) throws InterruptedException {
        check(latch.await(5, TimeUnit.SECONDS), message);
    }

    private static void check(boolean okay, String message) {
        if (!okay) throw new AssertionError(message);
    }
}
