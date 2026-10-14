# WorldSeedLab · P2.7 原创种子数学引擎

> 版本目标：Minecraft Java 26.1.2、26.2 · Java 25 · Fabric · Mojmap
> 研发状态：RandomSpread 前向数学模型、低 48 位有界候选搜索、训练/留出样本检查与独立对照测试；**尚非服务器完整 Seed 破解器**。

## 为什么从 random_spread 开始

这一类原版结构放置使用世界种子、Region 位置和 StructureSet 的间距、分离距离、分布方式、Salt 决定潜在的结构起始区块。自定义地形可能保留原版结构放置规律，也可能修改随机算法或结构集合参数。

因此 P2.7 的第一项验收是：原创前向公式与**两版 Minecraft 本体的 `RandomSpreadStructurePlacement.getPotentialStructureChunk`** 逐项一致。即使全部通过，也只验证了**潜在的放置区块**，不证明建筑最终通过群系筛选、频率过滤、排除规则及实际生成阶段。

## 已实现的独立算法

| 组件 | 内容 | 不包含 |
|---|---|---|
| `LegacyLcg48` | 48 位线性同余状态、受界 `nextInt` 拒绝采样 | 非 Legacy RNG |
| `RandomSpreadSpec` | StructureSet ID、spacing、separation、LINEAR/TRIANGULAR、salt | 秘密服务器生成参数的读取 |
| `RandomSpreadMath` | 负 region 坐标、结构候选 chunk、世界种子低 48 位 | 完整世界生成 |
| `StartPlacementConstraint` | 控制世界精确起点或几何估计的起点范围；分 FIT/HOLDOUT | 玩家站位自动提升成结构起点 |
| `CandidateSeedSearch` | 低 48 位窗口枚举、FIT 过滤、HOLDOUT 验证、取消/超时/续跑 | 一次完成全部 2^48 的快速数学逆向 |
| `SolverResearchInput` | 显式离线属性文件、参数与作用域校验 | 自动读取服务器 Seed |
| `SolverResearchCli` | 离线显示低 48 位候选及下一搜索偏移 | 在游戏主线程暴力搜索 |

## 结构种子和完整世界 Seed

**当前输出是 `LOW48` 结构候选**，例如一个 12 位十六进制数字，绝不能直接说它就是 64 位完整世界 Seed。对于只利用低 48 位的放置模型，不同的 64 位种子可能产生同样的潜在结构起点。自定义服务器还可能使用独立 RNG、结构盐值或不同生成器；即使某个候选符合已知结构，也不代表该数字是服务端真实 `level.dat` 的 Seed。

## 自定义服务器最重要的歧义：Seed 与 Salt 可以相互抵消

对于当前研究的 legacy random_spread 放置模型，Region 混合输入含有 `worldSeed + structureSalt` 这一加法组合。**如果结构集合的 Salt 完全未知，仅靠这一集合的结构位置，无法把 Seed 和 Salt 唯一拆开。**

例如 `seed - 19` 与 `salt + 19` 对相同 Region 会产生同一套随机放置候选。P2.7 已加入这个数学等价关系的回归测试。

这意味着：对于故意修改过 Salt 的自定义服务器，即使所有观察到的结构位置都通过某个候选模型，也**未必得到服务器后台真实 Seed**。后续需要多个可靠、独立、参数已确认的结构集合，或其它生成证据，才可能消除歧义。不能靠扩大暴力算力解决缺少信息的问题。

## 为什么不自动使用 P2.6 建筑标记

P2.6 观察到的沙岩、海晶石或木质沉船，只是**材料候选**；玩家人工标记的 XYZ 也不等于 `structure start chunk`。P2.7 不将这种位置自动转化为数学起点。必须先通过已知种子的受控世界以及后续的建筑几何/边界框识别，推断准确起始区块或带边界的候选区域，并明确记录不确定性。否则假建筑比假矿更容易制造错误 Seed。

## 离线搜索怎么调用

在安装 Java 25、Gradle 9.6.1 后，可把自己**受控测试世界**的结构起点参考填入 `solver-input.properties`。以下格式为解释字段的研究模板，示例世界指纹为模拟值，**不能作为服务器的真值**：

