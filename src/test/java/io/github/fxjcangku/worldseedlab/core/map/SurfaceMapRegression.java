package io.github.fxjcangku.worldseedlab.core.map;

import io.github.fxjcangku.worldseedlab.core.evidence.EvidenceKind;
import io.github.fxjcangku.worldseedlab.core.evidence.EvidenceOrigin;
import io.github.fxjcangku.worldseedlab.core.evidence.EvidenceRecord;
import io.github.fxjcangku.worldseedlab.core.generation.DimensionProfile;
import io.github.fxjcangku.worldseedlab.core.generation.TerrainChunkKey;
import io.github.fxjcangku.worldseedlab.core.probe.AsyncEvidenceWriter;
import io.github.fxjcangku.worldseedlab.core.probe.ChunkKey;
import io.github.fxjcangku.worldseedlab.core.probe.CompletedChunkStore;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.UUID;

/** 受控合成读取器验证逐方块链路；不把本测试当作真实 Fabric 地表验收。 */
public final class SurfaceMapRegression {
    private static final String WORLD = "e".repeat(64), OTHER = "f".repeat(64);
    private static final String DIM = "minecraft:overworld";
    private static final Instant TIME = Instant.parse("2026-10-23T00:00:00Z");
    private static final TerrainChunkKey KEY = new TerrainChunkKey(-2, 3);

    private SurfaceMapRegression() { }

    public static void run() throws Exception {
        horizontalRunsNeverFillGaps();
        var fixture = new ControlledReader();
        var cursor = new SurfaceSamplingCursor(profile("26.2"), KEY, fixture);
        cursor.advance(32);
        check(fixture.reads <= 32 && fixture.heights <= 32, "tick budgets must bound actual reads");
        reject(cursor::result, "partial chunk cannot be persisted");
        while (!cursor.isComplete()) {
            int before = fixture.reads, beforeHeights = fixture.heights;
            cursor.advance(32);
            check(fixture.reads - before <= 32 && fixture.heights - beforeHeights <= 32,
                    "transparent descent must also respect per-tick budget");
        }
        SurfaceChunkSample sample = cursor.result();
        check(sample.heights().get(0) == 64, "transparent blocks must use observed visible block below");
        check(sample.argb().get(1) != sample.argb().get(0), "adjacent blocks retain different observed colors");
        fixture.loaded = false;
        reject(cursor::result, "unloaded completed cursor cannot submit old data");
        int priorReads = fixture.reads;
        reject(() -> cursor.advance(32), "unloaded chunk must stop before any new read");
        check(priorReads == fixture.reads, "unload must not consume block reads");

        Path root = Files.createTempDirectory("worldseedlab-surface-map-test");
        try {
            persistenceAndScope(root, sample);
            queueAndFailure(root, sample);
        } finally {
            try (var paths = Files.walk(root)) {
                for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
            }
        }
        System.out.println("WorldSeedLab block surface map regression passed (controlled fixture, not Fabric GUI).");
    }

    private static void horizontalRunsNeverFillGaps() {
        var cells = List.of(new MapView.SurfaceCell(4, 0, 64, 0xFF112233),
                new MapView.SurfaceCell(1, 0, 65, 0xFF445566),
                new MapView.SurfaceCell(0, 1, 64, 0xFF445566),
                new MapView.SurfaceCell(3, 0, 64, 0xFF445566),
                new MapView.SurfaceCell(0, 0, 64, 0xFF445566));
        var view = new MapView(new MapQuery(0, 0, 4, 4, MapLayer.SURFACE_COLOR, 64, "26.2"),
                List.of(), List.of(), cells, false, "controlled run fixture");
        check(view.surfaceRuns().size() == 4 && view.surfaceRuns().get(0).length() == 2,
                "runs merge only adjacent identical colors, independent of input ordering");
        check(view.surfaceRuns().stream().mapToInt(MapView.SurfaceRun::length).sum() == cells.size(),
                "a missing column must not be included in a color run");
        check(view.surfaceRuns().stream().noneMatch(r -> r.blockZ() == 0 && r.blockX() <= 2
                        && r.blockX() + r.length() > 2), "horizontal merging must leave unknown gaps empty");
    }

