package io.github.fxjcangku.worldseedlab.client.ui;

import io.github.fxjcangku.worldseedlab.client.PlatformScreens;
import io.github.fxjcangku.worldseedlab.client.probe.WorldProbeController;
import io.github.fxjcangku.worldseedlab.core.map.MapLayer;
import io.github.fxjcangku.worldseedlab.core.map.MapPalette;
import io.github.fxjcangku.worldseedlab.core.map.MapQuery;
import io.github.fxjcangku.worldseedlab.core.map.MapView;
import java.util.List;
import java.util.function.Supplier;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/**
 * P2.5 已探索区域地图：展示客户端确实收到并保存的群系、地形和手动结构标记。
 *
 * <p>渲染只操作最新的不可变 MapView 快照；磁盘读取由 ObservedMapLoader
 * 的独立有界线程完成。未知区域保留暗色，不通过 Seed 或原版规则填空。
 */
public final class ObservedMapScreen extends Screen {
    private static final int[] ZOOM = {8, 10, 12, 16};
    private final WorldProbeController controller;
    private final ObservedMapLoader loader = new ObservedMapLoader();
    private WorldProbeController.MapAccess access;
    private MapLayer layer = MapLayer.SURFACE_BIOME;
    private int centerX;
    private int centerZ;
    private int selectedY;
    private int zoomIndex = 1;
    private int bx, by, bw, bh, mapX, mapY, mapW, mapH, controlX, controlW;
    private boolean initializedCenter;

    public ObservedMapScreen(WorldProbeController controller) {
        super(Component.literal("WorldSeedLab · 已探索真实地图"));
        this.controller = controller;
    }

    @Override
    protected void init() {
        bw = Math.min(1080, Math.max(240, width - 20));
        bh = Math.min(660, Math.max(230, height - 20));
        bx = (width - bw) / 2;
        by = (height - bh) / 2;
        int sidebar = bw >= 700 ? 180 : 118;
        mapX = bx + 16;
        mapY = by + 74;
        mapW = Math.max(100, bw - sidebar - 44);
        mapH = Math.max(75, bh - 140);
        controlX = mapX + mapW + 14;
        controlW = Math.max(78, bx + bw - 14 - controlX);

        // 只根据当前在线世界和世界纪元解析作用域，停止采集后仍能查看历史。
        access = controller.mapAccess(Minecraft.getInstance()).orElse(null);
        if (access != null && !initializedCenter) {
            centerX = access.x();
            centerZ = access.z();
            selectedY = access.y();
            initializedCenter = true;
        }

        addRenderableWidget(new MapBackground(0, 0, width, height, this.font));
        action(bx + bw - 91, by + 17, 72, 23, () -> "返回",
                this::onClose, false);

        if (access == null) return;

        int half = (controlW - 8) / 2;
        action(controlX, by + 89, controlW, 27,
                () -> "图层：" + layer.label(), () -> {
                    layer = layer.next();
                    request();
                }, true);
        action(controlX, by + 127, half, 24, () -> "Y -", () -> {
            selectedY = Math.max(access.minY(), selectedY - 16);
            request();
        }, false);
        action(controlX + half + 8, by + 127, half, 24, () -> "Y +", () -> {
            selectedY = Math.min(access.maxYExclusive() - 1, selectedY + 16);
            request();
        }, false);
        action(controlX, by + 161, half, 24, () -> "缩小", () -> {
            zoomIndex = Math.max(0, zoomIndex - 1);
            request();
        }, false);
        action(controlX + half + 8, by + 161, half, 24, () -> "放大", () -> {
            zoomIndex = Math.min(ZOOM.length - 1, zoomIndex + 1);
            request();
        }, false);

        // 使用按钮而非版本相关的 mouseScrolled/mouseDragged，兼容两套 Mojmap API。
        int arrowW = Math.min(44, controlW / 3);
        int middle = controlX + (controlW - arrowW) / 2;
        // 窄屏或较大 GUI 缩放时，按钮不能落到可用页面外。
        if (bh >= 380) {
            action(middle, by + 200, arrowW, 24, () -> "↑",
                    () -> pan(0, -1), false);
            action(controlX, by + 230, arrowW, 24, () -> "←",
                    () -> pan(-1, 0), false);
            action(controlX + controlW - arrowW, by + 230, arrowW, 24, () -> "→",
                    () -> pan(1, 0), false);
            action(middle, by + 260, arrowW, 24, () -> "↓",
                    () -> pan(0, 1), false);
        }
        if (bh >= 460) {
            action(controlX, by + 302, controlW, 25, () -> "回到玩家",
                    () -> {
                        centerX = access.x();
                        centerZ = access.z();
                        selectedY = access.y();
                        request();
                    }, false);
            action(controlX, by + 338, controlW, 25, () -> "刷新已保存地图",
                    this::request, false);
        }
        request();
    }

