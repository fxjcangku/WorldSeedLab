package io.github.fxjcangku.worldseedlab.client.ui;

import io.github.fxjcangku.worldseedlab.client.probe.AutoDesertFootprintController;
import io.github.fxjcangku.worldseedlab.client.probe.WorldProbeController;
import io.github.fxjcangku.worldseedlab.core.map.MapLayer;
import io.github.fxjcangku.worldseedlab.core.map.MapPalette;
import io.github.fxjcangku.worldseedlab.core.map.MapQuery;
import io.github.fxjcangku.worldseedlab.core.map.MapView;
import io.github.fxjcangku.worldseedlab.core.map.ObservedMapLoader;
import io.github.fxjcangku.worldseedlab.core.probe.ProbeSettings;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

/**
 * 透明玻璃工作台：顶部导航、主内容和独立说明栏共用同一投影。
 * 页面切换不重建采集控制器；地图只消费后台产生的不可变视窗，不读原始证据日志。
 */
public final class GlassDashboard extends Screen {
    private enum Page {
        MAP("已探索地图", "浏览你实际走过的世界"),
        EVIDENCE("取证概览", "观察与保存进度"),
        SETTINGS("采集设置", "调整本机采样方式"),
        RESEARCH("种子研究", "查看证据要求与研究条件");
        final String title, subtitle;
        Page(String title, String subtitle) { this.title = title; this.subtitle = subtitle; }
    }

    private final WorldProbeController controller;
    private final AutoDesertFootprintController automatic;
    private final List<GlassAction> settingsActions = new ArrayList<>();
    private PanelViewport viewport;
    private Page page = Page.MAP;
    private ObservedMapLoader loader;
    private WorldProbeController.MapAccess access;
    private Object openedLevel;
    private MapLayer layer = MapLayer.SURFACE_COLOR;
    private int centerX, centerZ, selectedY, zoom = 8;
    private int mainW, helpX, cx, cw, mapX, mapY, mapW, mapH;
    private long nextRefresh, observedRevision = -1;
    private String hoveredDetail = "拖动平移 · 滚轮缩放";

    public GlassDashboard(WorldProbeController controller, AutoDesertFootprintController automatic) {
        super(Component.literal("WorldSeedLab · 世界研究工作台"));
        this.controller = controller;
        this.automatic = automatic;
    }

    @Override protected void init() {
        viewport = PanelViewport.fit(width, height);
        mainW = viewport.width() - 214;
        helpX = mainW + 12;
        cx = 22;
        cw = mainW - 44;
        settingsActions.clear();
        if (loader == null) loader = new ObservedMapLoader();
        Minecraft client = Minecraft.getInstance();
        var next = controller.mapAccess(client).orElse(null);
        if (next != null && (access == null || !access.folder().equals(next.folder()))) {
            centerX = next.x(); centerZ = next.z(); selectedY = next.y();
        }
        access = next;
        selectedY = clampAltitude(selectedY);
        openedLevel = client.level;
        addRenderableWidget(new Backdrop());
        button(helpX + 154, 18, 30, 30, () -> "×", this::onClose, false);
        button(mainW - 174, 24, 152, 32,
                () -> automatic.running() ? "停止联合取证" : "开始联合取证", this::toggleAutomatic, true);
        int tabW = Math.min(148, cw / 4);
        int index = 0;
        for (Page item : Page.values()) {
            button(cx + index++ * tabW, 82, tabW, 36, () -> item.title,
                    () -> changePage(item), item == page).asTab();
        }
        button(helpX + 18, viewport.height() - 52, 166, 32,
                () -> "导出研究报告", controller::exportReport, false);
        switch (page) {
            case MAP -> createMap();
            case EVIDENCE -> createEvidence();
            case SETTINGS -> createSettings();
            case RESEARCH -> button(cx, viewport.height() - 52, 150, 32,
                    () -> "查看取证概览", () -> changePage(Page.EVIDENCE), false);
        }
        addRenderableWidget(new StatusHint(helpX + 18, viewport.height() - 116, 166, 58,
                () -> controller.status() + "；待保存 " + pendingWrites()));
        updateSettingsAvailability();
    }

