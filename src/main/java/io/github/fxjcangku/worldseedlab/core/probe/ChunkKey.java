package io.github.fxjcangku.worldseedlab.core.probe;

/** A complete chunk-scanning unit scoped to a particular vertical sampling stride. */
public record ChunkKey(int chunkX, int chunkZ, int verticalStride) {
    public ChunkKey {
        if (verticalStride != 4 && verticalStride != 8 && verticalStride != 16) {
            throw new IllegalArgumentException("invalid vertical stride");
        }
    }

    public String asJournalLine() {
        return chunkX + "," + chunkZ + "," + verticalStride;
    }

    public static ChunkKey parseJournalLine(String line) {
        String[] parts = line.split(",", -1);
        if (parts.length != 3) {
            throw new IllegalArgumentException("invalid completed chunk journal entry");
        }
        return new ChunkKey(Integer.parseInt(parts[0]),
                Integer.parseInt(parts[1]), Integer.parseInt(parts[2]));
    }
}
