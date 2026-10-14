package io.github.fxjcangku.worldseedlab.client;

import io.github.fxjcangku.worldseedlab.client.ui.GlassDashboard;
import io.github.fxjcangku.worldseedlab.client.probe.AutoDesertFootprintController;
import io.github.fxjcangku.worldseedlab.client.ui.ObservedWorldMapScreen;
import io.github.fxjcangku.worldseedlab.client.probe.WorldProbeController;
import net.minecraft.client.Minecraft;

/** Minecraft 26.1.2 screen API adapter. */
public final class PlatformScreens {
    private static AutoDesertFootprintController automatic;

    private PlatformScreens() {}

    /** F8/聊天与地图返回都复用唯一的一键自动扫描状态机。 */
    public static void registerAutomatic(AutoDesertFootprintController controller) {
        if (controller == null) throw new IllegalArgumentException("自动取证控制器缺失");
        automatic = controller;
    }

    /** 旧版地图返回入口仍保留，不重复创建或重置自动控制器。 */
    public static void open(Minecraft minecraft, WorldProbeController controller) {
        if (automatic == null) throw new IllegalStateException("客户端自动取证尚未初始化");
        open(minecraft, controller, automatic);
    }

    public static void open(Minecraft minecraft, WorldProbeController controller,
                            AutoDesertFootprintController automatic) {
        minecraft.setScreen(new GlassDashboard(controller, automatic));
    }

    /** 26.1.2 原生 GUI 入口。 */
    public static void openMap(Minecraft client, WorldProbeController controller) {
        client.setScreen(new ObservedWorldMapScreen(controller));
    }
}
