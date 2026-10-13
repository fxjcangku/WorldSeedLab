# WorldSeedLab · 开发智能体规范

先阅读根目录 [《开发习惯.md》](%E5%BC%80%E5%8F%91%E4%B9%A0%E6%83%AF.md)、[架构说明](docs/ARCHITECTURE.md)及对应阶段的研发文档，再开始修改源码。

本项目唯一源码仓库为 `fxjcangku/WorldSeedLab`。禁止访问、复制或公开 yiyiaddon 私有业务实现。两版 Minecraft（26.1.2 / 26.2）必须独立编译和测试；使用 Java 25、Mojmap、最新实际依赖，不套用旧 Yarn API。JavaDoc/关键代码注释中文化，文件和包目录清晰、禁止无用代码。

WorldSeedLab 仅是普通 Fabric 客户端的世界生成研究工具：默认无采集、无主动加载区块、无联网上传、无虚构种子结果。原版结构的人工观察位置不得冒充真实结构生成起点。新的证据格式和兼容性结论必须通过独立正反测试。

每次提交先检查最新远端状态，使用研发分支、PR、GitHub Actions 双版本构建；真实 runClient 未完成时如实说明。未经用户要求不发 Release、不强推覆盖既有分支。

在开始 P2.5 结构反推之前先阅读 [结构生成兼容性研究方案](docs/P2_5_STRUCTURE_COMPATIBILITY.md)。人工观察位置不能直接当作结构起点，必须先用已知 Seed 世界确认版本随机放置规则。
