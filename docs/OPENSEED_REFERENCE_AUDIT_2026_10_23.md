# OpenSeed 26.3 本机资料审计与借鉴结论

日期：2026-10-23。审计用户提供的 JAR 和源码压缩包；只读分析 ZIP、Java 源文件和 `javap` 字节码，未安装、启动或联网运行 OpenSeed。

## 结论

**可以借鉴基岩证据路线和搜索阶段划分；该包不能作为“未知自定义生成器的任意完整64位 Seed 已能破解”的证明。**

它具有实际候选生成代码，不只是界面：结构约束生成低48位候选，独立基岩模块搜索下界基岩随机状态。基岩随机规则如果保持相容，可以独立于结构 Salt 和群系气候模型，因此值得作为 WorldSeedLab 的下一条反演路线。

完整64位仍需要第二类可靠证据。该包基岩路径在网络哈希无法验证时使用的回退只覆盖 Java Random.nextLong 可达种子，不能代替任意高16位全域搜索，也没有独立 HOLDOUT 验证。

## 输入身份及可复核范围

| 输入 | SHA-256 |
| --- | --- |
| `OpenSeed26.3-src.zip` | `ac82501f723384522bd1c1a28fcdfe225858381e80a634c9941bd0be80627c2f` |
| `OpenSeed26.3_noauth.jar` | `890ab5d07f523c2f6e938be8df5ce3893cf364b9a2166f8c4fb7c957d7c9d848` |
| JAR 中 `bedrock_plugin.bin` | `437c2ec7aabed634fd692b5df44e029e4be30ccd5e10c57652c00d419ee58fa1` |

源码包有231个条目，展开约1.41 MB。其 README 自述为 CFR 反编译并修改的资料，包含 `source_original`、`source_patched` 和 `plugin_source`，不是包含构建文件与依赖锁定的原作者源码仓库。整个无授权 JAR 的 SHA 与 README 自述的补丁 JAR SHA 一致；JAR 内另含基岩插件字节码。身份一致不证明全部反编译源码与字节码语义一致，本次只对下述关键提升方法另做字节码复核。

`fabric.mod.json` 指向 [SeedCrackerX](https://github.com/19MisterX98/SeedcrackerX)，并保留该项目 MIT 文件；这仅能确认包内声明与已公开上游，不能据此认证所有新增模块的来源和许可。WorldSeedLab 不复制该包源码、字节码、授权修改或动态插件机制。审计副本仅保存在本机 `.git/task-research/openseed-20261023/`，不进入源码与发布产物。

以下行号均相对于源码包的 `source_patched/kaptainwutax/seedcrackerX/`，插件另标 `plugin_source`。结论涉及的 `BedrockCollector.liftAndVerify` 已再次核对实际 JAR 字节码。

## 实际计算路径

| 路径 | 源码证据 | 能证明的能力与前提 |
| --- | --- | --- |
| 结构分阶段搜索 | `cracker/storage/TimeMachine.java:144–177` | 低19位筛选再补剩余29位；调用结构库固定规则和 Salt，不是未知 Salt 求解器。 |
| 末地柱辅助结构搜索 | `TimeMachine.java:124–141、186–244` | 枚举柱子约束，随后搜索结构候选；需要对应规则和其它结构证据。 |
| 装饰物/哈希/群系提升 | `TimeMachine.java:247–369` | 有高16枚举代码；依赖装饰物规则、可信种子哈希或原版 OverworldBiomeSource，不是任意自定义 NoiseRouter。 |
| 下界基岩搜索 | `plugin_source/.../BedrockCracker.java:72–149、176–207` | 2^36个上位块加递归12位，实际搜索48位状态；需要相容的下界基岩随机规则。 |
| 基岩路径的64位回退 | `cracker/bedrock/BedrockCollector.java:455–474` | 先用 `WorldSeed.fromHash`；无匹配时改为 `reverseNextLong → nextLong`，并没有枚举全部65,536高位。 |

`BedrockMath.java:34–56` 的回退候选循环最多2×2次；随后 `BedrockCollector.java:443–444、477–485` 可直接显示“破译完成”。这不能证明人工指定的任意64位种子，也不能证明目标服模型已认证。

## 不能直接沿用的行为

1. **模型识别不足。** `BedrockCollector.java:62–63` 固定采样 Y=1..4、123..126；`166–175` 依靠维度名称或维度属性判断。维度名、无天光或比例8都不能认证表层规则、生成高度和随机算法；`BedrockPluginLoader.java:64` 直接选 NORMAL 模式。
2. **版本名称不等于算法校准。** JAR Manifest 标注26.3、类版本69；内嵌 `mc_core` 的 `MCVersion.latest()` 返回第一枚举 `v1_21_3`。这不证明所有规则都失效，也不构成26.1.2/26.2/26.3逐项校准证据。
3. **缺少独立留出与完整性状态。** 基岩数据没有独立 FIT/HOLDOUT；插件最长等待7天后可中断工作线程并正常返回，调用端没有区分全域完成、取消和预算结束。候选数少不等于完整域唯一。
4. **世界隔离不足。** 基岩样本键只有XYZ；登录会重置，重生/切维度入口未同样重置。多个自定义下界维度可能混用观察。
5. **“查到种子”可能来自数据库。** `mixin/ClientPacketListenerMixin.java:91–105、128–140` 在登录/重生时调用后端查询；`util/Database.java:150–179` 以服务器地址/主机名查询已有记录。数据库命中必须与本机反演区分。此分支与默认关闭的数据库提交开关不同；本次未发起这些请求。

## WorldSeedLab 的自研接入方向

采用 [BedrockSeedCracker 公开说明](https://github.com/MiranCZ/BedrockSeedCracker/blob/master/README.md) 中也能独立核对的两阶段问题划分：

1. 相容的下界 Legacy 基岩观察约束低48位。依据目标两版 Mojmap 原生随机调用链独立实现数学模型与反演，不依赖结构 Salt。
2. 对每个通过留出验证的 low48，完整枚举65,536个高16位，使用相容的主世界 Xoroshiro 基岩观察筛选。主世界与下界是否共用世界 Seed 必须显式声明，不能仅按地址相同推定。
3. 原始样本严格隔离世界、维度、版本和生成时期；先只把真实基岩作为阳性约束。空气、流体、玩家挖除、插件替换造成的非基岩，不能自动作为随机规则为假的证据。
4. 最终结果保留模型假设、实际搜索域/计数、FIT/HOLDOUT、取消/超时与剩余候选；模型相符仍只称条件候选。

本次静态审计没有在用户服务器执行该包，也没有取得目标服完整 Seed。已有本机资料不足以证明属于目标自定义服务器。真实基岩随机分支对照、FULL世界方块真值、真实客户端采样、未知目标服务器恢复是四项不同验收。
