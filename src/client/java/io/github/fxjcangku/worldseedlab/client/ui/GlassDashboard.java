package io.github.fxjcangku.worldseedlab.client.ui;

import io.github.fxjcangku.worldseedlab.client.probe.WorldProbeController;
import io.github.fxjcangku.worldseedlab.client.probe.AutoDesertFootprintController;
import io.github.fxjcangku.worldseedlab.client.PlatformScreens;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/**
 * P1 observation console: modern dark liquid-glass style built with vanilla
 * GUI extraction. No expensive blur passes, raw GL, or external renderer.
 */
public final class GlassDashboard extends Screen {
    private final WorldProbeController controller;
    private final AutoDesertFootprintController automatic;
    private int bx, by, bw, bh, side, cx, cw;

    public GlassDashboard(WorldProbeController controller,
                          AutoDesertFootprintController automatic) {
        super(Component.literal("WorldSeedLab · 多结构自动研究"));
        this.controller = controller;
        this.automatic = automatic;
    }

    @Override
    protected void init() {
        bw = Math.min(940, Math.max(400, width - 24));
        bh = Math.min(520, Math.max(320, height - 24));
        bx = (width - bw) / 2;
        by = (height - bh) / 2;
        side = bw >= 720 ? 170 : 132;
        cx = bx + side + 24;
        cw = bw - side - 42;

        // Draw backdrop first so actual focusable controls remain above glass.
        addRenderableWidget(new Backdrop(0, 0, width, height, bx, by, bw, bh,
                side, cx, cw, this.font, controller, automatic));
        // 一键自动联合取证始终占最显眼位置；旧的逐座手动结构标记仍可通过命令使用。
        button(bx + 16, by + 110, side - 32, 32,
                () -> automatic.running() ? "停止自动取证" : "一键自动取证",
                () -> {
                    if (automatic.running()) {
                        automatic.stop("用户通过F8关闭自动取证；已接受文件继续落盘");
                    } else {
                        automatic.start(Minecraft.getInstance());
                    }
                }, true);
        button(bx + 16, by + 153, side - 32, 26,
                () -> controller.active() ? "停止基础采集" : "仅采集群系地形",
                () -> {
                    if (controller.active()) controller.stop("用户停止基础采集");
                    else controller.start(Minecraft.getInstance());
                }, false);
        button(bx + 16, by + 190, side - 32, 26,
                () -> "已探索世界地图",
                () -> PlatformScreens.openMap(Minecraft.getInstance(), controller), false);
        button(bx + 16, by + 226, side - 32, 26,
                () -> "导出本地研究报告", controller::exportReport, false);

        int tileW = Math.max(80, (cw - 20) / 3);
        int sy = by + 228;
        button(cx + 12, sy + 28, tileW - 22, 25,
                () -> "半径 -",
                () -> controller.changeSettings(controller.settings()
                        .withRadius(Math.max(1, controller.settings().radiusChunks() - 1))), false);
        button(cx + 12, sy + 60, tileW - 22, 25,
                () -> "半径 +",
                () -> controller.changeSettings(controller.settings()
                        .withRadius(Math.min(6, controller.settings().radiusChunks() + 1))), false);
        button(cx + tileW + 12, sy + 40, tileW - 18, 30,
                () -> "切换采样密度",
                () -> controller.changeSettings(controller.settings().nextVerticalStride()), false);
        button(cx + tileW * 2 + 12, sy + 40, tileW - 22, 30,
                () -> "切换采样预算",
                () -> controller.changeSettings(controller.settings().nextBudget()), false);

        // 不允许在采集时修改证据档位；配置可持久化但采集许可永不持久化。
        button(cx + tileW + 12, sy + 74, cw - tileW - 24, 21,
                () -> "地形详情: " + controller.settings().detailMode().chineseLabel(),
                () -> controller.changeSettings(controller.settings().nextDetailMode()), false);

        // 从现有控制台进入已探索地图，切层/缩放不触发区块加载。
        if (bh >= 435) {
            button(bx + 18, by + bh - 107, side - 33, 28,
                    () -> "真实群系地图",
                    () -> PlatformScreens.openMap(Minecraft.getInstance(), controller), true);
        }

        // 窄画面避免额外按钮与主导航重叠；所有版本仍能使用 /wsl report。
        if (bh >= 450) {
            button(bx + 18, by + bh - 71, side - 33, 28,
                    () -> "导出研究日志", controller::exportReport, false);
        }
        // 结构材料候选辅助开关仍提供；一键自动开启时也联动该系统。
        if (bw >= 640) {
            button(bx + bw - 304, by + 18, 106, 22,
                    () -> controller.structureScanEnabled() ? "结构侦测：开" : "结构侦测：关",
                    () -> controller.setStructureScanning(!controller.structureScanEnabled()),
                    controller.structureScanEnabled());
        }
        button(bx + bw - 186, by + 18, 84, 22,
                () -> "真实地图",
                () -> PlatformScreens.openMap(Minecraft.getInstance(), controller), true);
        button(bx + bw - 92, by + 18, 70, 22, () -> "关闭", this::onClose, false);
    }

