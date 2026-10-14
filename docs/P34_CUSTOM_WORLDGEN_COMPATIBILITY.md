# WorldSeedLab P3.4 · 自定义地形兼容性与证据防误报

> 2026-10-14 · Minecraft 26.1.2 / 26.2 · Java 25 · Fabric / Mojmap  
> 当前状态：研发分支 [PR #21](https://github.com/fxjcangku/WorldSeedLab/pull/21)；真实双版本 CI 的最终通过情况必须另外核对。  
> **受控生成结果不能冒充用户目标服务器，也不意味着所有服务器都可恢复完整64位Seed。**

## 研究问题和必要前提

服务端可以只修改地形高度、改群系、替换噪声、变更 RandomSpread Salt，甚至将多年以前的区块预生成后继续保留。它仍会拥有某个保存用的 Seed，但 Seed 与客户端可见世界之间的具体映射依赖服务端实际生成器。普通 Fabric 客户端不掌握后台完整配置，不能从可见建筑“证明”结构集合或 Salt。

因此本阶段单独审核两个条件模型：

1. **结构位置**：P2.9 真正完整建筑外轮廓经匹配版本的受控校准，推导为有限、离散的起点 Region/Chunk 候选。使用 P2.7 RandomSpread 原创算法和研究者**显式提供**的 spacing/separation/distribution/Salt 验证；StructureStart 只在自建已知 Seed 服务端的独立测试中可作为真值。
2. **quart 群系**：P3.2 客户端真实已加载 FULL 区块、玩家手动命令得到的 CSV。只有显式选择原版 Overworld 假设时使用 Mojmap 对应版本的 MultiNoise/RandomState 模型。对自定义单群系/噪声配置不得默认套原版高16模型。

低48位已通过 Z3 的结构候选，仍需各自结构 FIT/HOLDOUT；完整64位必须有独立群系 FIT/HOLDOUT 且审计全部高16搜索域。P3.4 的**单候选兼容性审计**不等价于完整位域搜索。

## 四种判断状态

| 枚举 | 语义 | 严格限制 |
| --- | --- | --- |
| `MODEL_CONSISTENT` | 在声明的 Seed、规则与可信证据中，FIT及独立HOLDOUT均符合 | 只表示指定模型一致，不证明后台生成器或真实Seed |
| `INSUFFICIENT_EVIDENCE` | 没有足够独立可信结构Region或群系点 | 不能用人工建筑/材料簇填满证据 |
| `MODEL_CONFLICT` | 可靠观测至少一处与指定规则数学结果冲突 | 仅否定该**模型假设**，不等于服务器没有Seed |
| `GENERATOR_UNKNOWN` | 未知Salt、变更过的生成历史或未知/自定义群系模型 | 不能硬套原版Seed反演，不能偷报冲突 |

同一 HMAC 世界指纹并不证明所有老区块使用同一时期的生成器；历史未知必须显式标注。重复Region不得同时充当FIT与HOLDOUT。

## 已开发的只读实现

- `P34WorldgenCompatibilityAudit`：结构与群系独立审核、来源和重复校验、匿名中文报告。每个审计只读给定不可变数据，无 Tick/GUI、网络或自动导出。
- `P34OfflineAuditCli`：接受P3.2真实手动客户端CSV、P2.9结构约束配置与一个外部已产生的完整64位模型候选；需要人工明确生成历史/Salt/群系模型假设。候选的结构low48真实性与65,536高位完整性**不由本命令授予**。
- `controlledWorldBiomes`：只接受 `CONTROL_WORLD_OBSERVED`，拒绝将 Anvil 服务端真值伪装成 `CLIENT_OBSERVED`。
- `p34CompatibilitySmokeTest`：错误Salt、混杂历史、人工建筑、空间重复、跨世界、错误高16、原版实际FULL群系回归；双版本默认`check`。

本机离线实验入口（所有证据文件请自行保存在本地，**禁止发布私人坐标**）：

```bash
gradle -PmcVersion=26.2 p34OfflineAudit \
  -Pp34ClientCsv=/local/private/client-quart.csv \
  -Pp34StructureInput=/local/private/structure.properties \
  -Pp34CandidateHex16=0000000000000aab \
  -Pp34History=UNKNOWN \
  -Pp34Salt=UNKNOWN \
  -Pp34Biomes=UNKNOWN_OR_CUSTOM
```

`CONFIRMED_SINGLE_EPOCH` 与 `EXPLICIT_VANILLA_HYPOTHESIS` 均仍是**研究者输入声明**，不能由普通客户端单独证明服务端历史或生成器配置。默认 `UNKNOWN` 即拒绝过度解读。

## 自建真实服务器实验（A/B）

CI `.github/workflows/p34-custom-worldgen.yml` 在两版各自执行：

1. 新建公开已知 Seed=2731 的隔离原版 Minecraft **superflat 自定义沙漠地形**，明确指定 `structure_overrides=minecraft:desert_pyramids`。仅受控 CI 使用本机 RCON `/locate` / `/forceload` 生成结构区域。
2. 从新生成的真实 Anvil `Status=FULL` 区块读取 section 的 sandstone/sand 层，验证世界**确实使用了自定义平坦地形**，不是只看配置字符串。
3. 从服务端存档提取沙漠神殿真实 `StructureStart` NBT，逐项核对原版 RandomSpread 前向潜在区块，另用错误Salt作数学反例。
4. 从保存的 FULL 生物群系读取5个独立quart点；检验它们属于实际生成的自定义沙漠单群系，与原版 MultiNoise 在这些位置按 Seed=2731 的预测冲突。
5. 把结构与群系结果分别报告；**结构位置可能保留原版规则，但完整64位原版群系反演不再成立**。这既不是普通客户端可见轮廓、也不是目标私人服务器证据。

**验收状态：** 本文先记录设计与可执行脚本，真实 CI 结果须以最新 Actions 运行结束后的实际job日志为准；不能把排队、编译中或失败描述成成功。

## 仍未完成

- 自定义地形服务器普通 Fabric 客户端手工取证与**完整建筑可见外轮廓**，须独立于后台NBT。
- 未知 Salt 的多假设搜索及自定义结构集本体规则真正修改后的实验。
- 混合预生成/旧区块的实际控制世界与分段检测，不可仅凭世界HMAC推断时代相同。
- 非零高16、负数64位真实世界全部65,536高位复查；原版模型和自定义模型需分别成立。
- 对用户目标服的所有结论仍只能来自用户授权的正常客户端证据，拒绝自动探测新区块、后台读取与上传私人坐标。
