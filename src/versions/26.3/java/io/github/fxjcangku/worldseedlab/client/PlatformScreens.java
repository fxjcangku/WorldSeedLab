package io.github.fxjcangku.worldseedlab.client;

import io.github.fxjcangku.worldseedlab.client.probe.AutoDesertFootprintController;
import io.github.fxjcangku.worldseedlab.client.probe.WorldProbeController;
import io.github.fxjcangku.worldseedlab.client.ui.GlassDashboard;
import io.github.fxjcangku.worldseedlab.client.ui.ObservedWorldMapScreen;
import net.minecraft.client.Minecraft;

/**
 * Minecraft 26.3 界面入口适配。
 *
 * <p>本版本官方客户端仍由 {@code Minecraft.gui} 的 {@code Gui.setScreen}
 * 切换界面；不调用 26.1.2 的 Minecraft.setScreen，也不自行接管渲染生命周期。
 */
public final class PlatformScreens {
    private static AutoDesertFootprintController automatic;

    private PlatformScreens() {}

    /** F8、聊天指令和地图返回共用唯一的自动取证控制器。 */
    public static void registerAutomatic(AutoDesertFootprintController controller) {
        if (controller == null) throw new IllegalArgumentException("自动取证控制器缺失");
        automatic = controller;
    }

    /** 返回面板时复用已注册实例，避免重建或重置自动取证状态。 */
    public static void open(Minecraft minecraft, WorldProbeController controller) {
        if (automatic == null) throw new IllegalStateException("客户端自动取证尚未初始化");
        open(minecraft, controller, automatic);
    }

    public static void open(Minecraft minecraft, WorldProbeController controller,
                            AutoDesertFootprintController automatic) {
        minecraft.gui.setScreen(new GlassDashboard(controller, automatic));
    }

    /** 通过 26.3 原生 GUI 宿主打开已探索地图。 */
    public static void openMap(Minecraft client, WorldProbeController controller) {
        client.gui.setScreen(new ObservedWorldMapScreen(controller));
    }
}
