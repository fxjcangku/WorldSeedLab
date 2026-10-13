package io.github.fxjcangku.worldseedlab.client;

import io.github.fxjcangku.worldseedlab.core.BootstrapCheck;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.network.chat.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Client-only bootstrap. No world scan is started here.
 */
public final class WorldSeedLabClient implements ClientModInitializer {
    private static final Logger LOGGER = LoggerFactory.getLogger("WorldSeedLab");

    @Override
    public void onInitializeClient() {
        String gameVersion = FabricLoader.getInstance()
                .getModContainer("minecraft")
                .map(container -> container.getMetadata().getVersion().getFriendlyString())
                .orElse("unknown");

        BootstrapCheck.Report check = BootstrapCheck.inspect(Runtime.version().feature(), gameVersion);
        if (!check.ok()) {
            throw new IllegalStateException("WorldSeedLab bootstrap rejected: " + check.message());
        }

        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) -> {
            dispatcher.register(ClientCommands.literal("wsl")
                    .executes(context -> {
                        context.getSource().sendFeedback(Component.literal(
                                "WorldSeedLab | 已加载 | 数据采集默认关闭 | Seed 求解器尚未实现"));
                        return 1;
                    })
                    .then(ClientCommands.literal("status").executes(context -> {
                        context.getSource().sendFeedback(Component.literal(
                                "WorldSeedLab | Minecraft " + gameVersion
                                + " | Java " + Runtime.version().feature()
                                + " | 自检通过 | 仅基础框架"));
                        return 1;
                    }))
                    .then(ClientCommands.literal("about").executes(context -> {
                        context.getSource().sendFeedback(Component.literal(
                                "独立世界生成研究项目 | 不读取服务端 Seed | 不自动收集或上传数据"));
                        return 1;
                    })));
        });

        LOGGER.info("WorldSeedLab initialized for {} on Java {}. Probe collection: DISABLED.",
                gameVersion, Runtime.version().feature());
    }
}

