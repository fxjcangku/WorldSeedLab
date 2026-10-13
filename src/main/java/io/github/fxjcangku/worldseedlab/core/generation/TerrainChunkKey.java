package io.github.fxjcangku.worldseedlab.core.generation;

/** P2 地形完成索引只按区块坐标去重，不受 P1 纵向群系采样精度影响。 */
public record TerrainChunkKey(int x, int z) {
    public String asLine() {
        return x + "," + z;
    }

    public static TerrainChunkKey parse(String line) {
        String[] tokens = line.split(",", -1);
        if (tokens.length != 2) {
            throw new IllegalArgumentException("invalid terrain chunk index");
        }
        return new TerrainChunkKey(Integer.parseInt(tokens[0]), Integer.parseInt(tokens[1]));
    }
}
