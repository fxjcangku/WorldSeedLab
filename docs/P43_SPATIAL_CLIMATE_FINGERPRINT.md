# P4.3 · 六维气候场和自定义群系的空间留出检验

## 为什么这一步不是空转

P4.2真实服务器证据排除了完全未修改的原版主世界（有非minecraft群系和不同高度），但还没有排除服务器**部分复用原版六维NoiseRouter**的可能性，也没有证实Terralith/插件实际生成器版本及原始Seed。

本阶段首次提供与第三方参数文件无关的**条件模型可迁移性检验**：对于研究者明确提供的最多8个完整64位Seed假设，使用目标Minecraft 26.1.2/26.2原生Mojmap RandomState与Climate.Sampler，读取六维气候向量，用FIT地域真实的自定义群系标签学习极简最近邻预测，然后只在从未用于训练的独立HOLDOUT地域评估可预测性。

重要限制：**不能从这种拟合自动生成真实服务器Seed**。世界生成历史、服务端NoiseRouter/后处理、结构Salt仍未知；即使某个假设比错误Seed强，也只代表这个假设值得进一步调查。

## 运行方式

无需候选Seed时，仅审计原始客户端群系与空间覆盖：

~~~powershell
python scripts/study_climate_fingerprint.py --input "D:\private\observations.zip" --minecraft 26.2
~~~

若研究者已从其他独立来源得到明确完整64位候选，再显式启用尚未验证的原版Overworld气候噪声假设（下方数字仅为公开已知受控Seed示例，不是未知服务器Seed）：

~~~powershell
python scripts/study_climate_fingerprint.py --input "D:\private\observations.zip" --minecraft 26.2 --seeds "2731,2732" --assume-vanilla-noise-router
~~~

没有候选时返回 GENERATOR=UNKNOWN / FULL64=UNKNOWN_NO_SEED_HYPOTHESES。仅显式授权会运行Minecraft噪声预测，并输出真实独立HOLDOUT准确率、训练集中最多群系标签的多数类基线、最差HOLDOUT区域准确率、训练未覆盖的留出标签数量及其决定的理论最高准确率；训练未覆盖的留出标签超过25%时中止该模型测试，防止把数据不足当成模型错误；不输出IP/HMAC/原始XYZ。

## 训练泄漏防护

- 从已有本机原始P1 CLIENT_OBSERVATION逐行读完整文件，严格单个世界HMAC/维度/游戏版本和已加载FULL的quart中心，最多800万条，保守容量和异常失败关闭。
- 按32×32区块（512方块）定义宏区域。选择5..12个距离保守至少1024方块的不同宏区域：约三分之二作为FIT，至少2个作为HOLDOUT。每区域最多48个确定性空间样本；不同区域绝不共享训练与留出角色。
- 不按已观测的群系标签挑选好看的HOLDOUT地点；默认从宏区域位置进行确定性最远点选择。
- 每个输入的原始XYZ/HMAC只存在用户自己的临时CSV，任务结束自动删除，不发布GitHub，不将数据发往Minecraft服务器。
- 模型报告 EXPLORATORY_ONLY、INSUFFICIENT_SPATIALLY_INDEPENDENT_SAMPLES 或 INSUFFICIENT_LABEL_DIVERSITY 等有限结果。即使分数完美，也不是目标服务器Seed或真实NoiseRouter证明。

## 验收来源与局限

- Python脚本的本地受控ZIP正反例检查世界来源、负坐标空间区域、假客户端来源、错误版本、未授权候选、临时CSV清理和独立地区留出分组。
- Java p43ClimateFingerprintSmokeTest 先用已由**真正Minecraft保存**的5条受控 FULL quart 证明对应版本实际前向原版模型一致；这5条很稀疏，不冒充已经足够训练的真实服务器证据。
- 再用公开2731真实Minecraft气候场派生的人工两类标签做算法自检，比较2731与错误种子，核对可重复性与防混合门控。此人工标签仅证明算法能运行，不是目标服独立自然生成器真值。
- Java25/Gradle9.6.1的26.1.2和26.2 clean build，原P3.9真实Fabric GUI→Z3及P3.8/P3.6等E2E均需通过后才能合并。

未知自定义服务器的生成器ID、NoiseRouter、Salt、结构天然起点和完整64位Seed仍为UNKNOWN。不自动扫描未知服务器、不接触私人yiyiaddon、不复制或发布第三方受限生成器代码或配置。


## 使用用户第二轮原始证据的本地实测（仅匿名）

在当前研究环境真正解包并读取用户上传的 worlds.rar 中的原始P1 evidence.jsonl，不凭空由P4.2匿名JSON制造quart坐标。完整两遍流式回放均结束，得到：

- 已验证真实客户端P1记录：2,085,248；可见群系ID：61；宏区域：46
- 与训练/留出区域距离门槛相容的宏区域：9（地理贪心选择，与用户此前P4.2保守区域结果一致）
- FIT独立区域：6，实际随机且确定性采样384条；HOLDOUT独立区域：3，采样192条
- FIT已见群系标签数：27；HOLDOUT中未在FIT出现的标签记录：35/192（18.23%）
- 因此即便给出了真实正确Seed，对于最近邻只能预测训练曾出现过的标签，当前留出准确率的硬上限至多157/192约81.77%。报告必须披露这个上限，不把映射未覆盖误判为NoiseRouter不匹配。

实际服务端NoiseRouter、Salt、Seed及生成器来源**仍未知**，所以没有执行错误的“全Seed搜索”。原始ZIP、世界HMAC、服务器IP、坐标与身份密钥均只在本机私有取证区，不得上传到公共仓库。
