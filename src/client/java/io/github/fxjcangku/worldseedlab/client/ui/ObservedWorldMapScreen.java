package io.github.fxjcangku.worldseedlab.client.ui;

import io.github.fxjcangku.worldseedlab.client.PlatformScreens;
import io.github.fxjcangku.worldseedlab.client.probe.WorldProbeController;
import io.github.fxjcangku.worldseedlab.core.map.MapLayer;
import io.github.fxjcangku.worldseedlab.core.map.MapPalette;
import io.github.fxjcangku.worldseedlab.core.map.MapQuery;
import io.github.fxjcangku.worldseedlab.core.map.MapView;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/**
 * 已探索世界的真实观测地图。渲染仅使用已经落盘且由后台加载的不可变快照；
 * GUI 不从服务器请求区块，也不通过 Seed 预测尚未探索的地形。
 */
public final class ObservedWorldMapScreen extends Screen {
    private final WorldProbeController controller;
    private final ObservedMapLoader loader = new ObservedMapLoader();
    private WorldProbeController.MapAccess access;
    private MapLayer layer = MapLayer.SURFACE_BIOME;
    private int centerX, centerZ, altitudeY;
    private int zoom = 8;
    private int bx, by, bw, bh, mapX, mapY, mapW, mapH;
    private MapView indexedView;
    private Map<Long, MapView.Cell> visibleCells = Map.of();
    private List<Map.Entry<String, Long>> legend = List.of();

    public ObservedWorldMapScreen(WorldProbeController controller) {
        super(Component.literal("WorldSeedLab · 真实观测地图"));
        this.controller = controller;
    }

    @Override
    protected void init() {
        bw = Math.min(1040, Math.max(260, width - 20));
        bh = Math.min(670, Math.max(240, height - 20));
        bx = (width - bw) / 2;
        by = (height - bh) / 2;
        boolean wide = bw >= 730;
        mapX = bx + 17;
        mapY = by + 114;
        mapW = Math.max(80, bw - (wide ? 248 : 34));
        mapH = Math.max(55, bh - (wide ? 139 : 176));

        Minecraft client = Minecraft.getInstance();
        var scope = controller.mapAccess(client);
        if (scope.isPresent()) {
            var next = scope.get();
            if (access == null || !access.folder().equals(next.folder())
                    || !access.dimension().equals(next.dimension())) {
                access = next;
                centerX = access.x();
                centerZ = access.z();
                altitudeY = access.y();
            }
        } else {
            access = null;
        }

        // 背景首先注册，所有交互控件均在上层，保持键盘导航和朗读兼容。
        addRenderableWidget(new MapBackdrop(this, 0, 0, width, height, font));
        button(bx + 16, by + 43, wide ? 136 : 102, 26,
                () -> "图层: " + layer.label(), () -> { layer = layer.next(); reload(); }, true);
        button(bx + (wide ? 160 : 126), by + 43, 77, 26,
                () -> "高度 -16", () -> { altitudeY = clampAltitude(altitudeY - 16); reload(); }, false);
        button(bx + (wide ? 245 : 208), by + 43, 77, 26,
                () -> "高度 +16", () -> { altitudeY = clampAltitude(altitudeY + 16); reload(); }, false);
        if (wide) {
            button(bx + 333, by + 43, 74, 26, () -> "缩小", () -> changeZoom(-2), false);
            button(bx + 413, by + 43, 74, 26, () -> "放大", () -> changeZoom(2), false);
            button(bx + 494, by + 43, 72, 26, () -> "刷新", this::reload, false);
        }
        button(bx + bw - 86, by + 15, 70, 24, () -> "返回", this::back, false);

        int panY = by + 79;
        int buttonW = wide ? 92 : 68;
        int gap = wide ? 7 : 4;
        int startX = bx + 16;
        button(startX, panY, buttonW, 23, () -> "← 西", () -> move(-64, 0), false);
        button(startX + (buttonW + gap), panY, buttonW, 23,
                () -> "↑ 北", () -> move(0, -64), false);
        button(startX + (buttonW + gap) * 2, panY, buttonW, 23,
                () -> "↓ 南", () -> move(0, 64), false);
        button(startX + (buttonW + gap) * 3, panY, buttonW, 23,
                () -> "→ 东", () -> move(64, 0), false);
        if (wide) {
            button(startX + (buttonW + gap) * 4, panY, 104, 23,
                    () -> "回到玩家", this::recenter, false);
        }
        if (wide) {
            int sx = bx + bw - 212;
            button(sx + 10, by + 290, 89, 25,
                    () -> "缩小地图", () -> changeZoom(-2), false);
            button(sx + 106, by + 290, 88, 25,
                    () -> "放大地图", () -> changeZoom(2), false);
            button(sx + 10, by + 324, 184, 26, () -> "刷新本地证据", this::reload, false);
            button(sx + 10, by + 359, 184, 26, () -> "回到当前玩家", this::recenter, false);
        }
        if (!wide) {
            int bottom = by + bh - 48;
            button(bx + 18, bottom, 72, 24, () -> "缩小", () -> changeZoom(-2), false);
            button(bx + 97, bottom, 72, 24, () -> "放大", () -> changeZoom(2), false);
            button(bx + 176, bottom, 72, 24, () -> "刷新", this::reload, false);
        }
        reload();
    }

