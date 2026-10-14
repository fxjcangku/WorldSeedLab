# P4.0 · 真实 MultiNoise 前向模型与自定义生成器假设审计

P4.0 不增加 GUI 或材料识别：使用目标 **Minecraft 26.1.2 / 26.2 Mojmap 的真实 RandomState 气候噪声**与可配置的六维气候参数表，按有限完整64位候选计算预测群系，执行真实客户端证据 3 FIT + 2 HOLDOUT 的候选检验。

## 直接使用第二轮实服证据

仅使用原始单一 HMAC 世界与维度的 P1 CLIENT_OBSERVATION，完整流式校验世界、版本、quart中心和来源；内部每个512方块区域选择原版/自定义代表，再找至少1024方块间隔的5处样本，原始坐标只存本机自动销毁的临时CSV。原始ZIP只读、不上传、不公开任何原始私服世界标识。

不提供任何模型也可运行证据质量与可辨识性门控：

~~~powershell
python scripts/audit_custom_climate.py --input "D:\private\observations.zip" --minecraft 26.2
~~~

没有独立获得且能复算的目标服务器气候配置时，返回：

~~~text
MODEL=UNKNOWN LOW48=NOT_DERIVED FULL64=UNKNOWN_CUSTOM_WORLDGEN
~~~

**不能从 terralith: 命名空间、材料候选、假矿或海床错误高度图猜生成器参数，也不能未经完整沙漠神殿自然结构证据就声称有已验证低48。**

## 显式真实前向假设

如果研究者通过合法途径独立核验了对应版本的完整气候区域列表，可创建**本机私有**参数配置（只说明格式，下面不是Terralith真实参数）：

~~~json
{
  "schema": 1,
  "provenance": "USER_SUPPLIED_UNVERIFIED",
  "generator_profile": "worldseedlab:independent_custom_climate_hypothesis",
  "climate_sampler": "minecraft:vanilla_overworld_noise_router",
  "entries": [
    {
      "biome": "minecraft:plains",
      "temperature": [-1.0, 1.0],
      "humidity": [-1.0, 1.0],
      "continentalness": [-1.0, 1.0],
      "erosion": [-1.0, 1.0],
      "depth": [-1.0, 1.0],
      "weirdness": [-1.0, 1.0],
      "offset": 0.0
    }
  ]
}
~~~

目前仅实现**原版 Overworld 噪声路由器假设 + 可变的六维气候区间**；不支持任意 Terralith/插件自定义 NoiseRouter。对未独立证实的NoiseRouter必须保持UNKNOWN。输入最多4096条气候分区、1MiB；候选最多32个显式Java long或0x位模式。完整64位候选不能由未知生成器自动凭空构造。

~~~powershell
python scripts/audit_custom_climate.py --input "D:\private\observations.zip" --minecraft 26.2 --model "D:\private\climate.json" --seeds "2731,2732"
~~~

MODEL_REGISTRY_CONFLICT 是完整P1里存在气候表不能预测的群系；候选逐一返回 REJECTED_BY_FIT、REJECTED_BY_HOLDOUT 或 CONDITIONAL_MATCH_UNVERIFIED。所有结果属于**输入模型假设**，不是目标服务器真实Seed认证，也没有搜索整个2^64域。

## 独立验收与剩余研究

- 双版本 Gradle p40CustomClimateSmokeTest 用实际已保存 FULL Minecraft 世界的5条群系证据作为独立真值：src/test/resources/p31/vanilla_<version>_seed2731_quart.csv。对照独立 VanillaOverworldBiomeModel 的真噪声映射；正确候选2731通过，错误2732排除。另有错误群系注册表、重复Seed、错误维度的失败例。
- scripts/test_audit_custom_climate.py 检查ZIP流式样本隔离、1024方块距离、3 FIT + 2 HOLDOUT、无模型失败关闭、群系注册表矛盾和输出隐私。
- 原版受控世界证明**算法实现可复算**，并不能证明真实自定义服务器使用相同NoiseRouter、Salt、参数表或世界生成时期。
- 下一步真正突破须取得并验证目标服的实际前向世界生成规则，再用独立受控同配置世界证明模型有效，将完整自然结构low48与高16或其它可逆噪声约束组合。没有这条证据链，不应继续声称可以唯一求出64位Seed。
