# WorldSeedLab · P3.2 客户端真实群系证据与负数64位 Seed 验证

> Minecraft Java 26.1.2 / 26.2 · Java 25 · Fabric 原生 · Mojang Mojmap  
> **核心进度：** 普通客户端手动 quart 群系取证与明确假设下的离线 Seed 研究入口已经实现；负数高16位真实 Minecraft 受控世界验证已取得双版本成功结果。  
> **不可误读：** 客户端 GUI 实际加入单人世界、多人服务器取证，仍需要独立实机交互验收；自定义生成器/假矿的完整复现没有得到证明。

## 1. 与 P3.1 的实质区别

P3.1 在公开受控种子 `2731` 的两个真实 Minecraft 世界中，通过保存的 FULL 区块群系验证了全部 65,536 个高16候选。那是**受控服务端 Anvil 真值**，普通联网客户端无法直接读取服务端结构起始 NBT 或生成器配置。

P3.2 增加客户端自己的证据链：正常 Fabric 客户端，只利用当前可见、已经收到的 FULL 区块，通过用户手动命令采样 quart XYZ + 群系注册键，HMAC 匿名世界指纹和世界纪元隔离，再显式导出本机原始观测文件。不会因为连接服务器、进入世界、打开 GUI 就自动开始采集。

### 客户端操作

| 命令 | 行为 |
|---|---|
| `/wsl quart` | 查看本轮临时证据状态 |
| `/wsl quart sample` | 手动读取玩家所在已收到的 FULL 区块中心 quart 群系 |
| `/wsl quart export` | 至少 5 个相互水平距离不少于 1024 方块的独立样本后，才写入本机文件 |
| `/wsl quart clear` | 丢弃尚未导出的临时样本 |

前 3 条自动标为 FIT，第 4 条起为 HOLDOUT。最多缓存 64 条，避免无限增长；换服、维度、世界纪元、客户端退出时丢弃未导出证据。**没有独立结构起点证据，单靠该 CSV 不具备从 `2^64` 空间恢复世界种子的能力。** 用户必须自行探索到相隔足够远的地点；程序不会为了凑样本去加载未收到的区块或发送更多请求。

文件格式：`WSL_P32_CLIENT_QUART_V1`。包含 `worldFingerprint`、`gameVersion`、`dimension`、`generatorProfile=worldseedlab:unknown_worldgen`、`provenance=CLIENT_OBSERVED`、`sampling=MANUAL_ALREADY_LOADED_FULL` 与真实 quart 坐标、群系、FIT/HOLDOUT。客户端 **始终无法直接证明服务器的噪声生成参数**；由研究者显式选择原版生成器假设，不会改写原始 CSV。

导出位置：现有本地 HMAC 作用域目录下的 `p32-manual-quart/client-quart-<timestamp>.csv`。实际文件有精准方块／quart坐标；不要未经脱敏上传到公开仓库、群聊或 Issue。本模组不自动联网提交任何数据。

## 2. 离线低48结构与客户端群系 64 位联动

P3.2 新增 `p32OfflineClientQuartResearch`，研究必须在用户电脑的独立离线 Gradle 进程**显式执行**。输入包含两份**同一世界 HMAC 指纹 / 游戏版本 / 维度**的数据：

1. `p32ClientCsv` 是用户手动导出的客户端 quart 证据文件；
2. `p32StructureInput` 是使用 P2.9 真实受控校准规则和可靠结构起点范围的 SMT 属性文件（P2.6 的普通材料候选、玩家站位不能直接冒充 StructureStart）；
3. `p32GeneratorAssumption` 必须精确填写 `EXPLICIT_VANILLA_OVERWORLD_HYPOTHESIS`，以明确研究者正在尝试原版生成器假设，并非宣称已识别到真实服务器的生成器。

```bash
gradle --no-daemon -PmcVersion=26.2 \
  -Pp32ClientCsv=/path/to/client-quart.csv \
  -Pp32StructureInput=/path/to/structure-research.properties \
  -Pp32ShardIndex=0 -Pp32ShardCount=16 \
  -Pp32GeneratorAssumption=EXPLICIT_VANILLA_OVERWORLD_HYPOTHESIS \
  p32OfflineClientQuartResearch
```

本地需安装 `z3`，且不以客户端 GUI/游戏 Tick 启动；必须先通过 Z3 对结构 FIT/HOLDOUT 的低48候选进行正向 Java RNG 复核，再将群系映射到原版 Overworld 假设下对完整64位候选作有限分片筛选。

任何分片超时、候选数量上限、SMT有界拒绝采样、未验证自定义 Salt 都需要显式报告不完整，**不允许单片成功就输出全域已搜索**。即使单片只剩一个完整 64 位候选，也只是模型一致的研究结果。

## 3. 负数完整64位 Seed 受控实测

新增 [P3.2 真实负数 Seed 测试工作流](../.github/workflows/p32-negative-full64.yml)，在 CI runner 实际启动 Minecraft 26.1.2 和 26.2 原版服务器：

- 真正世界种子：`0xBEEF000000000AAB`，Java signed long = `-4688528687069394261`，低48位仍为 `2731`。
- 采集原版沙漠神殿 `StructureStart` NBT，并从 **`Status=FULL` 的实际 Anvil 已生成区块**提取独立 quart 生物群系。
- 两版均读取了 **5 个真实 StructureStart 和 5 个原版 FULL 群系观测点**。全部被目标版本官方 Mojmap `VanillaOverworldBiomeModel` 复算匹配。
- 两版均保留真实高16位 `0xBEEF`，排除了错误高16位 `0` 以及真值前后相邻候选；使用 3 群系 FIT + 2 HOLDOUT。
- **这是小窗口正反例**，不是对该负数种子重新检查全部 65,536 个高位；该全域验证仅在 P3.1 的正数 Seed `2731` 受控世界完成过。
- 服务器世界数据只用于独立校准和 CI 验证，不会因此直接进入正常客户端取证路径。

## 4. 自动化验收及尚未完成的任务

- `p32ClientQuartEvidenceTest`：手动采样归档与 world HMAC 作用域、最多64样本、最小水平距离、用户明确原版模型假设、恶意修改文件及重复 FIT/HOLDOUT 负例。
- `p32OfflinePipelineIntegrationTest`：真实 P2.9 神殿起点/真实 P3.1 FULL 存档 quart 群系，经**模拟的客户端传输层**进入独立 Z3 与原版64位群系模型。模拟传输成功**不能等价于实际 runClient 进入游戏取证**。
- `p32NegativeRealWorldTest`：在两个版本真实负数 Seed 游戏生成世界上检查实际 StructureStart、群系、正确高16与错误高16，不能改用自己编造的群系标签。
- `p32-client-startup.yml`：使用 Xvfb 尝试实际启动两个版本的 Fabric `runClient`，要求日志出现 WorldSeedLab client entrypoint 初始化。**即使成功也只是启动/载入模组验收，不是完整单人世界点击UI及采样验收**。
- 下一阶段仍须在 GUI 单人/远程世界中实际采样、回放原始证据、研究完整轮廓自动恢复可靠起点、未知 Salt、自定义多代生成器、插件矿物/反矿透视证据可靠性。未经这些步骤不得承诺破解所有服务器。

**WorldSeedLab 保持独立公开项目，不访问、不复制或公开 yiyiaddon 私有代码，不发布 Release。**
