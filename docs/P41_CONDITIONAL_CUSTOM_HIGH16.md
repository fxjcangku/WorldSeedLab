# P4.1 · 真实客户端轮廓 low48 → 自定义气候模型 high16 的实际可续算反演

## 与 P4.0 的本质区别

P4.0 只能逐一检查研究者明确列出的1..32个完整64位 Seed。P4.1 实际串接 P3.9 已验证的完整客户端21×21神殿轮廓 → 真 Z3 lower48 → Java RNG FIT/HOLDOUT，与 P4.0 目标Minecraft Mojmap RandomState + 研究者提供的六维气候参数表。**不需要研究者手写65536个完整Seed**；对每个低48候选可在64位中精确枚举全部65536个高16位，实际逐候选比较3个空间独立FIT和至少2个独立HOLDOUT quart。

新实现位于 P41ConditionalHigh16Search.java，和严格拒绝UNVERIFIED_OR_CUSTOM生成器的 Full64BiomeCandidateSearch 保持隔离。用户研究自定义世界时只返回 CONDITIONAL_CUSTOM_FULL64，**不能叫目标服务器真实Seed**。

## 单次执行（用户私人本机）

先确保普通玩家自愿提供的同一 HMAC 世界+维度的observations.zip中确实含有至少三处**不同RandomSpread Region**的完整神殿轮廓，且完整P1群系真实样本可提供3FIT+2HOLDOUT，此外还需合法、独立得到的实际生成器六维气候参数模型。缺任何一项不能启动这条条件搜索。

~~~powershell
python scripts/recover_seed.py --input "D:\private\observations.zip" --minecraft 26.2 --custom-model "D:\private\climate.json" --high16-shard 0 --high16-shards 16 --high16-evaluations 128
~~~

- 仅支持明确声明的 vanilla_overworld_noise_router 假设 + 用户独立参数表。不能根据terrarlith/terralith:群系ID猜测真实NoiseRouter。
- 仍保留P3.9默认结构Salt=14357617的原版假设；其天然来源、模板零偏移、Server Salt和生成历史均未经目标服证明。
- P1证据流式读取全部真实quart ID，缺失模型群系ID立即 MODEL_REGISTRY_INCOMPATIBLE，不运行Z3或高16；不把冲突误报为世界Seed不存在。
- 每次默认按16分片，每次最多128个实际候选 / 120秒。性能会受Minecraft RandomState初始化影响；每次打印实际examined、fitRejected、holdoutRejected、NEXT_INDEX和DOMAIN_SIZE。没有穷尽不称全域。
- 支持更改 --high16-shard 1..15。每个分片在已排序low48候选集合上使用完全相同的半开索引计划，防重复/防遗漏。仍然需要运行各分片且完成所有候选检查才能讨论在该假设下的完整搜索。
- 若到达 EVALUATION_BUDGET/TIME_BUDGET/RESULT_CAP，使用上次打印的 NEXT_INDEX 和 P4.1_INPUT_SHA256，继续**同一个分片**：

~~~powershell
python scripts/recover_seed.py --input "D:\private\observations.zip" --minecraft 26.2 --custom-model "D:\private\climate.json" --high16-shard 0 --high16-shards 16 --high16-evaluations 128 --high16-resume 128 --high16-expected-digest <上一轮实际打印的64位十六进制摘要>
~~~

上例的续算索引128只是解释格式，**必须改为上一轮真实 NEXT_INDEX**。摘要由客户端世界作用域、模型全部参数、真实quart、FIT/HOLDOUT角色及low48集合计算；不会打印原始HMAC、坐标、IP、identity.key。改变证据、样本、模型或low48候选集合必须拒绝旧断点；低48候选求解状态非 BOUNDED_MODEL_EXHAUSTED 也拒绝跨进程断点复用。高16只检查有限范围或尚未跑完所有分片时不得宣称全域唯一。

## 正反验收

- 两版独立 Java25/Gradle9.6.1 clean build 执行 p41ConditionalHigh16SmokeTest。使用以前实际已生成 FULL 世界（Seed=2731）保存的客户端可见5个quart真值，不由本轮模型合成观测；正式 vanilla 生成器前向独立复核再进入可插拔气候模型，对0..8高16窗口精确保留2731并排除错误高位。
- 校验分片完整覆盖65536、预算截断精确NEXT_INDEX、跨分片无重叠、取消、错误low48资格、模型注册表冲突、越界种子域；不会把8次测试夸大为65536次。
- Python测试验证真实P1转换临时CSV，3FIT+2HOLDOUT、无模型/跨作用域/不匹配注册表失败关闭、断点摘要必需、脚本只在本机用临时文件且自动删除。
- P3.9真实 Fabric GUI → Z3 和 P3.8/P3.6真实联机测试继续由既有CI进行；由于用户真实世界不足3个天然结构轮廓，P4.1不可能凭这轮百万群系给出服务端低48。**低48前提未建立时高16搜索没有定义**。

## 尚未解决

目标服务器究竟使用哪种生成器、具体NoiseRouter/Terralith数据包与服务器修改、结构Salt/地图生成时期及天然来源，这些未知参数决定是否有正确、可验证的 forward worldgen。用户上传的实际自定义服数据目前只允许判为 GEN_UNKNOWN / LOW48_NOT_DERIVED / FULL64_UNKNOWN；受控原版成功不得冒充破解未知服务器。
