# P3.9 · 真正从 CLIENT_OBSERVED 证据求世界 Seed（本机执行）

> 本轮直接编写并运行原有 Java RNG/Z3 求解器，而不是展示UI或材料统计。
> **完整64位自定义生成器 Seed 不是通用可逆命题**：如果结构起点无独立天然认证、
> Salt 不可知、地形/群系已被插件或数据包修改，程序必须报告假设缺失，而不是输出伪造的“破解成功”。

## 一条命令开始真实求解

在独立公开 WorldSeedLab 仓库目录下（Minecraft 26.1.2或26.2；Java25、Gradle9.6.1，本机Z3 CLI）：

```powershell
python scripts/recover_seed.py --input "D:\WorldSeedLabData\observations.zip" --minecraft 26.2
```

也可以提供 `/config/worldseedlab/observations/worlds/<HMAC>/dimensions/<维度hash>` 的单一原始世界目录。**文件仅在本机读取、不上传；客户端永不自动启动Seed暴力搜索。**

本流程直接读取 `p36-automatic-footprint/client-auto-desert-*.properties` 或 `client-footprint-*.properties`，并用 **原有真正方块栅格的完整21×21四边审计**检验每个观测点。相同位置/跨世界或版本混合/人工材料候选/不完整轮廓不能变成约束。至少取得3座不重合RandomSpread区域的完整可见沙漠神殿后，自动建立2+ FIT与独立HOLDOUT，并调用**真的本机Z3位向量求解器**寻找Low48候选，再由原始Java RandomSpread逐个复核。

默认研究原版 `minecraft:desert_pyramids` 结构集：`spacing=32 / separation=8 / LINEAR / Salt=14357617`。该模型在真实的26.1.2/26.2已知Seed=2731受控世界中独立校准：四份实际Fabric客户端轮廓与四个FULL NBT真实StructureStart的左上角偏移都是 `(0,0,20,20)`。**这是受控原版模型校准，不等于目标私人服采用相同模板或Salt。**

可以显式研究多个假设，例如：

```powershell
python scripts/recover_seed.py --input "D:\WorldSeedLabData\observations.zip" --minecraft 26.2 --salts 14357617,14357844
```

**真正跑遍低48位模型域**是默认目标：上界 `2^48`，Z3内部仍设置90秒、至多16模型/有限拒绝抽样展开的单轮安全上限，可能需要重试并改进剪枝。若结果为 `TIMEOUT`、`RESULT_CAP` 或 `BACKEND_UNAVAILABLE`，绝不能说已穷举。研究者可用 `--window-end 4096` 在**受控已知Seed**中验证管线，但4096窗口不代表全48位被破解。

## 完整64位：使用实际quart和经过验证的前向生成器

如果且仅如果研究目标适用原版Overworld气候生成器，可以**显式**加：

```powershell
python scripts/recover_seed.py --input "D:\WorldSeedLabData\observations.zip" --minecraft 26.2 --vanilla-high16
```

它从原始P1的 `evidence.jsonl` 读取已经完整落盘的客户端群系样本，选取5处横向距离至少1024格的独立真实quart，使用准确的 `sampling=AUTOMATIC_FULL_CHUNK_OBSERVATION` 来源导出临时本地档案，再串接Z3低48和真实Minecraft版本Overworld高16位生成器复核。模型候选最多1M次/120秒；只能使用**完整高16域、独立3FIT+2HOLDOUT、可信生成器模型**讨论候选唯一性。

**若检测到任何非minecraft群系注册键（如 `terralith:`），一律拒绝偷偷当成未修改的原版群系模型。** 自定义群系数据并不会因强行套用vanilla模型自动提供真实64位Seed；缺少自定义生成器前向规则时必须保留UNKNOWN。

## 对当前用户2026-10-14原始观测压缩包的真实检查

已在ChatGPT工作容器实际检查原件：P1 `evidence.jsonl`、P2 `terrain.jsonl` 均存在，**P3.5/P3.6 的完整结构轮廓文件数量为0**，且有非原版 `terralith` 群系。因此针对**该次采样**，自动反演返回：

```text
RESULT=INSUFFICIENT_GEOMETRY_EVIDENCE
LOW48=NOT_DERIVED
FULL64=UNKNOWN_CUSTOM_WORLDGEN
```

这不是求解器失败，也不能凭区块数量、材料簇或地形高度替代天然结构起点。未来实际目标服只要有经独立模型校准的完整结构证据或经过验证的自定义噪声前向生成器，才能增加约束。

## 真实客户端E2E合并门槛

新工作流 `.github/workflows/p39-real-seed-recovery.yml` 真实启动**两版Minecraft服务端和Fabric GUI**，让游戏产生实际客户端完整轮廓；**不向新CLI传递服务端StructureStart或真实Seed**。完整客户端轮廓单独由后验保存的FULL NBT校准，输入给新CLI后必须**实际调用本机Z3，从四个真实客户端轮廓反演出已知Seed=2731的low48**，并复核独立HOLDOUT。也有“没有结构/混合世界/非法栅格/错Seed”负例。受控Seed成功**不能**等于未知自定义地形服务器已被破解；这必须另行独立认证。

## 下一步真正的算法问题（不重复开发外观）

要在没有沙漠神殿真起点的自定义服务器上反推完整64位，必须找到至少一个**对真实Seed敏感、可从客户端观察且前向规则可验证**的独立地形/群系生成模型。优先解析实际世界使用的Terralith/数据包/插件版本与映射规则，并在真实已知Seed同配置隔离世界中证明；若生成器应用未知Salt、后处理或非Seed决定的随机数，可能无法唯一识别。未经这些前提，不能只靠增加CPU或轮廓模板得出真实Seed。
