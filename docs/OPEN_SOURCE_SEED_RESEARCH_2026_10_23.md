# 开源种子研究调研与 WorldSeedLab 自研方向

读取日期：2026-10-23（Asia/Shanghai）。本报告只记录本次核查到的公开仓库文档、源码片段、测试及许可证声明，不代表已运行这些外部工具，也不代表目标服务器已被破解。

本项目核对基线为 `origin/main` 对应的本地提交 `4b47d8f`（P4.2），以及读取时尚未合并的 [P4.3 PR #39](https://github.com/fxjcangku/WorldSeedLab/pull/39)，其头提交为 `f1d6ee6bdfa7daad6417b9023f0ef10631619950`。外部仓库均固定记录下表所列提交，后续更新不能自动沿用本报告的兼容结论。

## 1. 结论

可以借鉴公开的数学思路、模型分层和验证方法，继续原创实现 WorldSeedLab。最有价值的缺口是：**未知结构放置参数的候选搜索、未知 Salt 下有效结构状态的明确建模，以及不依赖结构 Salt 的独立证据路径**。

本次未找到已通过可复核证据证明能处理任意未知 NoiseRouter、未知独立 Salt、独立维度 Seed 和后处理的通用工具。仅替换客户端登录种子字段、修改结构网格参数、修改各结构 Salt、替换完整地形生成器，是不同问题，不能统一宣传为“已破解自定义种子”。

下面的许可证只记录所读仓库的声明。WorldSeedLab 仍遵循自己的原创实现约束：不复制、移植第三方求解器实现，不抄第三方配置；参考对象不能取代目标 Minecraft 26.1.2 / 26.2 本体对照。

## 2. 核查对象与实际边界

| 项目及读取提交 | 版本与许可证声明 | 已核实的用途与限制 | 对本项目的价值 |
| --- | --- | --- | --- |
| [SeedCracker Resilient](https://github.com/egarrr0808/seedcracker-resilient/tree/73f0ce39c202b21187a060c45570b5270da7f2f4)；`master`，`73f0ce3` | Fabric 26.2、Java 25；MIT | 将登录/重生的种子派生字段视作不可信；有显式放置参数、已知数据包模型切换、网格分析和村庄邻近约束。作者声称在修改登录字段的受控 Paper 26.2 上恢复成功；明确指出 anti-datapack 目前仅单测/构建，尚需受控游戏验证。任意自定义地形、未知独立 Salt 等不在已证明范围。 | 优先参考参数候选搜索、模型切换、冻结证据快照和失败分类；不继承其成熟度或外部模型配置。 |
| [SeedCrackerX](https://github.com/19MisterX98/SeedcrackerX/tree/4f4067dd85d63ab99bf529f6aa04180a468a693c)；`master`，`4f4067d` | README 列出 26.1—26.1.2 对应 2.16.0、26.2 对应 2.16.1；MIT | 分离结构低48位与完整64位候选，使用不同结构和装饰证据。README 的信息位和时间是其流程说明，不能直接当成独立证据数或本项目性能保证。 | 研究分阶段剪枝和不同随机过程的互证；不能复制其或依赖库的求解算法。 |
| [cubiomes](https://github.com/Cubitect/cubiomes/tree/e61f90580cbdd883214a8054670dacae655e59c0)；`master`，`e61f905` | MIT；该提交的 `biomes.h` 最高为 1.21 Winter Drop，未列 26.1.2 / 26.2 | 快速群系和结构候选前向计算。README 明确没有完整方块级生成；受表面高度等条件影响，某些结构候选会是假阳性。 | 参考批量前向、缓存、廉价约束先筛选。不能直接作为两目标版本真值，更不能将预测地图填进已探索地图。 |
| [LattiCG](https://github.com/mjtb49/LattiCG/tree/11e007edf4485dc236f26e71bc4420ec157c5430)；`master`，`11e007e` | MIT；JavaRandom 数学工具，无特定 Minecraft 兼容保证 | 将随机数输出的不等式约束转换为格点搜索，以规约和分支限界找内部状态。它没有自动识别未知 Minecraft 生成器的能力。 | 作为公开数学方法入口；不同区域重新初始化的 RNG 观测不能未经推导直接拼成一条连续序列。 |
| [BedrockSeedCracker](https://github.com/MiranCZ/BedrockSeedCracker/tree/b1c54dd40a3a9a7efa9905d9b72d6aa25dffaa34)；`master`，`b1c54dd` | MIT；`gradle.properties` 是 Minecraft 1.20.2 / Yarn | 此处 Bedrock 指基岩方块，项目针对 Java 版。README 描述下界基岩约束低48、主世界基岩筛高16的路径；本次未核到 26.1.2 / 26.2 实机验证。 | 有价值的独立证据研究方向，必须从两目标版 Mojmap 重新校准；不能直接引入旧 API 或跨维度假定同 Seed。 |
| [Nether Bedrock Cracker](https://github.com/19MisterX98/Nether_Bedrock_Cracker/tree/74da51b969ece1aa126ae9f7c62faf9541f43f11)；`gui`，`74da51b` | LGPL-3.0；README 描述 Java 1.18+ 下界基岩 | 主世界使用不同 RNG，不能套用该下界计算。项目记录了旧 Paper 基岩差异及手工指定世界种子对其完整种子搜索模式的限制。 | 参考下界顶层/底层独立观测、历史版本差异及错误模型负例；“1.18+”文字不等于本次目标版已经验收。 |
| [xaeroseed](https://github.com/jaysyrk/xaeroseed/tree/68637218955b77c7b0ff1281ad044ed07077e34d)；`main`，`6863721` | GPL-3.0；依赖 cubiomes，README 未提供本次两目标版独立验证 | 从 Xaero 本地地图提取材料、群系后离线搜索；有低位剪枝检查及独立要塞确认思路。README 仍假定相容的结构放置。 | 参考离线存量证据处理与留出验证；不能采用“稀有材料必定天然”、放宽起点容差或固定匹配比例就认定真值的规则。 |
| [SeedShield](https://github.com/MuNET-OSS/SeedShield/tree/c595e7d7e4c39d7db4e343b339c0266b4d14f636)；`master`，`c595e7d` | MIT；作者声明 Paper/Spigot 1.20.5+ | 这是服务端防护项目。所读代码按密钥、世界种子、结构类型派生32位 Salt，并单独修改同心环种子。README 的“完全防御”是作者主张，本报告没有验证该主张。 | 作为未知独立 Salt、结构间隔离、旧新区块混合的反例设计来源，不能把保护插件安装情况当成已知。 |

主要证据入口：

- [Resilient README：版本、支持范围和实测限制](https://github.com/egarrr0808/seedcracker-resilient/blob/73f0ce39c202b21187a060c45570b5270da7f2f4/README.md)、[原理和信任边界](https://github.com/egarrr0808/seedcracker-resilient/blob/73f0ce39c202b21187a060c45570b5270da7f2f4/docs/how-resilient-seed-recovery-works.md)、[MIT 声明](https://github.com/egarrr0808/seedcracker-resilient/blob/73f0ce39c202b21187a060c45570b5270da7f2f4/LICENSE)。
- [SeedCrackerX 版本和使用说明](https://github.com/19MisterX98/SeedcrackerX/blob/4f4067dd85d63ab99bf529f6aa04180a468a693c/README.md)、[MIT 声明](https://github.com/19MisterX98/SeedcrackerX/blob/4f4067dd85d63ab99bf529f6aa04180a468a693c/LICENSE)。
- [cubiomes 版本枚举](https://github.com/Cubitect/cubiomes/blob/e61f90580cbdd883214a8054670dacae655e59c0/biomes.h)、[前向功能与结构假阳性限制](https://github.com/Cubitect/cubiomes/blob/e61f90580cbdd883214a8054670dacae655e59c0/README.md)。
- [LattiCG 数学问题定义](https://github.com/mjtb49/LattiCG/blob/11e007edf4485dc236f26e71bc4420ec157c5430/README.md)。
- [BedrockSeedCracker 路径说明](https://github.com/MiranCZ/BedrockSeedCracker/blob/b1c54dd40a3a9a7efa9905d9b72d6aa25dffaa34/README.md)、[实际构建版本](https://github.com/MiranCZ/BedrockSeedCracker/blob/b1c54dd40a3a9a7efa9905d9b72d6aa25dffaa34/gradle.properties)。
- [Nether Bedrock Cracker 范围与例外](https://github.com/19MisterX98/Nether_Bedrock_Cracker/blob/74da51b969ece1aa126ae9f7c62faf9541f43f11/README.md)。
- [xaeroseed 方法、许可证和限制](https://github.com/jaysyrk/xaeroseed/blob/68637218955b77c7b0ff1281ad044ed07077e34d/README.md)。
- [SeedShield 说明](https://github.com/MuNET-OSS/SeedShield/blob/c595e7d7e4c39d7db4e343b339c0266b4d14f636/README.md)、[实际 Salt 与同心环处理代码](https://github.com/MuNET-OSS/SeedShield/blob/c595e7d7e4c39d7db4e343b339c0266b4d14f636/src/main/java/com/seedshield/SeedShieldPlugin.java)。

## 3. 本项目已经具备什么

| 阶段 | 核查到的现有能力 | 仍然不能推出的结论 |
| --- | --- | --- |
| [P3.9](P39_REAL_CLIENT_SEED_RECOVERY.md) | 读取真实客户端完整21×21沙漠神殿轮廓，审计来源和世界隔离；不同 Region 的 FIT/HOLDOUT；本机 Z3 低48候选经精确 Java RNG 复核。 | 天然来源、目标服模板、生成历史及 Salt 未证实；有限拒绝路径/预算不能充当完整无解证明。 |
| [P4.0](P40_CUSTOM_CLIMATE_FORWARD.md) | 目标版 Mojmap RandomState 前向；在显式原版 Overworld NoiseRouter 假设及用户气候参数表下检验给定完整64位候选。 | 不能从未知 NoiseRouter 或群系名字生成真实 Seed；不能自动认证参数表属于目标服。 |
| [P4.1](P41_CONDITIONAL_CUSTOM_HIGH16.md) | 将已经通过 P3.9 复核的低48候选串接到条件高16分片、预算与摘要绑定续跑。 | 前提不成立时不能启用；分片未全部完成不能称全域唯一；条件完整64位不是服务端真值。 |
| [P4.2](P42_GENERATOR_IDENTIFIABILITY.md) | 对已保存 P1/P2/STRATA 做身份、重复冲突、空间证据覆盖及候选元数据可证伪审计。 | 未被证伪不等于生成器已识别；材料、海床高度或非原版群系不能冒充独立前向模型。 |
| [P4.3 待合并分支](https://github.com/fxjcangku/WorldSeedLab/blob/f1d6ee6bdfa7daad6417b9023f0ef10631619950/docs/P43_SPATIAL_CLIMATE_FINGERPRINT.md) | 对用户给定1—8个完整64位假设计算六维气候场，以空间独立 FIT/HOLDOUT 检查自定义群系标签的可预测性，保留探索性标识。 | 不能产生未知 Seed 候选，也不能凭较高分数证明真实 NoiseRouter、生成器或 Seed。 |

因此，继续只给已知候选增加评分不能补上“候选从哪里来”的缺口。当前最值得投入的是扩展可验证的候选产生条件，同时维持现有独立复核链。

## 4. 未知 Salt 的 E48 研究：范围必须先说清

以下是对已验证的 Legacy RandomSpread 加盐模型作出的数学推论，不是直接复制任何外部工具实现。令 `W` 是世界 Seed 的低48位，`S` 是某一结构集合的固定 Salt，则其区域随机初始化中的可变项可写为：

```text
E48 = (W + S) mod 2^48
区域初始化项 = E48 + 区域X项 + 区域Z项  （mod 2^48）
```

在间距、分布、版本和几何条件固定时，同一结构集合的观测首先约束的是 `E48`。若 Salt 未知，合法域中的 `W + δ` 与 `S - δ` 可以给出相同 E48；单独恢复一个 E48 不等于恢复真实 W。Salt 的整数取值范围可约束这种歧义，但不能自动消除它。

建议作为独立、显式离线研究模式实现：

1. 默认已知盐流程继续保持原语义；未知盐模式必须由用户明确选择，不暗中替换默认假设。
2. 仅对同一世界、维度、版本、结构集合和固定放置规则建立 E48 约束。不同结构集合拥有独立未知盐时，不得把它们当成共享 E48。
3. 只有已经通过现有完整轮廓和校准门控的观测才能进入；仍需不同 Region 的独立 FIT/HOLDOUT。
4. 输出明确写成“条件有效结构状态候选”，同时保留 `WORLD_LOW48=UNKNOWN`、`SALT=UNKNOWN`、`FULL64=UNKNOWN` 的语义。不得把 E48 填入现有高16搜索的世界低48字段。
5. 每个候选仍由完整 Java RNG 前向复核；保留有界拒绝重抽、超时、结果上限和域完成状态。
6. 后续只有独立确认的盐锚点、另一条已校准随机过程或明确有界联合参数模型，才可能把有效状态继续约束到真实低48；未知完整生成器仍不可自动套原版高16模型。

这项研究可以让未知固定盐场景保留可检验的结构信息，不能承诺直接恢复未知自定义服务器的世界种子。

## 5. 后续优先级

### 第一优先级：结构放置参数兼容候选与 E48

未知 spacing/separation 时，可以先枚举有界参数候选，使用以坐标原点锚定的区域网格与负数向下取整，排除把两个已确认不同结构起点放到同一区域的模型，再结合实际偏移给 separation 的兼容区间。

正观测中尚未出现某个最大偏移，不能证明该偏移不允许出现；可见结构还受到群系、地形和频率筛选。因此不能凭两点距离 GCD 认定 spacing，不能从这些正观测单独认定精确 separation、frequency 或 Salt。起点是离散集合时，参数候选也必须保持集合约束，不能退化为任意外包矩形。

先在有限模型集合中复用 P3.9 的 SMT 和 Java 验证，再比较不同模型的完整性和独立 HOLDOUT；证据不足、模型冲突和预算耗尽分别报告。E48 与参数搜索都是待独立实现、待验证的方向，本报告不把它们列为基线已完成能力。

### 第二优先级：基岩或装饰的独立候选路径

研究不受结构 Salt 直接控制的、普通客户端真实可观察且前向过程可校准的证据。基岩是本次发现的具体方向，但必须先逐版核查 Mojmap 中实际随机过程、surface rule、维度种子关系和历史差异，再建立受控世界正反例。

下界顶层与底层可以提供不同约束；主世界模型必须单独校准。不能因为两个维度来自同一服务器就混合成同 Seed，也不能把只收到的方块视作未经后处理的天然真值。未知自定义表面规则或修改后的方块仍应拒绝作确定性证据。

### 第三优先级：模型切换与冲突诊断

每一轮求解使用冻结证据快照和明确模型摘要；新增待处理观测留到下一轮，切换模型重新构造约束。诊断应指向矛盾的观测/假设，不能悄悄删除不吻合数据来制造成功。未建模的生成器、旧新区块混合与坐标识别错误应保持不同候选原因。

P4.2 的只读审计和 P4.1 的摘要绑定续算可复用在这条流程中。任何新增缓存和索引都应沿用项目现有证据作用域，不新增与地图归档重复的格式。

## 6. 独立验证清单

外部代码中的测试只能帮助列出需要验证的问题，不能作为 WorldSeedLab 已通过的测试。

- **网格与参数**：负坐标边界、同区域互斥、separation 保留多个合法值、线性/三角分布、离散起点集合跨区域。外部 [PlacementInferenceTest](https://github.com/egarrr0808/seedcracker-resilient/blob/73f0ce39c202b21187a060c45570b5270da7f2f4/src/test/java/kaptainwutax/seedcrackerX/util/PlacementInferenceTest.java) 覆盖了前三类基本情况，可独立设计不同样本复核。
- **未知盐语义**：同一 E48 对应多组合法 W/S；不同结构集合的独立盐不得共享状态；E48 不得进入世界高16流程；已知盐默认结果保持原行为。
- **剪枝正确性**：对照两目标版真实 RNG，核查拒绝重抽和极端种子；优化不能排除真实候选。外部 [TimeMachineLiftingTest](https://github.com/egarrr0808/seedcracker-resilient/blob/73f0ce39c202b21187a060c45570b5270da7f2f4/src/test/java/kaptainwutax/seedcrackerX/cracker/storage/TimeMachineLiftingTest.java) 使用 seedfinding 库的 `v1_21_3` 产生样本，不是两目标版 Minecraft 本体证据。
- **真实模型正反例**：两版分别生成已知 Seed 控制世界，覆盖只改 spacing、只改 Salt、两者都改、独立 feature seed、未知 NoiseRouter、历史新旧区块混合。服务端真值只作独立后验，不能伪装成客户端输入。
- **证据资格**：人工材料、缺损轮廓、错起点、错误朝向、跨世界/维度/版本和重复观测必须失败关闭；人工夹具不得获得天然来源资格。
- **完整性和性能**：有界路径、超时、候选上限、分片域、实际计数、取消和续跑均需检查。性能以本机实际测量报告，不沿用其他仓库的秒数或成功率。

本次仅完成资料与仓库能力核查及本文档整理；未运行外部破解器，未在目标服务器进行种子恢复，也未完成本文建议功能的双版本构建、真实 Fabric GUI 或服务端实测。后续实现与验收结果应单独记录，不能用本报告替代。
