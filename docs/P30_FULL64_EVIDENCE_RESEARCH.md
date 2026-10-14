# WorldSeedLab · P3.0 独立证据约束下的完整64位候选研究

> 目标版本：Minecraft Java 26.1.2 / 26.2 · Java 25 · 原生 Fabric / Mojmap  
> 研发状态：**完整64位候选与原版群系模型已实现；真实受控世界存档的两种高16位候选正反验证成功；尚未穷尽真实生成器的65,536个高位或恢复未知服务器 Seed**。  
> 本文件不宣称已取得任何未知服务器的后台 Seed。

## 为什么 P2.9 的成功不能直接升级为64位

受控 Minecraft 26.1.2、26.2 世界确实从真实沙漠神殿的 `StructureStart` 和独立几何校准，经 Z3 找回了低48位 `2731`；但这类 Legacy RandomSpread 结构放置仅使用低48位：

```
placement(seed) == placement(seed + k * 2^48)
```

在有效64位范围内，**单个低48位候选对应 65,536 个不同的完整64位位型**，无论继续收集多少同样规则、同样 Salt 的结构起点，信息上仍无法区分这些高位。某服务器可能改写 Salt、结构 RNG、世界预生成或自定义矿物规则，因此完整种子还需要**独立、已确认来自完整64位种子**的生成机制证据。

## P3.0 已实现的内容

新增 `Full64BiomeCandidateSearch`（独立离线核心）：

- 接收 P2.8 已经通过 **至少2 FIT + 1 HOLDOUT** 的 `VerifiedLow48` 候选；拒绝重复或缺乏结构验证的候选。
- 使用 Java `long` 的全部64位，以 `(high16 << 48) | low48` 重组；高16位无符号范围 `0..65535`，支持最高符号位为1的负数 Seed。
- 用匹配版本、世界指纹、维度及生成器配置的真实三维 quart 群系样本作为独立条件。必须有独立 FIT/HOLDOUT，不能混合不同世界/维度/版本或重复坐标。
- 使用每个候选仅初始化一次 `BiomeSampler` 的模型接口，支持连续高16位枚举、CPU/时间/结果上限、取消、精确 `nextIndex` 续跑。
- 保留 `ModelTrust` 区分“经过实际 Minecraft 目标版本世界生成对照的群系模型”与“仅用来测试位域的演示模型”；未确认的自定义生成器默认拒绝。信任标签只是模型实现的声明，不代替独立版本对照。
- 所有结果称**模型一致的 FULL64 候选**；即使候选只剩一个，也只能在指定 low48 候选集合、群系模型和被检查的窗口范围内说明唯一。不能把这个结果直接宣布为服务器真实 Seed。

### 目前自动化测试的真实性边界

`P30Full64SmokeTest` 使用的是 Minecraft 两个真实版本的 `XoroshiroRandomSource` 作为**控制随机数算法**，以人为标记的类别作为测试观察点，构造一个已知64位控制值（高16位非零且符号位为1）。

它会扫描 **65,536 个高16位候选**，验证找回位型、负数签名、错误值拒绝、混服/重复/无 HOLDOUT 拒绝，以及取消/断点精确恢复。

这证明完整64位枚举、位转换和独立条件求解**在控制模型下成立**；它**没有**证明这些随机输出等价于 Minecraft 26.1.2／26.2 的原版气候/群系生成，更**没有**通过普通客户端世界证据找回任何64位 Seed。原始 `XoroshiroRandomSource` 本身不是完整的 Overworld 生物群系生成器。

## 真实 Minecraft 原版群系独立存档对照（已验收）

2026-10-14 在两个版本的隔离本机真实 Minecraft 服务端，用完整已知 Seed `2731` 建立世界；保存实际生成区块到 Anvil。受控世界同时提供经独立验证的沙漠神殿低48位结构候选。

