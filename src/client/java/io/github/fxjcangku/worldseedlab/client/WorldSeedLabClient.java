package io.github.fxjcangku.worldseedlab.client;

import com.mojang.blaze3d.platform.InputConstants;
import io.github.fxjcangku.worldseedlab.client.probe.WorldProbeController;
import io.github.fxjcangku.worldseedlab.client.probe.ClientQuartCaptureController;
import io.github.fxjcangku.worldseedlab.core.BootstrapCheck;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
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
    private ClientQuartCaptureController quart;

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
        quart = new ClientQuartCaptureController(probe, gameVersion);
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
            quart.tick(minecraft);
        });

        // 客户端退出时有限等待落盘，不阻塞正常运行中的 UI。
        ClientLifecycleEvents.CLIENT_STOPPING.register(client -> {
            quart.clear("客户端退出，未导出的手动群系样本已丢弃");
            probe.onClientStopping();
        });

        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) -> {
            // 全部为用户主动标记，绝不以观察坐标冒充结构生成起始区块。
            var mark = ClientCommands.literal("mark");
            for (var structure : WorldProbeController.structureCatalog().entrySet()) {
                String alias = structure.getKey();
                mark.then(ClientCommands.literal(alias).executes(context -> {
                    probe.markStructureByAlias(Minecraft.getInstance(), alias);
                    context.getSource().sendFeedback(Component.literal(probe.status()));
                    return 1;
                }));
            }
            var quartCmd = ClientCommands.literal("quart")
                    .executes(context -> {
                        context.getSource().sendFeedback(Component.literal(quart.status()));
                        return 1;
                    })
                    .then(ClientCommands.literal("sample").executes(context -> {
                        context.getSource().sendFeedback(Component.literal(
                                quart.sample(Minecraft.getInstance())));
                        return 1;
                    }))
                    .then(ClientCommands.literal("export").executes(context -> {
                        context.getSource().sendFeedback(Component.literal(
                                quart.export(Minecraft.getInstance())));
                        return 1;
                    }))
                    .then(ClientCommands.literal("clear").executes(context -> {
                        quart.clear("用户手动清空 P3.2 群系样本");
                        context.getSource().sendFeedback(Component.literal(quart.status()));
                        return 1;
                    }));
            dispatcher.register(ClientCommands.literal("wsl")
                    .executes(context -> {
                        PlatformScreens.open(Minecraft.getInstance(), probe);
                        return 1;
                    })
                    .then(ClientCommands.literal("gui").executes(context -> {
                        PlatformScreens.open(Minecraft.getInstance(), probe);
                        return 1;
                    }))
                    .then(ClientCommands.literal("map").executes(context -> {
                        // 只查看本地真实区块观测索引，不要求开启采集，也不获取 Seed。
                        PlatformScreens.openMap(Minecraft.getInstance(), probe);
                        return 1;
                    }))
                    .then(ClientCommands.literal("status").executes(context -> {
                        context.getSource().sendFeedback(Component.literal(
                                "WorldSeedLab | " + (probe.active() ? "采集中" : "已停止")
                                + " | 本次群系: " + probe.biomeTypes()
                                + " | 观测样本: " + probe.observedSamples()
                                + " | 自动结构: " + (probe.structureScanEnabled() ? "开" : "关")
                                + " | 疑似聚合组: " + probe.structureCandidateGroups()
                                + " | " + probe.status()));
                        return 1;
                    }))
                    .then(ClientCommands.literal("epoch")
                            .executes(context -> {
                                context.getSource().sendFeedback(Component.literal(
                                        probe.worldEpochStatus(Minecraft.getInstance())));
                                return 1;
                            })
                            .then(ClientCommands.literal("new").executes(context -> {
                                context.getSource().sendFeedback(Component.literal(
                                        probe.newWorldEpoch(Minecraft.getInstance())));
                                return 1;
                            })))
                    .then(ClientCommands.literal("report").executes(context -> {
                        context.getSource().sendFeedback(Component.literal(probe.exportReport()));
                        return 1;
                    }))
                    .then(ClientCommands.literal("path").executes(context -> {
                        context.getSource().sendFeedback(Component.literal(
                                "WorldSeedLab 本地证据目录: " + probe.dataRoot().toAbsolutePath()));
                        return 1;
                    }))
                    .then(mark)
                    .then(quartCmd)
                    .then(ClientCommands.literal("marks").executes(context -> {
                        context.getSource().sendFeedback(Component.literal(
                                "可手动标记的结构: " + String.join(", ",
                                        WorldProbeController.structureCatalog().keySet().stream()
                                                .sorted().toList())));
                        return 1;
                    }))
                    .then(ClientCommands.literal("detail").executes(context -> {
                        probe.changeSettings(probe.settings().nextDetailMode());
                        context.getSource().sendFeedback(Component.literal(
                                "扩展取证档位: " + probe.settings().detailMode().chineseLabel()
                                + " | " + probe.status()));
                        return 1;
                    }))
                    // 结构识别必须独立手动授权，不因普通跑图采集自动启动。
                    .then(ClientCommands.literal("structures")
                            .executes(context -> {
                                context.getSource().sendFeedback(Component.literal(
                                        "结构材料扫描: " + (probe.structureScanEnabled() ? "开启" : "关闭")
                                        + " | 近邻候选组: " + probe.structureCandidateGroups()
                                        + " | 已落盘候选: " + probe.structureCandidateRecordsWritten()));
                                return 1;
                            })
                            .then(ClientCommands.literal("on").executes(context -> {
                                context.getSource().sendFeedback(Component.literal(
                                        probe.setStructureScanning(true)));
                                return 1;
                            }))
                            .then(ClientCommands.literal("off").executes(context -> {
                                context.getSource().sendFeedback(Component.literal(
                                        probe.setStructureScanning(false)));
                                return 1;
                            })))
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
