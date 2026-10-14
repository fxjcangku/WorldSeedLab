<div align="center">

<img src="assets/worldseedlab-hero-motion-v2.webp" alt="WorldSeedLab — Minecraft 世界生成研究动态横幅" width="100%" />

<br />

**从可观测的世界证据出发，研究世界生成规律与真实种子的可验证反推。**

<sub>Independent Minecraft world generation research · Fabric client · Original implementation</sub>

<br /><br />

![Project status](https://img.shields.io/badge/STATUS-RESEARCH_PREVIEW-13263F?style=flat-square&labelColor=07111F&color=087EA4)
![Minecraft](https://img.shields.io/badge/MINECRAFT-26.1.2%20%2F%2026.2-13263F?style=flat-square&labelColor=07111F&color=236A99)
![Fabric](https://img.shields.io/badge/PLATFORM-FABRIC-13263F?style=flat-square&labelColor=07111F&color=236A99)
![Java](https://img.shields.io/badge/JAVA-25-13263F?style=flat-square&labelColor=07111F&color=236A99)

</div>

---

## 01 / 为什么是 WorldSeedLab

一个服务器可以保留原版村庄、沉船和神殿，同时使用完全不同的地形与群系。对普通客户端而言，**看得到群系与建筑，不等于拿得到生成它们的 Seed**。

**WorldSeedLab** 是一个独立、开放的 Minecraft Java 世界生成研究项目，目标是探索：在没有服务器后台权限的条件下，仅依据正常客户端可以观察到的证据，能否识别世界生成规则、推导候选种子，并进行独立验证。

我们关心的不只是“得到一个数字”，而是**这个数字为什么可信、适用于哪个维度和世界生成阶段，以及哪些结论仍然无法证明**。

> [!IMPORTANT]
> **研究预览（Research Preview）。P1 客户端群系观测与 P2 维度/地形诊断框架已开发，但尚未完成真实服务器实机验收。** 目前已有 P2.7 离线低 48 位有界候选求解器，但**尚未实现高效全空间逆向、64 位完整 Seed 恢复或可直接用于服务器的自动求解**，也没有发布可用的种子破解器。

## 01.5 / 可运行研究框架与实际数据采集

P0 基础框架及 P1/P2 研发源码已提交：**F8 或 `/wsl gui`** 打开中文液态玻璃面板，主动启动后读取客户端已加载区块的真实三维群系、稀疏地形高度和当前维度信息；还会区分客户端**连接时获知**的其它维度注册键与**实际进入**的维度。可手动记录尚未验证的结构观察，并用 **`/wsl report`** 导出中文诊断报告。所有数据仅保存在本地。

- **Minecraft 26.1.2**：独立 CI 编译、核心自检。
- **Minecraft 26.2**：独立 CI 编译、核心自检。
- 使用同一份核心源码，依赖和构建目标按 Minecraft 版本分别选择。
- 启动默认**关闭采集**，离开世界或切换维度自动停止；没有自动结构识别、结构种子或完整 Seed 求解器。
- 普通客户端不能读取服务器完整生成器配置；需要跑图获取更多可观测证据，并独立验证结构分布规律。
- 目前只有双版本 CI 构建与模型自检，仍需单人世界及真实客户端 `runClient` 验收。

[开发及构建说明](docs/DEVELOPMENT.md) · [P1 真实群系采集](docs/P1_OBSERVATION.md) · [P2 跑图与维度日志](docs/P2_DIAGNOSTICS.md) · [开发习惯](%E5%BC%80%E5%8F%91%E4%B9%A0%E6%83%AF.md) · [双版本 CI](../../actions/workflows/build.yml)

## 01.6 / 更细的地形与地下剖面研究

研究预览已加入 **OFF / SURFACE / STRATA** 三级取证。在玩家显式启动采集后，可读取客户端已加载区块的方块状态、流体、群系与地下稀疏切片。地表每区块 16 列进一步保存 **WORLD_SURFACE、OCEAN_FLOOR、MOTION_BLOCKING** 三种高度图与海床方块 ID。

扩展数据以 JSONL schema=4 保存并与 P1/P2 索引隔离。每 tick 分批读取，单维度详细日志最多 256 MiB；报告只输出没有精确坐标的材料与群系统计。

**注意：** 这些只代表观察到的现存世界，不证明其自然生成来源，也不等于获取了 Seed。运行表现与真实性仍需要 Minecraft 实机测试。

[详细取证说明](docs/P2_DETAIL.md) · [中文研究报告](docs/P2_DIAGNOSTICS.md)

### 扫描质量与原版结构生成规则研究

为后续大型鞘翅跑图开发了采样覆盖与失败诊断：分别统计**本次真正采到的区块、32×32 区块宏区域、高程带样本、卸载中断和磁盘背压**，不会把“地图已经加载”错误报告为“采集覆盖率 100%”。

结构随机放置与种子算法的研发计划见 [P2.5 原版结构兼容性设计](docs/P2_5_STRUCTURE_COMPATIBILITY.md)。目前所有人工建筑标记仍然只是观察点，**还不能作为原版结构起点或完整 Seed 的证明**。

## P2.5 / 已探索真实地图

**地图 MVP 已有研究实现，仍待实机验收：** F8 面板的「真实地图」或 `/wsl map`，支持地表群系、指定 Y 高度群系和地形高度三种图层。客户端跑图实际收到并持久化的原版与自定义群系格点会显示颜色；**未采样区域始终是未知**，不会采用候选 Seed 预测地形。地图只异步加载当前视窗瓦片，可以查看手动标记的建筑观察点（不是结构生成起点）。

[查看真实地图使用说明](docs/P25_OBSERVED_MAP.md) · [P2.5 结构兼容性研究](docs/P2_5_STRUCTURE_COMPATIBILITY.md)

## P2.6 / 原创结构材料候选探测

新增已加载区块的**低成本自动材料指纹候选扫描**：可识别部分海底神殿、沙漠神殿的特殊方块组合，以及低置信度的疑似沉船线索。只需正常跑图，不读后台 Seed、也不会尝试获取未加载区块。矿石与假矿分布完全不进入结构指纹计算。

结构扫描**独立默认关闭**：用户开启 F8 主采集后，再使用 `/wsl structures on` 或大尺寸液态玻璃控制台显式开启。识别结果写入世界/维度隔离的本地证据日志。材料相符不等于自然生成，自动识别尚不能输出原版结构起始区块或真实 Seed。

[查看 P2.6 识别与假矿隔离说明](docs/P26_STRUCTURE_RECOGNITION.md)

## P2.7 / 原创结构种子数学引擎

首次实现独立的 Java 48 位随机数模型、`RandomSpreadStructurePlacement` 潜在区块前向计算，以及具有训练/留出约束、超时、取消和精确续跑位置的**有限低 48 位窗口候选求解器**。

**26.1.2 / 26.2 对照测试：** 每版都直接调用目标 Minecraft 自身的 RandomSpread 放置实现，并用 162 组输入对照原创算法；但这只证明了**潜在结构放置区块的数学一致性**，不等于结构已经生成或完整 Seed 已被恢复。

当前支持开发者离线输入结构集合参数与已知起始区块范围；P2.6 普通建筑材料候选**不能自动当作精确起点**。没有经过确认的生成器参数与结构起始区块，不能给出可靠的服务器 Seed。

[查看 P2.7 算法与离线求解器](docs/P27_RANDOM_SPREAD_SOLVER.md)

## P2.8 / 原创位向量 Seed 反演研究

P2.7 已确认的 Minecraft Legacy RandomSpread 数学模型现可转换成**可执行的 48 位 SMT 约束**。P2.8 接入可选的本机 Z3 后端，采用**结构起点 FIT 训练 + HOLDOUT 独立验证 + Java RNG 精确复核**，寻找低48种子候选；它不同于简单逐个枚举所有种子。

两个 Minecraft 目标版本均通过真正的 Z3 受控已知 Seed 实验，**但未实现从普通玩家的材料观察自动定位可信结构起点，也未恢复任何未知服务器的完整64位 Seed**。自定义服务器 Salt、世界生成器或矿物规则一旦未知，不能把候选说成后台实际种子。

[查看原创 SMT 求解和真假矿边界](docs/P28_SYMBOLIC_SOLVER_AND_ORE_POLICY.md)

## P2.9 / 真实建筑起点的几何约束（研发中）

已开始实现**完整水平轮廓 + 已知 Seed 受控结构真值校准 → 多朝向离散起点假设 → 原创 Z3 约束与 Java FIT/HOLDOUT 复核**。不把不同朝向之间的空白区块填成合法起点；保留负坐标、世界/维度/版本隔离和有界求解。

**P2.9 已通过两版真实 Minecraft 离线世界生成实验：** Seed `2731` 的原版沙漠神殿共获取每版 4 条保存的真实 StructureStart（包含建筑部件边界框），经真实 Z3 以 2 FIT + 1 独立 HOLDOUT 找回低48候选，错误 Seed 被否决。与仅用合成建筑坐标测试不同，这次结构起点来自 Minecraft 真正生成的区块存档。**但仍未完成 GUI 单人客户端观察、玩家可见建筑轮廓提取、任何未知服务器完整 Seed 恢复。** 普通客户端不能直接读取服务器的 StructureStart NBT，自定义 Salt/算法与矿脉规则仍需独立研究。

[P2.9 几何逆推说明](docs/P29_GEOMETRY_INVERSION.md) · [真实 Minecraft 受控世界验收报告](docs/P29_REAL_WORLD_KNOWN_SEED.md)

## P3.3 / 真正 Fabric 联机 GUI 群系取证成功

**Minecraft 26.1.2 和 26.2 的实际客户端联机端到端测试已通过**：真实 Fabric 图形客户端连接公开已知 Seed 的受控原版专用服务器，使用 Minecraft 聊天框执行五次 `/wsl quart sample`，再执行 `/wsl quart export`，独立回放真实 `CLIENT_OBSERVED` CSV。两版均与服务器真实 FULL 区块群系五处完全一致，在有前提的原版模型下，有限高16窗口保留正确64位 Seed `2731` 并排除窗口内错误值。

这与 P3.1 从受控服务端存档的全部65,536高16位验证**不同**；本次客户端真实取证只对 `[0,8)` 搜索，且结构low48先验来自既有 P2.9。**不是已破解未知自定义服务器**。

[查看真实游戏内输入与CSV逐点验收](docs/P33_REAL_FABRIC_CLIENT_E2E.md) · [成功的双版本CI](https://github.com/fxjcangku/WorldSeedLab/actions/runs/38515523790)

## P3.2 / 客户端真实群系取证与负数完整64位验证

在普通 Fabric 客户端新增**手动读取已接收FULL区块的quart群系**：`/wsl quart sample`、`/wsl quart export`、`/wsl quart clear`。默认关闭，5处不同地区（3 FIT / 2 HOLDOUT、相隔至少1024方块）才允许本地导出；复用匿名HMAC世界指纹和世界纪元隔离。客户端不会主动下载区块、读取服务器Seed或自动上传坐标。

离线新增“**经过Z3复核的结构low48 + 用户明确确认的原版Overworld模型假设 + quart群系high16**”研究入口，并在**真实Minecraft两版生成的负数完整64位受控世界**中验证 `0xBEEF000000000AAB`（`-4688528687069394261`）的高位保留与错误高位排除。**受控世界服务端NBT正反验收不等于未知服务器完整Seed已破解，也不等于GUI玩家采集已实测。**

[查看P3.2代码、测试与隐私边界](docs/P32_CLIENT_QUART_NEGATIVE_FULL64.md)

## P3.1 / 完整高16位的真实原版群系分片反演

**已通过双版本完整65,536高16位真实原版群系模型搜索**：26.1.2和26.2各16分片，32片全部通过，独立审计确保无缺口、无重复、输入实际 FULL 世界群系 SHA 一致。每版仅剩1个完整64位模型候选 `2731`，正确匹配受控已知 Seed。代码包括取消/预算/恢复与独立FIT/HOLDOUT。

**研发中的全域验证不代表恢复了未知服务器真实 Seed**；所有结果受原版生成器假设、证据覆盖和未知 Salt/世界生成机制限制。真实客户端 `runClient` 及自定义地形仍是单独目标。

[查看 P3.1 双版本全域验证协议](docs/P31_REAL_HIGH16_EXHAUSTIVE.md)

## P3.0 / 64位独立证据反演研究

在已验证的低48位结构候选上，新增**完整64位候选位域、高16位65,536种组合、三维群系证据 FIT/HOLDOUT、有限搜索与断点续跑**。代码通过严格的证据类型和生成器作用域检查，避免把未知 Salt 或玩家目击的建筑位置冒充真实种子。

**目前已接入两版 Minecraft 原生 Overworld 气候群系模型，并用已生成、已保存的真实世界区块群系独立验证高16位敏感性。** 受控已知 Seed `2731` 的 5 个真实群系点通过 3 FIT / 2 HOLDOUT，在高16位 `[0,2)` 范围保留正确的完整64位候选并排除错误高位。模拟控制算法另外验证了 65,536 种高16位枚举逻辑，但**真实原版生成器尚未穷尽全部65,536种高位，也尚未从远程普通客户端数据恢复未知服务器的完整世界 Seed**。

[查看 P3.0 的准确能力与后续高16位验证条件](docs/P30_FULL64_EVIDENCE_RESEARCH.md)

## P3.5 / 普通客户端建筑轮廓真实取证（研发测试中）

新增独立的手动沙漠神殿轮廓取证：**/wsl footprint first / second / export / clear**。按玩家授权的两角区域只读已经完整加载的实际方块，外侧一圈与四条可见边缘一起审计；一旦区块缺失、遮挡或取证中断，即拒绝把残缺轮廓升级为结构起点候选。原始证据在用户显式导出时只存本机 HMAC 世界与维度隔离目录，不会主动加载新区块或上传。

完整的可见方块外沿可接入 P2.9 原有的**已校准、不同朝向离散起点并集**，不会复用服务端 StructureStart 当成客户端证据。天然生成来源始终 UNKNOWN，不能区分人工复制。当前新增双版本真实客户端/受控服务器 CI 方案与正反例，**是否通过必须以对应工作流实际结果为准；不能将代码已提交说成真实联机验收通过**。

[查看 P3.5 真正客户端可见轮廓取证和验收规范](docs/P35_CLIENT_VISIBLE_DESERT_FOOTPRINT.md)

## P3.4 / 自定义地形兼容性与结构证据防误报（受控实机双版本已验收）

WorldSeedLab 已建立新的**只读、显式离线执行**的双轴研究流程。结构侧使用 P2.9 完整建筑轮廓推导的稀疏起点候选，调用原创 RandomSpread 数学复核；群系侧对独立真实 quart 观测使用**明示的原版 Overworld 模型假设**。结构匹配与群系匹配必须分别报告，不能互相替代。

- 新增 `MODEL_CONSISTENT`（仅模型一致）、`INSUFFICIENT_EVIDENCE`（证据不足）、`MODEL_CONFLICT`（指定模型冲突）、`GENERATOR_UNKNOWN`（生成器身份/生成历史未知）的明确状态。
- 对未知 Salt、旧区块或预生成区块、人工建筑、跨世界/维度/版本、重复 Region 和不可信轮廓严格降级或拒绝。
- **26.1.2 与 26.2 真实 Minecraft 自定义平坦沙漠服务器实验均通过**：服务端 FULL Anvil 区块确认地层改变、沙漠神殿 StructureStart 继续满足原版 RandomSpread，实际自定义群系与原版 MultiNoise 假设冲突。
- **两版本真实 Fabric GUI 客户端联机也通过**：在隔离的自定义地形服务器由真正游戏聊天框手动执行 5 次 `/wsl quart sample` 和 1 次 `/wsl quart export`，5/5 CLIENT_OBSERVED 均与服务器另行存储的 FULL 区块匹配，3 FIT + 2 HOLDOUT。**这是受控 Seed=2731 世界的兼容性验收，不是未知服的完整64位 Seed 恢复。**
- 所有匿名审计摘要不输出私人坐标、世界指纹或 Seed；普通客户端不会自动访问新区块，也不会自动上传证据。

**P3.4.1 新增真实修改 Salt 的双版本服务器对照，已通过**：两版 Minecraft 都确实启用了实验数据包，真实保存的7条沙漠神殿StructureStart均匹配修改后的Salt `14357844`，并全部拒绝原版Salt `14357617`。见[真实修改Salt研究报告](docs/P34_REAL_MODIFIED_SALT_CONTROL.md)与[实际Actions #38520815668](https://github.com/fxjcangku/WorldSeedLab/actions/runs/38520815668)。这不代表未知目标服务器Salt已被破解。

[详细技术与测试边界](docs/P34_CUSTOM_WORLDGEN_COMPATIBILITY.md) · [真实自定义服务端 Actions #38518930209](https://github.com/fxjcangku/WorldSeedLab/actions/runs/38518930209) · [真实自定义客户端 Actions #38518930253](https://github.com/fxjcangku/WorldSeedLab/actions/runs/38518930253) · [P3.4 PR #21](https://github.com/fxjcangku/WorldSeedLab/pull/21)

## 02 / 研究边界

| 研究对象 | 我们希望回答的问题 |
| :--- | :--- |
| **世界生成指纹** | 服务器的地形、群系、结构规则中，哪些仍与原版兼容？ |
| **原版及自定义群系** | 客户端实际观察到什么？注册表与世界中真实生成的群系有什么区别？ |
| **结构证据** | 沉船、神殿等结构的观察坐标，是否能被原版结构放置规则解释？ |
| **结构种子** | 能否依据适用的随机规则恢复部分种子信息？ |
| **完整世界 Seed** | 是否存在足够的独立证据恢复并确认完整 64 位种子？ |
| **自定义生成器** | 即使 Seed 已知，还缺少哪些生成器参数和数据包规则？ |

**三个不可混淆的结果：** 结构种子 ≠ 完整世界 Seed ≠ 可复现的自定义世界。

## 03 / 研究体系

```text
Minecraft Java / Fabric 客户端
             │
             ▼
   World Probe · 观测采集
             │
     ┌───────┴────────┐
     ▼                ▼
Biome Evidence   Structure Evidence
群系 / 区块证据    结构 / 位置证据
     └───────┬────────┘
             ▼
 Generation Compatibility
     生成规则兼容性分析
             │
             ▼
 Seed Candidate Research
   候选结构种子 / 世界种子
             │
             ▼
  Independent Verification
      独立验证与证据报告
```

世界探测与本地证据存储已具备实验性实现；结构生成模型、种子求解和独立验证仍是研发计划。探测、求解和验证分层隔离，避免用“候选结果”冒充“已验证结果”。

## 04 / 版本与技术路线

| 项目 | 研究目标 |
| :--- | :--- |
| Minecraft Java | **26.1.2 / 26.2**（计划双版本支持） |
| 模组平台 | Fabric 客户端，独立安装运行 |
| 编程语言 | Java 25 |
| 映射标准 | Mojang 官方映射（Mojmap） |
| UI / 文档 | 中文优先 |
| 数据处理 | 本地优先，服务器与维度数据隔离 |
| 仓库与开发 | 独立原创项目，不依赖 yiyiaddon |

> 双版本为**计划兼容目标**，目前尚无经过构建和游戏实测的发行版。

## 05 / 开发路线

| 阶段 | 研究目标 | 状态 |
| :--- | :--- | :--- |
| **P0 · Foundation** | 基础框架、验证规范和双版本构建 | 已通过 CI，待实机 |
| **P1 · Observation** | 群系扫描、人工结构观察、世界维度隔离 | 已通过 CI，待实机 |
| **P2 · Diagnostics** | 自定义维度、地形高度图、研究日志与兼容性前置取证 | 研究预览，待实机 |
| **P2.5 · Compatibility** | 原版生成算法独立复现、结构兼容性验证 | 待研究 |
| **P3 · Seed Research** | 候选求解、结构种子与完整 Seed 研究 | 待研究 |
| **P4 · Verification** | 独立样本复核、报告与失败诊断 | 待开发 |

详细计划见 [研究路线图](docs/ROADMAP.md)，测试方法见 [验证原则](docs/RESEARCH_PROTOCOL.md)。

## 06 / 为什么强调验证

自定义地形服务器可能修改结构间距、随机盐值、群系判定或其他生成参数。地图还可能经历跨版本升级、区块预生成、世界重置或人工建筑放置。

因此，WorldSeedLab 遵循：

- **观察不是证明。** 客户端看到建筑，不等于得知其服务器生成来源。
- **候选不是答案。** 必须保留独立结构证据进行复核。
- **失败也是结果。** 不兼容、证据不足和无法确认需要清晰报告。
- **不做虚假承诺。** 并非所有自定义服务器都存在可逆向恢复的单一 Seed。
- **原创实现。** 不复制其他种子破解工具的源码；可以阅读公开规范、游戏源实现与技术资料以确认规则。

## 07 / 隐私与负责任研究

项目计划默认在本地存储和处理观测证据；不主动上传服务器地址、地理坐标、世界数据或 Seed。公开讨论与贡献时应避免泄露未经同意的私人服务器信息，并遵守服务器规则。

研究仅使用普通客户端能够合法观察到的数据，不提供获取管理权限、读取服务器私有文件或绕过访问控制的功能。

## 08 / 参与研究

目前仓库用于公布研究定位、验证方法与后续实现计划。随着源码和可复现测试逐步开放，欢迎提交可公开分享的规则分析、反例、测试思路和文档修正。

**目前没有编译产物或可下载安装的 Release。** 请不要把研究阶段的预期功能视作已经可用。

---

<div align="center">

<sub>WORLDSEEDLAB · OBSERVE THE WORLD · VERIFY THE SEED</sub>

<sub>Independent research · Built for understanding, not guessing.</sub>

</div>
