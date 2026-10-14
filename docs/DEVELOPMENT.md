# WorldSeedLab · 开发、构建与验收

> 真实研发状态：P0～P2.7 代码及双版本 CI 验证；尚未 runClient 实机验收。P2.7 只有离线低48位有界候选搜索，并非完整世界 Seed 破解器。

## 项目环境

- Minecraft 26.1.2 / 26.2，Fabric 纯客户端模组
- Java 25，Gradle 9.6.1，Fabric Loom 1.17.21（`net.fabricmc.fabric-loom`）
- Fabric Loader 0.19.5；26.1.2 Fabric API 0.155.3+26.1.2；26.2 Fabric API 0.161.0+26.2
- Mojang 官方映射；不引入 Yarn 或迁移现有 SeedCracker 源码
- 独立包 `io.github.fxjcangku.worldseedlab`

## 本地构建

仓库不包含 Gradle Wrapper 二进制；请安装 JDK 25 和 Gradle 9.6.1。

```bash
gradle -PmcVersion=26.1.2 clean build
gradle -PmcVersion=26.2 clean build
```

运行单人测试客户端：

```bash
gradle -PmcVersion=26.2 runClient
```

全部 `build` 将执行：

- `coreSmokeTest`：世界 HMAC、默认禁用、P0 模型存储。
- `p1SmokeTest`：三维 quart 群系取样、区块日志、维度隔离、背压约束。
- `p2SmokeTest`：维度档案、地形高度图、独立索引、中文报告、异步退出落盘。

**CI 产物仅供受控实验，当前不自动发布 GitHub Release。**

## P1 真实群系采集

进入世界后按 **F8**，或输入 `/wsl gui`。初次启动采集开关关闭。确认服务器规则后手动点击启动，可以在后台正常跑图。已加载区块的群系通过 `ClientLevel.getBiome` 读取；停止、断线或换维度后安全停机。

参考 [P1 采样细节](P1_OBSERVATION.md)。

## P2 世界生成观测

采集开启后，除 P1 群系外，P2 还记录本机观察到的维度 ID、高度范围和 WORLD_SURFACE 高度图，并保存连接公布的维度注册键（与实际到访维度区分）（每区块 16 点）。不获取后台生成器配置或 Seed。

用户可使用：

```text
/wsl status    采样运行情况
/wsl path      输出本机日志目录
/wsl report    导出当前采集会话中文研究报告
/wsl gui       打开液态玻璃面板
/wsl map       打开真实已探索群系/指定Y层/地表高度地图
/wsl about     查看模块范围
```

正常退出客户端时会等待有限时间完成已接受的磁盘任务，并尝试导出最后一份报告；非正常退出不保证完整落盘。发送日志给维护者时，优先分享 `research-report.txt`；不要公开 `identity.key`、私人服务器地址或未经脱敏的原始坐标。

参考 [P2 跑图及日志说明](P2_DIAGNOSTICS.md)。

## P2.2 详细地形取证

在 F8 控制台中可以选择 OFF / SURFACE / STRATA 三档，也可输入 `/wsl detail` 切换。采集始终需要用户显式点击开始；不会自动读取尚未加载的区块。

- 地表：每区块 16 点的方块状态、地表群系和流体，并记录三种高度图与海床方块 ID。
- 地下：选择 STRATA 后以有界每 Tick 预算采集固定位置的跨高度剖面。
- 本地：`terrain-detail.jsonl` 与 `terrain-detail-completed.csv` 独立于旧日志；单维度容量上限 256 MiB。
- 分析：`/wsl report` 添加地表、海床与地下材料的匿名化统计；不自动上传原始坐标。

详细操作参阅 [P2.2 精细取证说明](P2_DETAIL.md)。

## P2.5 已探索真实地图

用户手动采集后，P1 群系证据和 P2 高度图会各自生成当前世界纪元/维度隔离的 gzip 地图索引。进入世界输入 `/wsl map`，或从 F8 液态玻璃面板进入地图，切换地表群系、指定高度群系和高度图，查看真实格点与人工建筑观察标记。未知区域保持空白，永远不从 Seed 猜测。

