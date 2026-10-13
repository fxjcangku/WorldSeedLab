package io.github.fxjcangku.worldseedlab.client;

import com.mojang.blaze3d.platform.InputConstants;
import io.github.fxjcangku.worldseedlab.core.BootstrapCheck;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * P1 client-only bootstrap. Probe is opt-in; a join or restart NEVER resumes it.
 */
public final class WorldSeedLabClient implements ClientModInitializer {
    private static final Logger LOGGER = LoggerFactory.getLogger("WorldSeedLab");
    private WorldProbeController probe;

    @Override
    public void onInitializeClient() {
        String gameVersion = FabricLoader.getInstance().getModContainer("minecraft")
                .map(container -> container.getMetadata().getVersion().getFriendlyString())
                .orElse("unknown");
        BootstrapCheck.Report check = BootstrapCheck.inspect(Runtime.version().feature(), gameVersion);
        if (!check.ok()) {
            throw new IllegalStateException("WorldSeedLab bootstrap rejected: " + check.message());
        }

        probe = new WorldProbeController(FabricLoader.getInstance()
                .getConfigDir().resolve("worldseedlab"), gameVersion, LOGGER);
        KeyMapping.Category category = KeyMapping.Category.register(
                Identifier.fromNamespaceAndPath("worldseedlab", "general"));
        KeyMapping panelKey = KeyMappingHelper.registerKeyMapping(
                new KeyMapping("key.worldseedlab.panel", InputConstants.Type.KEYSYM,
                        InputConstants.KEY_F8, category));

        ClientTickEvents.END_CLIENT_TICK.register(minecraft -> {
            while (panelKey.consumeClick()) {
                if (minecraft.player != null) {
                    PlatformScreens.open(minecraft, probe);
                }
            }
            probe.tick(minecraft);
        });

        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) -> {
            dispatcher.register(ClientCommands.literal("wsl")
                    .executes(context -> {
                        PlatformScreens.open(Minecraft.getInstance(), probe);
                        return 1;
                    })
                    .then(ClientCommands.literal("gui").executes(context -> {
                        PlatformScreens.open(Minecraft.getInstance(), probe);
                        return 1;
                    }))
                    .then(ClientCommands.literal("status").executes(context -> {
                        context.getSource().sendFeedback(Component.literal(
                                "WorldSeedLab | " + (probe.active() ? "采集中" : "已停止")
                                + " | 本次群系: " + probe.biomeTypes()
                                + " | 观测样本: " + probe.observedSamples()
                                + " | " + probe.status()));
                        return 1;
                    }))
                    .then(ClientCommands.literal("about").executes(context -> {
                        context.getSource().sendFeedback(Component.literal(
                                "独立世界生成研究 | F8 打开液态玻璃面板 | 仅本地已加载区块 | 无 Seed 破解功能"));
                        return 1;
                    })));
        });
        LOGGER.info("WorldSeedLab P1 ready for Minecraft {}: biome capture disabled until manual activation.",
                gameVersion);
    }
}
