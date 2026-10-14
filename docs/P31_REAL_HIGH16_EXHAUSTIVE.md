# WorldSeedLab · P3.1 原版群系证据下的完整高16位枚举验收

> 基线：Minecraft Java 26.1.2 / 26.2，Java25，原生 Fabric / Mojmap。  
> 本轮目标：对 **P2.9 已证实的一个低48位候选**及**原版 Overworld 生成器假设**，在实际 Minecraft 保存的群系证据下逐项审计高16位 `0..65535`。
>
> **结果口径：** 只有本次 32 个分片全部完成、独立汇总器验证从 0 到 65,536 的连续覆盖，并且真实模型候选未超限，才算该受控实验“全域已检查”。未看到汇总器成功结果之前不允许宣称已经完成。

## 为什么不是 P3.0 的控制随机数演示

P3.0 已在 Minecraft 26.1.2 和 26.2 的**真实离线已知 Seed 世界**里读取保存的 `Status=FULL` 区块、提取真实 quart 群系，从结构随机放置反推低48后，用原版 `RandomState` / `MultiNoiseBiomeSource` 验证完整 Seed `2731`，并排除高16位 `1`。

P3.1 继续使用这份**实际保存的原版群系证据**，真正对全部65,536个高16位进行有限资源枚举，而不是用自行制造的伪群系或复制已知完整 Seed 为候选答案。

固定公开夹具：

| 参数 | 本轮 |
|---|---|
| 已知完整世界 Seed | `2731`（仅用于受控结果验证，不参与群系 FIT 判定） |
| P2.9 结构候选低48位 | `2731` |
| 实际 Minecraft 世界生成版本 | `26.1.2` 和 `26.2` |
| 生成器模型 | `minecraft:vanilla_overworld` |
| 观测来源 | 真实游戏生成后保存的 Anvil 完整区块，`section.Y=4` 的单群系 palette |
| 观测数量 | 5 组不同位置，空间距离至少 1024 方块 |
| FIT / HOLDOUT | 3 / 2 |
| 两个版本夹具 SHA-256 | `73b8e9915d422b9632c4d411d0dcd6f0f2150e920d1a1d18630b07fb5e4e9602` |
| 原始来源 | 2026-10-14 GitHub Actions [P3.0 真实世界验收](https://github.com/fxjcangku/WorldSeedLab/actions/runs/38506886029) |

以上两个 CSV 均位于 `src/test/resources/p31/`。公开受控 Seed 的实验坐标可以随开源仓库公开；不得将目标服务器的精确坐标上传到公共 PR。

## 真实生成器扫描与可审计分片

- `Full64High16ShardPlan` 对一个 low48 候选的连续完整域 `[0, 65536)` 按既定数量分片，所有区间无重叠无缺口；支持多个已验证 low48 候选但本轮只验收一个。
- `P31Full64ShardRunner` 每片调用目标版本真正的 `VanillaOverworldBiomeModel`，逐次初始化对应完整64位 `RandomState`，使用三个实测群系 FIT、两个留出 HOLDOUT 检查结果，候选值仍必须满足既有结构 low48 约束。
- 单轮运行有 CPU 最大计算数、总超时120秒、最多128个候选和取消保护。达到上限则**本片失败、不产出完成证明**。
- 每片只在 `build/p31-high16-shards/<version>/` 写入 UTF-8 清单；含版本、模型、CSV 内容 SHA、精确起止、实际求值数、下一索引、完成状态、所有候选64位十六进制和 FIT/HOLDOUT 次数。没有服务端配置外传或远程网络发送。
- GitHub Actions `P3.1 Exhaustive Vanilla Full64 High16` 运行 **16片 × 2版本 = 32个独立工作**；合并之前由 `verify_p31_high16_shards.py` 检查同版本各片连续、完整、计数精确、候选合法无重复、世界证据 SHA 一致、两版各自覆盖65,536位。
- 独立汇总器允许剩下多个模型一致的64位候选并如实列出，**不会因为控制真实 Seed 出现就强行宣称只有它一个**。仅当所有片覆盖且候选数为1才写 `uniqueInSpecifiedModel=true`。

## 运行方式

在配置正确的 JDK25/Gradle9.6.1 工程中：

```bash
gradle --no-daemon -PmcVersion=26.2 -Pp31ShardIndex=0 -Pp31ShardCount=16 p31Full64Shard
```

运行一个片**不等于**验证完高16全域。完整运行使用 GitHub Actions 新增的工作流，成功后得到 `P31-FullHigh16-Complete-Controlled-Vanilla` 的 `p31-full64-summary.json`；只有这个文件加上全部真实成功工作日志才构成全域覆盖的工程验收证据。

普通双版本 `gradle -PmcVersion=<version> clean build` 只运行 P3.1 的分片无缺口算法测试和使用真实群系的窄窗口正反例，不会因为正常构建自动产生大规模世界或试图破解服务器。

## 64位 Seed 的解释边界

这项全域扫描即使成功，结论也仅限于：**给定确切低48候选、假定原版 Overworld 生成器配置、采用这五处实际存档群系证据**的 65,536 个高16位候选均已检查。它不等于“破解所有 Minecraft 世界”、“恢复自定义地形服务器真正 Seed”或“依据种子确定真实矿点”。

完整远端客户端研究仍缺以下独立环节：正常可见完整结构轮廓转可靠起点、客户端 `runClient` 实际群系扫描、未知 Salt/多版本/自定义群系/地形噪声兼容性、更多真实完整负数 Seed 世界实测，以及世界修改矿石和反矿透视的独立证据。
