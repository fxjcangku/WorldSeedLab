package io.github.fxjcangku.worldseedlab.core.probe;

import java.util.Iterator;
import java.util.LinkedHashSet;

/**
 * P3.6.2 有界已完成区块扫描历史，仅用于避免近期重复消耗材料扫描预算。
 *
 * <p>旧实现收集满4096个已扫区块就结束整轮自动取证；这里采用FIFO滚动窗口，
 * 在用户继续跑图时淘汰最早的已完成区块，不将“不在缓存”解释为“从未扫描”。
 * 缓存只存匿名作用域内的坐标长整型键，不写盘、不上传，也不认证实际地图覆盖率。
 */
public final class BoundedChunkHistory {
    private final int capacity;
    private final LinkedHashSet<Long> recent = new LinkedHashSet<>();
    private long evictions;

    public BoundedChunkHistory(int capacity) {
        if (capacity < 1 || capacity > 65_536) {
            throw new IllegalArgumentException("已扫区块缓存容量必须在1..65536以内");
        }
        this.capacity = capacity;
    }

    public boolean contains(long packedChunkKey) {
        return recent.contains(packedChunkKey);
    }

    /**
     * @return true 表示该区块不在当前窗口内，本次扫描新记入历史；
     *         false 表示仍在窗口中、重复完成不新增扫描批次。
     */
    public boolean remember(long packedChunkKey) {
        if (!recent.add(packedChunkKey)) return false;
        if (recent.size() > capacity) {
            Iterator<Long> oldest = recent.iterator();
            oldest.next();
            oldest.remove();
            evictions++;
        }
        return true;
    }

    public int retained() { return recent.size(); }
    public int capacity() { return capacity; }
    public long evictions() { return evictions; }

    /** 新会话必须清空，不得将不同世界或维度的区块混入同一扫描历史。 */
    public void clear() {
        recent.clear();
        evictions = 0;
    }
}
