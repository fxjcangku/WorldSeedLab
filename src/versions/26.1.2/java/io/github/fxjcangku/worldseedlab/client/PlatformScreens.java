package io.github.fxjcangku.worldseedlab.client;

import io.github.fxjcangku.worldseedlab.client.ui.GlassDashboard;
import net.minecraft.client.Minecraft;

/** Minecraft 26.1.2 screen API adapter. */
public final class PlatformScreens {
    private PlatformScreens() {}

    public static void open(Minecraft minecraft, WorldProbeController controller) {
        minecraft.setScreen(new GlassDashboard(controller));
    }
}