    private static void persistenceAndScope(Path root, SurfaceChunkSample sample) throws Exception {
        var store = new CompletedChunkStore(root.resolve("observations"));
        var observation = new EvidenceRecord(UUID.randomUUID(), WORLD, DIM, "26.2",
                EvidenceKind.BIOME_OBSERVATION, "minecraft:plains", -30, 66, 50,
                EvidenceOrigin.CLIENT_OBSERVATION, TIME, "controlled surface map test");
        store.appendChunk(WORLD, DIM, new ChunkKey(-2, 3, 8), List.of(observation));
        byte[] original = Files.readAllBytes(store.dataPath(WORLD, DIM));
        check(store.loadSurfaceCompleted(WORLD, DIM, "26.2").isEmpty(),
                "legacy biome completion must never block the new surface layer");
        var repository = new ObservedMapRepository(store.scopedFolder(WORLD, DIM));
        MapQuery query = new MapQuery(-24, 56, 4, 4, MapLayer.SURFACE_COLOR, 64, "26.2");
        check(repository.query(query).surfaceCells().isEmpty(), "old quart evidence cannot fabricate block colors");
        store.appendSurface(sample);
        check(store.loadSurfaceCompleted(WORLD, DIM, "26.2").equals(java.util.Set.of(KEY)),
                "surface progress is committed only after a complete tile");
        MapView view = repository.query(query);
        check(view.cells().isEmpty() && view.surfaceCells().size() == 256,
                "surface layer must contain 256 genuine columns, not 16 enlarged quart cells");
        var positions = new HashSet<String>();
        for (var cell : view.surfaceCells()) positions.add(cell.blockX() + "," + cell.blockZ());
        check(positions.size() == 256, "every block column must have its own independent position");
        check(repository.query(new MapQuery(-24, 56, 1, 1, MapLayer.SURFACE_COLOR, 64, "26.2"))
                .surfaceCells().size() == 16, "viewport clipping must preserve one-block granularity");
        check(repository.query(new MapQuery(-24, 56, 4, 4, MapLayer.SURFACE_COLOR, 64))
                .surfaceCells().isEmpty(), "unspecified game version must fail closed");
        var oldVersion = new MapQuery(-24, 56, 4, 4, MapLayer.SURFACE_COLOR, 64, "26.1.2");
        check(repository.query(oldVersion).surfaceCells().isEmpty(), "versions must never share surface tiles");
        check(store.loadSurfaceCompleted(WORLD, DIM, "26.1.2").isEmpty(), "completion is version isolated");
        store.appendSurface(copy(sample, WORLD, DIM, "26.1.2", -2, 3));
        check(repository.query(oldVersion).surfaceCells().size() == 256
                        && repository.query(query).surfaceCells().size() == 256,
                "both supported versions must coexist without overwrite");
        check(java.util.Arrays.equals(original, Files.readAllBytes(store.dataPath(WORLD, DIM))),
                "surface upgrade may not rewrite original evidence");
        check(new ObservedMapRepository(store.scopedFolder(OTHER, DIM)).query(query).surfaceCells().isEmpty(),
                "different HMAC world has no surface from prior world");
        check(new ObservedMapRepository(store.scopedFolder(WORLD, "minecraft:the_nether"))
                .query(query).surfaceCells().isEmpty(), "different dimensions have independent surface maps");

        Path source = MapTileArchive.root(store.scopedFolder(WORLD, DIM))
                .resolve("surface/26.2/r.-1.0/s.-2.3.wsl.gz");
        Path foreign = MapTileArchive.root(store.scopedFolder(OTHER, DIM))
                .resolve("surface/26.2/r.-1.0/s.-2.3.wsl.gz");
        Files.createDirectories(foreign.getParent());
        Files.copy(source, foreign);
        MapView copiedAcrossScope = new ObservedMapRepository(store.scopedFolder(OTHER, DIM)).query(query);
        check(copiedAcrossScope.incomplete() && copiedAcrossScope.surfaceCells().isEmpty(),
                "foreign tile payload must be rejected even when copied to another valid scope directory");
        store.appendSurface(copy(sample, WORLD, DIM, "26.2", -1, 3));
        Files.write(source, new byte[] {0});
        MapView damaged = repository.query(new MapQuery(-16, 56, 8, 4, MapLayer.SURFACE_COLOR, 64, "26.2"));
        check(damaged.incomplete() && damaged.surfaceCells().size() == 256,
                "damaged surface tile stays unknown while validated neighbor remains visible");
    }

