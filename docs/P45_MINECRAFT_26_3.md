# P4.5 · Minecraft 26.3 与既有双版本同步

## 范围与基线

用户明确要求在 WorldSeedLab 同步 26.1.2 / 26.2 重构，并新增 26.3。P4.4 [PR #40](https://github.com/fxjcangku/WorldSeedLab/pull/40) 的11条工作流均通过后，已合并为 `c8c623ced77436dcc3835ff5dff5ba61c9dfd071`。本阶段从该提交继续，保留同一核心与客户端业务实现，按目标版本构建独立 JAR。

| Minecraft | Java | Fabric Loader | Fabric API |
| --- | --- | --- | --- |
| 26.1.2 | 25 | 0.19.5 | 0.155.3+26.1.2 |
| 26.2 | 25 | 0.19.5 | 0.161.0+26.2 |
| 26.3 | 25 | 0.20.1 | 0.165.2+26.3 |

构建工具统一为 Gradle 9.9.0 与 Fabric Loom 1.19.4。Loom 官方 Gradle module 元数据要求 JVM25 和 Gradle plugin API9.8.0，原9.6.1不能直接搭配。依赖身份来源：[Mojang 26.3 JSON](https://piston-meta.mojang.com/v1/packages/80608ff6753705b6a82e2cb86a5937d4d233abd4/26.3.json)、[Fabric Loader 元数据](https://meta.fabricmc.net/v2/versions/loader/26.3)、[Fabric API Maven](https://maven.fabricmc.net/net/fabricmc/fabric-api/fabric-api/maven-metadata.xml)、[Loom module](https://maven.fabricmc.net/net/fabricmc/fabric-loom/1.19.4/fabric-loom-1.19.4.module)、[Gradle 官方发行](https://services.gradle.org/versions/current)。

## 真实 API 适配

26.3 的 `VanillaRegistries`、`RandomState`、气候采样上下文与 `MultiNoiseBiomeSource` 接口有变化，不能只添加版本字符串。核心适配放入 `src/versions/<版本>/main/java`，客户端 GUI 绑定保留在 `src/versions/<版本>/java`。Gradle 只编译当前版本的适配器；旧两版走旧接口。

版本资格在 Java/Python 各自集中定义；证据解析、恢复 CLI、真实世界脚本、CI 矩阵均纳入26.3。HMAC、维度、世界纪元、版本和来源检查继续拒绝混合输入。26.3 数据包格式经官方 server JAR 内 `version.json` 核实为 `[121,0]`，真实修改 Salt 回归按该格式创建数据包。

## 26.3 独立 FULL/NBT 校准

[官方原版校准 Actions #42465203278](https://github.com/fxjcangku/WorldSeedLab/actions/runs/42465203278) 已成功。该工作流先验证官方版本 JSON 及 server JAR 的 SHA1，再启动全新26.3原版服务器，使用公开受控 Seed `2731`；待保存和正常停服后，直接读取已生成区块的 NBT。

- 源码：`73975ec93caa608b45dadf7774a453eb33cffd1e`。
- 官方 server JAR SHA1：`33680f5f2ac32864d6d7cf5e56a705fdb3e05f4c`。
- 已保存区块计数4,373；quart样本只取状态为FULL的真实区块。
- 五个quart样本，水平间隔至少1,024方块，含三种群系；前三个用于FIT，后两个用于HOLDOUT。
- 四个来自不同RandomSpread区域的真实沙漠神殿StructureStart；几何包围盒也独立保存。
- quart CSV SHA256：`73b8e9915d422b9632c4d411d0dcd6f0f2150e920d1a1d18630b07fb5e4e9602`。
- structure CSV SHA256：`dc40fe0218d7c90a8fbe06e580435e65a123d9219704dc5693968932e682115d`。

本次新生成样本恰好与旧版样本坐标及群系相同，因此quart摘要相同。它们有独立26.3生成来源，未通过重命名旧文件建立新版真值。可核对[来源记录](evidence/P45_26_3_CALIBRATION_2026-11-08.json)与原始Actions日志。

`src/test/resources/p31/fixture-manifest.json` 按版本绑定文件、SHA256、来源、已知种子、样本数和校准run。Java与Python都校验该清单；缺少26.3、错版本、错摘要均失败，不会回退旧版夹具。P3.2 的26.3结构输入读取新真实CSV；历史常量仍只用于其原来已验证版本。

这些是公开受控服务端真值，不是普通客户端观察，也不证明未知服务器种子。真正客户端轮廓与FULL/StructureStart的后验复核仍由P3.5/P3.6/P3.9执行。

## 验收门槛

新增目标后，既有Minecraft工作流均扩为26.1.2 / 26.2 / 26.3，保留各自的构建、真实Mojmap RNG/气候/高度、Z3与负例检查。P3.1从32片扩为48片，每版16片覆盖全部65,536个高16候选；聚合必须逐版确认无缺口、无重叠、实际检查数和对应输入摘要。

三版本完整构建、真实Fabric GUI、受控自定义地形与修改Salt、负数种子、全高16回归是合并门槛；最终结果应以本阶段PR对应提交的Actions为准。未通过这些回归前，官方校准成功只证明测试输入来源有效，不等于模组适配完成。

既有v0.1.0 Release只有26.1.2 / 26.2；本次不创建Release。恢复结果仍区分候选、已知生成模型和未知服务器真值，`ready_for_mining=false`，没有因升级游戏版本就增加矿物预测资格。

## CI 下载故障与修复

初轮Actions遇到官方Gradle分发ZIP间歇性HTTP504，多个任务在编译前失败。现使用统一 `scripts/setup_gradle_ci.py`，锁定9.9.0及官方SHA256，在180秒下载预算内最多6次尝试三个官方入口（已完整下载并验证的Gradle官方GitHub发行资产优先，两个官方分发域名后备）；摘要、归档和实际版本全部验证后才向后续步骤提供Gradle。`setup-gradle`继续配置原有缓存和报告，不再重复安装分发包，GitHub令牌及缓存权限不变。重试仍失败的任务保持失败，不跳过游戏或求解验收。

## 26.3 真实图形启动修复

真实CI发现两个独立兼容问题：键盘枚举从KEYSYM变为KEYBOARD，客户端改用三版官方均存在且默认键盘语义一致的三参KeyMapping构造；26.3 RenderPearl/SDL3的OpenGL窗口要求sRGB，在Xvfb默认GLX路径下未能选中visual。26.3受控GUI启动改用[SDL官方EGL路径](https://wiki.libsdl.org/SDL3/SDL_HINT_VIDEO_FORCE_EGL)，显式安装Mesa EGL，不改变实际OpenGL版本或游戏的sRGB要求；旧两版保留原路径。

原P3.2只检查模组初始化与超时退出，曾在26.3窗口创建失败时误判成功。这类旧结果不作为26.3图形验收。新门禁同时检查默认采集关闭、真实映射窗口、渲染线程atlas创建、26.3实际SDL主窗口、聚焦游戏窗口的非单色中央截图；崩溃或超时均失败。真正联机取证仍须独立通过P3.3/P3.5/P3.6/P3.9，不能由启动截图替代。

## 26.3 受控存档与联机默认值

官方26.3默认BlockState可直接保存为ID字符串，非默认状态使用 `id/properties`；混合NBT列表的字符串会保存为唯一空键compound。原先只读取 `Name/Properties` 的地层验证因此漏读。统一palette解析明确接受这四种官方形态并拒绝歧义、错误包装和非法ID，属性值继续严格要求字符串；布尔与整数属性已通过官方codec的真实二进制编码/读取确认仍为字符串。FULL区块、section Y3/Y4及砂岩/沙层数量门槛保持，地形高度未改。

26.3的 `white-list` 服务端默认值变为true。EGL修复后的真实截图与服务器日志已证实测试客户端被白名单拒绝；三个GUI实验入口现在对其自建127.0.0.1测试服显式设为false，以允许受控玩家正常加入。该设置仅用于隔离实验，不进入模组业务。
