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

**[v0.1.0 初代研究预览 · 下载与发布说明](https://github.com/fxjcangku/WorldSeedLab/releases/tag/v0.1.0)**

请选择对应 **Minecraft 26.1.2 / 26.2** 的 JAR，两个版本不要同时安装。客户端需要 **Java 25、Fabric Loader ≥0.19.5** 与对应版本的 Fabric API（本次构建使用 `0.155.3+26.1.2` / `0.161.0+26.2`）。普通安装者无需安装 Z3；它仅用于显式执行的离线研究。

## 01 / 为什么是 WorldSeedLab

一个服务器可以保留原版村庄、沉船和神殿，同时使用完全不同的地形与群系。对普通客户端而言，**看得到群系与建筑，不等于拿得到生成它们的 Seed**。

**WorldSeedLab** 是一个独立、开放的 Minecraft Java 世界生成研究项目，目标是探索：在没有服务器后台权限的条件下，仅依据正常客户端可以观察到的证据，能否识别世界生成规则、推导候选种子，并进行独立验证。

我们关心的不只是“得到一个数字”，而是**这个数字为什么可信、适用于哪个维度和世界生成阶段，以及哪些结论仍然无法证明**。

> [!IMPORTANT]
> **v0.1.0 初代研究预览（Research Preview）。** 已实现客户端群系/地形观测、已探索地图和一次授权自动沙漠神殿轮廓取证；离线低48与有前提的完整64位研究已有双版本受控已知 Seed 验证。**未知自定义服务器的生成器、Salt 与完整64位 Seed 仍未证明，客户端不会自动给出真实服务器 Seed。** 本轮发行包未在本机实机验收；已有图形客户端实证来自下文链接的隔离受控 CI 实验。

## 01.5 / 可运行研究框架与实际数据采集

**F8 或 `/wsl gui`** 打开中文液态玻璃面板，主动启动后读取客户端已加载区块的真实三维群系、稀疏地形高度和当前维度信息；还会区分客户端**连接时获知**的其它维度注册键与**实际进入**的维度。输入 **`/wsl auto on`** 可一次授权联合采集群系、基础地形、结构材料线索与完整可见沙漠神殿轮廓。可用 **`/wsl map`** 查看已保存地图，**`/wsl report`** 导出中文诊断报告。所有数据仅保存在本地。

- **Minecraft 26.1.2**：已有独立 CI 构建、自检与受控真实 Fabric GUI 取证验收。
- **Minecraft 26.2**：已有独立 CI 构建、自检与受控真实 Fabric GUI 取证验收。
- 使用同一份核心源码，依赖和构建目标按 Minecraft 版本分别选择。
- 启动默认**关闭采集**，离开世界或切换维度自动停止；自动取证只保存客户端观察证据，不会自行运行离线种子求解。
- 普通客户端不能读取服务器完整生成器配置；需要跑图获取更多可观测证据，并独立验证结构分布规律。
- 已有真实客户端受控联机实验不代表任意目标服务器已验收；本轮发行包未本机实机，未知服与高速跑图表现仍需验证。

[开发及构建说明](docs/DEVELOPMENT.md) · [P1 真实群系采集](docs/P1_OBSERVATION.md) · [P2 跑图与维度日志](docs/P2_DIAGNOSTICS.md) · [开发习惯](%E5%BC%80%E5%8F%91%E4%B9%A0%E6%83%AF.md) · [双版本 CI](../../actions/workflows/build.yml)

## 01.6 / 更细的地形与地下剖面研究

研究预览已加入 **OFF / SURFACE / STRATA** 三级取证。在玩家显式启动采集后，可读取客户端已加载区块的方块状态、流体、群系与地下稀疏切片。地表每区块 16 列进一步保存 **WORLD_SURFACE、OCEAN_FLOOR、MOTION_BLOCKING** 三种高度图与海床方块 ID。

扩展数据以 JSONL schema=4 保存并与 P1/P2 索引隔离。每 tick 分批读取，单维度详细日志最多 256 MiB；报告只输出没有精确坐标的材料与群系统计。

**注意：** 这些只代表观察到的现存世界，不证明其自然生成来源，也不等于获取了 Seed。P3.6 已验证受控联机的基础群系与地形落盘；详细地下剖面和实际跑图表现仍需单独实机验证，首次详细取证档位默认 `OFF`。

[详细取证说明](docs/P2_DETAIL.md) · [中文研究报告](docs/P2_DIAGNOSTICS.md)

### 扫描质量与原版结构生成规则研究

为后续大型鞘翅跑图开发了采样覆盖与失败诊断：分别统计**本次真正采到的区块、32×32 区块宏区域、高程带样本、卸载中断和磁盘背压**，不会把“地图已经加载”错误报告为“采集覆盖率 100%”。

结构随机放置与种子算法的研发计划见 [P2.5 原版结构兼容性设计](docs/P2_5_STRUCTURE_COMPATIBILITY.md)。目前所有人工建筑标记仍然只是观察点，**还不能作为原版结构起点或完整 Seed 的证明**。

## P2.5 / 已探索真实地图

**地图 MVP 已实现，视觉与交互仍待专项实机验收：** F8 面板的「真实地图」或 `/wsl map`，支持地表群系、指定 Y 高度群系和地形高度三种图层。客户端跑图实际收到并持久化的原版与自定义群系格点会显示颜色；**未采样区域始终是未知**，不会采用候选 Seed 预测地形。地图只异步加载当前视窗瓦片，可以查看手动标记的建筑观察点（不是结构生成起点）。

[查看真实地图使用说明](docs/P25_OBSERVED_MAP.md) · [P2.5 结构兼容性研究](docs/P2_5_STRUCTURE_COMPATIBILITY.md)

## P2.6 / 原创结构材料候选探测

新增已加载区块的**低成本自动材料指纹候选扫描**：可识别部分海底神殿、沙漠神殿的特殊方块组合，以及低置信度的疑似沉船线索。只需正常跑图，不读后台 Seed、也不会尝试获取未加载区块。矿石与假矿分布完全不进入结构指纹计算。

结构扫描**默认关闭**：用户开启 F8 主采集后，再使用 `/wsl structures on` 或大尺寸液态玻璃控制台显式开启；`/wsl auto on` 也会一并授权开启材料扫描。识别结果写入世界/维度隔离的本地证据日志。材料相符不等于自然生成，P2.6 材料候选本身不能输出原版结构起始区块或真实 Seed。

[查看 P2.6 识别与假矿隔离说明](docs/P26_STRUCTURE_RECOGNITION.md)

## P2.7 / 原创结构种子数学引擎

首次实现独立的 Java 48 位随机数模型、`RandomSpreadStructurePlacement` 潜在区块前向计算，以及具有训练/留出约束、超时、取消和精确续跑位置的**有限低 48 位窗口候选求解器**。

**26.1.2 / 26.2 对照测试：** 每版都直接调用目标 Minecraft 自身的 RandomSpread 放置实现，并用 162 组输入对照原创算法；但这只证明了**潜在结构放置区块的数学一致性**，不等于结构已经生成或完整 Seed 已被恢复。

当前支持开发者离线输入结构集合参数与已知起始区块范围；P2.6 普通建筑材料候选**不能自动当作精确起点**。没有经过确认的生成器参数与结构起始区块，不能给出可靠的服务器 Seed。

[查看 P2.7 算法与离线求解器](docs/P27_RANDOM_SPREAD_SOLVER.md)

## P2.8 / 原创位向量 Seed 反演研究

P2.7 已确认的 Minecraft Legacy RandomSpread 数学模型现可转换成**可执行的 48 位 SMT 约束**。P2.8 接入可选的本机 Z3 后端，采用**结构起点 FIT 训练 + HOLDOUT 独立验证 + Java RNG 精确复核**，寻找低48种子候选；它不同于简单逐个枚举所有种子。

两个 Minecraft 目标版本均通过真正的 Z3 受控已知 Seed 实验。**普通材料线索仍不能直接成为可信结构起点；P3.5/P3.6 的完整可见轮廓只在独立校准和参数前提下形成离散起点候选，尚未恢复任何未知服务器的完整64位 Seed。** 自定义服务器 Salt、世界生成器或矿物规则一旦未知，不能把候选说成后台实际种子。

[查看原创 SMT 求解和真假矿边界](docs/P28_SYMBOLIC_SOLVER_AND_ORE_POLICY.md)

## P2.9 / 真实建筑起点的几何约束（受控研究已验证）

已实现**完整水平轮廓 + 已知 Seed 受控结构真值校准 → 多朝向离散起点假设 → 原创 Z3 约束与 Java FIT/HOLDOUT 复核**。不把不同朝向之间的空白区块填成合法起点；保留负坐标、世界/维度/版本隔离和有界求解。

**P2.9 已通过两版真实 Minecraft 离线世界生成实验：** Seed `2731` 的原版沙漠神殿共获取每版 4 条保存的真实 StructureStart（包含建筑部件边界框），经真实 Z3 以 2 FIT + 1 独立 HOLDOUT 找回低48候选，错误 Seed 被否决。与仅用合成建筑坐标测试不同，这次结构起点来自 Minecraft 真正生成的区块存档。后续 **P3.5/P3.6 已完成真实受控 GUI 联机的玩家可见轮廓提取与独立起点复核**；任何未知服务器完整 Seed 恢复仍未证明。普通客户端不能直接读取服务器的 StructureStart NBT，自定义 Salt/算法与矿脉规则仍需独立研究。

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

**受控全域验证不代表恢复了未知服务器真实 Seed**；所有结果受原版生成器假设、证据覆盖和未知 Salt/世界生成机制限制。后续真实客户端及受控自定义地形验收分别见 P3.3–P3.6，不能扩大为任意目标服结论。

[查看 P3.1 双版本全域验证协议](docs/P31_REAL_HIGH16_EXHAUSTIVE.md)

## P3.0 / 64位独立证据反演研究

在已验证的低48位结构候选上，新增**完整64位候选位域、高16位65,536种组合、三维群系证据 FIT/HOLDOUT、有限搜索与断点续跑**。代码通过严格的证据类型和生成器作用域检查，避免把未知 Salt 或玩家目击的建筑位置冒充真实种子。

**已接入两版 Minecraft 原生 Overworld 气候群系模型，并用已生成、已保存的真实世界区块群系独立验证高16位敏感性。** P3.0 初期受控已知 Seed `2731` 的 5 个真实群系点通过 3 FIT / 2 HOLDOUT，在高16位 `[0,2)` 范围保留正确的完整64位候选并排除错误高位。后续 P3.1 已在相同已知低48及明示原版模型前提下，完成两版全部65,536种高位的真实模型搜索；**仍未从普通远程客户端数据恢复未知服务器的完整世界 Seed**。

[查看 P3.0 的准确能力与后续高16位验证条件](docs/P30_FULL64_EVIDENCE_RESEARCH.md)

## P4.1 / 自定义气候生成器的条件高16位分片反演

P4.1 将 P3.9 **真实客户端建筑轮廓 → Z3 low48 → Java FIT/HOLDOUT** 与 P4.0 自定义 MultiNoise 前向模型联动；模型参数须由研究者合法、独立取得。成功得到可复核 low48 后，不再需要手工输入完整64位列表，而是在真实 Minecraft 26.1.2 / 26.2 的气候噪声上按分片实际枚举高16位，精确输出下一次续算位置与证据/模型摘要。使用 3FIT + 2HOLDOUT 的独立客户端quart数据排除不匹配候选。

~~~powershell
python scripts/recover_seed.py --input "D:\private\observations.zip" --minecraft 26.2 --custom-model "D:\private\climate.json" --high16-shard 0 --high16-shards 16
~~~

**未知服务器真正生成器/NoiseRouter、结构Salt、生成历史及建筑自然来源尚未认证。** 如果像当前真实实服观测一样，没有≥3个独立RandomSpread区域的完整结构轮廓，程序必须返回低48证据不足，而非冒充解出Seed。自定义模型高16仅为条件研究，不降低原有强安全门槛，不自动推断全部64位。

[详细算法、跨次断点与验收说明](docs/P41_CONDITIONAL_CUSTOM_HIGH16.md)

## P4.2 / 自定义生成器可辨识性实证审计与高度取证修正

已实现一个完全只读的离线生成器假设审计器，利用同世界的P1真实quart群系、P2地表与STRATA地下方块进行完整流式校验：重复/矛盾位置、三证据链交叉覆盖、已声明候选模型的世界高度和群系集合冲突。支持修复旧版错误标记为排他上界的 getMaxY() 历史记录，未来取证改为 minY+getHeight()。**模型没有被排除不等于识别生成器；仍不会在未知Salt/NoiseRouter/自然StructureStart条件下伪报64位Seed。**

实服第二轮匿名回放：2,085,248条真实quart均不重复、61种群系含27种非原版、46处三链共同宏区域、9处保守空间独立地区。客户端原始高度上界639为历史含上界错误记录，应规范化为排他上界640；完全未修改原版Overworld已被排除，但服务端可能仍复用部分原版NoiseRouter。详情见[生成器识别与可辨识性P4.2](docs/P42_GENERATOR_IDENTIFIABILITY.md)。

## P3.9 / 真实客户端证据直接进行种子反演（本机Z3，不再手工填写起点）

本轮新增 **真正的反演执行入口**，输入 `observations.zip` / 本机 HMAC 世界原始取证目录即可。自动读取完整21×21的 P3.6/P3.5 真实方块轮廓，按受控真世界几何校准建立独立 RandomSpread FIT/HOLDOUT，实际调用 Z3 求低48位候选，再由精确 Java RNG 复核；可选真实quart+显式原版生成器假设进行高16条件筛选。**这并不证明未知自定义服务器生成器/建筑自然来源/Salt。**

```powershell
python scripts/recover_seed.py --input "D:\WorldSeedLabData\observations.zip" --minecraft 26.2
```

**首次真实源码 E2E：** [P3.9 两版本真实Minecraft客户端→Z3求解](https://github.com/fxjcangku/WorldSeedLab/actions/runs/38567833619) 已在26.1.2公开受控世界用四份实际客户端轮廓计算回 `LOW48_MODEL_CANDIDATE=0x000000000aab`（十进制2731，3FIT+1HOLDOUT）。CI采用受控窗口0～4096，真正目标服运行默认全低48模型域，但有严格计算上限，超时/截断不可误报已穷举。最终版本成功状态以PR #34最新CI为准。

**针对你上传的首批真实 `observations.zip`：完整神殿轮廓文件为0**，因此会安全报告 `RESULT=INSUFFICIENT_GEOMETRY_EVIDENCE`，不能凭大量Terralith群系和高度图直接编造世界Seed。自定义世界高16位仍需可复算的真实生成器算法，而不是再增加更多花哨UI。详见 [P3.9直接求解说明](docs/P39_REAL_CLIENT_SEED_RECOVERY.md)。

## P3.8 / 全中文多结构自动取证与自定义地形联合研究（研发验收中）

**P3.8 地形核心增强（叠加研发 PR #33）**：`/wsl auto on` 不再仅收集P1群系/P2高度，而是临时开启P2.2真实地下STRATA与海床多高度图，停止后恢复用户此前的详细取证设置、不修改配置文件。新增 `P38TerrainCandidateAudit` 纯离线前向地形模型接口，对同一世界与版本的真实16列高度图、空间间隔≥1024格的2 FIT + 1 HOLDOUT进行有限候选Seed排除；任何未独立验证的生成器/未知Salt均保留UNKNOWN。**现阶段不具备未知自定义生成器的可靠逆函数，也没有证明已破解完整64位Seed**。此项必须通过真实双版本GUI的一次授权、实际STRATA落盘与独立负例后方可标为正式支持。[实现边界](docs/P38_CUSTOM_TERRAIN_AND_STRUCTURES.md)。


**进入服务器只需一次 `/wsl auto on`，不用逐座标记建筑。** P3.8 扩展自动发现：海洋神殿、沉船、冷暖海底遗迹、水下废弃传送门，并对远古城市、试炼密室、末地城、下界要塞、堡垒遗迹等提供有界材料指纹候选。水域已加载FULL区块按地表与海床高度图增加有界稀疏采样，候选自动进入本机 HMAC 世界/维度隔离的 `structure-candidates-v2.jsonl`。旧v1候选继续保留，v2不会因为旧扫描结果漏检新模型。

**请严格区分三层证据**：①自动材料候选（可能由玩家/插件仿造）②已验证完整的客户端建筑轮廓（目前仅严格验证沙漠神殿21×21）③后验独立证实的服务端StructureStart（只能在受控实验中读取）。海洋神殿、海底遗迹和沉船**目前没有获得同等级的完整天然结构认证**，不能直接用于随机结构低48位约束。

**界面重做**：F8液态玻璃面板把“一键自动取证”置顶，区分群系、建筑候选、完整轮廓和真正落盘；新增 `/wsl help`、`/wsl structures`、`/wsl auto status` 的中文加粗、彩色、逐行播报，绿色成功/青色结构/黄色警告，避免一大串白色状态。用户可正常探索自定义地形，P1/P2/STRATA的独立群系、地表、地下证据仍是完整64位生成器研究的核心。

P3.8 **正在进行双版实际验证**，以 [研发PR #32](https://github.com/fxjcangku/WorldSeedLab/pull/32) 最新CI为准；受控材料自动识别不等于未知目标服务器种子被破解。不开新Release、不支持26.3。

## P3.7 / 自定义地形生成器与完整64位可辨识性（P3.7.0 离线审计已验收）

**不再只分析沙漠神殿。** P3.7 新增本机只读 `scripts/research_p37_custom_worldgen.py`，可对 P1 真实 quart 群系、P2 真实 WORLD_SURFACE 高度和显式开启的 STRATA 地层进行分源审计：统计独立宏区域、检测非原版群系注册ID、识别同一块重复高度的可疑变化，区分“未修改原版群系模型不适用”“生成器未知”“完整64位无法辨识”。输出匿名中文摘要或JSON，不泄露私人坐标、世界HMAC或地址。

```powershell
python scripts/research_p37_custom_worldgen.py --input "D:\取证\observations.zip" --json-out "D:\取证\p37-anonymous-summary.json"
```

当前 P3.7.0 的 [12项离线正反例](https://github.com/fxjcangku/WorldSeedLab/actions/runs/38557758349) 已全部成功（含用户原始ZIP防覆盖、跨世界HMAC拒绝、1024方块空间独立门槛）。此外已下载**同一CI校验的分析脚本**在本机完整只读回放用户自愿提供的真实 26.2 观测ZIP；客户端证据格式、流式处理和匿名输出均通过，原始坐标、HMAC与私人文件没有提交到公开仓库。该档案缺失 STRATA 细节，**没有实现未知自定义生成器的前向逆函数，也没有从未知服务器恢复64位Seed**。详见 [P3.7研究方法](docs/P37_CUSTOM_WORLDGEN_IDENTIFIABILITY.md)。

## P3.6 / 自定义地形与建筑自动联合取证（双版本受控实机已通过）

**P3.6.2 长期跑图加强已通过双版受控验收：** 已扫描区块不再达到4096就停止整个自动模式，改为4096容量的滚动近期缓存，旧区块再次经过可能被重扫（完成批次≠全图唯一覆盖）。依然严格保留256次额外BlockState/Tick、64份完整轮廓和256条蓝陶瓦线索等安全限制。[双版Java25 clean build](https://github.com/fxjcangku/WorldSeedLab/actions/runs/38556249266) 与 [真实Fabric GUI自动四座神殿+FULL+Z3](https://github.com/fxjcangku/WorldSeedLab/actions/runs/38556249269) 已成功。这不是无限空间或磁盘的保证。

**WorldSeedLab 的最终目标是研究自定义地形服务器的生成器和世界 Seed。** 沙漠神殿、海底神殿只能在其结构放置算法经过验证的前提下作为 low48 的条件约束；实际地形高度、群系、地层和未知的噪声映射必须单独取证。不能把识别建筑说成恢复了自定义地形的64位后台Seed。

新增**一次手动授权 /wsl auto on**：玩家无需逐座选择两个角。客户端只在已经加载 FULL 区块中有界识别真实蓝陶瓦，自动尝试29×29粗区域→唯一21×21完整可见沙漠神殿外缘，并异步保存本机CLIENT_OBSERVED。同时自动开启现有 P1/P2 真实群系与地形、P2.6 低成本材料候选。切服/维度更改或无法确认完整性即停止/拒绝，不会请求新区块、上传坐标、读取服务端StructureStart或自动求解Seed。

**P3.6.1 高空漏检与取证诊断修复已通过真实双版本验收：** [PR #28](https://github.com/fxjcangku/WorldSeedLab/pull/28) 已合并（`e8d2c03d`）。客户端按每列已加载区块 `WORLD_SURFACE` 规划32格材料探测带，不再以鞘翅玩家Y选取唯一扫描层；将区块卸载中断与真正轮廓不完整分开统计，并在同一会话内对验证过的完整轮廓去重。[最新26.1.2/26.2双版本 clean build](https://github.com/fxjcangku/WorldSeedLab/actions/runs/38549576956) 和 [真实 Fabric GUI 自动取证、四座神殿、Z3 + Java RNG](https://github.com/fxjcangku/WorldSeedLab/actions/runs/38549576931) 均 `success`。这不是地下完整扫描或未知自定义服务器 Seed 恢复，高速飞行和埋藏目标仍可能漏检。

**2026-10-14 P3.6 最终合并验收：** [PR #25](https://github.com/fxjcangku/WorldSeedLab/pull/25) 已合并；最终源码 `fd481292` 的 [双版构建 #38534515840](https://github.com/fxjcangku/WorldSeedLab/actions/runs/38534515840) 与 [真实双版 Fabric GUI 自动取证 #38534515870](https://github.com/fxjcangku/WorldSeedLab/actions/runs/38534515870) 均 success，合并后 main `24e4fb34` 的 [双版 clean build #38535882551](https://github.com/fxjcangku/WorldSeedLab/actions/runs/38535882551) 也 success。每版本对已知 Seed=2731 的受控自定义沙漠服务器只执行一次 `/wsl auto on`，自主保存4座完整21×21神殿轮廓，后验 FULL/StructureStart 4/4、真 Z3+Java RNG 2FIT+1HOLDOUT 通过；26.1.2 落盘 107,520 条群系/144 个地形区块/P2.6 扫描104区块，26.2 落盘 105,984 条群系/148 个地形区块/P2.6 扫描106区块。**P2.2 详细地下剖面默认 OFF；若要采集地层，须在 `/wsl auto on` 前选择 `STRATA`**。这只验证受控已知世界的取证链，不代表已破解未知自定义服务器或完整64位 Seed。

[自动命令与模型限制](docs/P36_AUTOMATIC_CUSTOM_WORLD_EVIDENCE.md) · [P3.6双版受控实证JSON](docs/evidence/P36_REAL_AUTOMATIC_CUSTOM_EVIDENCE_2026-10-14.json)

## P3.5 / 普通客户端建筑轮廓真实取证（双版本受控实机已验收）

新增独立的手动沙漠神殿轮廓取证：**/wsl footprint first / second / export / clear**。按玩家授权的两角区域只读已经完整加载的实际方块，外侧一圈与四条可见边缘一起审计；一旦区块缺失、遮挡或取证中断，即拒绝把残缺轮廓升级为结构起点候选。原始证据在用户显式导出时只存本机 HMAC 世界与维度隔离目录，不会主动加载新区块或上传。

完整的可见方块外沿可接入 P2.9 原有的**已校准、不同朝向离散起点并集**，不会复用服务端 StructureStart 当成客户端证据。天然生成来源始终 UNKNOWN，不能区分人工复制。**2026-10-14 受控真实 Minecraft 双版本已成功：** [Actions #38525522872](https://github.com/fxjcangku/WorldSeedLab/actions/runs/38525522872)（提交f6a4ed8）对两版各4座实际联机可见神殿执行了12条真实聊天指令、4份独立CLIENT_OBSERVED栅格导出、每座30752方块、4/4完整21×21轮廓、独立受控NBT起点对照及 Z3/Java 2FIT+1HOLDOUT低48已知Seed=2731恢复。更严格的最终版本 [Actions #38527252093](https://github.com/fxjcangku/WorldSeedLab/actions/runs/38527252093) 已在 26.1.2 / 26.2 **再次全部 success**：每座四条可见边缘严格21/21，不允许一列缺失；服务端对所测4/4处 StructureStart 所在区块单独确认FULL；另确证自定义平坦沙漠 FULL 区块每版1,226 / 1,260。两版真 Z3 + Java 2FIT+1HOLDOUT 仍通过。正常用户目标服的自然来源、未知Salt和完整64位仍未知。

[查看 P3.5 真正客户端可见轮廓取证和验收规范](docs/P35_CLIENT_VISIBLE_DESERT_FOOTPRINT.md) · [P3.5 结构化双版本受控实验证据](docs/evidence/P35_REAL_FABRIC_CLIENT_FOOTPRINT_2026-10-14.json)

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

世界探测、本地证据存储、结构放置数学模型、离线候选求解和独立 FIT/HOLDOUT 复核已具备研究实现，并完成上述双版本受控实验。未知自定义生成器识别与目标服完整 Seed 恢复仍待研究。探测、求解和验证分层隔离，避免用“候选结果”冒充“已验证结果”。

## 04 / 版本与技术路线

| 项目 | 研究目标 |
| :--- | :--- |
| Minecraft Java | **26.1.2 / 26.2**（分别构建与验证） |
| 模组平台 | Fabric 客户端，独立安装运行 |
| 编程语言 | Java 25 |
| 映射标准 | Mojang 官方映射（Mojmap） |
| UI / 文档 | 中文优先 |
| 数据处理 | 本地优先，服务器与维度数据隔离 |
| 仓库与开发 | 独立原创项目，不依赖 yiyiaddon |

> 双版本已有独立构建与受控真实 Fabric GUI 实证；v0.1.0 为初代研究预览，**本轮发行包未本机实机验收**。既有受控测试的范围与链接见 P3.3–P3.6。

## 05 / 开发路线

| 阶段 | 研究目标 | 状态 |
| :--- | :--- | :--- |
| **P0 · Foundation** | 基础框架、验证规范和双版本构建 | 已通过双版本 CI，形成初代研究预览 |
| **P1 · Observation** | 群系扫描、人工结构观察、世界维度隔离 | 已通过受控双版本真实客户端取证 |
| **P2 · Diagnostics** | 自定义维度、地形高度图、研究日志与兼容性前置取证 | 基础地形与群系联合落盘已有受控实证；详细剖面待专项验证 |
| **P2.5 · Compatibility** | 已探索地图、原版生成算法独立复现、结构兼容性验证 | 地图 MVP 已实现；结构数学、几何与指定模型已有受控验证 |
| **P3 · Seed Research** | 候选求解、结构种子与完整 Seed 研究 | 受控 low48/有前提 full64 及自动神殿取证通过；未知服仍待研究 |
| **P4 · Verification** | 独立样本复核、报告与失败诊断 | FIT/HOLDOUT 与失败审计已接入受控研究；更广泛验收待完成 |

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

项目默认在本地存储和处理观测证据；不主动上传服务器地址、地理坐标、世界数据或 Seed。公开讨论与贡献时应避免泄露未经同意的私人服务器信息，并遵守服务器规则。

研究仅使用普通客户端能够合法观察到的数据，不提供获取管理权限、读取服务器私有文件或绕过访问控制的功能。

## 08 / 参与研究

仓库公开研究实现、验证方法与后续计划。欢迎提交可公开分享的规则分析、反例、测试思路和文档修正。

**[下载 v0.1.0 初代研究预览](https://github.com/fxjcangku/WorldSeedLab/releases/tag/v0.1.0)**，并按发布页选择对应版本。已实现能力、受控实验结论和后续研究目标以本文及阶段报告的明确范围为准。

---

<div align="center">

<sub>WORLDSEEDLAB · OBSERVE THE WORLD · VERIFY THE SEED</sub>

<sub>Independent research · Built for understanding, not guessing.</sub>

</div>
