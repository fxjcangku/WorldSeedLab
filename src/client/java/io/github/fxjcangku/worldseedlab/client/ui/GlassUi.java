package io.github.fxjcangku.worldseedlab.client.ui;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.FontDescription;
import net.minecraft.resources.Identifier;

/** 统一玻璃表面与独立中文字体；所有测量、裁切、绘制使用相同的字体样式。 */
public final class GlassUi {
    public static final int TEXT = 0xFFF1EFF7;
    public static final int MUTED = 0xFFB6BACD;
    public static final int CYAN = 0xFFC3A4FF;
    public static final int MINT = 0xFF83DCC0;
    public static final int AMBER = 0xFFE4C58B;
    public static final int ROSE = 0xFFF19AAB;
    public static final int LINE = 0x28D7DDF2;
    public static final int FONT_HEIGHT = 14;
    private static final FontDescription UI_FONT = new FontDescription.Resource(
            Identifier.fromNamespaceAndPath("worldseedlab", "ui"));
    private static final FontDescription TITLE_FONT = new FontDescription.Resource(
            Identifier.fromNamespaceAndPath("worldseedlab", "title"));

    private GlassUi() { }

    /** 用不重叠的水平扫描线构造圆角，避免透明矩形重复叠加成过亮的十字。 */
    public static void rounded(GuiGraphicsExtractor g, int x, int y, int w, int h,
                               int radius, int color) {
        if (w <= 0 || h <= 0) return;
        int r = Math.min(radius, Math.min(w, h) / 2);
        g.fill(x, y + r, x + w, y + h - r, color);
        for (int row = 0; row < r; row++) {
            double dy = r - row - 0.5;
            int inset = (int) Math.ceil(r - Math.sqrt(r * r - dy * dy));
            g.fill(x + inset, y + row, x + w - inset, y + row + 1, color);
            g.fill(x + inset, y + h - row - 1, x + w - inset, y + h - row, color);
        }
    }

    public static void glass(GuiGraphicsExtractor g, int x, int y, int w, int h, boolean vivid) {
        int radius = vivid ? 13 : 8;
        rounded(g, x, y, w, h, radius, vivid ? 0xAF202532 : 0x302B3043);
        outline(g, x, y, w, h, radius, vivid ? 0x68D3DCF5 : LINE);
        // 高光只叠加薄薄一层；边框不先铺满内部，避免透明度累加成不透明面板。
        if (vivid) for (int row = 1; row < 42 && row < h / 2; row++) {
            int inset = row < radius ? radius : 1;
            int alpha = Math.max(0, 13 - row / 3);
            g.fill(x + inset, y + row, x + w - inset, y + row + 1, (alpha << 24) | 0xD6DDFB);
        }
    }

    public static void outline(GuiGraphicsExtractor g, int x, int y, int w, int h, int radius, int color) {
        int r = Math.min(radius, Math.min(w, h) / 2);
        for (int row = 0; row < h; row++) {
            int edge = Math.min(row, h - row - 1);
            double dy = r - edge - 0.5;
            int inset = edge >= r ? 0 : (int) Math.ceil(r - Math.sqrt(r * r - dy * dy));
            if (row == 0 || row == h - 1) g.fill(x + inset, y + row, x + w - inset, y + row + 1, color);
            else {
                g.fill(x + inset, y + row, x + inset + 1, y + row + 1, color);
                g.fill(x + w - inset - 1, y + row, x + w - inset, y + row + 1, color);
            }
        }
    }

    public static void badge(GuiGraphicsExtractor g, int x, int y, int w, int h, int color) {
        rounded(g, x, y, w, h, h / 2, color);
    }

    /** 中英文宽度不同；操作标签都以字体实际像素宽度截断。 */
    public static String fit(Font font, String value, int width) {
        if (value == null || width <= 0) return "";
        if (width(font, value) <= width) return value;
        if (width(font, "…") > width) return "";
        int end = value.length();
        while (end > 0 && width(font, value.substring(0, end) + "…") > width)
            end = value.offsetByCodePoints(end, -1);
        return value.substring(0, end) + "…";
    }

    public static Component styled(String value) {
        return Component.literal(value).withStyle(style -> style.withFont(UI_FONT));
    }

    public static int width(Font font, String value) { return font.width(styled(value)); }

    public static void text(GuiGraphicsExtractor g, Font font, String value,
                            int x, int y, int width, int color) {
        // 原生GlyphBitmap使用7-bearingTop定位；当前TTF的14px墨迹需下移6px才对应排版顶边。
        g.text(font, styled(fit(font, value, width)), x, y + 6, color, false);
    }

    public static void title(GuiGraphicsExtractor g, Font font, String value, int x, int y, int color) {
        g.text(font, Component.literal(value).withStyle(style -> style.withFont(TITLE_FONT)), x, y + 11, color, false);
    }

    /** 原版 wordWrap 的行距是9；独立14像素字体必须明确使用更大的行距。 */
    public static int wrap(GuiGraphicsExtractor g, Font font, String value, int x, int y,
                           int width, int color, int maxLines) {
        var wrapped = font.split(styled(value), width);
        int count = Math.min(maxLines, wrapped.size());
        for (int index = 0; index < count; index++) {
            var line = wrapped.get(index);
            if (index == count - 1 && wrapped.size() > count) {
                StringBuilder tail = new StringBuilder();
                line.accept((position, style, codePoint) -> { tail.appendCodePoint(codePoint); return true; });
                text(g, font, tail + "…", x, y + index * 19, width, color);
            } else g.text(font, line, x, y + 6 + index * 19, color, false);
        }
        return count * 19;
    }
}
