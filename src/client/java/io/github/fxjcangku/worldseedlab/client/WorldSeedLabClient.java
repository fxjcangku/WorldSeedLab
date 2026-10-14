package io.github.fxjcangku.worldseedlab.client;

import com.mojang.blaze3d.platform.InputConstants;
import io.github.fxjcangku.worldseedlab.client.probe.WorldProbeController;
import io.github.fxjcangku.worldseedlab.client.probe.ClientQuartCaptureController;
import io.github.fxjcangku.worldseedlab.client.probe.ClientDesertFootprintCaptureController;
import io.github.fxjcangku.worldseedlab.client.probe.AutoDesertFootprintController;
import io.github.fxjcangku.worldseedlab.core.BootstrapCheck;
import io.github.fxjcangku.worldseedlab.client.ui.WslCommandDisplay;
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
    private ClientDesertFootprintCaptureController footprint;
    private AutoDesertFootprintController automatic;

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
        footprint = new ClientDesertFootprintCaptureController(probe, gameVersion);
        automatic = new AutoDesertFootprintController(probe, gameVersion);
        PlatformScreens.registerAutomatic(automatic);
        KeyMapping.Category category = KeyMapping.Category.register(
                Identifier.fromNamespaceAndPath("worldseedlab", "general"));
        KeyMapping panelKey = KeyMappingHelper.registerKeyMapping(
                new KeyMapping("key.worldseedlab.panel", InputConstants.Type.KEYSYM,
                        InputConstants.KEY_F8, category));

        ClientTickEvents.END_CLIENT_TICK.register(minecraft -> {
            while (panelKey.consumeClick()) {
                if (minecraft.player != null) {
                    PlatformScreens.open(minecraft, probe, automatic);
                }
            }
            probe.tick(minecraft);
            quart.tick(minecraft);
            footprint.tick(minecraft);
            automatic.tick(minecraft);
        });

        // 客户端退出时有限等待落盘，不阻塞正常运行中的 UI。
        ClientLifecycleEvents.CLIENT_STOPPING.register(client -> {
            automatic.onClientStopping();
            quart.clear("客户端退出，未导出的手动群系样本已丢弃");
            footprint.clear("客户端退出，未导出的建筑轮廓已丢弃");
            probe.onClientStopping();
        });

        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) -> {
            // 所有客户端指令采用统一的中文分行/加粗/颜色设计，且不会自动刷聊天。
            var mark = ClientCommands.literal("mark");
            for (var structure : WorldProbeController.structureCatalog().entrySet()) {
                String alias = structure.getKey();
                mark.then(ClientCommands.literal(alias).executes(context -> {
                    probe.markStructureByAlias(Minecraft.getInstance(), alias);
                    WslCommandDisplay.notice(context.getSource()::sendFeedback,
                            "手动结构标记 · 非天然起点", probe.status());
                    return 1;
                }));
            }
            var quartCmd = ClientCommands.literal("quart")
                    .executes(context -> {
                        WslCommandDisplay.notice(context.getSource()::sendFeedback,
                                "群系独立取样", quart.status());
                        return 1;
                    })
                    .then(ClientCommands.literal("sample").executes(context -> {
                        WslCommandDisplay.notice(context.getSource()::sendFeedback,
                                "群系独立取样", quart.sample(Minecraft.getInstance()));
                        return 1;
                    }))
                    .then(ClientCommands.literal("export").executes(context -> {
                        WslCommandDisplay.notice(context.getSource()::sendFeedback,
                                "群系样本导出", quart.export(Minecraft.getInstance()));
                        return 1;
                    }))
                    .then(ClientCommands.literal("clear").executes(context -> {
                        quart.clear("用户清空独立quart观测");
                        WslCommandDisplay.notice(context.getSource()::sendFeedback,
                                "群系样本", quart.status());
                        return 1;
                    }));
            var footprintCmd = ClientCommands.literal("footprint")
                    .executes(context -> {
                        WslCommandDisplay.notice(context.getSource()::sendFeedback,
                                "沙漠神殿手动完整轮廓（高级）", footprint.status());
                        return 1;
                    })
                    .then(ClientCommands.literal("first").executes(context -> {
                        WslCommandDisplay.notice(context.getSource()::sendFeedback,
                                "手动轮廓 · 第一个角", footprint.first(Minecraft.getInstance()));
                        return 1;
                    }))
                    .then(ClientCommands.literal("second").executes(context -> {
                        WslCommandDisplay.notice(context.getSource()::sendFeedback,
                                "手动轮廓 · 第二个角", footprint.second(Minecraft.getInstance()));
                        return 1;
                    }))
                    .then(ClientCommands.literal("export").executes(context -> {
                        WslCommandDisplay.notice(context.getSource()::sendFeedback,
                                "手动轮廓导出", footprint.export(Minecraft.getInstance()));
                        return 1;
                    }))
                    .then(ClientCommands.literal("clear").executes(context -> {
                        footprint.clear("用户取消手动轮廓取证");
                        WslCommandDisplay.notice(context.getSource()::sendFeedback,
                                "手动轮廓", footprint.status());
                        return 1;
                    }));
            var autoCmd = ClientCommands.literal("auto")
                    .executes(context -> {
                        WslCommandDisplay.auto(context.getSource()::sendFeedback, automatic, probe);
                        return 1;
                    })
                    .then(ClientCommands.literal("on").executes(context -> {
                        String result = automatic.start(Minecraft.getInstance());
                        WslCommandDisplay.notice(context.getSource()::sendFeedback,
                                "一键自动取证 · 授权结果", result);
                        WslCommandDisplay.auto(context.getSource()::sendFeedback, automatic, probe);
                        return 1;
                    }))
                    .then(ClientCommands.literal("off").executes(context -> {
                        String result = automatic.stop("用户主动停止P3.8自动取证；已接收证据继续落盘");
                        WslCommandDisplay.notice(context.getSource()::sendFeedback,
                                "自动取证 · 安全停止", result);
                        WslCommandDisplay.auto(context.getSource()::sendFeedback, automatic, probe);
                        return 1;
                    }))
                    .then(ClientCommands.literal("status").executes(context -> {
                        WslCommandDisplay.auto(context.getSource()::sendFeedback, automatic, probe);
                        return 1;
                    }));
            dispatcher.register(ClientCommands.literal("wsl")
                    .executes(context -> {
                        PlatformScreens.open(Minecraft.getInstance(), probe, automatic);
                        return 1;
                    })
                    .then(ClientCommands.literal("gui").executes(context -> {
                        PlatformScreens.open(Minecraft.getInstance(), probe, automatic);
                        return 1;
                    }))
                    .then(ClientCommands.literal("help").executes(context -> {
                        WslCommandDisplay.help(context.getSource()::sendFeedback);
                        return 1;
                    }))
                    .then(ClientCommands.literal("map").executes(context -> {
                        PlatformScreens.openMap(Minecraft.getInstance(), probe);
                        return 1;
                    }))
                    .then(ClientCommands.literal("status").executes(context -> {
                        WslCommandDisplay.general(context.getSource()::sendFeedback, probe, automatic);
                        return 1;
                    }))
                    .then(ClientCommands.literal("epoch")
                            .executes(context -> {
                                WslCommandDisplay.notice(context.getSource()::sendFeedback,
                                        "当前世界纪元", probe.worldEpochStatus(Minecraft.getInstance()));
                                return 1;
                            })
                            .then(ClientCommands.literal("new").executes(context -> {
                                WslCommandDisplay.notice(context.getSource()::sendFeedback,
                                        "创建新世界纪元", probe.newWorldEpoch(Minecraft.getInstance()));
                                return 1;
                            })))
                    .then(ClientCommands.literal("report").executes(context -> {
                        WslCommandDisplay.notice(context.getSource()::sendFeedback,
                                "研究报告导出", probe.exportReport());
                        return 1;
                    }))
                    .then(ClientCommands.literal("path").executes(context -> {
                        WslCommandDisplay.notice(context.getSource()::sendFeedback,
                                "本机证据路径", probe.dataRoot().toAbsolutePath().toString());
                        return 1;
                    }))
                    .then(mark)
                    .then(quartCmd)
                    .then(footprintCmd)
                    .then(autoCmd)
                    .then(ClientCommands.literal("marks").executes(context -> {
                        WslCommandDisplay.notice(context.getSource()::sendFeedback,
                                "可手动标记类型 · 与自动识别分离",
                                String.join(" | ", WorldProbeController.structureCatalog()
                                        .keySet().stream().sorted().toList()));
                        return 1;
                    }))
                    .then(ClientCommands.literal("detail").executes(context -> {
                        probe.changeSettings(probe.settings().nextDetailMode());
                        WslCommandDisplay.notice(context.getSource()::sendFeedback,
                                "自定义地形详情档位",
                                probe.settings().detailMode().chineseLabel()
                                        + " | " + probe.status());
                        return 1;
                    }))
                    .then(ClientCommands.literal("structures")
                            .executes(context -> {
                                WslCommandDisplay.structures(context.getSource()::sendFeedback, probe);
                                return 1;
                            })
                            .then(ClientCommands.literal("on").executes(context -> {
                                WslCommandDisplay.notice(context.getSource()::sendFeedback,
                                        "结构材料扫描", probe.setStructureScanning(true));
                                WslCommandDisplay.structures(context.getSource()::sendFeedback, probe);
                                return 1;
                            }))
                            .then(ClientCommands.literal("off").executes(context -> {
                                WslCommandDisplay.notice(context.getSource()::sendFeedback,
                                        "结构材料扫描", probe.setStructureScanning(false));
                                WslCommandDisplay.structures(context.getSource()::sendFeedback, probe);
                                return 1;
                            })))
                    .then(ClientCommands.literal("about").executes(context -> {
                        WslCommandDisplay.help(context.getSource()::sendFeedback);
                        return 1;
                    })));
        });
        LOGGER.info("WorldSeedLab P1 ready for Minecraft {}: biome capture disabled until manual activation.",
                gameVersion);
    }
}
