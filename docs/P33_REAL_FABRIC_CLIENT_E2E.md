# WorldSeedLab P3.3 · 真实 Fabric 联机客户端群系取证端到端验收

> Minecraft Java 26.1.2 / 26.2 · Java 25 · Fabric / Mojmap · 2026-10-14
>
> **验收结论：受控原版服务器的真实 GUI 客户端采样、手动命令导出、独立 FULL 区块群系复核已在两个版本通过。**
> 不等于自定义服务器完整世界 Seed 已恢复，也不等于完成单人集成服务器的 UI 测试。

## 真正执行了什么

[GitHub Actions P3.3 全程联机回归 #38515523790](https://github.com/fxjcangku/WorldSeedLab/actions/runs/38515523790) 的两个 job 均为 `success`。每个 job 在隔离的 Linux 虚拟 X11 环境上启动一份 **Minecraft 原版专用服务端**（公开受控 Seed=`2731`）和一份 **Fabric runClient 图形客户端**；客户端通过 Quick Play 与 `127.0.0.1:25565` 建立真实协议连接。测试通过服务端在线玩家列表确认玩家已登入，而不是只从日志中看到模组初始化。

真实输入序列：使用 X11 发送按键打开 Minecraft 聊天框，输入 `/wsl quart sample` 共 5 次，分别在公开已知受控世界的不同地点；最后输入 `/wsl quart export`。测试必须找到 **唯一一个由模组写到 HMAC 世界作用域的 `CLIENT_OBSERVED` CSV**；找不到即失败。

| 观测与验证 | 26.1.2 | 26.2 |
|---|---:|---:|
| Fabric 真正连到受控专用服务器 | 通过 | 通过 |
| 玩家真实聊天框 quart sample 命令 | 5 次 | 5 次 |
| 模组显式导出 CSV | 通过 | 通过 |
| CSV 原始 quart 群系与已生成 FULL 区块真值逐点匹配 | 5/5 | 5/5 |
| 离线群系角色 | 3 FIT / 2 HOLDOUT | 3 FIT / 2 HOLDOUT |
| 原版模型完整 64 位真值候选 | `0000000000000aab` | `0000000000000aab` |
| 本轮检查高 16 位范围 | `[0,8)` | `[0,8)` |
| 其它高16位 `1..7` | 全部拒绝 | 全部拒绝 |
| CI 状态 | Success | Success |

**低48来源边界：** 本轮完整 Seed 搜索的 `low48=2731` 是先前 P2.9 受控世界结构起点及 Z3 的独立验证结果，并不是这五次客户端群系采样自己从零求解出来的。前一阶段 P3.1 已另行用受控存档证据对同一个 low48 做过全部 65,536 种高位验证；P3.3 的真实客户端环节只在 `[0,8)` 做有限正反例，不要混淆。

## 踩坑与修复的验证轨迹

前期 CI 真实失败不是模组种子计算问题：

- Fabric Loom 开发客户端在自己的嵌套 Xvfb `DISPLAY=:99` 渲染，测试脚本最初错误地只在外层 `:97` 搜索窗口；后改为从**真实客户端 JVM 的 X11 DISPLAY/XAUTHORITY**定位。
- 旧 `--server/--port` 自动连服参数无效，改用目标客户端的 `--quickPlayMultiplayer 127.0.0.1:25565`。
- 全新 Minecraft 实例被 `Welcome to Minecraft` 无障碍欢迎页阻挡。截图验证发现虚拟鼠标点击和按 Enter 仍未真正跳出欢迎页；最终仅为 CI 的**全新本机测试客户端**写入原生 `options.txt`：`onboardAccessibility:false`、`narrator:0`、`pauseOnLostFocus:false`。不修改模组用户已有选项、世界 Seed 或群系取证数据。
- **失败的轮次明确保留 Failure 状态**，成功依据仅采用最终完整双版本 run #38515523790；[相同代码的 Fabric 双版构建 #38515523807](https://github.com/fxjcangku/WorldSeedLab/actions/runs/38515523807) 也通过。

## 测试边界与后续任务

- 是真实 GUI 客户端到**受控本机专用原版服务器**的联机，不是通过后台 `StructureStart` NBT 代替客户端 quart 取证，也不是用户目标自定义服务器。
- 服务端使用管理员传送/forceload 仅用于**受控测试布景**，使无人值守客户端访问五个已知区域；普通玩家部署的 WorldSeedLab 不会发送这些管理指令，不会额外强制加载区块，也不会自动外传坐标。
- 导出的原始 CSV 带真实 XYZ/quart 位置，只在公开受控世界测试中允许保存 CI 产物；未知私人服务器绝不可自动将其上传到 GitHub。
- 用户目标服务器还需验证未知结构起点、未知 Salt、修改群系/地形、历史区块、自定义矿点、反矿透视及生成器兼容性。**不能承诺任何自定义服务器都能由客户端恢复全部64位 Seed**。
- 下一阶段 P3.4 可以研究权限最小化的自定义地形兼容性试验，生成“不足以判断/模型冲突/仍兼容”等状态；不能把未知规则的世界强套原版 Seed 模型。

技术入口：`scripts/run_p33_real_client_e2e.py`、`.github/workflows/p33-real-client-quart.yml`、`src/test/java/io/github/fxjcangku/worldseedlab/core/P33RealFabricClientQuartTest.java`。
