package io.github.fxjcangku.worldseedlab.core.probe;

/**
 * Traverse the transmitted biome grid at quarter resolution (4x4 horizontally).
 * Each vertical slice is an observation; at stride 8/16 some quart-height cells
 * are intentionally omitted and the report must preserve that sampling stride.
 */
public final class ChunkSamplingCursor {
    private final ChunkKey chunk;
    private final int minY;
    private final int maxYExclusive;
    private int sampleY;
    private int quartX;
    private int quartZ;
    private boolean finished;

    public ChunkSamplingCursor(ChunkKey chunk, int minY, int maxYExclusive) {
        if (maxYExclusive <= minY || maxYExclusive - minY > 4096) {
            throw new IllegalArgumentException("invalid dimension build height");
        }
        if ((minY & 3) != 0) {
            throw new IllegalArgumentException("minimum height must align to biome quart grid");
        }
        this.chunk = chunk;
        this.minY = minY;
        this.maxYExclusive = maxYExclusive;
        this.sampleY = minY + 2;
    }

    public boolean hasNext() {
        return !finished;
    }

    public Sample next() {
        if (finished) {
            throw new IllegalStateException("chunk already scanned");
        }
        Sample point = new Sample(
                (chunk.chunkX() << 4) + (quartX << 2) + 2,
                sampleY,
                (chunk.chunkZ() << 4) + (quartZ << 2) + 2
        );
        sampleY += chunk.verticalStride();
        if (sampleY >= maxYExclusive) {
            sampleY = minY + 2;
            quartX++;
            if (quartX == 4) {
                quartX = 0;
                quartZ++;
                if (quartZ == 4) {
                    finished = true;
                }
            }
        }
        return point;
    }

    public record Sample(int x, int y, int z) {
    }
}
