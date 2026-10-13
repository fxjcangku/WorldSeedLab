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
> **研究预览（Research Preview）。P1 客户端群系观测与 P2 维度/地形诊断框架已开发，但尚未完成真实服务器实机验收。** 目前没有 Seed 求解器，也未发布可用的种子破解功能。

## 01.5 / 可运行研究框架与实际数据采集

P0 基础框架及 P1/P2 研发源码已提交：**F8 或 `/wsl gui`** 打开中文液态玻璃面板，主动启动后读取客户端已加载区块的真实三维群系、稀疏地形高度和当前维度信息；还会区分客户端**连接时获知**的其它维度注册键与**实际进入**的维度。可手动记录尚未验证的结构观察，并用 **`/wsl report`** 导出中文诊断报告。所有数据仅保存在本地。

- **Minecraft 26.1.2**：独立 CI 编译、核心自检。
- **Minecraft 26.2**：独立 CI 编译、核心自检。
- 使用同一份核心源码，依赖和构建目标按 Minecraft 版本分别选择。
- 启动默认**关闭采集**，离开世界或切换维度自动停止；没有自动结构识别、结构种子或完整 Seed 求解器。
- 普通客户端不能读取服务器完整生成器配置；需要跑图获取更多可观测证据，并独立验证结构分布规律。
- 目前只有双版本 CI 构建与模型自检，仍需单人世界及真实客户端 `runClient` 验收。

[开发及构建说明](docs/DEVELOPMENT.md) · [P1 真实群系采集](docs/P1_OBSERVATION.md) · [P2 跑图与维度日志](docs/P2_DIAGNOSTICS.md) · [开发习惯](%E5%BC%80%E5%8F%91%E4%B9%A0%E6%83%AF.md) · [双版本 CI](../../actions/workflows/build.yml)

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