    /** 原生背景只模糊一次，保留真实场景；不叠加默认菜单纹理和深色全屏遮罩。 */
    @Override public void extractBackground(GuiGraphicsExtractor g, int mx, int my, float delta) {
        if (Minecraft.getInstance().level == null) extractPanorama(g, delta);
        extractBlurredBackground(g);
    }

    private void changePage(Page target) {
        if (target == page) return;
        page = target;
        rebuild();
    }

    private void rebuild() { clearWidgets(); init(); }

    private void toggleAutomatic() {
        if (automatic.running()) automatic.stop("用户通过面板停止联合取证，已接受文件继续落盘");
        else automatic.start(Minecraft.getInstance());
        updateSettingsAvailability();
    }

    private void createMap() {
        int layerW = (cw - 18) / 4, index = 0;
        for (MapLayer candidate : MapLayer.values()) {
            button(cx + index++ * (layerW + 6), 134, layerW, 28, candidate::label,
                    () -> { layer = candidate; rebuild(); }, layer == candidate);
        }
        mapX = cx; mapY = 174; mapW = cw; mapH = viewport.height() - 258;
        addRenderableWidget(new MapCanvas());
        int y = viewport.height() - 48;
        button(cx, y, 88, 28, () -> "回到玩家", this::recenter, false);
        button(cx + 98, y, 88, 28, () -> "刷新地图", this::requestMap, false);
        button(cx + 210, y, 32, 28, () -> "−", () -> zoom(-1), false);
        button(cx + 340, y, 32, 28, () -> "+", () -> zoom(1), false);
        button(cx + 386, y, 72, 28, () -> "重置", () -> {
            zoom = 8; selectedY = clampAltitude(access == null ? 64 : access.y()); recenter();
        }, false);
        if (layer == MapLayer.ALTITUDE_BIOME) {
            button(helpX + 18, 356, 32, 28, () -> "−", () -> altitude(-16), false);
            button(helpX + 152, 356, 32, 28, () -> "+", () -> altitude(16), false);
        }
        addRenderableWidget(new StatusHint(helpX + 18, 218, 166, 130,
                () -> access == null ? "请先进入世界，再查看已保存地图。" : loader.result().message()));
        requestMap();
    }

    private void createEvidence() {
        button(cx, viewport.height() - 52, 178, 32,
                () -> controller.active() ? "停止基础采集" : "仅采集群系与地形", () -> {
                    if (automatic.running()) automatic.stop("用户通过概览停止联合采集");
                    else if (controller.active()) controller.stop("用户通过概览停止基础采集");
                    else controller.start(Minecraft.getInstance());
                }, false);
        button(cx + 192, viewport.height() - 52, 178, 32,
                () -> controller.structureScanEnabled() ? "结构材料侦测：开" : "结构材料侦测：关",
                () -> controller.setStructureScanning(!controller.structureScanEnabled()), false);
    }

    private void createSettings() {
        int controls = cx + cw - 234;
        for (int row = 0; row < 3; row++) {
            int field = row, y = 147 + row * 74;
            settingsActions.add(button(controls, y, 30, 30, () -> "−", () -> adjustSetting(field, -1), false));
            settingsActions.add(button(controls + 88, y, 30, 30, () -> "+", () -> adjustSetting(field, 1), false));
            settingsActions.add(button(controls + 178, y, 56, 30, () -> "重置", () -> resetSetting(field), false));
        }
        settingsActions.add(button(controls, 369, 160, 30,
                () -> controller.settings().detailMode().chineseLabel(),
                () -> updateSettings(controller.settings().nextDetailMode()), false));
        settingsActions.add(button(controls + 178, 369, 56, 30, () -> "重置", () -> {
            var s = controller.settings();
            updateSettings(new ProbeSettings(s.radiusChunks(), s.verticalStride(), s.samplesPerTick(), ProbeSettings.DEFAULT.detailMode()));
        }, false));
        settingsActions.add(button(cx, viewport.height() - 52, 172, 32,
                () -> "恢复全部采集设置", () -> updateSettings(ProbeSettings.DEFAULT), false));
    }

