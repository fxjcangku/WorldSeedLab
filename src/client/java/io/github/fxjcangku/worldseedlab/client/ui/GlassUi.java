package io.github.fxjcangku.worldseedlab.client.ui;

import net.minecraft.client.gui.GuiGraphicsExtractor;

/**
 * Lightweight liquid-glass approximation based solely on standard GUI shapes:
 * no raw OpenGL, blur shaders, custom pipelines, texture packs or framebuffers.
 */
public final class GlassUi {
    public static final int TEXT = 0xFFEAF6FF;
    public static final int MUTED = 0xFFA0BBD0;
    public static final int CYAN = 0xFF7EE5FF;
    public static final int MINT = 0xFF6DE9C6;

    private GlassUi() {}

    public static void glass(GuiGraphicsExtractor g, int x, int y, int w, int h, boolean vivid) {
        if (w < 12 || h < 12) return;
        int top = vivid ? 0xDF25384C : 0xCC1D2B40;
        int bottom = vivid ? 0xDB0A1629 : 0xCC0B1424;
        // 5px rounded-corner illusion, inexpensive even with Vulkan.
        g.fillGradient(x + 5, y, x + w - 5, y + h, top, bottom);
        g.fillGradient(x + 2, y + 2, x + w - 2, y + h - 2, top, bottom);
        g.fillGradient(x, y + 5, x + w, y + h - 5, top, bottom);
        g.fill(x + 8, y, x + w - 8, y + 1, 0x6BC5F4FF);
        g.fill(x + 5, y + 1, x + w - 5, y + 2, 0x2AFFFFFF);
        g.fill(x + 1, y + 6, x + 2, y + h - 6, 0x3858C8E8);
        g.fill(x + w - 2, y + 6, x + w - 1, y + h - 6, 0x3258C8E8);
        g.fill(x + 7, y + h - 2, x + w - 7, y + h - 1, 0x2558C8E8);
    }

    public static void badge(GuiGraphicsExtractor g, int x, int y, int w, int h, int color) {
        g.fill(x + 3, y, x + w - 3, y + h, color);
        g.fill(x, y + 3, x + w, y + h - 3, color);
        g.fill(x + 3, y, x + w - 3, y + 1, 0x3AFFFFFF);
    }

    public static String compact(String value, int maxChars) {
        if (value == null) return "";
        return value.length() <= maxChars ? value : value.substring(0, Math.max(1, maxChars - 2)) + "..";
    }
}
