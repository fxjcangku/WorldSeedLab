package io.github.fxjcangku.worldseedlab.core;

import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.core.registries.Registries;
import net.minecraft.data.registries.VanillaRegistries;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.levelgen.NoiseGeneratorSettings;

/**
 * P4.2 仅使用两版目标Minecraft Mojmap真值验证高度口径及严格原版基线。
 *
 * LevelHeightAccessor.getMaxY() 是含上界(639)，而项目DimensionProfile字段
 * maxBuildYExclusive需要排他上界(640)；旧版真实JSONL可能错写了639。
 * 该受控验证不能证明用户的世界噪声路由器或结构Salt是原版。
 */
public final class P42HeightAndVanillaReferenceSmokeTest {
    private P42HeightAndVanillaReferenceSmokeTest() {}

    public static void main(String[] args) {
        var extended = LevelHeightAccessor.create(-64, 704);
        if (extended.getMinY() != -64 || extended.getHeight() != 704
                || extended.getMaxY() != 639
                || extended.getMinY() + extended.getHeight() != 640) {
            throw new AssertionError("目标Minecraft getMaxY的含上界语义与排他Y计算不一致");
        }
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        var registries = VanillaRegistries.createLookup();
        var original = registries.lookupOrThrow(Registries.NOISE_SETTINGS)
                .getOrThrow(NoiseGeneratorSettings.OVERWORLD).value();
        var settings = original.noiseSettings();
        if (settings.minY() != -64 || settings.height() != 384
                || settings.minY() + settings.height() != 320
                || original.seaLevel() != 63) {
            throw new AssertionError("当前Minecraft的Overworld默认噪声高度或海平面与P4.2假设冲突");
        }
        System.out.println("P4.2 real Minecraft " + System.getProperty("mcVersion", "26.2")
                + " LevelHeightAccessor -64..639 inclusive = -64..640 exclusive PASS;"
                + " default vanilla Overworld noise -64..320 exclusive, sea63 PASS;"
                + " unknown remote server NoiseRouter remains UNKNOWN");
    }
}
