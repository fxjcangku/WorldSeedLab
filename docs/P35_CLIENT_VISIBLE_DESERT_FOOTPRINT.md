# P3.5 · 普通 Fabric 客户端可见沙漠神殿轮廓取证

> 版本：26.1.2 / 26.2 · Java25 · Mojmap；状态：研发测试中，禁止将未完成的 Actions 写成成功。

## 与 P2.9 的重要区别

P2.9 真实沙漠神殿轮廓验收使用的是已知 Seed 的服务端存档 StructurePiece / StructureStart NBT 盒子，并非普通客户端的已加载方块。P3.5 新增普通 Fabric 客户端手动双角三维栅格，只记录已有 FULL chunk 的实际方块，原始文件保持 CLIENT_OBSERVED_BLOCK_STATES、naturalOrigin=UNKNOWN。普通玩家无法仅凭形态排除人工复制、损坏修复或世界历史混合。

## 真实玩家操作

在沙漠神殿附近的水平取证范围一角执行 /wsl footprint first；前往该范围对角执行 /wsl footprint second；**玩家指定的是比建筑更大的粗略ROI，不是神殿的精确起点或轮廓**。客户端必须从实际方块自己找到唯一21×21紧致外轮廓；待 /wsl footprint 状态显示完成，再手动 /wsl footprint export。中止使用 /wsl footprint clear。

区域 X/Z 每轴限 16..40 格；高度窗口 16..40 格。自动读取区域外侧一格用于验证“墙体外实际为空气”，包含外侧环带的所有 ChunkStatus.FULL 必须事先在客户端接收；没有就拒绝，无任何主动发包请求。每 Tick 最多 256 次读取，区块卸载、断线、切维度、世界纪元变更或失败立即清空。原始文件只在用户主动导出时写本机世界HMAC/维度隔离目录 p35-manual-footprint；含私人精确坐标，未经脱敏不能分享。

本阶段只针对已知版本受控沙漠神殿21×21模板。对ROI内每个可能的21×21闭矩形逐一审计，并且必须**恰好一个**矩形通过；多个重叠解释一律拒绝。采用保守门槛：四边至少各80%连续位置存在砂岩/切制砂岩接触外侧空气，建筑内部需出现至少4个橙色陶瓦、1个蓝色陶瓦、3个雕纹/切制砂岩。该门槛并非对所有沙漠神殿的完备识别；不完整、被沙遮挡、被拆改、纹饰缺失等情况必须失败关闭。沙漠神殿材料相似只算外观，不能证明自然来源。

## 算法接入与独立反演

ClientVisibleDesertPyramidRaster 是不可变的完整方块栅格及匿名作用域、受限本地序列化。ClientDesertFootprintCaptureController 管理手动会话及有界采样。完整轮廓审计通过后，离线用 StructureFootprintInverter 的已验证校准产生不同朝向的起点稀疏候选并集；不重新实现 Legacy RNG/Z3，也不把包络空白当成可能起点。真正种子约束仍必须有相同世界、版本、维度、结构集合、明确模型参数以及独立 FIT/HOLDOUT。

默认双版本 check 会执行 P35ClientVisibleFootprintSmokeTest：这些是合成方块、遮挡、缺采、跨版本、负坐标、文件来源与候选空洞正反例，绝不作为真实 runClient 证据。

## 真实 Minecraft 双版本验收门槛

GitHub Actions p35-real-client-footprint.yml 使用 Java25/Gradle9.6.1，两个版本分别运行真正 Minecraft 自定义沙漠专用服务器与 Fabric GUI runClient；在游戏聊天框为四处可见神殿分别执行 first/second/export，选取比预期真实建筑**四边各多4格**的手动粗略ROI，真实完整外缘必须由客户端BlockState独立发现，保留客户端原始栅格。只有客户端导出完成后才能独立解析受控存档真正 FULL Anvil 和 StructureStart NBT；服务端真值绝不能送入客户端取证或冒充 CLIENT_OBSERVED。

测试布景允许公开已知 Seed2731 的受控服务器 RCON locate/forceload/tp，用于导航和准备可见建筑，**绝不将locate所得坐标直接视作紧致外轮廓输入P2.9**；这不等于普通玩家可以在未知服运行 RCON，也不等于盲找建筑验收。验证器要求真实客户端四份不同 Region 的完整外围、受控真结构起点全部位于候选中、至少两种朝向、一个受控几何校准参考及其余 2 FIT+1 HOLDOUT，原创 Java RandomSpread/Z3 一致、错 Salt 和错 Seed 拒绝。

当前任何 CI 未实际完成前，只能称代码/验收入口已编写，不能宣称双版本客户端真实验收通过。若任一真实轮廓与上面的可见性假设矛盾，应保留失败结果、修正模型及正反例后重跑，**未通过不得合并 main**。

## 尚未实现

未知服务器自然生成来源认证、历史旧区块时序判别、完全自动结构外形覆盖、未知 Salt 多假设与高16全域负数 Seed 验收仍是独立研究。P3.5 无论成功与否，都不证明所有自定义服务器的完整64位 Seed 可反演。