    /** 数字变化有界，单位和重置控件始终位于加减按钮命中区域之外。 */
    private void adjustSetting(int field, int direction) {
        var s = controller.settings();
        updateSettings(switch (field) {
            case 0 -> s.withRadius(Math.clamp(s.radiusChunks() + direction, 1, 6));
            case 1 -> new ProbeSettings(s.radiusChunks(), Math.clamp(direction > 0 ? s.verticalStride() * 2 : s.verticalStride() / 2, 4, 16), s.samplesPerTick(), s.detailMode());
            default -> new ProbeSettings(s.radiusChunks(), s.verticalStride(), Math.clamp(s.samplesPerTick() + direction * 32, 32, 256), s.detailMode());
        });
    }

    private void resetSetting(int field) {
        var s = controller.settings(); var d = ProbeSettings.DEFAULT;
        updateSettings(new ProbeSettings(field == 0 ? d.radiusChunks() : s.radiusChunks(),
                field == 1 ? d.verticalStride() : s.verticalStride(),
                field == 2 ? d.samplesPerTick() : s.samplesPerTick(), s.detailMode()));
    }

    private void updateSettings(ProbeSettings next) { controller.changeSettings(next); updateSettingsAvailability(); }
    private int pendingWrites() { return controller.pendingWrites() + automatic.pendingFiles(); }
    private void updateSettingsAvailability() {
        for (GlassAction action : settingsActions)
            action.active = !controller.active() && !automatic.running() && pendingWrites() == 0;
    }
    private GlassAction button(int x, int y, int w, int h, Supplier<String> title, Runnable callback, boolean primary) {
        return addRenderableWidget(new GlassAction(viewport.screenX(x), viewport.screenY(y),
                w, h, font, title, callback, primary, viewport.scale()));
    }
    private MapQuery mapQuery() {
        return new MapQuery(centerX, centerZ, Math.max(1, Math.min(128, mapW / zoom)),
                Math.max(1, Math.min(96, mapH / zoom)), layer, clampAltitude(selectedY), controller.gameVersion());
    }
    /** 飞行高度越界时限制查询高度，不能让打开面板失败。 */
    private int clampAltitude(int y) {
        return Math.clamp(y, access == null ? -4096 : Math.max(-4096, access.minY()),
                access == null ? 8192 : Math.min(8192, access.maxYExclusive() - 1));
    }
    private void requestMap() {
        if (access != null && loader != null && page == Page.MAP) loader.request(access.folder(), mapQuery());
        nextRefresh = System.nanoTime() + 2_000_000_000L;
    }
    private void pan(int x, int z) {
        centerX = (int) Math.clamp((long) centerX + x, -30_000_000, 30_000_000);
        centerZ = (int) Math.clamp((long) centerZ + z, -30_000_000, 30_000_000);
        requestMap();
    }
    private void zoom(int delta) { zoom = Math.clamp(zoom + delta * 4, 8, 32); requestMap(); }
    private void altitude(int delta) { if (access != null) { selectedY = clampAltitude(selectedY + delta); requestMap(); } }
    private void recenter() {
        if (access == null) return;
        var client = Minecraft.getInstance();
        centerX = client.level == openedLevel && client.player != null ? client.player.blockPosition().getX() : access.x();
        centerZ = client.level == openedLevel && client.player != null ? client.player.blockPosition().getZ() : access.z();
        requestMap();
    }

