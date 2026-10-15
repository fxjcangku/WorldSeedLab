# P4.2 · 实服生成器假设证伪与客户端世界高度纠错

## 实际目标

WorldSeedLab 的P4.2是可运行的、只读的世界生成**可辨识性门槛**：流式检查单一真实客户端HMAC世界/维度/版本的P1群系、P2地表与STRATA地下证据。分析同一quart坐标的重复和群系冲突、地形块复测冲突、空间上三条证据链的交叉区域；当合法取得独立模型参考时，只否决与确凿客户端观测矛盾的模型。

**模型没有被证伪，不代表已经识别生成器或恢复Seed。** 仅仅观察到Terrralith命名空间和漂亮地形不能推导NoiseRouter、Salt、生成器版本或地下真矿位置。本项目不下载、不复制、也不发布第三方受限世界生成器内容。

## 本机使用

~~~powershell
python scripts/audit_generator_identifiability.py --input "D:\private\observations.zip" --json-out "D:\private\p42-anonymous.json"
~~~

也可以使用原始单一世界/维度的P1/P2/STRATA目录。报告路径必须不存在，原始证据只读；拒绝软链接输入、超大证据、混合世界/维度/游戏版本。整个输出无服务器地址、HMAC、原始XYZ、本机identity.key。

可选输入独立获得的候选**元数据指纹**（假设格式示例，不代表实际第三方生成器参数）：

~~~json
{
  "schema": 1,
  "reference_models": [
    {
      "model_id": "worldseedlab:owned_control_example",
      "provenance": "USER_SUPPLIED_UNVERIFIED",
      "game_version": "26.2",
      "dimension": "minecraft:overworld",
      "dimension_profile": {
        "min_build_y": -64,
        "max_build_y_exclusive": 320,
        "sea_level": 63
      },
      "biome_catalog_complete": false,
      "possible_biomes": ["minecraft:plains"]
    }
  ]
}
~~~

~~~powershell
python scripts/audit_generator_identifiability.py --input "D:\private\observations.zip" --reference-models "D:\private\hypotheses.json"
~~~

若完整声明的候选注册表缺少已观察的群系、或明确版本/维度/世界高度冲突，返回 MODEL_HYPOTHESIS_CONTRADICTED，**仅否定该候选的已声明参数**。若目录未声明完整性，缺失群系不能被当作冲突。即使剩下一个模型，也只能写 NOT_FALSIFIED_IDENTITY_UNVERIFIED。一个参数文件写了自己被认证，不代表实际目标服务器提供了认证。

## 发现并修复真正的客户端高度口径错误

旧 WorldSeedLab TerrainObserver 曾把 Mojmap ClientLevel.getMaxY() 直接填入名称为 maxBuildYExclusive 的字段。但 getMaxY() 返回**包含最高Y的一格**；正确排他上界应为 level.getMinY() + level.getHeight()。

P4.2修复客户端从此以后新写入的高度上界，同时对历史P2数据在离线研究中保留原件、按16格区块高度合法性做只读兼容：
- 记录max-min能被16整除：新格式，直接采用排他上界；
- 记录max-min除16余15：旧含上界数据，内存中+1成为排他上界；
- 其它未知格式拒绝推断。

新增真实 Minecraft 26.1.2/26.2 Mojmap类受控对照：世界下界-64，高度704，则含上界639、排他上界640；从两版本官方噪声注册表读取默认Overworld为[-64,320)、海平面63。**这些控制结果是模型边界，不证明未知服务器用的全部NoiseRouter规则。**

## 第二轮真实服务器证据（仅匿名）

此前上传的26.2实服原始档案已在本机完整流式回放，未提交原始数据：
- 2,085,248条P1记录恰好对应2,085,248个不同quart XYZ，重复位置与互相矛盾群系均为0。
- P2区块2,679；STRATA区块2,034；垂直地层方块357,984。
- 实际观察61种群系，其中27种非minecraft注册ID。
- P1/P2/STRATA共同宏区域46；保守至少1024方块间隔的独立区域9。
- 原P2错误记载的排他上界639需归一化为640，当前主世界真实可见高度为[-64,640)；默认原版Overworld为[-64,320)。
- 旧海床高度图32,544列有32,530列无效，不可假定这些是天然海底生成证据。

因此当前可以数学上否决**未经修改的完整原版Overworld世界高度与仅minecraft群系注册表**假设，但无法证明服务端没有复用原版NoiseRouter模块。模型身份、Salt、区块历史、StructureStart天然来源均UNKNOWN。P3.9缺少3座可靠独立沙漠神殿轮廓，低48不可推导。P4.0/P4.1条件高16也不能凭当前数据算出后台完整64位Seed。

## 合并验收

- 跨世界/版本/维度、错误P1 quart来源、重叠冲突、假模型完备性、旧含上界高度与新排他高度兼容、匿名报告只写新文件：Python正反例。
- Java25/Gradle9.6.1两版本clean build运行P42HeightAndVanillaReferenceSmokeTest，以真正Mojmap验证高度API语义及原版NoiseGeneratorSettings基线。
- P3.9真实客户端轮廓→Z3、P3.8/P3.6真正Fabric GUI以及其它既有回归全部保持通过。受控测试不等于未知实服种子恢复。
