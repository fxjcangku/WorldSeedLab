# WorldSeedLab · 软件分层与数据边界

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

## 研究结论的边界

普通玩家无法直接得知服务器完整生成器配置和原始 Seed。P2 的地形/维度特征只能提供**观测证据**；是否符合某套原版世界生成规则，需要独立实现该规则并使用结构起始坐标与留出样本验证。P2 在没有确证模型时必须报告 `UNKNOWN`。