    private int clampAltitude(int y) {
        int min = access == null ? -4096 : access.minY();
        int max = access == null ? 8192 : access.maxYExclusive() - 1;
        return Math.max(min, Math.min(max, y));
    }

    private void move(int dx, int dz) {
        centerX += dx;
        centerZ += dz;
        reload();
    }

    private void changeZoom(int delta) {
        zoom = Math.max(5, Math.min(16, zoom + delta));
        reload();
    }

    private void recenter() {
        if (access == null) return;
        var client = Minecraft.getInstance();
        if (client.player != null) {
            centerX = client.player.blockPosition().getX();
            centerZ = client.player.blockPosition().getZ();
        } else {
            centerX = access.x();
            centerZ = access.z();
        }
        reload();
    }

    private void reload() {
        indexedView = null;
        visibleCells = Map.of();
        legend = List.of();
        if (access == null) return;
        int columns = Math.max(1, Math.min(128, mapW / zoom));
        int rows = Math.max(1, Math.min(96, mapH / zoom));
        loader.request(access.folder(), new MapQuery(
                centerX, centerZ, columns, rows, layer, clampAltitude(altitudeY)));
    }

    private void index(MapView view) {
        if (view == indexedView) return;
        Map<Long, MapView.Cell> next = new HashMap<>();
        Map<String, Long> frequencies = new HashMap<>();
        for (MapView.Cell cell : view.cells()) {
            next.put(pack(cell.quartX(), cell.quartZ()), cell);
            if (cell.biomeId() != null) {
                frequencies.merge(cell.biomeId(), 1L, Long::sum);
            }
        }
        visibleCells = Map.copyOf(next);
        legend = frequencies.entrySet().stream()
                .sorted(Map.Entry.<String, Long>comparingByValue(Comparator.reverseOrder()))
                .limit(7)
                .toList();
        indexedView = view;
    }

    private static long pack(int x, int z) {
        return ((long) x << 32) | (z & 0xffffffffL);
    }

    private void back() {
        loader.close();
        PlatformScreens.open(Minecraft.getInstance(), controller);
    }

    @Override
    public void onClose() {
        loader.close();
        super.onClose();
    }

    private void button(int x, int y, int w, int h, java.util.function.Supplier<String> label,
                        Runnable action, boolean primary) {
        addRenderableWidget(new GlassAction(x, y, w, h, font, label, action, primary));
    }

    /** 非交互背景：只绘制实际地图和当前状态，按钮负责一切交互。 */
    private static final class MapBackdrop extends AbstractWidget {
        private final ObservedWorldMapScreen owner;
        private final Font font;

        MapBackdrop(ObservedWorldMapScreen owner, int x, int y, int w, int h, Font font) {
            super(x, y, w, h, Component.empty());
            this.owner = owner;
            this.font = font;
            this.active = false;
        }