    @Override public void tick() {
        updateSettingsAvailability();
        if (Minecraft.getInstance().level != openedLevel) {
            loader.close(); loader = null; access = null; rebuild(); return;
        }
        long revision = controller.writtenSamples() + controller.terrainChunksWritten()
                + controller.writtenManualMarks() + controller.surfaceChunksWritten();
        if (page == Page.MAP && access != null && revision != observedRevision
                && System.nanoTime() >= nextRefresh && !loader.result().loading()) {
            observedRevision = revision; requestMap();
        }
    }
    @Override public boolean isPauseScreen() { return false; }
    @Override public void removed() { if (loader != null) loader.close(); loader = null; }

    private void line(GuiGraphicsExtractor g, String text, int x, int y, int w, int color) {
        GlassUi.text(g, font, text, x, y, w, color);
    }
    private int paragraph(GuiGraphicsExtractor g, String text, int x, int y, int w, int color, int maxLines) {
        return GlassUi.wrap(g, font, text, x, y, w, color, maxLines);
    }

    /** 所有正文与原生控件共用排版尺寸；玻璃底仅绘制一次，不靠多层黑底压暗。 */
    private final class Backdrop extends AbstractWidget {
        Backdrop() { super(0, 0, GlassDashboard.this.width, GlassDashboard.this.height, Component.empty()); active = false; }
        @Override protected void extractWidgetRenderState(GuiGraphicsExtractor g, int mx, int my, float delta) {
            g.pose().pushMatrix();
            try {
                g.pose().translate(viewport.x(), viewport.y()); g.pose().scale(viewport.scale(), viewport.scale());
                GlassUi.glass(g, 0, 0, mainW, viewport.height(), true);
                GlassUi.glass(g, helpX, 0, 202, viewport.height(), true);
                GlassUi.title(g, font, "WorldSeedLab", cx, 21, GlassUi.TEXT);
                line(g, page.subtitle, cx, 50, mainW - 220, GlassUi.MUTED);
                g.fill(cx, 71, mainW - 22, 72, GlassUi.LINE);
                g.fill(cx, 119, mainW - 22, 120, GlassUi.LINE);
                drawHelp(g);
                switch (page) {
                    case MAP -> {
                        line(g, hoveredDetail, cx, viewport.height() - 74, cw, GlassUi.MUTED);
                        line(g, zoom / 4 + " 像素/格", cx + 253, viewport.height() - 42, 78, GlassUi.TEXT);
                        line(g, "北 ↑", mainW - 66, viewport.height() - 42, 44, GlassUi.MUTED);
                    }
                    case EVIDENCE -> drawEvidence(g);
                    case SETTINGS -> drawSettings(g);
                    case RESEARCH -> drawResearch(g);
                }
            } finally { g.pose().popMatrix(); }
        }

