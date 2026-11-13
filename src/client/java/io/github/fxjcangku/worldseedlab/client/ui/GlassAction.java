package io.github.fxjcangku.worldseedlab.client.ui;

import java.util.function.Supplier;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.network.chat.Component;

/** 半透明操作按钮；绘制比例与原生点击、键盘焦点区域保持一致。 */
public final class GlassAction extends AbstractWidget {
    private final Font font;
    private final Supplier<String> text;
    private final Runnable action;
    private final boolean primary;
    private final int logicalWidth;
    private final int logicalHeight;
    private final float contentScale;
    private boolean tab;

    public GlassAction(int x, int y, int width, int height, Font font,
                       Supplier<String> text, Runnable action, boolean primary) {
        this(x, y, width, height, font, text, action, primary, 1f);
    }

    public GlassAction asTab() { tab = true; return this; }

    /**
     * x/y 已是屏幕坐标，宽高是逻辑排版尺寸；原生控件尺寸与绘制使用同一比例。
     * 地图等原有页面继续使用默认 1 倍构造方法，不受控制台缩放影响。
     */
    public GlassAction(int x, int y, int width, int height, Font font,
                       Supplier<String> text, Runnable action, boolean primary, float contentScale) {
        super(x, y, Math.max(1, Math.round(width * contentScale)),
                Math.max(1, Math.round(height * contentScale)), Component.literal(text.get()));
        this.font = font;
        this.text = text;
        this.action = action;
        this.primary = primary;
        this.logicalWidth = width;
        this.logicalHeight = height;
        this.contentScale = contentScale;
    }

    @Override
    protected void extractWidgetRenderState(GuiGraphicsExtractor g, int mouseX,
                                            int mouseY, float delta) {
        g.pose().pushMatrix();
        try {
            g.pose().translate(getX(), getY());
            g.pose().scale(contentScale, contentScale);
            extractSurface(g);
        } finally {
            g.pose().popMatrix();
        }
    }

    /** 文字和装饰随按钮整体缩放，不在缩小时保留会溢出的原始字体尺寸。 */
    private void extractSurface(GuiGraphicsExtractor g) {
        int x = 0, y = 0, w = logicalWidth, h = logicalHeight;
        boolean highlighted = isHovered() || isFocused();
        int edge = !active ? 0x20FFFFFF : primary ? 0x9EB799F2
                : highlighted ? 0x80C4CAE0 : 0x3FC4CAE0;
        int surface = !active ? 0x122C3040 : primary ? 0x665D487F
                : highlighted ? 0x405A6077 : 0x1C949BB4;
        if (tab) {
            if (highlighted) GlassUi.rounded(g, x, y, w, h - 3, 6, 0x18C6B5FF);
            if (primary) GlassUi.rounded(g, x + 16, h - 3, w - 32, 2, 1, GlassUi.CYAN);
        } else {
            GlassUi.rounded(g, x, y, w, h, 7, surface);
            GlassUi.outline(g, x, y, w, h, 7, edge);
        }
        String label = GlassUi.fit(font, text.get(), w - 14);
        int textX = x + (w - GlassUi.width(font, label)) / 2;
        GlassUi.text(g, font, label, textX, y + (h - GlassUi.FONT_HEIGHT) / 2 - 1, w - 14,
                !active ? 0xFF858A9D : primary ? GlassUi.CYAN : GlassUi.TEXT);
    }

    @Override
    public void onClick(MouseButtonEvent event, boolean doubleClick) {
        if (this.active) {
            action.run();
            setMessage(Component.literal(text.get()));
        }
    }

    /** 自定义外观仍保留原生 Tab 焦点以及 Enter / 空格激活。 */
    @Override
    public boolean keyPressed(KeyEvent event) {
        if (!active || !isFocused() || !event.isConfirmation()) return false;
        action.run();
        setMessage(Component.literal(text.get()));
        return true;
    }

    @Override
    protected void updateWidgetNarration(NarrationElementOutput output) {
        this.defaultButtonNarrationText(output);
    }
}
