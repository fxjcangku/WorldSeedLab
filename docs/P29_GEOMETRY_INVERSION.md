# WorldSeedLab · P2.9 结构轮廓几何反演（第一阶段）

> 版本：Minecraft Java 26.1.2 / 26.2 · Java 25 · Fabric/Mojmap  
> 状态：**研发功能分支；完整轮廓到低48位 SMT 候选的离线代码闭环**。尚无 Minecraft 实机生成的 StructureStart 真值验收，不能宣称破解未知服务器 Seed。

## 核心问题

P2.6 仅能从客户端收到的完整区块检测**疑似建筑材料**。材料集中出现的区块、玩家目击坐标、地图图钉与服务端生成时保存的 `StructureStart` 完全不是同一数据。通常普通联网客户端拿不到权威的服务端结构起始区块。服务端也不必须向客户端发送 Seed、结构 Salt 或完整生成器规则。

P2.9 首先打通**有来源的完整轮廓**到**结构起点的有限假设集合**，而非把目击位置强行转成准确坐标：

1. 在研究者控制的**已知 Seed 测试世界**中，分别记录真实 StructureStart 区块与相同结构类型、相同朝向的完整建筑水平外边界，形成可追溯的朝向校准。测试程序不能替用户证明输入的真值来源。
2. 在目标世界由人工或有审计记录的测绘流程确认**完整、未遮挡**外轮廓（Block X/Z 闭区间）。未加载区域、断裂材料簇或 P2.6 的稀疏候选不能替代完整轮廓。
3. 对每种受控校准，以起点区块左上方的方块坐标 `chunkX*16, chunkZ*16` 为锚点，计算轮廓边缘相对位移，容许有限边缘测量误差。
4. 用整数上下界反推候选起始 Chunk。负坐标使用数学意义上的 `floorDiv/ceilDiv`，不会向零截断。
5. **保留各朝向候选矩形的并集**：多个朝向可能仅允许第 A / 第 D 区块，而不能允许 A-D 之间所有起点。原来只支持单矩形的求解器现将它们编码为 SMT 中的 OR；原始 Java RNG 和 HOLDOUT 也严格验证同一集合。
6. 继续沿用 P2.8 的世界指纹、版本、维度、结构集合、FIT/HOLDOUT 隔离与本地显式 Z3 启动。假矿、猜测的 Salt 和未知生成器规则均不自动写成约束。

## 数学口径

若校准记录的轮廓左边界相对起始区块方块锚点为 `rMinX`，误差最多为 `t` 方块，实测轮廓左边界为 `oMinX`，则：

```text
oMinX - rMinX - t <= startChunkX * 16 <= oMinX - rMinX + t
```

右边界同样给出一对不等式。两者取交集后，`startChunkX` 下界按 16 **向上取整**，上界按 16 **向下取整**。Z 轴相同。若任一维度没有整数起点则该朝向不兼容。所有校准都不兼容时**拒绝生成 Seed 约束**。

离线本机 Z3 进程设置 512 MiB 求解内存上限，并保留总超时和候选数量限制。它不等价于整台机器或 JVM 的统一内存限制。\n\n每项测绘最多八种校准朝向；合并后的候选区块最多 256 个，包络尺寸和位置跨度也受限制。这个上限是资源保护，不是“必定能求解”的保证。包络只能用于展示，**不能作为实际 SMT 或 Java 允许集合**。原有单矩形属性输入维持兼容。

## 离线 `.properties` 输入示例

保留 [P2.7 输入格式](P27_RANDOM_SPREAD_SOLVER.md)中的 `worldFingerprint`、`gameVersion`、`dimension`、`models.count` 和每组 `model.<n>.*`、`constraints.count` 与 `search.*`。其中一个结构观察用以下字段替代原 `constraint.0.minChunkX` 等四个起点字段：

