package io.github.fxjcangku.worldseedlab.core;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;

/**
 * 按真实游戏版本绑定受控FULL存档夹具及其独立来源，禁止新版本回退旧CSV。
 *
 * <p>清单只登记已经实际生成并留存的公开Seed=2731控制世界；缺少版本、
 * 来源或SHA时直接失败，不能靠重命名旧样本或模型自身计算补齐新版本真值。
 */
final class P31ControlledFixture {
    private static final Path ROOT = Path.of("src", "test", "resources", "p31");

    private P31ControlledFixture() {}

    static Path path(String version) throws IOException {
        return ROOT.resolve(entry(version).get("file").getAsString());
    }

    static List<String> readLines(String version) throws IOException {
        return readLines(version, path(version));
    }

    static List<String> readLines(String version, Path file) throws IOException {
        verify(version, file);
        List<String> rows = Files.readAllLines(file, StandardCharsets.UTF_8);
        if (rows.size() != 6 || !"quart_x,quart_y,quart_z,biome_id".equals(rows.getFirst())) {
            throw new IOException("受控FULL群系CSV必须恰好包含五个独立样本");
        }
        return rows;
    }

    static String verify(String version, Path file) throws IOException {
        return verifyFile(file, entry(version).get("sha256").getAsString());
    }

    /** 新版结构真值必须来自同版实际保存的StructureStart，不能沿用旧版硬编码坐标。 */
    static List<String> readStructureStarts(String version) throws IOException {
        JsonObject fixture = entry(version);
        JsonObject structures = fixture.getAsJsonObject("structure_starts");
        if (structures == null) {
            throw new IOException("该版本缺少独立真实StructureStart夹具，请先完成受控世界生成验收");
        }
        try {
            String file = structures.get("file").getAsString();
            if (!file.equals("vanilla_" + version + "_seed2731_structures.csv")
                    || !"REAL_MINECRAFT_STRUCTURE_START".equals(structures.get("provenance").getAsString())
                    || structures.get("sample_count").getAsInt() < 4) {
                throw new IllegalArgumentException("结构真值来源或数量不完整");
            }
            requireSource(structures);
            Path path = ROOT.resolve(file);
            verifyFile(path, structures.get("sha256").getAsString());
            List<String> rows = Files.readAllLines(path, StandardCharsets.UTF_8);
            if (rows.size() != structures.get("sample_count").getAsInt() + 1
                    || !"chunk_x,chunk_z".equals(rows.getFirst())) {
                throw new IllegalArgumentException("结构真值CSV与来源清单不一致");
            }
            return rows;
        } catch (RuntimeException invalid) {
            throw new IOException("该版本真实StructureStart夹具清单非法", invalid);
        }
    }

    private static JsonObject entry(String version) throws IOException {
        if (!BootstrapCheck.supportsMinecraftVersion(version)) {
            throw new IOException("受控夹具不支持该游戏版本");
        }
        Path manifest = ROOT.resolve("fixture-manifest.json");
        if (!Files.isRegularFile(manifest, LinkOption.NOFOLLOW_LINKS)
                || Files.size(manifest) > 65_536) {
            throw new IOException("缺少受控夹具来源清单");
        }
        try {
            var root = JsonParser.parseString(Files.readString(manifest, StandardCharsets.UTF_8)).getAsJsonObject();
            if (root.get("schema").getAsInt() != 1) {
                throw new IllegalArgumentException("未知夹具清单版本");
            }
            JsonObject result = root.getAsJsonObject("fixtures").getAsJsonObject(version);
            if (result == null) {
                throw new IOException("该版本尚无独立真实FULL群系夹具；不得回退其它版本");
            }
            if (!result.get("file").getAsString().equals("vanilla_" + version + "_seed2731_quart.csv")
                    || !"REAL_MINECRAFT_FULL_CHUNK".equals(result.get("provenance").getAsString())
                    || !"2731".equals(result.get("known_seed").getAsString())
                    || result.get("sample_count").getAsInt() != 5) {
                throw new IllegalArgumentException("受控FULL群系真值来源或数量非法");
            }
            requireSource(result);
            return result;
        } catch (RuntimeException invalid) {
            throw new IOException("受控夹具来源清单非法", invalid);
        }
    }

    private static void requireSource(JsonObject entry) {
        if (!entry.get("sha256").getAsString().matches("[a-f0-9]{64}")
                || !entry.get("source_run_url").getAsString().matches(
                    "https://github\\.com/fxjcangku/WorldSeedLab/actions/runs/[0-9]+")) {
            throw new IllegalArgumentException("夹具必须保留独立CI来源和明确SHA256");
        }
    }

    private static String verifyFile(Path file, String expected) throws IOException {
        if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS) || Files.size(file) > 8192) {
            throw new IOException("缺少真实受控夹具，或输入不是受限普通文件");
        }
        try {
            String actual = HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file)));
            if (!actual.equals(expected)) {
                throw new IOException("真实Minecraft夹具SHA256与目标版本来源不一致");
            }
            return actual;
        } catch (NoSuchAlgorithmException unavailable) {
            throw new IllegalStateException("Java运行时缺少SHA256", unavailable);
        }
    }
}