        private void drawHelp(GuiGraphicsExtractor g) {
            int x = helpX + 18, w = 166;
            line(g, "使用说明", x, 25, 120, GlassUi.TEXT);
            g.fill(x, 61, x + w, 62, GlassUi.LINE);
            line(g, page.title, x, 80, w, GlassUi.CYAN);
            switch (page) {
                case MAP -> {
                    paragraph(g, layer == MapLayer.SURFACE_COLOR ? "逐方块保存地表材质颜色。拖动地图平移，滚轮放大或缩小。"
                            : layer == MapLayer.ALTITUDE_BIOME ? "查看指定高度的真实群系样本。缺少该高度证据时保持空白。"
                            : layer == MapLayer.TERRAIN_HEIGHT ? "用颜色显示已保存的地表高度。稀疏采样不会补齐未知地形。"
                            : "按四方块群系采样网格显示。群系边界不等同于地表方块边界。", x, 110, w, GlassUi.MUTED, 5);
                    var load = loader.result();
                    int y = 218;
                    line(g, "地图状态", x, y, w, GlassUi.TEXT);
                    paragraph(g, access == null ? "请先进入世界，再查看已保存地图。" : load.message(), x, y + 24, w,
                            load.view() != null && !load.view().incomplete()
                                    && (!load.view().cells().isEmpty() || !load.view().surfaceCells().isEmpty())
                                    ? GlassUi.MINT : GlassUi.AMBER, 5);
                    if (layer == MapLayer.ALTITUDE_BIOME) line(g, "Y  " + selectedY, x + 44, 362, 82, GlassUi.TEXT);
                }
                case SETTINGS -> {
                    paragraph(g, "设置立即保存到本机。采集过程中参数锁定，停止后可以调整。", x, 110, w, GlassUi.MUTED, 5);
                    line(g, "详细取证", x, 226, w, GlassUi.TEXT);
                    paragraph(g, "联合取证期间临时启用地下详情，结束后恢复原档位。恢复设置不会启动采集。", x, 252, w, GlassUi.MUTED, 6);
                }
                case EVIDENCE -> {
                    paragraph(g, "观察到的数据进入保存队列后，仍需等待写盘完成。此处分别显示两种状态。", x, 110, w, GlassUi.MUTED, 6);
                    paragraph(g, "材料候选只是线索。完整建筑轮廓也需要独立验证天然来源。", x, 250, w, GlassUi.AMBER, 5);
                }
                case RESEARCH -> {
                    paragraph(g, "服务器生成器和 Salt 目前未知。可研究符合证据的条件模型，但不能直接认定为真实种子。", x, 110, w, GlassUi.MUTED, 7);
                    paragraph(g, "离线工具：recover_seed.py。面板不自动启动求解进程。", x, 270, w, GlassUi.MUTED, 5);
                }
            }
            int bottom = viewport.height() - 128;
            g.fill(x, bottom, x + w, bottom + 1, GlassUi.LINE);
            GlassUi.badge(g, x, bottom + 21, 6, 6, controller.active() ? GlassUi.MINT : GlassUi.MUTED);
            line(g, controller.active() ? "正在采集" : "采集已关闭", x + 16, bottom + 16, w - 16, GlassUi.TEXT);
            line(g, "待保存 " + pendingWrites(), x, bottom + 43, w, GlassUi.MUTED);
        }