地图窗口读取本地分块文件且限在后台执行。已有旧 JSONL 数据**不会自动迁移**；完成区块再次飞过不会自动重采。需未来明确授权的离线重建工具。

[P2.5 已探索地图操作说明](P25_OBSERVED_MAP.md)。

## P2.6 自动结构材料候选

确认服务器允许后：F8 开启普通群系采集，再用 `/wsl structures on` 或液态玻璃顶部“结构侦测”按钮开启可选扫描。`/wsl structures` 查看统计；`/wsl structures off` 停止。扫描只读取地表/海床周围的客户端已加载方块，假矿不参与计算。首次/再次登录，结构扫描自动保持关闭。

结构候选文件 `structure-candidates-v1.jsonl` 含精确区块坐标，`structure-scanned-v1.csv` 是此版稀疏扫描的完成记录。无命中不代表该区块没有自然结构。材料指纹既不是起始区块，也不是 Seed 求解结果。

[查看详细限制、操作和日志](P26_STRUCTURE_RECOGNITION.md)。

## P2.7 原创 RandomSpread 数学模型及离线有界求解

本阶段新增 `p27SolverSmokeTest`、`p27MinecraftParityTest`、`p27InputSmokeTest` 三项强制回归。每个 Minecraft 目标版本的 `p27MinecraftParityTest` 会直接对照游戏本体的候选区块计算，不沿用其它模组的破解源码。

本机准备明确来源的起始区块研究属性文件后：

```bash
gradle -PmcVersion=26.2 -PsolverInput=/path/to/solver-input.properties p27ResearchCli
```

只枚举文件中明确的低 48 位半开区间，可取消、分批续跑，列出匹配训练和独立留出约束的**低48候选**。它不读取 P2.6 的自动材料候选，也不会在游戏 tick/GUI 线程运行。**没有真正结构起点与可靠原版/自定义放置参数时，不可能可靠求出目标服务器真实 Seed。**

[P2.7 使用方法、输入样例和已知限制](P27_RANDOM_SPREAD_SOLVER.md)。

## P2.8 原创 SMT 符号种子求解

本地显式安装 Z3 命令行版（`z3 --version`）；只供离线研究，**不捆绑或自动下载到 Fabric JAR**。需要确知起始区块范围和已核对的结构集合参数。

```bash
gradle -PmcVersion=26.2 -PsolverInput=/path/to/solver-input.properties -PsmtMaxModels=12 p28SmtResearch
```

两版本 CI 增加 `p28SymbolicSmokeTest`、`p28Z3IntegrationTest`；其中后者**真正运行 Z3 并将低48候选送回 Java 精确复算与独立留出验证**。不使用矿石或假矿观察作为 Seed 约束。遇到非二幂 nextInt 的受界拒绝重采，SMT 的 UNSAT 不被视为全空间不可满足证明。

[完整实现约束、注意事项及 Seed 挖矿限制](P28_SYMBOLIC_SOLVER_AND_ORE_POLICY.md)。

## 正式验收仍需实机测试

1. 在 Minecraft 26.1.2 / 26.2 的正常客户端上装载 mod。
2. F8 / `/wsl gui` 正常打开且中文没有溢出，关闭面板后后台采样正常工作。
3. 已知 Seed 的单人世界运行，F3 所示群系和日志 sample 对应；检查 Y 轴洞穴群系。
4. 在自建自定义维度验证实际到访维度 ID、海平面、最高/最低高度，并核实已公布但未到访的维度键不会误报为探索完成。
5. 切换世界、切换维度、瞬间离线时安全停止，数据不跨服。
6. 限制扫描预算与磁盘队列、真实鞘翅高速跑图、写盘失败等场景下无卡顿或静默数据丢失。
7. 验证报告不会把原版建筑观察点伪装成真实结构生成起始区块。

开源和改动约束：[根目录《开发习惯.md》](../%E5%BC%80%E5%8F%91%E4%B9%A0%E6%83%AF.md)。

**未实现：** 结构自动检测、原版结构放置模型适配与验证、完整或部分 Seed 求解、自定义生成器参数还原；没有任何可保证破解的承诺。
