package io.github.wurtzitane.avogadro.client;

import java.util.ArrayList;
import java.util.List;
import java.util.function.ToIntFunction;

import net.minecraft.client.gui.FontRenderer;

import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL14;

/**
 * Measured chemical subscripts and superscripts, independent of the font's tiny Unicode numeral glyphs.
 *
 * <p>
 * Ordinary text keeps the selected game font. Script numerals use a legible 3 by 5 pixel shape with a full pixel of
 * spacing. Drawing and measurement share these advances, so a subscript cannot paint over the next element. Formatting
 * codes and shadow colors carry across runs; no chemistry or stored strings are changed.
 */
final class ScriptText {
    /** Supported script characters and their ordinary equivalents, in matching order. */
    private static final String SUB = "₀₁₂₃₄₅₆₇₈₉₊₋", SUPER = "⁰¹²³⁴⁵⁶⁷⁸⁹⁺⁻", NORMAL = "0123456789+-";
    /** Five rows of three bits, from the top of each numeral. */
    private static final int[][] DIGITS = {{7, 5, 5, 5, 7}, {2, 6, 2, 2, 7}, {7, 1, 7, 4, 7}, {7, 1, 7, 1, 7},
            {5, 5, 7, 1, 1}, {7, 4, 7, 1, 7}, {7, 4, 7, 5, 7}, {7, 1, 2, 2, 2}, {7, 5, 7, 5, 7}, {7, 5, 7, 1, 7},
            {0, 2, 7, 2, 0}, {0, 0, 7, 0, 0}};

    /**
     * One positioned run. Script text contains ordinary digits ready for the small glyph renderer.
     *
     * @param text text including inherited Minecraft formatting codes
     * @param left horizontal advance from the beginning of the line
     * @param top baseline offset: positive for subscripts, negative for superscripts
     * @param script whether this run uses the small chemical numerals
     */
    record Run(String text, int left, int top, boolean script) {
    }
    /**
     * A complete measured line, shared by width calculations and drawing.
     *
     * @param runs ordered positioned runs
     * @param width total GUI width, including script spacing
     */
    record Layout(List<Run> runs, int width) {
        Layout {
            runs = List.copyOf(runs);
        }
    }
    private ScriptText() {}

