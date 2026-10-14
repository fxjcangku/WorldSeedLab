# P3.4.1 · 真实修改结构 Salt 的自建世界对照

> 阶段：双版本真实 Minecraft 结构集数据包实验 · 研发中（CI 尚需核对）  
> 项目仅处理 `fxjcangku/WorldSeedLab`，禁止接入私人服务端、私有源码或上传坐标。

## 为什么需要真正的数据包实验

P3.4 已经在真实自定义沙漠平坦地形中证明，原版沙漠神殿的
`StructureStart` 仍然可能符合原版 RandomSpread。但是“修改 Salt”之前只作过
**数学模型反例**：尚未运行一个确实覆盖原版结构集合数据的 Minecraft 世界。
P3.4.1 新建与旧世界完全隔离的测试世界，真正修改目标版本的结构集：
`minecraft:desert_pyramids`。

这是结构放置规则的条件兼容性研究，不是破解未知服务器 Salt 或后台真实完整64位 Seed。

## 真实受控环境

| 条目 | 条件 |
| --- | --- |
| Minecraft | 26.1.2 / 26.2，分别真实 `runServer` |
| Java / Gradle | Java 25 / Gradle 9.6.1 |
| 世界 | 公开已知完整 Seed=2731；单一全新世界纪元 |
| 地形 | 自定义超平坦沙漠，砂岩与沙层保存为真实 FULL |
| 结构集合 | `minecraft:desert_pyramids` |
| 原版 Salt | `14357617` |
| 修改后 Salt | `14357844` |
| 数据包路径 | 受控世界 `datapacks/worldseedlab_p34_modified_salt/data/minecraft/worldgen/structure_set/desert_pyramids.json` |
| 数据包版本 | Minecraft 26.1.2: `101.1`；26.2: `107.1` |

测试脚本只在**新建的隔离测试世界**安装数据包，开启原版结构生成；
服务器真实启动后必须使用 RCON `datapack list enabled` 确认加载成功。
测试驱动为了受控世界生成独立 Region，可以在本机使用 `/locate` 与 `/forceload`；
普通 Fabric 客户端不存在该权限或功能。

## 验收顺序与严格来源区分

1. 真实 Minecraft 服务端启动，并报告数据包已启用；否则**失败**。
2. 从服务端实际保存的 `Status=FULL` Anvil 区块，独立确认有自定义地层。
3. 提取独立保存的**真实 StructureStart NBT**（不是 `/locate` 输出，也不是公式合成坐标）。
4. 至少四个不同 Region，前 2 FIT、其余 HOLDOUT：
   - 应满足**已安装的修改 Salt=14357844**；
   - 同一组实际 NBT 起点应冲突于**错误的原版 Salt=14357617**；
   - `SaltKnowledge.UNKNOWN` 保持 `GENERATOR_UNKNOWN`，不能把指定模型冲突误当成服务器没有 Seed；
   - 相同低48位不同高16位的真实结构位置仍一致，不能冒充完整Seed高16证据。
5. 输出匿名汇总；控制组原始坐标仍明确标注服务端真值，不能标注客户端 `CLIENT_OBSERVED`。

测试入口：
```bash
python3 scripts/run_p34_custom_worldgen.py --minecraft=26.2 --modified-salt
gradle --no-daemon -PmcVersion=26.2 p34ModifiedSaltWorldTest
```

GitHub Actions：`.github/workflows/p34-modified-salt.yml`，双版本独立执行；
失败日志必须真实保留，不得以合成测试替代。

## 判定边界

- 这次能验证的是：**真实改变了已知服务器的结构数据包后，我们的 RandomSpread 前向模型会按新 Salt 匹配实际生成位置，而原版 Salt 与之冲突。**
- 普通客户端仍然不能从可见结构直接得知后台 Salt；只能用完整可见建筑轮廓、模型假设与留出样本研究条件兼容性。
- 混合旧区块、人工复制建筑、未知结构集版本、不同随机分布、实际用户服务器仍需要独立控制实验。
- 原版/修改结构 Salt 都只能约束对应 RandomSpread 低48位；**高16完整域以及自定义群系的Seed映射未被本实验证明**。

验收状态应以真实 Actions job 及其保存的 FULL/StructureStart 证据为准，**本文件在 CI 完成前不声称成功**。
