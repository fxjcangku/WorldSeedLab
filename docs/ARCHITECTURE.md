# WorldSeedLab · 软件分层与数据边界

## P3.6 自动联合取证入口（独立于离线求解器）

`/wsl auto on` 只会在玩家明确授权后启动一次 P1/P2 群系与地形采集、P2.6 低成本材料候选，以及 `client/probe/AutoDesertFootprintController`。它从已经接受的 FULL 区块逐 Tick 有界读取蓝陶瓦锚点，调用 `core/geometry/AutoDesertFootprintPlan` 制定29×29粗ROI，然后复用 `ClientVisibleDesertPyramidRaster` 的四侧21/21严格轮廓校验。只有完整不歧义的 CLIENT_OBSERVED_Blocks 经有界单线程异步写入HMAC世界/维度目录。生成器和人工来源仍UNKNOWN，绝不自动调用Z3、服务端NBT、强制区块加载或网络上传。离线独立研究另用群系/地形生成器模型检验完整64位，不因自动材料匹配就当作已证明Seed。

## P3.7 离线世界生成研究（与客户端采集完全隔离）

`scripts/research_p37_custom_worldgen.py` 只在用户本机显式运行，只读现有HMAC世界/维度目录中的 `evidence.jsonl`、`terrain.jsonl`、`terrain-detail.jsonl` 或一个本机ZIP。以白名单文件名逐行读取、检查CLIENT_OBSERVATION/schema/版本/作用域，单独统计群系注册ID与16列WORLD_SURFACE/地下STRATA，使用32×32宏区域判断能否为**未来**生成器模型划分独立FIT/HOLDOUT。客户端不启动外部Z3、不读取后台Seed/NBT、不自动把材料锚点提升到结构真起点。P3.7只输出匿名聚合，缺少真实生成器前向模型和独立验证则保持完整64位 `UNKNOWN`。

## 数据流

```text
Minecraft Client (already loaded chunks)
         │  玩家显式开启 + 世界身份已确认
         ▼
client/probe/WorldProbeController
         ├─ client/scope/WorldScopeResolver
         ├─ core/probe/ProbeGate            ← 世界 / 维度安全隔离
         ├─ core/probe/ChunkSamplingCursor  ← quart 三维群系格点
         ├─ core/generation/                ← P2 已加载区块地形 / 维度特征
         └─ core/probe/AsyncEvidenceWriter  ← 单线程有界队列
                     │
                     ▼
         core/probe/CompletedChunkStore
                     │
             用户本地 config/worldseedlab
              ├─ evidence.jsonl
              ├─ terrain.jsonl
              ├─ dimension-profile.jsonl
              ├─ advertised-dimensions.jsonl
              ├─ completed-chunks.csv
              └─ research-report.txt (用户显式导出)

client/ui/*   ← 只读取 Controller 当前会话快照，绝不自己取世界或写文件
```

## 核心模型

- `EvidenceRecord`：群系采样或人工建筑观察记录。结构的玩家坐标不是结构起始区块。
- `ChunkSamplingCursor`：基于世界高度与 quart 网格的跨 Tick 迭代。
- `ChunkKey`：区块 X/Z + 垂直采样间距，同一区块更高精度可重新采样。
- `AdvertisedDimensions`：服务器连接公布的维度键快照，与当前已访问维度严格区分。
- `DimensionProfile`：当前维度 ID、建造高度、海平面和环境特征，不等于服务端完整生成器配置。
- `TerrainChunkSample`：客户端已加载区块 WORLD_SURFACE 高度图稀疏采样，不等于原始噪声或完整地貌。
- `GenerationDiagnostics`：证据量、覆盖限制、模型状态的审计输出，不会猜测真实 Seed。
- `ProbeGate`：显式启动且世界/维度一致才允许继续记录。

## 包依赖方向

```text
 client/ui ──► client/probe ──► core/probe ──► core/evidence
                  │                  │
                  ├─► core/generation│
                  └─► client/scope ──└──────► core/privacy
```

`core/*` 不依赖 Minecraft 类、GUI、Fabric 事件或网络；`client/*` 只在客户端环境加载。两版互不相同的方法集中在 `src/versions/<版本>` 中。

## 失败模型

- 样本读取超时 / 区块卸载：丢弃未完成区块并等待未来重试，不强迫世界加载。
- 异步写盘队列满：暂停扫描，保留当前区块数据。
- 磁盘异常：停止本会话并保留可核对日志。
- 世界或维度变化：立即撤销采样授权，不跨源合并。
- 不完整日志：已完成索引只在数据写盘之后记录；异常时可能少量重复但不虚报完成。

## P2.2 独立详细观测

`client/probe/TerrainDetailCursor` 分 Tick 读取地表方块、三种高度图、海床与可选地下剖面；`core/generation/TerrainDetailSample` 保留不可变证据；`TerrainDetailJsonLines` 按 schema=4 序列化；`CompletedChunkStore` 将详细日志与 P1/P2 分开处理并加容量上限；`DetailObservationStats` 只统计非定位信息。

## 研究结论的边界

普通玩家无法直接得知服务器完整生成器配置和原始 Seed。P2 的地形/维度特征只能提供**观测证据**；是否符合某套原版世界生成规则，需要独立实现该规则并使用结构起始坐标与留出样本验证。P2 在没有确证模型时必须报告 `UNKNOWN`。

## P2.7 结构放置与求解（离线）

```text
已验证的结构集合参数 + 起始区块几何约束
                │
                ▼
core/solver/RandomSpreadSpec + StartPlacementConstraint
                │
                ▼
core/solver/LegacyLcg48 + RandomSpreadMath
                │
                ├─ 目标 Minecraft 26.1.2 / 26.2 本体对照
                ▼
core/solver/CandidateSeedSearch
     FIT 搜索 → HOLDOUT 检验 → LOW48 候选
                │
                ▼
core/solver/SolverResearchCli（显式离线输入）
```

- `core/solver` 为纯 Java，**不依赖 Fabric、客户端世界对象或网络**。
- 求解器只提供有界、可恢复的低 48 位枚举；真实 Seed 的完整逆向仍为 P3 研究。
- P2.6 材料候选没有精确结构起始坐标，不能自动提升为 P2.7 输入。
- 未公开或自定义的结构 Salt、频率/排除规则等会影响结论，模型无从自动得知。
- CI 对照的是**潜在结构放置区块**，不代表所有 Minecraft 结构实际自然生成。

## P2.8 低48位约束反演（单独离线进程）

```text
用户明确提供的结构集合参数（spacing/separation/Salt）
+ 可信起点范围（至少 2 FIT + 1 独立 HOLDOUT）
                    │
                    ▼
core/solver/SymbolicRandomSpread（原创 QF_BV / 48bit LCG）
                    │
                    ▼
LocalSmtSeedSolver → 本机外部 Z3（可选依赖）
                    │
                    ▼
候选 Seed → P2.7 精确 RandomSpreadMath FIT + HOLDOUT
                    │
                    ▼
LOW48_CANDIDATE（不等于后台64位真实世界Seed）
```

`p28SmtResearch` 只能由用户显式执行，不得自动读取玩家世界、后台 Seed、矿物 NBT 或服务器未公开参数。模型均基于已知版本与已知 Salt 的**条件假设**，不能把 SAT / UNSAT 当作自定义服真实 Seed 的证明。非二幂 nextInt 的有限拒绝路径须在报告中注明，最终候选用完整 Java RNG 二次验证。