        @Override
        protected void extractWidgetRenderState(GuiGraphicsExtractor g,
                                                int mouseX, int mouseY, float delta) {
            var o = owner;
            g.fill(0, 0, getWidth(), getHeight(), 0xC6050E1B);
            g.fillGradient(0, 0, getWidth(), getHeight(), 0x70143853, 0x70201935);
            GlassUi.glass(g, o.bx, o.by, o.bw, o.bh, true);
            g.text(font, "WORLDSEEDLAB / OBSERVED WORLD MAP", o.bx + 18, o.by + 20,
                    GlassUi.CYAN, true);

            if (o.access == null) {
                g.text(font, "没有可识别的世界身份，请先进入世界后重试。",
                        o.bx + 21, o.by + 136, GlassUi.TEXT, false);
                return;
            }
            g.text(font, "当前维度: " + GlassUi.compact(o.access.dimension(), 47),
                    o.bx + 18, o.by + o.bh - 15, GlassUi.MUTED, false);
            GlassUi.glass(g, o.mapX - 5, o.mapY - 5, o.mapW + 10, o.mapH + 10, false);
            g.fill(o.mapX, o.mapY, o.mapX + o.mapW, o.mapY + o.mapH, 0xF4091625);

            ObservedMapLoader.Result load = o.loader.result();
            MapView view = load.view();
            if (view == null) {
                g.text(font, GlassUi.compact(load.message(), 80),
                        o.mapX + 22, o.mapY + 30, GlassUi.MUTED, false);
            } else {
                o.index(view);
                drawObservedCells(g, view);
                drawKnownMarkers(g, view);
                drawHover(g, mouseX, mouseY, view);
            }
            boolean wide = o.bw >= 730;
            if (wide) drawSidebar(g, view, load);
            else {
                g.text(font, GlassUi.compact(load.message(),
                                Math.max(15, o.bw / 8)),
                        o.mapX, o.by + o.bh - 64, GlassUi.MUTED, false);
            }
        }

        private int originX(MapQuery query) {
            return owner.mapX + (owner.mapW - query.widthCells() * owner.zoom) / 2;
        }
        private int originY(MapQuery query) {
            return owner.mapY + (owner.mapH - query.heightCells() * owner.zoom) / 2;
        }

        private void drawObservedCells(GuiGraphicsExtractor g, MapView view) {
            MapQuery q = view.query();
            int x0 = originX(q), y0 = originY(q);
            for (MapView.Cell sample : view.cells()) {
                int x = x0 + (sample.quartX() - q.leftQuart()) * owner.zoom;
                int y = y0 + (sample.quartZ() - q.topQuart()) * owner.zoom;
                int color = q.layer() == MapLayer.TERRAIN_HEIGHT
                        ? MapPalette.elevation(sample.surfaceY())
                        : MapPalette.biome(sample.biomeId());
                g.fill(x, y, x + owner.zoom, y + owner.zoom, color);
            }
            // 每四个 quart 格点画一条细分区块网格；不是尚未观测的地形。
            for (int x = 0; x <= q.widthCells(); x++) {
                if (Math.floorMod(q.leftQuart() + x, 4) != 0) continue;
                int px = x0 + x * owner.zoom;
                g.fill(px, y0, px + 1, y0 + q.heightCells() * owner.zoom, 0x3656B9DC);
            }
            for (int z = 0; z <= q.heightCells(); z++) {
                if (Math.floorMod(q.topQuart() + z, 4) != 0) continue;
                int py = y0 + z * owner.zoom;
                g.fill(x0, py, x0 + q.widthCells() * owner.zoom, py + 1, 0x3656B9DC);
            }
            int px = Math.floorDiv(owner.access.x(), 4) - q.leftQuart();
            int pz = Math.floorDiv(owner.access.z(), 4) - q.topQuart();
            if (px >= 0 && px < q.widthCells() && pz >= 0 && pz < q.heightCells()) {
                int x = x0 + px * owner.zoom + owner.zoom / 2;
                int y = y0 + pz * owner.zoom + owner.zoom / 2;
                g.fill(x - 2, y - 5, x + 2, y + 5, 0xFF0B1524);
                g.fill(x - 5, y - 2, x + 5, y + 2, 0xFFDAFAFF);
            }
        }

        private void drawKnownMarkers(GuiGraphicsExtractor g, MapView view) {
            MapQuery q = view.query();
            int x0 = originX(q), y0 = originY(q);
            for (MapView.Marker marker : view.markers()) {
                int x = x0 + (Math.floorDiv(marker.x(), 4) - q.leftQuart()) * owner.zoom
                        + owner.zoom / 2;
                int y = y0 + (Math.floorDiv(marker.z(), 4) - q.topQuart()) * owner.zoom
                        + owner.zoom / 2;
                g.fill(x - 4, y - 4, x + 5, y + 5, 0xFF132C40);
                g.fill(x - 2, y - 2, x + 3, y + 3, 0xFFFFCE7D);
            }
        }

