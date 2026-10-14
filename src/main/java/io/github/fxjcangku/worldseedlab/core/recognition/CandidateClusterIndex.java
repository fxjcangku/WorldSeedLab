package io.github.fxjcangku.worldseedlab.core.recognition;

import java.util.HashMap;
import java.util.Map;

/**
 * 同一建筑横跨多个区块可能产生重复指纹。邻近 2 区块内同类型聚合为一组。
 * 仅用于会话统计，既不声称真实建筑数量也不会推导结构起始点。
 */
public final class CandidateClusterIndex {
    private final Map<String, Map<Long, Integer>> kinds = new HashMap<>();
    private int nextClusterId;

    public int add(String kind, int chunkX, int chunkZ) {
        Map<Long, Integer> groups = kinds.computeIfAbsent(kind, ignored -> new HashMap<>());
        for (int dx = -2; dx <= 2; dx++) {
            for (int dz = -2; dz <= 2; dz++) {
                Integer known = groups.get(pack(chunkX + dx, chunkZ + dz));
                if (known != null) {
                    groups.put(pack(chunkX, chunkZ), known);
                    return known;
                }
            }
        }
        int newId = ++nextClusterId;
        groups.put(pack(chunkX, chunkZ), newId);
        return newId;
    }

    public int totalGroups() { return nextClusterId; }

    private static long pack(int x, int z) {
        return ((long) x << 32) | (z & 0xFFFFFFFFL);
    }
}
