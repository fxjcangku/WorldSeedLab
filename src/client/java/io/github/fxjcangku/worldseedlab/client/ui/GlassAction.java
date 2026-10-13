package io.github.fxjcangku.worldseedlab.client.ui;

import java.util.function.Supplier;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

/** Keyboard-accessible translucent action surface. */
public final class GlassAction extends AbstractWidget {
    private final Font font;
    private final Supplier<String> text;
    private final Runnable action;
    private final boolean primary;

    public GlassAction(int x, int y, int width, int height, Font font,
                       Supplier<String> text, Runnable action, boolean primary) {
        super(x, y, width, height, Component.literal(text.get()));
        this.font = font;
        this.text = text;
        this.action = action;
        this.primary = primary;
    }

    @Override
    protected void extractWidgetRenderState(GuiGraphicsExtractor g, int mouseX,
                                            int mouseY, float delta) {
        int x = getX(), y = getY(), w = getWidth(), h = getHeight();
        int top = primary ? 0xB447B5E0 : 0xA6294057;
        int bottom = primary ? 0xB61A6989 : 0xA614273D;
        if (this.isHovered()) {
            top = primary ? 0xD85ACFF5 : 0xBE3A607A;
            bottom = primary ? 0xD82383AC : 0xBE1C3950;
        }
        g.fillGradient(x + 4, y, x + w - 4, y + h, top, bottom);
        g.fillGradient(x, y + 4, x + w, y + h - 4, top, bottom);
        g.fill(x + 5, y, x + w - 5, y + 1, 0x80BBEFFF);
        g.fill(x + 5, y + h - 1, x + w - 5, y + h, 0x406BC8EC);
        String label = GlassUi.compact(text.get(), Math.max(5, w / 7));
        int textX = x + (w - font.width(label)) / 2;
        g.text(font, label, textX, y + (h - font.lineHeight) / 2, GlassUi.TEXT, false);
    }

    @Override
    public void onClick(MouseButtonEvent event, boolean doubleClick) {
        if (this.active) {
            action.run();
            setMessage(Component.literal(text.get()));
        }
    }

    @Override
    protected void updateWidgetNarration(NarrationElementOutput output) {
        this.defaultButtonNarrationText(output);
    }
}
