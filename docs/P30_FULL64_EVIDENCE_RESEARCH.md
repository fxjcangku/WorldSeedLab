# WorldSeedLab · P3.0 独立证据约束下的完整64位候选研究

> 目标版本：Minecraft Java 26.1.2 / 26.2 · Java 25 · 原生 Fabric / Mojmap  
> 研发状态：**64 位候选空间与高16位枚举引擎已实现；原版群系生成适配和真实64位反演尚未完成**。  
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

## P3.1 真正恢复完整64位的验收要求

1. 以两个版本实际 Minecraft `RandomState`、`NoiseGeneratorSettings`、`MultiNoiseBiomeSource` 以及各自正确的世界预设/数据包为依据，构建**可重复播放**的原版 quart XYZ 群系生成模型，不能直接把单个 Xoroshiro 随机输出当作真实群系。
2. 在已知完整64位 Seed、已实际生成的单人/离线受控 Minecraft 世界，读真实三维群系样本（不通过直接暴露 Seed 的配置作为拟合输入），独立生成多处训练与留出样本。
3. 在已知低48、未知高16条件下执行真正的高16位候选筛选；至少做一例非零高16位、负数完整世界种子、反例错误模型，双版本对照并证明观测可以区分高位。预算过小只能报告搜索不完整。
4. 面对自定义群系、TerrainGenerator、插件矿物或未知世界生成配置，先诊断兼容性；不能强套原版 Overworld 生成器输出“后台真实 Seed”。
5. 严格控制成本：在游戏 Tick 之外显式运行，限制 Z3 计算、世界噪声初始化、CPU、内存、取消和结果数量。成功仅表示候选通过指定受控模型；真实矿点仍需另行确认。

**重要**：即使此后找到了单个64位候选，世界实际矿石/假矿/生成器策略仍必须独立验证。不会绕过服务端权限或获取服务端私人文件。

## 可审核源码

- `src/main/java/io/github/fxjcangku/worldseedlab/core/solver/Full64BiomeCandidateSearch.java`
- `src/test/java/io/github/fxjcangku/worldseedlab/core/P30Full64SmokeTest.java`
- `gradle -PmcVersion=26.1.2 clean build`
- `gradle -PmcVersion=26.2 clean build`

全套测试通过之后，该引擎仍属于**受控模型的 64位候选研究基础设施**，而非已完成的真实服务器64位破解。