    private static void queueAndFailure(Path root, SurfaceChunkSample sample) throws Exception {
        var store = new CompletedChunkStore(root.resolve("queue"));
        var writer = new AsyncEvidenceWriter(store, WORLD, DIM, "26.2");
        check(!writer.offerSurface(copy(sample, OTHER, DIM, "26.2", -2, 3)), "writer rejects foreign world");
        check(!writer.offerSurface(copy(sample, WORLD, "minecraft:the_nether", "26.2", -2, 3)),
                "writer rejects foreign dimension");
        check(!writer.offerSurface(copy(sample, WORLD, DIM, "26.1.2", -2, 3)), "writer rejects foreign version");
        check(writer.offerSurface(sample), "surface should enter the existing bounded writer");
        check(writer.closeAndAwait(3000), "accepted surface must drain after normal stop");
        check(writer.writtenSurfaceChunks() == 1 && writer.failure() == null,
                "saved counter is backed by an actual complete tile");
        check(!writer.offerSurface(sample), "closed writer cannot silently accept new surface data");
        Path deniedRoot = root.resolve("not-a-directory");
        Files.writeString(deniedRoot, "controlled I/O failure");
        var failed = new AsyncEvidenceWriter(new CompletedChunkStore(deniedRoot), WORLD, DIM, "26.2");
        check(failed.offerSurface(sample), "I/O failure occurs asynchronously after queue acceptance");
        check(failed.closeAndAwait(3000), "failed writer must still terminate");
        check(failed.failure() != null && failed.writtenSurfaceChunks() == 0,
                "I/O error must be visible to fail-closed controller and never count as saved");
        var heights = new ArrayList<>(sample.heights());
        heights.removeLast();
        reject(() -> new SurfaceChunkSample(WORLD, DIM, "26.2", -2, 3, -64, 320,
                heights, sample.argb(), TIME), "partial samples cannot reach persistence API");
    }

    private static SurfaceChunkSample copy(SurfaceChunkSample sample, String world, String dim,
                                           String version, int x, int z) {
        return new SurfaceChunkSample(world, dim, version, x, z, sample.minY(), sample.maxYExclusive(),
                sample.heights(), sample.argb(), sample.observedAt());
    }

    private static DimensionProfile profile(String version) {
        return new DimensionProfile(WORLD, DIM, version, -64, 320, 63, true, false, null, TIME);
    }

    private static final class ControlledReader implements SurfaceSamplingCursor.Reader {
        boolean loaded = true;
        int reads, heights;
        @Override public boolean stillLoaded() { return loaded; }
        @Override public int surfaceY(int x, int z) { heights++; return x == 0 && z == 0 ? 67 : 65; }
        @Override public int argb(int x, int y, int z) {
            reads++;
            if (x == 0 && z == 0 && y > 64) return 0;
            return ((x + z) & 1) == 0 ? 0xFF6A9933 : 0xFFB27845;
        }
    }

    private static void reject(Runnable operation, String message) {
        try { operation.run(); }
        catch (IllegalArgumentException | IllegalStateException expected) { return; }
        throw new AssertionError(message);
    }

    private static void check(boolean okay, String message) {
        if (!okay) throw new AssertionError(message);
    }
}