    /**
     * Tests whether a string needs chemical numeral handling.
     *
     * @param text displayed text
     * @return true when supported subscript or superscript characters occur
     */
    static boolean contains(String text) {
        if (text == null)
            return false;
        for (int i = 0; i < text.length(); i++)
            if (kind(text.charAt(i)) != 0)
                return true;
        return false;
    }
    /**
     * Separates ordinary and script runs while preserving colors and formatting.
     *
     * @param text displayed text; newlines are handled by the caller's normal wrapping
     * @param measure ordinary game-font width function
     * @return positioned runs using the same script advances as drawing
     */
    static Layout layout(String text, ToIntFunction<String> measure) {
        List<Run> runs = new ArrayList<>();
        String style = "";
        int cursor = 0;
        for (int i = 0; i < text.length();) {
            int kind = kind(text.charAt(i));
            StringBuilder run = new StringBuilder(style);
            while (i < text.length()) {
                char c = text.charAt(i);
                if (c == '§' && i + 1 < text.length()) {
                    char code = Character.toLowerCase(text.charAt(i + 1));
                    run.append(c).append(code);
                    style = code == 'r'
                            ? ""
                            : code >= '0' && code <= '9' || code >= 'a' && code <= 'f'
                                    ? "§" + code
                                    : style + "§" + code;
                    i += 2;
                    continue;
                }
                if (kind(c) != kind)
                    break;
                run.append(kind == 0 ? c : NORMAL.charAt((kind == 1 ? SUB : SUPER).indexOf(c)));
                i++;
            }
            String value = run.toString();
            runs.add(new Run(value, cursor, kind == 1 ? 4 : kind == 2 ? -1 : 0, kind != 0));
            cursor += kind == 0 ? measure.applyAsInt(value) : scriptWidth(value);
        }
        return new Layout(runs, cursor);
    }
    /**
     * Returns the script style used by a character.
     *
     * @param character displayed character
     * @return zero for ordinary text, one for subscript, two for superscript
     */
    private static int kind(char character) {
        return SUB.indexOf(character) >= 0 ? 1 : SUPER.indexOf(character) >= 0 ? 2 : 0;
    }
    /**
     * Measures the compact numeral alphabet, including bold spacing and ignoring formatting codes.
     *
     * @param text ordinary numeral text with inherited formatting
     * @return advance in GUI pixels
     */
    private static int scriptWidth(String text) {
        int width = 0;
        boolean bold = false;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '§' && i + 1 < text.length()) {
                char code = text.charAt(++i);
                if (code == 'l')
                    bold = true;
                else if (code == 'r' || "0123456789abcdef".indexOf(code) >= 0)
                    bold = false;
            } else
                width += bold ? 5 : 4;
        }
        // Keep digits within a count close; reserve one extra pixel after the whole count before an element.
        return width == 0 ? 0 : width + 1;
    }
    /**
     * Draws a script run on the current GUI transform, snapping its cells to the framebuffer grid.
     *
     * @param font source of Minecraft formatting colors
     * @param text small numeral run, already converted to ordinary digits
     * @param color caller's text color
     * @param shadow whether to draw the normal one-pixel shadow first
     */
    static void draw(FontRenderer font, String text, int color, boolean shadow) {
        if (!UiThemes.isScoped() && (color & 0xfc000000) == 0)
            color |= 0xff000000;
        PixelGrid grid = PixelGrid.capture();
        // Expand each authored cell to whole physical pixels, even under fractional label scaling.
        float cell = grid == null ? 1 : Math.max(1, (float) Math.floor(grid.scale() + .0001f)) / grid.scale();
        GL11.glPushAttrib(GL11.GL_ENABLE_BIT | GL11.GL_CURRENT_BIT | GL11.GL_COLOR_BUFFER_BIT);
        try {
            GL11.glDisable(GL11.GL_TEXTURE_2D);
            GL11.glEnable(GL11.GL_BLEND);
            GL11.glDisable(GL11.GL_ALPHA_TEST);
            GL14.glBlendFuncSeparate(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA, GL11.GL_ONE,
                    GL11.GL_ONE_MINUS_SRC_ALPHA);
            for (int pass = shadow ? 0 : 1; pass < 2; pass++) {
                int ink = color, cursor = 0;
                boolean bold = false;
                int offset = pass == 0 ? 1 : 0;
                GL11.glBegin(GL11.GL_QUADS);
                for (int i = 0; i < text.length(); i++) {
                    char c = text.charAt(i);
                    if (c == '§' && i + 1 < text.length()) {
                        char code = text.charAt(++i);
                        if (code == 'l')
                            bold = true;
                        else if (code == 'r') {
                            ink = color;
                            bold = false;
                        } else if ("0123456789abcdef".indexOf(code) >= 0) {
                            ink = ThemeText.formatted(code, color & 0xff000000 | font.getColorCode(code));
                            bold = false;
                        }
                        continue;
                    }
                    int index = NORMAL.indexOf(c);
                    if (index < 0)
                        continue;
                    int shaded = pass == 0 ? ThemeText.shadow(ink) : ink;
                    GL11.glColor4f((shaded >> 16 & 255) / 255f, (shaded >> 8 & 255) / 255f, (shaded & 255) / 255f,
                            (shaded >>> 24) / 255f);
                    for (int row = 0; row < 5; row++)
                        for (int column = 0; column < 3; column++)
                            if ((DIGITS[index][row] & (4 >> column)) != 0) {
                                float x = cursor + column * cell + offset, y = row * cell + offset,
                                        w = (bold ? 2 : 1) * cell;
                                float x0 = grid == null ? x : grid.snapX(x),
                                        x1 = grid == null ? x + w : grid.snapX(x + w);
                                float y0 = grid == null ? y : grid.snapY(y),
                                        y1 = grid == null ? y + cell : grid.snapY(y + cell);
                                GL11.glVertex2f(x0, y0);
                                GL11.glVertex2f(x0, y1);
                                GL11.glVertex2f(x1, y1);
                                GL11.glVertex2f(x1, y0);
                            }
                    cursor += bold ? 5 : 4;
                }
                GL11.glEnd();
            }
        } finally {
            GL11.glPopAttrib();
        }
    }
}