    private void pan(int directionX, int directionZ) {
        // 一次移动约 4 个区块，控制速度与可见范围而非随机跳跃。
        centerX += directionX * 64;
        centerZ += directionZ * 64;
        request();
    }

    private MapQuery query() {
        int perCell = ZOOM[zoomIndex];
        int cellsWide = Math.max(1, Math.min(84, (mapW - 8) / perCell));
        int cellsHigh = Math.max(1, Math.min(60, (mapH - 8) / perCell));
        return new MapQuery(centerX, centerZ, cellsWide, cellsHigh, layer, selectedY);
    }

    private void request() {
        if (access != null) loader.request(access.folder(), query());
    }

    private GlassAction action(int x, int y, int w, int h,
                               Supplier<String> label, Runnable callback, boolean primary) {
        GlassAction button = new GlassAction(x, y, Math.max(22, w), h,
                this.font, label, callback, primary);
        addRenderableWidget(button);
        return button;
    }

    @Override
    public void onClose() {
        loader.close();
        PlatformScreens.open(Minecraft.getInstance(), controller);
    }

    @Override
    public void removed() {
        loader.close();
    }

    private final class MapBackground extends AbstractWidget {
        private final Font uiFont;

        private MapBackground(int x, int y, int w, int h, Font font) {
            super(x, y, w, h, Component.empty());
            this.uiFont = font;
            this.active = false;
        }

