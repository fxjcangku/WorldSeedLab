<div align="center">

<img src="assets/worldseedlab-hero.webp" alt="WorldSeedLab — Minecraft 世界生成研究动态横幅" width="100%" />

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
> **项目处于研究筹备期（Research Preview）。目前没有发布可用的种子破解功能。** 文档中的检测器、求解器和验证器均为计划研发模块，不能视为已经实现的功能。

## 01.5 / 基础框架已落地

首个 **P0 Fabric 客户端框架**已经提交，包含独立客户端入口、`/wsl` 状态命令、Java 25 启动自检，以及证据模型、默认禁用的采集权限门控、世界/维度隔离存储基础。

- **Minecraft 26.1.2**：独立 CI 编译、核心自检。
- **Minecraft 26.2**：独立 CI 编译、核心自检。
- 使用同一份核心源码，依赖和构建目标按 Minecraft 版本分别选择。
- 目前 **没有自动采集、自动结构识别或 Seed 求解功能**；还需真实客户端启动验收。

[开发及构建说明](docs/DEVELOPMENT.md) · [双版本 CI](../../actions/workflows/build.yml)

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

这是**拟议技术架构**，并非当前已存在的运行管线。探测、求解和验证将分层隔离，避免用“候选结果”冒充“已验证结果”。

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
| **P0 · Foundation** | 项目品牌、技术约束、文档与测试标准 | **进行中** |
| **P1 · Observation** | 世界、群系与结构证据采集及持久化 | 待开发 |
| **P2 · Compatibility** | 原版生成算法独立复现与兼容性测试 | 待开发 |
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
