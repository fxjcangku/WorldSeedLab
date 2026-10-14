# WorldSeedLab · P2.9 真正生成的已知 Seed 世界验证

> 测试日期：2026-10-14  
> Minecraft：26.1.2 / 26.2 · Java 25 · Fabric · Mojmap  
> 实验对象：**本地离线真实 Minecraft 服务端世界**；使用与单人模式一致的服务端世界生成规则。  
> 核心结论：受控世界的 StructureStart NBT → 原创 RandomSpread → 真 Z3 低48反演 → FIT/HOLDOUT 验证通过。**尚未运行图形单人客户端，不代表远程服务器 Seed 已恢复。**

## 1. 为什么不只验证 /locate

`/locate structure` 的坐标很有参考价值，但不能天然代表服务端存档内真实 StructureStart；普通远程客户端也不能直接读取服务端的 StructureStart NBT。

这次把验收进一步推进到：真实 Minecraft 启动 → 新世界原版地形生成 → 在不同远处区域定位结构 → 显式强加载并等待相关区块生成 → `save-all flush` → 正常关闭 → 只读解析 **Anvil 存档真实结构起点** → 用之前独立研发的 RNG/SMT 重算。

- 固定公开受控完整 Seed：`2731`，对应低48位 `0x000000000aab`。
- 结构类型：`minecraft:desert_pyramid`。
- 原版结构集合研究模型：`minecraft:desert_pyramids`，`spacing=32`、`separation=8`、`LINEAR`、`salt=14357617`。这些是假设参数，验收时与实际生成起点逐一交叉比对。
- 每个版本独立运行，不直接复制模拟世界的起点作为输入，不访问玩家的服务器、私人项目、用户名或 IP。
- 仅绑定 `127.0.0.1`；本地隔离 RCON 使用临时随机凭据；实验世界停止后只上传最小结构审计结果，不自动发布 Release。

## 2. 实际取得的原版结构起点

两个 Minecraft 版本均保存了 **4,373 个区块**，从结构 NBT 中确认了下列 **4 个真实生成的沙漠神殿起点**：

| StructureStart Chunk X | Chunk Z | 真实结构部件 X/Z 边界 | 部件朝向 O |
| ---: | ---: | --- | ---: |
| -784 | 749 | [-12544, -12524] × [11984, 12004] | 0 |
| -1166 | -1044 | [-18656, -18636] × [-16704, -16684] | 0 |
| -117 | -106 | [-1872, -1852] × [-1696, -1676] | 3 |
| 930 | 791 | [14880, 14900] × [12656, 12676] | 3 |

数据来自 **`structures.starts.minecraft:desert_pyramid`** 对应的 `ChunkX/ChunkZ` 和非空 `Children`。这两个版本的结构部件 NBT 中，`BB` 位于 `Children[].BB`，不应假定 StructureStart 顶层必有 `BB`。边界为真实生成结构部件包围盒聚合，**不是客户端目视材料轮廓**。

Minecraft Java 26.1 起世界存档目录发生变化。主世界 Anvil 区块保存在：

```text
worldseedlab_known_seed_<version>/
  dimensions/minecraft/overworld/region/r.<x>.<z>.mca
```

不是旧版本常见的世界根目录 `region/`。零字节 Region 占位文件不得解释为已生成区块；损坏的非空 Region 必须报错，不能默默忽略。

## 3. 实际 Seed 恢复验收

使用真实 StructureStart 的 ChunkX/Z，逐一调用原创 `RandomSpreadMath` 验证受控 Seed 的放置位置，并在离线 Z3 中：

- **FIT：** 两个不同 Region 的实际结构起点。
- **HOLDOUT：** 第三个、未参与求解的真实起点。
- **检索范围：** `[0,4096)`，不假装已遍历全部 2^48。
- **原始 Java RNG 复核：** 每个 SMT 候选都再由前向精确算法验证。
- **错误 Seed 反例：** `2732` 不满足给定真实结构起点约束。
- **数学限制：** `2731 + 2^48` 对 Legacy RandomSpread 有相同低48放置结果，说明结构起点本身并不能决定高16位。

CI 每版日志须实际包含：

```text
WorldSeedLab P2.9 REAL Minecraft <version> StructureStart NBT confirmed:
4 genuine starts; low48=0x000000000aab recovered through real Z3 FIT=2 HOLDOUT=1; wrong seed rejected.
```

当前已取得上述真实成功输出。真实结构部件 `BB` 经校准推导候选，再送进同一 SMT 的强化几何回归属于后续独立检查，不能用最初的精确 StructureStart 正例冒充。

## 4. 操作与复现

仓库文件：

- `scripts/run_known_seed_server.py`：使用 Gradle 启动独立本机 Minecraft、实际生成并保存结构、从原版世界 NBT 提取证据。
- `src/test/java/io/github/fxjcangku/worldseedlab/core/P29RealWorldStructureTest.java`：原版实际起点数学对照、Z3 FIT/HOLDOUT、错误 Seed 反例及几何实验。
- `p29RealWorldStructureTest`：仅显式离线调用的 Gradle 研究任务，不接入 `check` 以避免日常编译自动生成大型世界。
- `.github/workflows/p29-real-worldgen.yml`：在 GitHub Actions runner 分别以 Java25 启动 Minecraft 26.1.2、26.2 实际生成世界、安装 Z3，离线执行真世界数据验收并保留短期审计产物。

完整流水线从 GitHub Actions 运行，用户无需在公开仓库中放置私人测试世界或服务器日志。

## 5. 不得扩大解释的结论

1. **真实生成的 StructureStart 通过：** 已取得官方 Minecraft 真实世界生成的结构起点真值，数学引擎确实找回受控低48位候选。
2. **不等于真实远程客户端可逆：** 这次通过已知 Seed 受控本地服务端读取真值，而不是仅用玩家可见方块就获得起点。
3. **不等于普通 GUI 客户端验收：** 没有完成 `runClient`、F8 界面或图形单人世界中的实机采集测试。
4. **不等于完整世界64位：** 高16位以及未知 Salt、未知结构参数、自定义 RNG 和多世代区块仍需其它独立证据。
5. **不等于真矿预测：** 自定义矿物生成、假矿反透视必须另行验证；当前测试完全不使用矿石作为 Seed 约束。

**后续路线：** 在已知 Seed 的实际 Minecraft 图形单人世界采集普通客户端可观察的材料与建筑轮廓，对照此次 StructureStart 真值，量化轮廓误差/朝向，才能进一步研究没有服务端权限时是否能够恢复可靠的低48位候选。