        private void metric(GuiGraphicsExtractor g, int x, int y, int w, String label, long number, int color) {
            GlassUi.glass(g, x, y, w, 82, false);
            line(g, label, x + 14, y + 14, w - 28, GlassUi.MUTED);
            GlassUi.title(g, font, String.valueOf(number), x + 14, y + 43, color);
        }
        private void drawEvidence(GuiGraphicsExtractor g) {
            int w = (cw - 20) / 3;
            metric(g, cx, 136, w, "地表地图区块", controller.surfaceChunksWritten(), GlassUi.CYAN);
            metric(g, cx + w + 10, 136, w, "材料候选组", controller.structureCandidateGroups(), GlassUi.AMBER);
            metric(g, cx + (w + 10) * 2, 136, w, "已保存完整轮廓", automatic.persistedFootprints(), GlassUi.MINT);
            line(g, "本次观察与保存", cx, 242, cw, GlassUi.TEXT);
            line(g, "群系样本    已观察 " + controller.observedSamples() + "    已写盘 " + controller.writtenSamples(), cx, 270, cw, GlassUi.MUTED);
            line(g, "地形区块 " + controller.terrainChunksWritten() + "    地下详情 " + controller.detailChunksWritten()
                    + "    材料记录 " + controller.structureCandidateRecordsWritten(), cx, 296, cw, GlassUi.MUTED);
            g.fill(cx, 329, cx + cw, 330, GlassUi.LINE);
            line(g, "已观察群系", cx, 347, cw, GlassUi.TEXT);
            int y = 377;
            var entries = controller.topBiomes(Math.max(1, (viewport.height() - 448) / 25));
            if (entries.isEmpty()) line(g, "本次会话尚无样本；历史地图仍可查看。", cx, y, cw, GlassUi.MUTED);
            for (var entry : entries) {
                GlassUi.rounded(g, cx, y + 3, 9, 9, 2, MapPalette.biome(entry.getKey()));
                line(g, entry.getKey(), cx + 20, y, cw - 100, GlassUi.TEXT);
                line(g, String.valueOf(entry.getValue()), cx + cw - 72, y, 72, GlassUi.MUTED);
                y += 25;
            }
        }
        private void settingRow(GuiGraphicsExtractor g, int y, String title, String note) {
            line(g, title, cx + 8, y + 8, cw - 260, GlassUi.TEXT);
            line(g, note, cx + 8, y + 34, cw - 260, GlassUi.MUTED);
            g.fill(cx + 8, y + 64, cx + cw - 8, y + 65, GlassUi.LINE);
        }
        private void drawSettings(GuiGraphicsExtractor g) {
            settingRow(g, 134, "采集半径", "只检查当前已经加载的区块");
            settingRow(g, 208, "垂直采样间距", "间距更小，群系证据更密集");
            settingRow(g, 282, "每刻采样预算", "限制每刻附加读取次数");
            settingRow(g, 356, "地形详情档位", "点击切换表层与地下取证");
            int controls = cx + cw - 234;
            int[] values = {controller.settings().radiusChunks(), controller.settings().verticalStride(), controller.settings().samplesPerTick()};
            String[] units = {"区块", "方块", "样本"};
            for (int row = 0; row < 3; row++) {
                String value = String.valueOf(values[row]);
                line(g, value, controls + 35 + (48 - GlassUi.width(font, value)) / 2, 154 + row * 74, 48, GlassUi.TEXT);
                line(g, units[row], controls + 132, 154 + row * 74, 40, GlassUi.MUTED);
            }
            line(g, controller.active() ? "采集期间参数已锁定" : "设置仅保存到本机", cx + 190, viewport.height() - 43, cw - 190, GlassUi.MUTED);
        }
        private void drawResearch(GuiGraphicsExtractor g) {
            line(g, "生成器身份尚未确认", cx, 140, cw, GlassUi.AMBER);
            researchStep(g, 184, "01   结构证据", "需要完整轮廓，以及不同区域的拟合和留出证据。材料簇不能直接作为天然结构起点。");
            researchStep(g, 274, "02   结构低48位", "已知 Salt 时研究种子低48位。未知 Salt 时，只研究种子与 Salt 合成的有效状态，不能据此枚举高16位。");
            researchStep(g, 364, "03   完整世界种子", "需要可验证的生成器前向模型和独立群系证据。未经验证的模型匹配仍然属于条件候选。");
        }
        private void researchStep(GuiGraphicsExtractor g, int y, String title, String description) {
            line(g, title, cx, y, cw, GlassUi.CYAN);
            paragraph(g, description, cx + 30, y + 28, cw - 30, GlassUi.TEXT, 2);
        }
        @Override protected void updateWidgetNarration(NarrationElementOutput output) { }
    }

    /** 长状态在栏内明确省略；悬停可查看完整原文，不隐藏地图或采集失败原因。 */
    private final class StatusHint extends AbstractWidget {
        private final Supplier<String> value;
        private String last = "";
        StatusHint(int x, int y, int w, int h, Supplier<String> value) {
            super(viewport.screenX(x), viewport.screenY(y), Math.round(w * viewport.scale()),
                    Math.round(h * viewport.scale()), Component.literal("查看完整状态"));
            this.value = value;
        }
        @Override protected void extractWidgetRenderState(GuiGraphicsExtractor g, int mx, int my, float delta) {
            String next = value.get();
            if (!next.equals(last)) {
                last = next;
                setMessage(Component.literal(next));
                // 原生Tooltip自己使用9像素行距，所以保持原生字体，避免长中文行互相覆盖。
                setTooltip(Tooltip.create(Component.literal(next)));
            }
        }
        @Override protected void updateWidgetNarration(NarrationElementOutput output) { defaultButtonNarrationText(output); }
    }

