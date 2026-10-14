# WorldSeedLab · 开发、构建与验收

> 2026-10-14 状态：P3.6 正式验收及 P3.6.1 实服漏检修复已合并 `main`，最新功能 PR #28 合并 `e8d2c03d`。26.1.2/26.2 同一最新功能提交的真实 Fabric GUI 自动取证、4座受控神殿、后验 FULL + Z3/Java 均成功；未知服务器 Seed 尚未确定。

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

完整 `clean build` 会执行依赖本机 `z3` 命令的真实 SMT 回归；若当前只需生成可装载 Fabric JAR、尚未准备 Z3，可使用 `gradle --no-daemon -PmcVersion=26.2 clean assemble`（**跳过独立回归，不可据此声称测试通过**）。正常 `.jar` 位于 `build/libs/`，排除 `-sources.jar` 和开发专用 `-dev.jar`。

运行单人测试客户端：

```bash
gradle -PmcVersion=26.2 runClient
```

全部 `build` 将执行：

- `coreSmokeTest`：世界 HMAC、默认禁用、P0 模型存储。
- `p1SmokeTest`：三维 quart 群系取样、区块日志、维度隔离、背压约束。
- `p2SmokeTest`：维度档案、地形高度图、独立索引、中文报告、异步退出落盘。

**CI 产物仅供受控实验，当前不自动发布 GitHub Release。**

## P3.8 · 一键自定义地层与多建筑材料取证（新版本待CI）

- 进入服务器后按F8点击「一键联合取证」，或输入 `/wsl auto on`。本次授权同时临时开启P2.2 `STRATA`，不用再先手动切档；关闭后恢复 `probe.properties` 中原有详情模式，不覆盖偏好。
- `/wsl help` 提供分组中文指令；`/wsl auto status` 和 `/wsl status` 使用不同颜色、加粗与换行的模块播报。旧 `/wsl mark` 是人工目击、P2.6 v2 是自动材料候选、P3.6是沙漠神殿完整可见轮廓，可信度不可混用。
- 结构指纹索引升级为 `structure-candidates-v2.jsonl` 和 `structure-scanned-v2.csv`；旧v1资料保留、不覆盖。新水下结构候选必须坚持人工复制风险，不把弱候选自动升级为自然StructureStart。
- 目前 P3.8 真正离线生成器接口 `P38TerrainCandidateAudit` 需要显式提供前向地形模型；只做有限候选的条件 FIT/HOLDOUT 检查，未知Salt/生成器/完整64位仍为UNKNOWN。详见 [P3.8报告](P38_CUSTOM_TERRAIN_AND_STRUCTURES.md)。

## P3.6 一键联合取证（真正 GUI 双版本验收通过）

- 玩家进入目标服务器后主动输入 `/wsl auto on`，不再逐座手动 first/second/export；断线、切世界、维度变化默认停止。`/wsl auto status` 查看完成轮廓与**真正已落盘**数量，`/wsl auto off` 停止，`/wsl path` 定位原始私有证据目录。
- 自动取证同时开启 P1 群系、P2 基础地形高度和 P2.6 材料扫描；目前完整结构轮廓只验证沙漠神殿21×21，蓝陶瓦缺失、Y 高度差、鞘翅高速移动、被改建或人工仿造都可能误检/漏检，天然来源与生成器未知。
- P2.2 首次安装时**手动采集详细档位仍默认为 OFF**；P3.8 `/wsl auto on` 则代表用户明确授权自动联合采集，临时启用 `STRATA`，无需提前切档；关闭后恢复原有个人偏好。
- 早期 P3.6 真实联机验收 [#38534515870](https://github.com/fxjcangku/WorldSeedLab/actions/runs/38534515870) 已通过；后续 P3.6.1 修复高空扫描漏检、区块卸载诊断、同座去重，最新双版本 [真实 Fabric GUI #38549576931](https://github.com/fxjcangku/WorldSeedLab/actions/runs/38549576931) 与 [clean build #38549576956](https://github.com/fxjcangku/WorldSeedLab/actions/runs/38549576956) 全成功。所有研究仅来自受控已知 Seed=2731，未知 Salt/自定义生成器/完整64位仍待独立研究。详情见 [P3.6报告](P36_AUTOMATIC_CUSTOM_WORLD_EVIDENCE.md)。

## P3.7 · 对已有真实自定义地形证据做离线审计

只需 Python 3.12 标准库。可以离线处理已保存的单一 `observations.zip`，**不会重新加入服务器，也不会自动运行未知Salt或完整64位逆推**：

```powershell
python scripts/research_p37_custom_worldgen.py --input "D:\取证\observations.zip" --json-out "D:\取证\p37-anonymous-summary.json"
```

结果是匿名群系/高度/地层统计与严格的生成器 `UNKNOWN` 门控。真实ZIP中包含多世界、来源伪造、截断JSONL或作用域不一致时拒绝混合。离线Python脚本测试走独立 `p37-evidence-audit.yml`，不改变普通Java25/Fabric用户的Gradle构建依赖。参阅 [P3.7研究边界](P37_CUSTOM_WORLDGEN_IDENTIFIABILITY.md)。

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

P3.8结构候选 `structure-candidates-v2.jsonl` 含精确区块坐标，`structure-scanned-v2.csv` 是新密度/分类模型完成记录；此前 `v1` 文件保留作为历史，升级后不阻断再次扫描。无命中不代表该区块没有自然结构。材料指纹既不是起始区块，也不是 Seed 求解结果。

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

## 未知目标服务器仍需实际验证

1. 在 Minecraft 26.1.2 / 26.2 的正常客户端上装载 mod。
2. F8 / `/wsl gui` 正常打开且中文没有溢出，关闭面板后后台采样正常工作。
3. 已知 Seed 的单人世界运行，F3 所示群系和日志 sample 对应；检查 Y 轴洞穴群系。
4. 在自建自定义维度验证实际到访维度 ID、海平面、最高/最低高度，并核实已公布但未到访的维度键不会误报为探索完成。
5. 切换世界、切换维度、瞬间离线时安全停止，数据不跨服。
6. 限制扫描预算与磁盘队列、真实鞘翅高速跑图、写盘失败等场景下无卡顿或静默数据丢失。
7. 验证报告不会把原版建筑观察点伪装成真实结构生成起始区块。

开源和改动约束：[根目录《开发习惯.md》](../%E5%BC%80%E5%8F%91%E4%B9%A0%E6%83%AF.md)。

**研究边界：** 已有受控已知 Seed 的自动沙漠神殿完整可见轮廓、条件 low48 求解与真实 GUI 回归，但没有通用结构自然来源认证、未知 Salt 自适应求解、未知自定义生成器还原或任意目标服务器完整64位 Seed 破解保证。
