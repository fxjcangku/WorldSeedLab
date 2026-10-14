package io.github.fxjcangku.worldseedlab.client;

import io.github.fxjcangku.worldseedlab.client.ui.GlassDashboard;
import io.github.fxjcangku.worldseedlab.client.probe.AutoDesertFootprintController;
import io.github.fxjcangku.worldseedlab.client.ui.ObservedWorldMapScreen;
import io.github.fxjcangku.worldseedlab.client.probe.WorldProbeController;
import net.minecraft.client.Minecraft;

/** Minecraft 26.1.2 screen API adapter. */
public final class PlatformScreens {
    private PlatformScreens() {}

    public static void open(Minecraft minecraft, WorldProbeController controller,
                            AutoDesertFootprintController automatic) {
        minecraft.setScreen(new GlassDashboard(controller, automatic));
    }

    /** 26.1.2 原生 GUI 入口。 */
    public static void openMap(Minecraft client, WorldProbeController controller) {
        client.setScreen(new ObservedWorldMapScreen(controller));
    }
}
