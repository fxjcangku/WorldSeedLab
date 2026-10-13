# WorldSeedLab · 开发智能体规范

先阅读根目录 [《开发习惯.md》](%E5%BC%80%E5%8F%91%E4%B9%A0%E6%83%AF.md)、[架构说明](docs/ARCHITECTURE.md)及对应阶段的研发文档，再开始修改源码。

本项目唯一源码仓库为 `fxjcangku/WorldSeedLab`。禁止访问、复制或公开 yiyiaddon 私有业务实现。两版 Minecraft（26.1.2 / 26.2）必须独立编译和测试；使用 Java 25、Mojmap、最新实际依赖，不套用旧 Yarn API。JavaDoc/关键代码注释中文化，文件和包目录清晰、禁止无用代码。

WorldSeedLab 仅是普通 Fabric 客户端的世界生成研究工具：默认无采集、无主动加载区块、无联网上传、无虚构种子结果。原版结构的人工观察位置不得冒充真实结构生成起点。新的证据格式和兼容性结论必须通过独立正反测试。

每次提交先检查最新远端状态，使用研发分支、PR、GitHub Actions 双版本构建；真实 runClient 未完成时如实说明。未经用户要求不发 Release、不强推覆盖既有分支。

P2.5 验证严格区分“玩家目击”和“已知 Seed 测试世界的真实结构起点”；留出区域与训练区域不能复用。相关类型在 `core/compatibility`，当前只具备安全边界与离线测试，未实现真实 Minecraft 结构生成算法或 Seed 破解。设计参见 `docs/P2_5_STRUCTURE_COMPATIBILITY.md`。