```properties
worldFingerprint=ffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff
gameVersion=26.2
dimension=minecraft:overworld

models.count=1
model.0.id=minecraft:research_set
model.0.spacing=32
model.0.separation=8
model.0.distribution=LINEAR
model.0.salt=-42

constraints.count=3
constraint.0.structureSet=minecraft:research_set
constraint.0.minChunkX=-159
constraint.0.minChunkZ=266
constraint.0.maxChunkX=-159
constraint.0.maxChunkZ=266
constraint.0.origin=REFERENCE_FROM_CONTROL_WORLD
constraint.0.split=FIT

constraint.1.structureSet=minecraft:research_set
constraint.1.minChunkX=420
constraint.1.minChunkZ=-527
constraint.1.maxChunkX=420
constraint.1.maxChunkZ=-527
constraint.1.origin=REFERENCE_FROM_CONTROL_WORLD
constraint.1.split=FIT

constraint.2.structureSet=minecraft:research_set
constraint.2.minChunkX=809
constraint.2.minChunkZ=115
constraint.2.maxChunkX=809
constraint.2.maxChunkZ=115
constraint.2.origin=REFERENCE_FROM_CONTROL_WORLD
constraint.2.split=HOLDOUT

search.startLow48=0
search.endExclusive=5000
search.maxEvaluations=5000
search.maxResults=64
search.timeoutMillis=30000
```

**说明：** 这份合成样本以已知低 48 位 Seed `2731` 生成，是测试工具输入格式的示例，不是任何服务器的实际结构坐标。参数名称是研究用 `minecraft:research_set`，不代表某种真实原版结构集合的 Salt。

```bash
gradle -PmcVersion=26.2 -PsolverInput=/绝对路径/solver-input.properties p27ResearchCli
```

程序输出窗口完成状态、已评估候选数、下一低 48 位搜索偏移、被留出样本拒绝的候选数，以及符合模型的 `LOW48` 候选。可把下一偏移复制到输入文件的 `search.startLow48`，继续搜索其它区间。**完成一个有限窗口不代表搜索了全部 2^48 个候选。**

### 证据填写规则

- `structureSet` 必须对应要验证的**结构集合**，不是仅看建筑外观随意填一个类型。
- `REFERENCE_FROM_CONTROL_WORLD` 仅允许在确认了已知 Seed 且能核对真正起始位置的测试世界使用；必须精确到单个区块。
- `GEOMETRY_ESTIMATE` 则允许填写有边界的可能结构起点范围，但不得把玩家观察点直接填成区块起点。
- `FIT` 用于筛候选；`HOLDOUT` 必须来自**不同 region 的独立证据**，且不会参与搜索前过滤。
- 同世界、同维度、同版本才能组合；同结构集合在相同 region 的多份观察不能重复作为独立证据。
- 没有可靠参数时，不能假装服务器使用模板中的 Salt；即使候选存在也只能看作某个假设模型的匹配结果。

## 成功标准和已知限制

1. `p27SolverSmokeTest`：Java RNG 与 JDK 对照、负坐标、候选搜索、恢复游标、超时/取消和独立留出检验。
2. `p27MinecraftParityTest`：**直接实例化目标版本 Minecraft 类**，比较 LINEAR/TRIANGULAR 模型和负 region 的候选区块；两版都应得到一致结果。
3. `p27InputSmokeTest`：离线清单解析、作用域隔离、人工标记拒绝和有界文件限制。
4. 之后仍需使用已知 Seed 世界**实际生成的结构起点真值**做独立验收，区分“potential placement”与“实际生成”。
5. 大范围 48 位结构种子逆向需要 P3 的更高效约束枚举/求解策略，当前逐窗口搜索不应冒充实用的完整空间逆向器。
6. 64 位完整 Seed 的恢复需要独立的高位信息或其它有效约束；自定义群系与假矿不能未经模型确认直接用于筛选。

本阶段没有发布正式 Release；真实 GUI / runClient 测试与服务器外部实验另行验收。