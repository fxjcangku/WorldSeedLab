package io.github.fxjcangku.worldseedlab.client.ui;

import io.github.fxjcangku.worldseedlab.client.probe.WorldProbeController;
import io.github.fxjcangku.worldseedlab.client.PlatformScreens;
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
    private int bx, by, bw, bh, side, cx, cw;

    public GlassDashboard(WorldProbeController controller) {
        super(Component.literal("WorldSeedLab · 世界研究面板"));
        this.controller = controller;
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
                side, cx, cw, this.font, controller));
        button(bx + 18, by + 111, side - 33, 27,
                () -> controller.active() ? "停止采集" : "开始采集",
                () -> { if (controller.active()) controller.stop("已手动停止，后台队列继续完成写入");
                        else controller.start(Minecraft.getInstance()); }, true);
        button(bx + 18, by + 149, side - 33, 25, () -> "标记沉船",
                () -> controller.markStructure(Minecraft.getInstance(), "minecraft:shipwreck"), false);
        button(bx + 18, by + 183, side - 33, 25, () -> "标记沙漠神殿",
                () -> controller.markStructure(Minecraft.getInstance(), "minecraft:desert_pyramid"), false);
        button(bx + 18, by + 217, side - 33, 25, () -> "标记村庄",
                () -> controller.markStructure(Minecraft.getInstance(), "worldseedlab:village_unclassified"), false);

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

        private Backdrop(int x, int y, int w, int h, int bx, int by, int bw, int bh,
                         int side, int cx, int cw, Font font, WorldProbeController controller) {
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
            g.text(font, "WORLDSEEDLAB", bx + 21, by + 51, GlassUi.TEXT, true);
            g.text(font, "世界生成研究", bx + 21, by + 72, GlassUi.MUTED, false);
            GlassUi.badge(g, bx + 18, by + 263, side - 35, 27, 0x57467A92);
            g.text(font, "P1 · OBSERVATION", bx + 27, by + 271, GlassUi.TEXT, false);
            if (bh >= 355) {
                g.text(font, "仅分析可见数据",
                        bx + 21, by + 311, GlassUi.MUTED, false);
            }
            if (bh >= 370) {
                g.text(font, "本地存储 · 无数据上传",
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

            g.text(font, "世界生成观察台", cx, by + 29, GlassUi.TEXT, true);
            g.text(font, "WORLD PROBE / BIOME INTELLIGENCE", cx, by + 48, GlassUi.MUTED, false);
            var profile = controller.dimensionProfile();
            if (profile != null) {
                g.text(font, "维度 " + GlassUi.compact(profile.dimension(),
                                Math.max(12, (cw - 260) / 7))
                                + " · 高度 " + profile.minBuildY() + " ~ "
                                + (profile.maxBuildYExclusive() - 1),
                        cx, by + 63, GlassUi.MUTED, false);
            }
            GlassUi.badge(g, cx + cw - 104, by + 24, 99, 23,
                    controller.active() ? 0x5533BD9B : 0x555B728C);
            g.text(font, controller.active() ? "采集中" : "已停止",
                    cx + cw - 82, by + 32,
                    controller.active() ? GlassUi.MINT : GlassUi.TEXT, false);

            int gap = 10, tileWidth = (cw - 2 * gap) / 3;
            metric(g, cx, by + 81, tileWidth, "已观察群系种类",
                    String.valueOf(controller.biomeTypes()), GlassUi.CYAN);
            metric(g, cx + tileWidth + gap, by + 81, tileWidth, "已读取网格样本",
                    String.valueOf(controller.observedSamples()), GlassUi.TEXT);
            metric(g, cx + (tileWidth + gap) * 2, by + 81, tileWidth, "累计完成区块",
                    String.valueOf(controller.writtenChunks()), GlassUi.MINT);

            GlassUi.glass(g, cx, by + 173, cw, 43, false);
            g.text(font, "状态 / " + GlassUi.compact(controller.status(),
                    Math.max(14, (cw - 18) / 6)), cx + 13, by + 184,
                    GlassUi.TEXT, false);
            g.text(font, GlassUi.compact(
                    "已加载群系待办 " + controller.queuedChunks()
                    + " · 待写盘 " + controller.pendingWrites()
                    + " · 地形 " + controller.terrainChunksWritten()
                    + " · 细节 " + controller.detailChunksWritten(),
                    Math.max(28, (cw - 18) / 6)),
                    cx + 13, by + 200, GlassUi.MUTED, false);

            GlassUi.glass(g, cx, by + 223, cw, 102, false);
            g.text(font, "采样参数 / SAMPLING", cx + 12, by + 234, GlassUi.TEXT, true);
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
                g.text(font, "真实群系 / OBSERVED BIOMES", cx + 13, listY + 13,
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
            g.text(font, "只读群系 + 手动结构观察 | Seed 求解器未实现",
                    bx + 20, by + bh - 17, GlassUi.MUTED, false);
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
