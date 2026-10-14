package io.github.fxjcangku.worldseedlab.core;

import io.github.fxjcangku.worldseedlab.core.geometry.ClientVisibleDesertPyramidRaster;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

/**
 * P3.6 真实自动取证的离线验证：必须先有四份由真正 Fabric GUI 自动保存的证据。
 *
 * <p>不接受手动双角或服务端伪造的输入。随后继续调用既有 P3.5
 * 受控 FULL Anvil StructureStart/多朝向/Z3/Java 精确验证代码，
 * 不复制已成熟的低48反演算法，也不假称已破解未知服务器完整64位 Seed。
 */
public final class P36RealFabricAutoEvidenceTest {
    private P36RealFabricAutoEvidenceTest() {}

    public static void main(String[] args) throws Exception {
        if (args.length != 2) throw new IllegalArgumentException("真实自动客户端数据目录、版本缺失");
        Path root = Path.of(args[0]);
        String version = args[1];
        if (!"26.1.2".equals(version) && !"26.2".equals(version)) {
            throw new IllegalArgumentException("P3.6不支持此Minecraft版本");
        }
        int count = 0;
        String firstScope = null;
        for (int i = 0; i < 4; i++) {
            Path file = root.resolve("client-footprint-" + i + ".properties");
            if (!Files.isRegularFile(file) || Files.size(file) > 320000) {
                throw new AssertionError("真实客户端自动证据缺失或无界："+i);
            }
            var raw = new Properties();
            try (var reader=Files.newBufferedReader(file,StandardCharsets.UTF_8)) {
                raw.load(reader);
            }
            if (!"P36_AUTOMATIC_EXPLICIT_OPT_IN".equals(raw.getProperty("captureMode"))
                    || !"UNKNOWN".equals(raw.getProperty("generatorProfile"))
                    || !"false".equals(raw.getProperty("structureStartKnown"))
                    || !"UNKNOWN".equals(raw.getProperty("naturalOrigin"))
                    || !"CLIENT_OBSERVED_BLOCK_STATES".equals(raw.getProperty("origin"))) {
                throw new AssertionError("真实自动模式没有完整的客户端取证与生成器未知声明");
            }
            var archive=ClientVisibleDesertPyramidRaster.read(file);
            if (!version.equals(archive.scope().gameVersion())
                    || !"minecraft:overworld".equals(archive.scope().dimension())) {
                throw new AssertionError("P3.6自动证据版本或维度不一致");
            }
            String scope=archive.scope().worldFingerprint();
            if (firstScope == null) firstScope=scope;
            if (!firstScope.equals(scope)
                    || !archive.audit().completeVisiblePerimeter()
                    || archive.audit().matchedFootprints()!=1
                    || archive.rasterSize()!=30752) {
                throw new AssertionError("客户端实际自动取证不完整、歧义、跨世界或栅格大小错误");
            }
            count++;
        }
        if (count != 4) throw new AssertionError("客户端自动建筑数量不完整");
        var control=Files.readString(root.resolve("posthoc-control-summary.json"));
        if (!control.contains("\"not_client_observed\": true")
                || !control.contains("\"selected_true_starts_with_full_chunks\": 4")
                || !control.contains("\"source\": \"CONTROL_WORLD_POSTHOC_FULL_ANVIL_STRUCTURE_START\"")) {
            throw new AssertionError("真实自动客户端证据之后缺少独立FULL存档和真值");
        }
        // 核心几何与逆向流程由已经通过P3.5的受控真实 E2E 验证器复用。
        P35RealFabricClientFootprintTest.main(args);
        System.out.println("P3.6 ACTUAL Fabric auto evidence "+version
                + ": one opt-in command; four automatically captured FULL-voxel "
                + "21x21 perimeters; actual saved StructureStart posthoc + "
                + "Z3/Java FIT/HOLDOUT verified. Unknown generator remains UNKNOWN.");
    }
}