        @Override
        protected void extractWidgetRenderState(GuiGraphicsExtractor g,
                                                int mouseX, int mouseY, float delta) {
            g.fill(0, 0, getWidth(), getHeight(), 0xC2050D1B);
            g.fillGradient(0, 0, getWidth(), getHeight(), 0x280D4664, 0x28101336);
            GlassUi.glass(g, bx, by, bw, bh, true);
            g.text(uiFont, "W / S / L   OBSERVED WORLD MAP", bx + 22, by + 27,
                    GlassUi.CYAN, true);
            g.text(uiFont, "真实观测 · 未探索区域不推测", bx + 22, by + 47,
                    GlassUi.MUTED, false);
            GlassUi.glass(g, mapX - 5, mapY - 5, mapW + 10, mapH + 10, false);
            g.fill(mapX, mapY, mapX + mapW, mapY + mapH, 0xEF0A182A);
            GlassUi.glass(g, controlX - 6, by + 76, controlW + 12,
                    Math.min(bh - 92, 350), false);

            if (access == null) {
                g.text(uiFont, "尚未进入可识别的游戏世界", mapX + 18, mapY + 32,
                        GlassUi.TEXT, false);
                g.text(uiFont, "请进入游戏后重试；无服务器后台 Seed 访问", mapX + 18,
                        mapY + 51, GlassUi.MUTED, false);
                return;
            }

            ObservedMapLoader.Result data = loader.result();
            MapView view = data.view();
            MapQuery requested = view == null ? query() : view.query();
            int cellSize = ZOOM[zoomIndex];
            int mapLeft = mapX + (mapW - requested.widthCells() * cellSize) / 2;
            int mapTop = mapY + (mapH - requested.heightCells() * cellSize) / 2;

            // 每四个 quart 格点就是一个实际区块；先画背景网格，未知格保持暗色。
            for (int cx = 0; cx <= requested.widthCells(); cx++) {
                if (Math.floorMod(requested.leftQuart() + cx, 4) != 0) continue;
                int x = mapLeft + cx * cellSize;
                g.fill(x, mapTop, x + 1, mapTop + requested.heightCells() * cellSize,
                        0x184C729A);
            }
            for (int cz = 0; cz <= requested.heightCells(); cz++) {
                if (Math.floorMod(requested.topQuart() + cz, 4) != 0) continue;
                int y = mapTop + cz * cellSize;
                g.fill(mapLeft, y, mapLeft + requested.widthCells() * cellSize, y + 1,
                        0x184C729A);
            }

            MapView.Cell hoveredCell = null;
            MapView.Marker hoveredMark = null;
            if (view != null) {
                for (MapView.Cell cell : view.cells()) {
                    int sx = mapLeft + (cell.quartX() - requested.leftQuart()) * cellSize;
                    int sy = mapTop + (cell.quartZ() - requested.topQuart()) * cellSize;
                    if (sx < mapLeft || sy < mapTop
                            || sx >= mapLeft + requested.widthCells() * cellSize
                            || sy >= mapTop + requested.heightCells() * cellSize) continue;
                    int color = cell.biomeId() != null
                            ? MapPalette.biome(cell.biomeId())
                            : MapPalette.elevation(cell.surfaceY());
                    g.fill(sx, sy, sx + cellSize, sy + cellSize, color);
                    if (mouseX >= sx && mouseX < sx + cellSize
                            && mouseY >= sy && mouseY < sy + cellSize) {
                        hoveredCell = cell;
                        g.fill(sx, sy, sx + cellSize, sy + 1, 0xFFEBFBFF);
                        g.fill(sx, sy + cellSize - 1, sx + cellSize, sy + cellSize,
                                0xFFEBFBFF);
                    }
                }
                for (MapView.Marker mark : view.markers()) {
                    int sx = mapLeft + (Math.floorDiv(mark.x(), 4)
                            - requested.leftQuart()) * cellSize + cellSize / 2;
                    int sy = mapTop + (Math.floorDiv(mark.z(), 4)
                            - requested.topQuart()) * cellSize + cellSize / 2;
                    g.fill(sx - 3, sy - 3, sx + 4, sy + 4, 0xFFF7CF6F);
                    g.fill(sx - 1, sy - 5, sx + 2, sy + 6, 0xFFFCECC2);
                    if (Math.abs(sx - mouseX) < 7 && Math.abs(sy - mouseY) < 7) {
                        hoveredMark = mark;
                    }
                }
            }

            g.text(uiFont, "地图模式", controlX + 10, by + 76, GlassUi.MUTED, false);
            if (bh >= 495) {
                g.text(uiFont, "Y = " + selectedY, controlX + 7, by + 379,
                        GlassUi.TEXT, true);
                g.text(uiFont, "每格 4 × 4 方块", controlX + 7, by + 400,
                        GlassUi.MUTED, false);
                g.text(uiFont, GlassUi.compact("自定义群系按 ID 固定配色",
                                Math.max(9, controlW / 7)),
                        controlX + 7, by + 419, GlassUi.MUTED, false);
            }

            String footer = data.message();
            if (view != null) {
                footer = view.message();
            }
            g.text(uiFont, GlassUi.compact(footer, Math.max(22, (bw - 37) / 6)),
                    bx + 21, by + bh - 35, GlassUi.MUTED, false);

            int quartX = requested.leftQuart() + Math.floorDiv(mouseX - mapLeft, cellSize);
            int quartZ = requested.topQuart() + Math.floorDiv(mouseY - mapTop, cellSize);
            String detail;
            if (mouseX >= mapLeft && mouseY >= mapTop
                    && mouseX < mapLeft + requested.widthCells() * cellSize
                    && mouseY < mapTop + requested.heightCells() * cellSize) {
                detail = "X " + (quartX * 4 + 2) + "  Z " + (quartZ * 4 + 2);
                if (hoveredCell != null) {
                    detail += "  |  实测 Y " + hoveredCell.observedY()
                            + (hoveredCell.biomeId() == null ? "  高度 " + hoveredCell.surfaceY()
                            : "  " + hoveredCell.biomeId());
                } else {
                    detail += "  |  未采样";
                }
                if (hoveredMark != null) {
                    detail += "  |  人工标记: " + hoveredMark.structureId();
                }
            } else {
                detail = "地图中心 X " + centerX + " / Z " + centerZ
                        + "   ·   " + layer.label();
            }
            g.text(uiFont, GlassUi.compact(detail, Math.max(18, (bw - 38) / 6)),
                    bx + 21, by + bh - 57, GlassUi.TEXT, false);
        }

        @Override
        protected void updateWidgetNarration(NarrationElementOutput output) {
            // 这是纯展示层；焦点、键盘操作与辅助旁白由 GlassAction 控制按钮提供。
        }
    }
}
