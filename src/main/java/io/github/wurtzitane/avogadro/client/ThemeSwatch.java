package io.github.wurtzitane.avogadro.client;

import io.github.wurtzitane.avogadro.theme.ThemeDefinition;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.renderer.GlStateManager;

/** A small color sample for each theme: window, frame, text, info icon and both button states. */
final class ThemeSwatch {
    private ThemeSwatch() {}

    /** Uses the normal drawing helpers and candidate colors; the active theme remains unchanged. */
    static void draw(ThemeDefinition.Compiled theme, int x, int y, int width, int height) {
        try (var ignored = UiThemes.preview(theme)) {
            Gui.drawRect(x, y, x + width, y + height, StructureStyle.BORDER());
            Gui.drawRect(x + 1, y + 1, x + width - 1, y + height - 1, StructureStyle.PANEL());
            Gui.drawRect(x + 4, y + 13, x + width - 4, y + height - 4, StructureStyle.PAPER());
            PixelFont.renderer().drawString("Aa", x + 5, y + 3, UiThemes.color("text.title", StructureStyle.INK));
            GlStateManager.pushMatrix();
            try {
                GlStateManager.translate(x + width - 12, y + 2, 0);
                GlStateManager.scale(.6f, .6f, 1);
                ExaminerPresentation.drawInfoIcon(0, 0, false, StructureStyle.MUTED());
            } finally {
                GlStateManager.popMatrix();
            }
            int buttonWidth = (width - 14) / 2;
            button(x + 6, y + 16, buttonWidth, height - 23, "");
            button(x + 8 + buttonWidth, y + 16, buttonWidth, height - 23, "hover.");
        }
    }

    private static void button(int x, int y, int width, int height, String state) {
        Gui.drawRect(x, y, x + width, y + height, UiThemes.color("button." + state + "border", StructureStyle.BORDER));
        Gui.drawRect(x + 1, y + 1, x + width - 1, y + height - 1,
                UiThemes.color("button." + state + "background", StructureStyle.SURFACE));
        var font = PixelFont.renderer();
        font.drawString("Aa", x + (width - font.getStringWidth("Aa")) / 2, y + (height - font.FONT_HEIGHT) / 2 + 1,
                UiThemes.color("button." + state + "text", StructureStyle.INK));
    }
}