```properties
constraint.0.structureSet=minecraft:research_set
constraint.0.origin=GEOMETRY_ESTIMATE
constraint.0.split=FIT
constraint.0.geometry.quality=COMPLETE_PERIMETER
constraint.0.geometry.structureId=minecraft:desert_pyramid
constraint.0.geometry.minBlockX=-203
constraint.0.geometry.minBlockZ=17
constraint.0.geometry.maxBlockX=-176
constraint.0.geometry.maxBlockZ=47
constraint.0.geometry.calibrations.count=1
constraint.0.geometry.calibration.0.referenceKind=CONTROLLED_TRUE_STRUCTURE_START
constraint.0.geometry.calibration.0.referenceId=controlled_world_A_orientation_N
constraint.0.geometry.calibration.0.variant=north
constraint.0.geometry.calibration.0.gameVersion=26.2
constraint.0.geometry.calibration.0.dimension=minecraft:overworld
constraint.0.geometry.calibration.0.structureSet=minecraft:research_set
constraint.0.geometry.calibration.0.structureId=minecraft:desert_pyramid
constraint.0.geometry.calibration.0.control.trueStartChunkX=12
constraint.0.geometry.calibration.0.control.trueStartChunkZ=-6
constraint.0.geometry.calibration.0.control.minBlockX=192
constraint.0.geometry.calibration.0.control.minBlockZ=-105
constraint.0.geometry.calibration.0.control.maxBlockX=219
constraint.0.geometry.calibration.0.control.maxBlockZ=-75
constraint.0.geometry.calibration.0.toleranceBlocks=0
```

该示例只是**字段和几何计算格式**，不对应任何真实服务器。结构集合 ID 决定 RandomSpread 的位置规则，具体结构 ID 决定轮廓模板；两者必须单独填写、分别核对，不能因为同属一个集合就互换校准。

一个求解清单仍必须达到至少 **2 FIT + 1 独立 HOLDOUT**，来自不同结构 Region，并使用确实适用的结构集合参数。第二种朝向可增加 `geometry.calibration.1.*`，不得重复 `variant`。

`referenceKind` 字段是研究者对证据来源的**显式声明**，程序无法仅凭这个文字证明“实际拿到了 StructureStart”。伪造校准真值仍会得到数学上自洽但对服务器毫无意义的结果。

显式离线运行：

```bash
gradle -PmcVersion=26.2 -PsolverInput=/path/to/your-input.properties -PsmtMaxModels=12 p28SmtResearch
```

保留 `input.properties` 及离线校准引用索引以复核。研究数据含精确坐标，不得上传到公共 PR。

## 当前实际验收边界

- `p29GeometrySmokeTest`：两个声明版本的合成完整轮廓、负坐标、不同朝向、缺口不产生假阳性、拒绝 P2.6 材料簇/遮挡、跨版本与错误校准。
- `p29Z3GeometryIntegrationTest`：真正调用本机 Z3 反演合成低48位已知值、原始 Java RNG 与 HOLDOUT 二次复核，并拒绝包络缺口的错误起点。两版独立 CI 执行。
- 尚未实现：从真实 Minecraft 已知 Seed 测试世界**实际生成完成**的结构中读取校准用 StructureStart 真值；自动准确测量全轮廓；真实客户端 `runClient`、服务器采集；全48域性能基准、后台真实64位 Seed 或服务器真矿预测。
- 即使数学验证通过，也只能给出**假设条件下的低48候选**。未知 Salt、随机算法、人工建筑、混合地图世代和自定义地形可能使世界 Seed 不可辨识。有限客户端观测不是服务端配置的直接读取。

## 后续 P2.9 验收门槛

1. 在本机**已知完整 Seed** 的 Minecraft 26.1.2 和 26.2 世界，实际生成结构、确认服务器侧 StructureStart 区块与完整几何边界，并导出脱敏可重复实验夹具。
2. 将至少三处不同 Region 的真实结构起点（2 FIT + 1 HOLDOUT）闭环验证：原版真实 Seed 的低48位必须落在候选集合，错误规则和错误 Salt 必须有清晰反例。
3. 增加自定义地形但保留原版结构随机放置的受控数据包实验，以及改变结构分布规则的反例；分别报告不可辨识/证据不足/模型不兼容。
4. 扩展可界定完整性的拒绝采样、全48搜索性能与内存预算，之后再独立研究高16位信息和矿物生成规则。

**本阶段禁止将 CI 中的合成结果称为“所有服务器已破解”。**
