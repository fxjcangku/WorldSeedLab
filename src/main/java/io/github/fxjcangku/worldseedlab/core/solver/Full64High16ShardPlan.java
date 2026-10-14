package io.github.fxjcangku.worldseedlab.core.solver;

/**
 * P3.1 完整高16位有限域分片规则。
 *
 * <p>与 GitHub Actions 并行任务数量无关：任一 low48 候选集合的全部高16位
 * 使用一个连续索引区间，确定性分割成没有重叠、没有遗漏的半开子区间。
 * 所有分片完成之前，严禁声称完整64位候选域已经穷尽。
 */
public final class Full64High16ShardPlan {
    public static final int MAX_SHARDS = 256;

    private Full64High16ShardPlan() {}

    public record Partition(int shardIndex, int shardCount,
                            long fromIndex, long endExclusive, long domainSize) {
        public Partition {
            if (shardCount < 1 || shardCount > MAX_SHARDS
                    || shardIndex < 0 || shardIndex >= shardCount
                    || domainSize < 1 || fromIndex < 0
                    || endExclusive <= fromIndex || endExclusive > domainSize) {
                throw new IllegalArgumentException("P3.1 分片范围越界");
            }
        }

        public long length() { return endExclusive - fromIndex; }

        /** 单轮搜索需要的最大求值量，不允许从用户输入伪造扫描量。 */
        public Full64BiomeCandidateSearch.SearchBudget budget(
                long timeoutMillis, int maximumResults
        ) {
            return new Full64BiomeCandidateSearch.SearchBudget(
                    fromIndex, endExclusive, length(), timeoutMillis, maximumResults);
        }
    }

    public static Partition partition(
            int low48Candidates, int shardIndex, int shardCount
    ) {
        if (low48Candidates < 1 || low48Candidates > 32
                || shardCount < 1 || shardCount > MAX_SHARDS
                || shardIndex < 0 || shardIndex >= shardCount) {
            throw new IllegalArgumentException("low48候选数量或分片索引非法");
        }
        long domain = (long) low48Candidates * Full64BiomeCandidateSearch.HIGH16_COUNT;
        long from = domain * shardIndex / shardCount;
        long to = domain * (shardIndex + 1L) / shardCount;
        if (from == to) {
            throw new IllegalArgumentException("高16位分片不能是空区间");
        }
        return new Partition(shardIndex, shardCount, from, to, domain);
    }
}
