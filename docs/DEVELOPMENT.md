# WorldSeedLab · 开发与验收

## 当前能力

- 双版本 Gradle 配置和 Fabric 客户端空壳。
- `/wsl`、`/wsl status` 和 `/wsl about`：只显示基础框架状态。
- 纯 Java 证据数据结构、JSON Lines 序列化、世界指纹和维度隔离的本地存储基础。
- `ProbeGate` 默认禁止采集，维度或世界变化时自动失效。
- 核心 smoke test；CI 双版本构建矩阵。

**未实现：** 实际群系采样、结构自动识别、数据采集开关 UI、候选种子求解、服务器世界指纹接入与自动保存。项目不可用于获取 Seed。

## 编译环境

- Java 25
- Gradle 9.6.1
- Fabric Loom 1.17.21（`net.fabricmc.fabric-loom`）
- Fabric Loader 0.19.5
- Minecraft 26.1.2 + Fabric API 0.155.3+26.1.2
- Minecraft 26.2 + Fabric API 0.161.0+26.2

本项目使用 26.1 起非混淆 Minecraft 的官方类名，**不添加旧 Yarn mappings 依赖，也不使用旧的 `fabric-loom` 插件 ID**。

## 本地构建

安装 JDK 25 和 Gradle 9.6.1，在仓库根目录执行：

```bash
gradle -PmcVersion=26.1.2 clean build
gradle -PmcVersion=26.2 clean build
```

单独执行数据模型与隔离策略测试：

```bash
gradle -PmcVersion=26.2 coreSmokeTest
```

在可图形化运行的机器上启动客户端：

```bash
gradle -PmcVersion=26.2 runClient
```

两个版本都从相同源文件编译为**不同目标版本的 JAR**，不会把两个游戏版本一起打包。

> 暂未包含 Gradle Wrapper 的二进制启动 JAR。当前 CI 使用 `gradle/actions/setup-gradle` 安装已锁定的版本；后续将在可下载官方 Wrapper 二进制的环境生成并提交标准 Wrapper。请勿声称当前可以执行 `./gradlew`。

## 首轮实机检查

1. 构建 26.2，加载独立 Fabric 客户端。
2. 在模组列表确认 `WorldSeedLab` 出现。
3. 进入单人世界或允许该模组的服务器，在聊天输入 `/wsl status`。
4. 确认报告的 Minecraft / Java 版本正确。
5. 确认没有自动扫描、没有自动写入观测文件、没有网络上传和 Seed 破解活动。
6. 切换 26.1.2，重复上述检查。

## 项目边界

- 唯一仓库：`fxjcangku/WorldSeedLab`。
- 不使用或公开 yiyiaddon 私有源码，不复制第三方种子破解算法源码。
- 禁止输出虚假完整 Seed，任何未来候选必须经过留出样本交叉验证。
- 所有新增游戏 API 要以目标 Minecraft 版本实际源码/依赖为依据。

## 验证状态

项目提交前，若无法在具备 JDK 25、依赖联网的环境中完成 Gradle 构建，则**只能宣称源码已提交和静态检查完成**。CI 的真实构建结果必须单独核对后更新状态。