    private GlassAction button(int x, int y, int w, int h,
                               Supplier<String> title, Runnable callback, boolean primary) {
        GlassAction widget = new GlassAction(x, y, Math.max(22, w), h,
                this.font, title, callback, primary);
        addRenderableWidget(widget);
        return widget;
    }

    private static final class Backdrop extends AbstractWidget {
        private final int bx, by, bw, bh, side, cx, cw;
        private final Font font;
        private final WorldProbeController controller;
        private final AutoDesertFootprintController automatic;

        private Backdrop(int x, int y, int w, int h, int bx, int by, int bw, int bh,
                         int side, int cx, int cw, Font font,
                         WorldProbeController controller,
                         AutoDesertFootprintController automatic) {
            super(x, y, w, h, Component.empty());
            this.bx = bx;
            this.by = by;
            this.bw = bw;
            this.bh = bh;
            this.side = side;
            this.cx = cx;
            this.cw = cw;
            this.font = font;
            this.controller = controller;
            this.automatic = automatic;
            this.active = false;
        }

        @Override
        protected void extractWidgetRenderState(GuiGraphicsExtractor g,
                                                int mouseX, int mouseY, float delta) {
            g.fill(0, 0, getWidth(), getHeight(), 0xA9040915);
            g.fillGradient(0, 0, getWidth(), getHeight(), 0x2B0A344F, 0x160E082A);
            GlassUi.glass(g, bx, by, bw, bh, true);
            g.fillGradient(bx + 6, by + 6, bx + side, by + bh - 6,
                    0xA9182A3E, 0xA00A172A);
            g.fill(bx + side + 4, by + 14, bx + side + 5, by + bh - 14, 0x285AD8FF);
            g.text(font, "W / S / L", bx + 21, by + 27, GlassUi.CYAN, true);
            g.text(font, "世界种子研究", bx + 21, by + 51, GlassUi.TEXT, true);
            g.text(font, "自定义地形 · 自动取证", bx + 21, by + 72, GlassUi.MUTED, false);
            GlassUi.badge(g, bx + 18, by + 263, side - 35, 27,
                    automatic.running() ? 0x5D246E57 : 0x57467A92);
            g.text(font, "P3.8 · 多结构", bx + 27, by + 271,
                    automatic.running() ? GlassUi.MINT : GlassUi.CYAN, true);
            if (bh >= 355) {
                g.text(font, GlassUi.compact("沉船 / 海底遗迹 / 神殿",
                                Math.max(11, (side - 23) / 6)),
                        bx + 21, by + 311, GlassUi.CYAN, false);
            }
            if (bh >= 370) {
                g.text(font, "本地采集 · 不上传",
                        bx + 21, by + 329, GlassUi.MUTED, false);
            }

            // 维度注册键属于“客户端已获知”，不意味着玩家进入过对应维度。
            if (controller.advertisedDimensions() != null && bh >= 490) {
                var discovered = controller.advertisedDimensions();
                g.text(font, "已知维度键: " + discovered.dimensionIds().size(),
                        bx + 21, by + 355, GlassUi.MUTED, false);
                g.text(font, "非原版键: " + discovered.nonVanillaCount(),
                        bx + 21, by + 374, GlassUi.MUTED, false);
            }

            g.text(font, "世界生成与建筑自动取证", cx, by + 29, GlassUi.CYAN, true);
            g.text(font, "群系 · 高度 · 地层 · 遗迹 · 沉船", cx, by + 48, GlassUi.MUTED, false);
            var profile = controller.dimensionProfile();
            if (profile != null) {
                g.text(font, "维度 " + GlassUi.compact(profile.dimension(),
                                Math.max(12, (cw - 260) / 7))
                                + " · 高度 " + profile.minBuildY() + " ~ "
                                + (profile.maxBuildYExclusive() - 1),
                        cx, by + 63, GlassUi.MUTED, false);
            }
            GlassUi.badge(g, cx + cw - 104, by + 24, 99, 23,
                    automatic.running() ? 0x5533BD9B : 0x555B728C);
            g.text(font, automatic.running() ? "自动运行" : "等待授权",
                    cx + cw - 88, by + 32,
                    automatic.running() ? GlassUi.MINT : GlassUi.AMBER, true);

            int gap = 10, tileWidth = (cw - 2 * gap) / 3;
            metric(g, cx, by + 81, tileWidth, "真实群系种类",
                    String.valueOf(controller.biomeTypes()), GlassUi.CYAN);
            metric(g, cx + tileWidth + gap, by + 81, tileWidth, "疑似建筑组数",
                    String.valueOf(controller.structureCandidateGroups()), GlassUi.AMBER);
            metric(g, cx + (tileWidth + gap) * 2, by + 81, tileWidth, "完整神殿轮廓",
                    String.valueOf(automatic.verifiedFootprints()), GlassUi.MINT);

            GlassUi.glass(g, cx, by + 173, cw, 43, false);
            g.text(font, GlassUi.compact(
                    "自动建筑：" + (automatic.running() ? "运行中" : "已关闭")
                    + "    原始证据已落盘 " + automatic.persistedFootprints()
                    + "    结构候选 " + controller.structureCandidateRecordsWritten(),
                    Math.max(18, (cw - 18) / 6)),
                    cx + 13, by + 184, automatic.running() ? GlassUi.MINT : GlassUi.AMBER, true);
            g.text(font, GlassUi.compact(
                    "群系点 " + controller.observedSamples()
                    + " · 地形区块 " + controller.terrainChunksWritten()
                    + " · 地层区块 " + controller.detailChunksWritten()
                    + " · 写盘待办 " + (controller.pendingWrites() + automatic.pendingFiles()),
                    Math.max(24, (cw - 18) / 6)),
                    cx + 13, by + 200, GlassUi.MUTED, false);

            GlassUi.glass(g, cx, by + 223, cw, 102, false);
            g.text(font, "取证参数 · 有界扫描", cx + 12, by + 234, GlassUi.TEXT, true);
            g.text(font, "半径：" + controller.settings().radiusChunks() + " 区块",
                    cx + 12, by + 251, GlassUi.CYAN, false);
            g.text(font, "高度：" + controller.settings().verticalStride() + " 方块",
                    cx + tileWidth + 12, by + 251, GlassUi.CYAN, false);
            g.text(font, "预算：" + controller.settings().samplesPerTick() + " / tick",
                    cx + tileWidth * 2 + 12, by + 251, GlassUi.CYAN, false);

            if (bh >= 405) {
                int listY = by + 338;
                GlassUi.glass(g, cx, listY, cw,
                        Math.max(55, bh - (listY - by) - 25), false);
                g.text(font, "真实群系 · 当前观察样本", cx + 13, listY + 13,
                        GlassUi.TEXT, true);
                List<Map.Entry<String, Long>> entries = controller.topBiomes(
                        Math.max(1, Math.min(6, (bh - (listY - by) - 49) / 19)));
                if (entries.isEmpty()) {
                    g.text(font, "等待真实采样 · 不是群系注册表候选列表",
                            cx + 14, listY + 39, GlassUi.MUTED, false);
                } else {
                    int line = listY + 33;
                    for (Map.Entry<String, Long> entry : entries) {
                        g.fill(cx + 13, line + 3, cx + 17, line + 11, 0xFF53C8E9);
                        g.text(font, GlassUi.compact(entry.getKey(),
                                        Math.max(10, (cw - 85) / 6)),
                                cx + 24, line + 3, GlassUi.TEXT, false);
                        g.text(font, String.valueOf(entry.getValue()),
                                cx + cw - 62, line + 3, GlassUi.MUTED, false);
                        line += 19;
                    }
                }
            }
            g.text(font, "只读已加载区块  ·  结构自然来源未知  ·  无法直接证明完整64位Seed",
                    bx + 20, by + bh - 17, GlassUi.AMBER, false);
        }

        private void metric(GuiGraphicsExtractor g, int x, int y, int w,
                            String title, String value, int color) {
            GlassUi.glass(g, x, y, w, 81, false);
            g.text(font, title, x + 12, y + 14, GlassUi.MUTED, false);
            g.text(font, GlassUi.compact(value, Math.max(7, (w - 22) / 7)),
                    x + 12, y + 38, color, true);
        }

        @Override
        protected void updateWidgetNarration(NarrationElementOutput output) {
            // This is a non-interactive visual layer; controls provide narration.
        }
    }
}