- 使用 **仅 `Status=FULL` 的实际生成完成区块**。开始时误将结构搜索留下的未完成区块默认 `plains` 群系 palette 作为真实群系，独立交叉校验立即发现不一致；本轮已修复。不能通过调节算法去拟合伪造的默认 `plains`。
- 为避免不安全地猜测紧凑 NBT 群系长数组下标，仅从 **`section.Y=4` 且 `biomes.palette` 恰有单一合法群系**的 16 方块高区块节提取 quart 格点；同一节内所有 4×4×4 quart 均对应相同群系。
- 每版在距离至少 256 quart（1024 方块）约束下选择 **5 个互不重叠的空间观测位置**，采用 **3 FIT + 2 HOLDOUT**。
- 用目标版本真实 `RandomState`、`NoiseGeneratorSettings` 与 `MultiNoiseBiomeSource` **复算游戏已保存的真实群系注册键**，所有选择的独立样本均匹配。
- 已知低48为 `2731`，在真实原版群系模型中比较高16位 `0` 和 `1`（只检查 `[0,2)`）；实际完整 Seed `2731` 通过，错误 `2731 + 2^48` 被排除。错误高16差异不应被误解释为服务器完整 Seed 可被确定。
- 使用 `P30VanillaBiomeNoiseTest` 的控制自校验（另一个高16位非零且符号位为1的已知完整64位 Seed）验证真实 MultiNoise 模型是高16位敏感的，但控制点来源仍由模型自身产生。
- 对照来源是 **受控服务端保存的真实世界存档**，而不是目标远端普通 Fabric 客户端仅凭可见方块重建结构和群系；此处绝不使用服务器私有数据。

双版实测运行：GitHub Actions [独立真实世界对照](https://github.com/fxjcangku/WorldSeedLab/actions/runs/38506386891)。当前可以断言：真实原版群系能区分这两种高16候选；**不能**断言 65,536 种高位均已由实际世界生成器检查完成。

## P3.1 真正恢复完整64位的验收要求

1. 已完成原版基础对照：两个版本已构建 `VanillaOverworldBiomeModel`，并在 `Status=FULL` 的受控实际存档群系中交叉验证。下一步须验证更多真实种子、非零高16和其它群系/高度带，不能仅凭一个固定种子宣称对所有世界有效。
2. 需要真实客户端 `runClient`，仅从用户已加载区块取得对应 quart XYZ 的多个可靠群系观测，建立带世界/维度/版本/生成器配置的审计清单；不得借用服务端存档的结构起点做远端自动推断。
3. 当前真实世界只检查了高16位 `0` 和 `1`；仍须制定可审计的全 `[0,65536)` 或分片预算、断点续跑与完成性判定，以及非零高16、负数完整世界种子的实际存档独立验收。预算过小只能报告搜索不完整。
4. 面对自定义群系、TerrainGenerator、插件矿物或未知世界生成配置，先诊断兼容性；不能强套原版 Overworld 生成器输出“后台真实 Seed”。
5. 严格控制成本：在游戏 Tick 之外显式运行，限制 Z3 计算、世界噪声初始化、CPU、内存、取消和结果数量。成功仅表示候选通过指定受控模型；真实矿点仍需另行确认。

**重要**：即使此后找到了单个64位候选，世界实际矿石/假矿/生成器策略仍必须独立验证。不会绕过服务端权限或获取服务端私人文件。

## 可审核源码

- `src/main/java/io/github/fxjcangku/worldseedlab/core/solver/Full64BiomeCandidateSearch.java`
- `src/main/java/io/github/fxjcangku/worldseedlab/core/solver/VanillaOverworldBiomeModel.java`\n- `src/test/java/io/github/fxjcangku/worldseedlab/core/P30Full64SmokeTest.java`\n- `src/test/java/io/github/fxjcangku/worldseedlab/core/P30VanillaBiomeNoiseTest.java`\n- `scripts/run_known_seed_server.py` 与双版本实际世界生成工作流
- `gradle -PmcVersion=26.1.2 clean build`
- `gradle -PmcVersion=26.2 clean build`

即使本次真实独立群系验证通过，整体仍是**有前提的受控世界完整64位候选研究基础设施**，不是已破解任何未知服务器、也不能依此推断真假矿分布。