        private void drawHover(GuiGraphicsExtractor g, int mx, int my, MapView view) {
            MapQuery q = view.query();
            int x0 = originX(q), y0 = originY(q);
            if (mx < x0 || my < y0 || mx >= x0 + q.widthCells() * owner.zoom
                    || my >= y0 + q.heightCells() * owner.zoom) return;
            int qx = q.leftQuart() + (mx - x0) / owner.zoom;
            int qz = q.topQuart() + (my - y0) / owner.zoom;
            MapView.Cell sample = owner.visibleCells.get(pack(qx, qz));
            String text;
            if (sample == null) {
                text = "X " + (qx * 4 + 2) + " · Z " + (qz * 4 + 2)
                        + " · 未知（尚无实际样本）";
            } else if (q.layer() == MapLayer.TERRAIN_HEIGHT) {
                text = "X " + (qx * 4 + 2) + " · Z " + (qz * 4 + 2)
                        + " · 地表高度 Y=" + sample.surfaceY();
            } else {
                text = "X " + (qx * 4 + 2) + " · Z " + (qz * 4 + 2)
                        + " · 样本 Y=" + sample.observedY()
                        + " · " + sample.biomeId();
            }
            int footY = owner.mapY + owner.mapH - 26;
            g.fill(owner.mapX + 8, footY - 3, owner.mapX + owner.mapW - 8,
                    footY + 15, 0xE90A1A2D);
            g.text(font, GlassUi.compact(text, Math.max(15, (owner.mapW - 28) / 6)),
                    owner.mapX + 14, footY + 1, GlassUi.TEXT, false);
        }

        private void drawSidebar(GuiGraphicsExtractor g, MapView view,
                                 ObservedMapLoader.Result load) {
            int sx = owner.bx + owner.bw - 212, sw = 194;
            GlassUi.glass(g, sx, owner.by + 113, sw, 167, false);
            g.text(font, "探索地图 / MAP", sx + 14, owner.by + 130, GlassUi.TEXT, true);
            g.text(font, "当前图层: " + owner.layer.label(), sx + 14,
                    owner.by + 155, GlassUi.CYAN, false);
            g.text(font, "地图中心 X: " + owner.centerX, sx + 14,
                    owner.by + 179, GlassUi.MUTED, false);
            g.text(font, "地图中心 Z: " + owner.centerZ, sx + 14,
                    owner.by + 197, GlassUi.MUTED, false);
            g.text(font, "高度层 Y: " + owner.altitudeY, sx + 14,
                    owner.by + 215, GlassUi.MUTED, false);
            g.text(font, "缩放: " + owner.zoom + " px / quart", sx + 14,
                    owner.by + 237, GlassUi.MUTED, false);
            g.text(font, "未采集 = 未知", sx + 14, owner.by + 259, GlassUi.CYAN, false);

            int legendY = owner.by + 399;
            GlassUi.glass(g, sx, legendY, sw, Math.max(80, owner.bh - 421), false);
            g.text(font, "当前视窗实际群系", sx + 12, legendY + 13,
                    GlassUi.TEXT, true);
            if (view == null) {
                g.text(font, GlassUi.compact(load.message(), 25), sx + 12,
                        legendY + 38, GlassUi.MUTED, false);
                return;
            }
            g.text(font, "已知格点 " + view.cells().size()
                    + " · 人工建筑 " + view.markers().size(), sx + 12,
                    legendY + 33, GlassUi.MUTED, false);
            int y = legendY + 54;
            for (Map.Entry<String, Long> item : owner.legend) {
                if (y >= owner.by + owner.bh - 40) break;
                g.fill(sx + 13, y + 2, sx + 21, y + 10, MapPalette.biome(item.getKey()));
                g.text(font, GlassUi.compact(item.getKey(), 21),
                        sx + 27, y + 1, GlassUi.TEXT, false);
                y += 18;
            }
        }

        @Override
        protected void updateWidgetNarration(NarrationElementOutput narration) {
            // 图形只是信息展示；交互按钮已有键盘与辅助功能朗读。
        }
    }
}
