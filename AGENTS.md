# WorldSeedLab · 开发智能体规范

先阅读根目录 [《开发习惯.md》](%E5%BC%80%E5%8F%91%E4%B9%A0%E6%83%AF.md)、[架构说明](docs/ARCHITECTURE.md)及对应阶段的研发文档，再开始修改源码。

本项目唯一源码仓库为 `fxjcangku/WorldSeedLab`。禁止访问、复制或公开 yiyiaddon 私有业务实现。两版 Minecraft（26.1.2 / 26.2）必须独立编译和测试；使用 Java 25、Mojmap、最新实际依赖，不套用旧 Yarn API。JavaDoc/关键代码注释中文化，文件和包目录清晰、禁止无用代码。

WorldSeedLab 仅是普通 Fabric 客户端的世界生成研究工具：默认无采集、无主动加载区块、无联网上传、无虚构种子结果。原版结构的人工观察位置不得冒充真实结构生成起点。新的证据格式和兼容性结论必须通过独立正反测试。

每次提交先检查最新远端状态，使用研发分支、PR、GitHub Actions 双版本构建；真实 runClient 未完成时如实说明。未经用户要求不发 Release、不强推覆盖既有分支。

在开始 P2.5 结构反推之前先阅读 [结构生成兼容性研究方案](docs/P2_5_STRUCTURE_COMPATIBILITY.md)。人工观察位置不能直接当作结构起点，必须先用已知 Seed 世界确认版本随机放置规则。

地图代码必须先读 [P2.5 已探索地图](docs/P25_OBSERVED_MAP.md)；不允许新增与 MapTileArchive 重复的地图索引格式，所有渲染操作读取不可变视窗快照，不在 GUI 线程遍历原始 evidence.jsonl。

P2.6 材料结构候选需要先看 [docs/P26_STRUCTURE_RECOGNITION.md](docs/P26_STRUCTURE_RECOGNITION.md)。矿石、假矿不得成为结构证据；客户端观察点和候选材料永远不能冒充原版真实 structure start。

P2.7 研发请先阅读 [docs/P27_RANDOM_SPREAD_SOLVER.md](docs/P27_RANDOM_SPREAD_SOLVER.md)，禁止把结构候选模型的“potential chunk”解释为结构真实生成。修改 RNG、Salt、随机 spread、Region 计算或约束求解时，必须运行目标 Minecraft 26.1.2 与 26.2 本体对照，不复制 SeedCrackerX 或其依赖中的求解算法。

P2.8/P3 符号求解请阅读 [docs/P28_SYMBOLIC_SOLVER_AND_ORE_POLICY.md](docs/P28_SYMBOLIC_SOLVER_AND_ORE_POLICY.md)：SMT 的拒绝重抽路径有界，UNSAT 不代表服务器没有 Seed；SAT 候选也只能算有参数前提的 low48。矿石显示受到假矿干扰，不能以可见 ore 作为自然生成真值，完全 Seed 与原版矿点必须独立验证。

P2.9 结构几何逆推必须先阅读 [docs/P29_GEOMETRY_INVERSION.md](docs/P29_GEOMETRY_INVERSION.md)：材料指纹和玩家标记没有完整轮廓资格；受控校准来源须可追溯；不同朝向的离散起点并集不可被外包矩形代替，必须在 SMT、Java FIT 与 HOLDOUT 中使用完全相同的允许集合。

P3.0 完整64位研究必须阅读 [docs/P30_FULL64_EVIDENCE_RESEARCH.md](docs/P30_FULL64_EVIDENCE_RESEARCH.md)：Legacy 结构仅含低48位信息；高16位需要独立、已验证的真实 Minecraft 世界生成证据。受控 Xoroshiro 随机数演示不能冒充客户端实际群系。即便64位候选唯一，也要在报告中保留生成器模型、候选集合、FIT/HOLDOUT 和尚未确认的服务端设置。

P3.1 全高16位验收先读 [docs/P31_REAL_HIGH16_EXHAUSTIVE.md](docs/P31_REAL_HIGH16_EXHAUSTIVE.md)。必须用真实已生成 FULL 区块的 quart 群系证据与目标版本 Mojmap 的原版生成器模型，所有高16分片完整覆盖、无重叠、检查数量等于总域才允许写“全域完成”；已验证的控制世界不代表未知服务器真实 Seed。

P3.2 客户端 quart 证据与负数64位世界对照必须阅读 [docs/P32_CLIENT_QUART_NEGATIVE_FULL64.md](docs/P32_CLIENT_QUART_NEGATIVE_FULL64.md)。普通客户端仅允许用户手动读取已加载 FULL 区块、HMAC 世界和维度隔离，不得默认抓取或上传原始坐标；离线求解先独立复核结构 low48，原版生成器只能作为用户显式声明的假设。负数Seed受控服务端存档和客户端可见数据不可混淆。