    /** 鼠标输入只改变视窗，任何磁盘查询都提交给后台加载器。 */
    private final class MapCanvas extends AbstractWidget {
        private double dragX, dragZ;
        MapCanvas() {
            super(viewport.screenX(mapX), viewport.screenY(mapY), Math.round(mapW * viewport.scale()),
                    Math.round(mapH * viewport.scale()), Component.literal("已探索地图，拖动平移，滚轮缩放"));
        }
        @Override protected void onDrag(MouseButtonEvent event, double dx, double dy) {
            dragX -= dx / viewport.scale() * 4 / zoom; dragZ -= dy / viewport.scale() * 4 / zoom;
            int moveX = (int) dragX, moveZ = (int) dragZ;
            if (moveX != 0 || moveZ != 0) { dragX -= moveX; dragZ -= moveZ; pan(moveX, moveZ); }
        }
        @Override public boolean mouseScrolled(double x, double y, double horizontal, double vertical) {
            if (!isMouseOver(x, y) || vertical == 0) return false;
            zoom(vertical > 0 ? 1 : -1); return true;
        }
        @Override protected void extractWidgetRenderState(GuiGraphicsExtractor g, int mx, int my, float delta) {
            g.pose().pushMatrix();
            try {
                g.pose().translate(viewport.x(), viewport.y()); g.pose().scale(viewport.scale(), viewport.scale());
                drawMap(g, viewport.logicalX(mx), viewport.logicalY(my));
            } finally { g.pose().popMatrix(); }
        }
        private void drawMap(GuiGraphicsExtractor g, int mx, int my) {
            GlassUi.glass(g, mapX, mapY, mapW, mapH, false);
            MapQuery q = mapQuery();
            int left = mapX + (mapW - q.widthCells() * zoom) / 2;
            int top = mapY + (mapH - q.heightCells() * zoom) / 2;
            int blockSize = zoom / 4;
            g.enableScissor(mapX + 1, mapY + 1, mapX + mapW - 1, mapY + mapH - 1);
            try {
                g.fill(mapX + 1, mapY + 1, mapX + mapW - 1, mapY + mapH - 1, 0x77161C28);
                MapView view = loader.result().view();
                String detail = null;
                if (view != null) {
                    for (MapView.Cell cell : view.cells()) {
                        int x = left + (cell.quartX() - q.leftQuart()) * zoom;
                        int y = top + (cell.quartZ() - q.topQuart()) * zoom;
                        int color = cell.biomeId() == null ? MapPalette.elevation(cell.surfaceY()) : MapPalette.biome(cell.biomeId());
                        g.fill(x, y, x + zoom, y + zoom, color);
                        if (mx >= x && mx < x + zoom && my >= y && my < y + zoom)
                            detail = "实测 Y " + cell.observedY() + (cell.biomeId() == null ? " · 地表高度 " + cell.surfaceY() : " · " + cell.biomeId());
                    }
                    for (MapView.SurfaceRun run : view.surfaceRuns()) {
                        int x = left + (run.blockX() - q.leftQuart() * 4) * blockSize;
                        int y = top + (run.blockZ() - q.topQuart() * 4) * blockSize;
                        g.fill(x, y, x + run.length() * blockSize, y + blockSize, run.argb());
                    }
                    int hoveredX = q.leftQuart() * 4 + Math.floorDiv(mx - left, blockSize);
                    int hoveredZ = q.topQuart() * 4 + Math.floorDiv(my - top, blockSize);
                    if (isMouseOver(viewport.screenX(mx), viewport.screenY(my)))
                        for (MapView.SurfaceCell cell : view.surfaceCells()) {
                            if (cell.blockX() == hoveredX && cell.blockZ() == hoveredZ) {
                                detail = "实测地表 Y " + cell.observedY(); break;
                            }
                        }
                    for (MapView.Marker marker : view.markers()) {
                        int x = left + (marker.x() - q.leftQuart() * 4) * blockSize;
                        int y = top + (marker.z() - q.topQuart() * 4) * blockSize;
                        GlassUi.rounded(g, x - 3, y - 3, 7, 7, 3, GlassUi.AMBER);
                    }
                }
                // 地表不叠加格线，避免把连续地形重新切成大马赛克；网格仅用于稀疏证据图层。
                if (layer != MapLayer.SURFACE_COLOR) {
                    for (int x = 0; x <= q.widthCells(); x++) if (Math.floorMod(q.leftQuart() + x, 4) == 0)
                        g.fill(left + x * zoom, top, left + x * zoom + 1, top + q.heightCells() * zoom, 0x185C6580);
                    for (int z = 0; z <= q.heightCells(); z++) if (Math.floorMod(q.topQuart() + z, 4) == 0)
                        g.fill(left, top + z * zoom, left + q.widthCells() * zoom, top + z * zoom + 1, 0x185C6580);
                }
                if (access != null) {
                    var player = Minecraft.getInstance().player;
                    int px = player == null ? access.x() : player.blockPosition().getX();
                    int pz = player == null ? access.z() : player.blockPosition().getZ();
                    int x = left + (px - q.leftQuart() * 4) * blockSize + blockSize / 2;
                    int y = top + (pz - q.topQuart() * 4) * blockSize + blockSize / 2;
                    GlassUi.rounded(g, x - 5, y - 5, 11, 11, 5, 0xDC202737);
                    g.fill(x - 3, y, x + 4, y + 1, GlassUi.TEXT);
                    g.fill(x, y - 3, x + 1, y + 4, GlassUi.TEXT);
                }
                if (view == null || view.cells().isEmpty() && view.surfaceCells().isEmpty()) drawEmpty(g);
                hoveredDetail = "中心 X " + centerX + " / Z " + centerZ + " · 拖动平移，滚轮缩放";
                if (mx >= mapX && mx < mapX + mapW && my >= mapY && my < mapY + mapH) {
                    int x = q.leftQuart() * 4 + Math.floorDiv(mx - left, blockSize);
                    int z = q.topQuart() * 4 + Math.floorDiv(my - top, blockSize);
                    hoveredDetail = "X " + x + " / Z " + z + " · " + (detail == null ? "尚无实际样本" : detail);
                }
            } finally { g.disableScissor(); }
        }
        private void drawEmpty(GuiGraphicsExtractor g) {
            String title = access == null ? "尚未进入世界" : loader.result().loading() ? "正在读取本地地图" : "此处尚无已保存地图";
            String help = access == null ? "进入世界后可以直接查看历史地图。"
                    : loader.result().loading() ? "后台正在读取当前视窗的地图瓦片。"
                    : layer == MapLayer.SURFACE_COLOR ? "开启一次采集以记录逐方块地表。已有群系资料可切换上方图层查看。"
                    : "回到玩家，或开启采集后再查看。";
            int w = Math.min(400, mapW - 40), x = mapX + (mapW - w) / 2, y = mapY + mapH / 2 - 49;
            GlassUi.rounded(g, x, y, w, 98, 9, 0xAF262C3C);
            GlassUi.outline(g, x, y, w, 98, 9, GlassUi.LINE);
            line(g, title, x + 18, y + 18, w - 36, GlassUi.TEXT);
            paragraph(g, help, x + 18, y + 47, w - 36, GlassUi.MUTED, 2);
        }
        @Override protected void updateWidgetNarration(NarrationElementOutput output) { defaultButtonNarrationText(output); }
    }
}