P3.3 真实客户端联机回归必须阅读 [docs/P33_REAL_FABRIC_CLIENT_E2E.md](docs/P33_REAL_FABRIC_CLIENT_E2E.md)：验证须通过真正 Fabric GUI 联机及手动命令取得 CLIENT_OBSERVED quart CSV，和独立 FULL 区块存档真值复核。不得将虚拟 GUI 启动、模拟传输、服务端 StructureStart 或未完成分片混为一谈；受控 Seed 正反例不代表未知自定义服务器 Seed 恢复。CI 特定 options.txt 只跳过首次欢迎页，不修改用户配置或任何种子证据。

P3.4 自定义生成器研究先读 [docs/P34_CUSTOM_WORLDGEN_COMPATIBILITY.md](docs/P34_CUSTOM_WORLDGEN_COMPATIBILITY.md)。结构与群系双轴判定必须区分模型一致/证据不足/模型冲突/生成器未知；未知 Salt、历史区块或人工建筑必须降级。真实受控存档的 FULL Anvil 与 StructureStart 不能标记为普通客户端 CLIENT_OBSERVED；只有两版实测全通过才可合并。

P3.4.1 修改 Salt 研究必须先读 [docs/P34_REAL_MODIFIED_SALT_CONTROL.md](docs/P34_REAL_MODIFIED_SALT_CONTROL.md)。将真实受控 Minecraft 数据包 enabled、FULL Anvil 及保存的 StructureStart 作为三项独立验收条件；仅校验 JSON 文件存在不能声称实际自定义结构规则生效。普通客户端不能访问这些服务端真值。

P3.6 自动世界生成证据研发必须先阅读 [docs/P36_AUTOMATIC_CUSTOM_WORLD_EVIDENCE.md](docs/P36_AUTOMATIC_CUSTOM_WORLD_EVIDENCE.md)。一键手动授权只检查已加载 FULL 区块，发现材料候选不得冒充自然生成 StructureStart；仅完整21×21外沿可以成为条件几何候选，且生成器和Salt未知时不得直接运行 low48/完整64位求解。群系/地形与结构证据必须保持独立来源，默认关闭、失败关闭、单次有界、局部异步保存、仅本机原始坐标，双版独立编译及真实 Fabric GUI 自动取证验收，不能用P3.5手动双角成功替代自动功能成功。

P3.7 新增离线证据兼容性分析，必须先阅读 [docs/P37_CUSTOM_WORLDGEN_IDENTIFIABILITY.md](docs/P37_CUSTOM_WORLDGEN_IDENTIFIABILITY.md)。只能在用户显式操作下只读已保存的真实客户端 P1/P2/STRATA；不得混合同一压缩包中的不同世界/维度，不能上传或记录原始服务器地址、坐标、HMAC私钥。非原版群系ID仅说明原样原版注册表模型不能直接解释，不等于已识别自定义生成器。没有可验证模型和独立FIT/HOLDOUT时，Salt、自然结构起点、完整64位Seed保持UNKNOWN。P3.7 的纯Python正反例与 Fabric 双版本真实GUI验收分开执行。
P3.8 自动多结构和中文界面变更必须阅读 [docs/P38_AUTOMATIC_MULTISTRUCTURE_EVIDENCE.md](docs/P38_AUTOMATIC_MULTISTRUCTURE_EVIDENCE.md)。新结构只从已加载FULL BlockState取得材料/局部候选，自动写入HMAC作用域v2索引，保留v1归档；海洋神殿、冷暖海底遗迹、沉船、废弃传送门等没有独立几何真值时不得标自然起点或接入low48。UI命令、F8与地图返回必须复用同一AutoController，中文分行彩色不刷屏。CI必须包含双版真正Fabric GUI与明确标为CONTROLLED_ARTIFICIAL的海底材料布景负自然来源验收；不得以人工材料假造天然结构Seed。

P3.8地形核心研究在自动多结构候选基础上还须阅读 [docs/P38_CUSTOM_TERRAIN_AND_STRUCTURES.md](docs/P38_CUSTOM_TERRAIN_AND_STRUCTURES.md)。一次auto on应暂时启用STRATA真实已加载方块，退出后恢复玩家配置；必须在真实双版本Fabric GUI明确核查地下详情已落盘。离线候选模型不得只靠3个合成高度猜后台生成器，需独立真实前向实现与同一HMAC/维度/版本的2FIT+1HOLDOUT，任何一致只可叫CONDITIONAL_MATCH_UNVERIFIED，未知Seed/Salt仍未知。禁止覆盖本轮多结构材料识别和中文UI分支已有改动。
P3.9真实客户端种子反演前必须阅读[docs/P39_REAL_CLIENT_SEED_RECOVERY.md](docs/P39_REAL_CLIENT_SEED_RECOVERY.md)。未经完全可见轮廓审计与不同RandomSpreadRegion的至少2FIT+1HOLDOUT，不得启动低48求解；不能把人工材料候选/服务器真NBT输入伪装成普通客户端观测。真实CLI允许本机Z3和Java RNG受控假设匹配，但UNKNOWN生成器、Salt、天然来源及完整64位不得因受控2731结果自动宣称已破解。真实客户端受控ZIP必须由真正Minecraft Fabric产生，并通过独立后验FULL+StructureStart验收；跨世界/维度/版本必须失败关闭，原始私人数据不上公开仓库。