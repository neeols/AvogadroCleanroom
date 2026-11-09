package io.github.wurtzitane.avogadro.client;

import java.awt.image.BufferedImage;
import java.io.IOException;

import javax.imageio.ImageIO;

import com.cleanroommc.kirino.ui.simpletext.text.StyledText;
import com.cleanroommc.kirino.ui.simpletext.text.TextStyle;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.FontRenderer;
import net.minecraft.util.ResourceLocation;

import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL20;

/**
 * Pixel shapes extracted from Minecraft's selected bitmap font, drawn without texture filtering or distance fields.
 *
 * <p>
 * The atlas supplies the actual letter forms; there is no second authored alphabet. Native font measurements,
 * formatting, and resource reloads remain authoritative. Non-bitmap packs and unsupported characters use the existing
 * font backend instead. The caller owns graphics-state restoration through PixelFontBackend.State.
 */
final class PixelGlyphFont {
    /** Native resource-pack font atlas; ordinary Minecraft characters occupy an eight-pixel cell. */
    private static final ResourceLocation ATLAS = new ResourceLocation("minecraft", "textures/font/ascii.png");
    /** One 64-bit occupancy mask per atlas cell, rebuilt after a resource reload. */
    private static long[] masks;
    /** Resource revision whose atlas has already been inspected, including an unsupported atlas. */
    private static long revision = Long.MIN_VALUE;
    private PixelGlyphFont() {}

    /**
     * Draws supported text using the same glyph advances as Minecraft.
     *
     * @param font native source of advances and formatting colors
     * @param text text with ordinary Minecraft formatting codes
     * @param x local left coordinate
     * @param y local top coordinate
     * @param color text color
     * @param shadow whether to draw the native one-pixel shadow first
     * @return false when the resource pack or characters need the normal font backend
     */
    static boolean draw(FontRenderer font, String text, float x, float y, int color, boolean shadow) {
        StyledText styled = new StyledText(text, StyledText.Syntax.MINECRAFT, TextStyle.DEFAULT.withColor(color));
        String plain = styled.getPureText();
        int[] themeColors = ThemeText.colors(text, color);
        for (int i = 0; i < plain.length(); i++) {
            char c = plain.charAt(i);
            if ((c < 32 || c > 126) && c != '·' && c != '\n' && c != '\r')
                return false;
        }
        if (!prepare())
            return false;
        PixelGrid grid = PixelGrid.capture();
        if (grid == null)
            return false;
        GL20.glUseProgram(0);
        GL11.glDisable(GL11.GL_TEXTURE_2D);
        GL11.glDisable(GL11.GL_LIGHTING);
        GL11.glDisable(GL11.GL_CULL_FACE);
        for (int pass = shadow ? 0 : 1; pass < 2; pass++) {
            boolean shade = pass == 0;
            float cursor = grid.snapX(x), line = grid.snapY(y), offset = shade ? 1 : 0;
            GL11.glBegin(GL11.GL_QUADS);
            for (int index = 0; index < plain.length(); index++) {
                char c = plain.charAt(index);
                if (c == '\n') {
                    cursor = grid.snapX(x);
                    line += font.FONT_HEIGHT;
                    continue;
                }
                if (c == '\r')
                    continue;
                TextStyle style = styled.get(index);
                boolean bold = (style.hint() & (1 << 25)) != 0;
                boolean italic = (style.hint() & (1 << 26)) != 0;
                int ink = themeColors == null ? style.color() : themeColors[index];
                if (!UiThemes.isScoped() && (ink & 0xfc000000) == 0)
                    ink |= 0xff000000;
                if (shade)
                    ink = ThemeText.shadow(ink);
                GL11.glColor4f((ink >> 16 & 255) / 255f, (ink >> 8 & 255) / 255f, (ink & 255) / 255f,
                        (ink >>> 24) / 255f);
                long mask = masks[c == '·' ? '.' : c];
                for (int row = 0; row < 8; row++)
                    for (int column = 0; column < 8; column++) {
                        if ((mask & (1L << (row * 8 + column))) == 0)
                            continue;
                        float slant = italic ? (row < 4 ? 1 : -1) : 0;
                        quad(grid, cursor + column + offset + slant, line + row + offset - (c == '·' ? 3 : 0),
                                bold ? 2 : 1);
                    }
                int advance = font.getCharWidth(c) + (bold ? 1 : 0);
                if (style.underlineEnabled())
                    for (int column = 0; column < advance; column++)
                        quad(grid, cursor + column + offset, line + font.FONT_HEIGHT - 1 + offset, 1);
                if (style.strikethroughEnabled())
                    for (int column = 0; column < advance; column++)
                        quad(grid, cursor + column + offset, line + font.FONT_HEIGHT / 2 - 1 + offset, 1);
                cursor += advance;
            }
            GL11.glEnd();
        }
        return true;
    }

    /**
     * Adds one atlas pixel, expanded as a rectangle on the current integer framebuffer grid.
     *
     * @param grid active framebuffer coordinate mapping
     * @param x pixel's local left coordinate
     * @param y pixel's local top coordinate
     * @param width one pixel normally, two for bold overdraw
     */
    private static void quad(PixelGrid grid, float x, float y, int width) {
        float x0 = grid.snapX(x), x1 = grid.snapX(x + width), y0 = grid.snapY(y), y1 = grid.snapY(y + 1);
        GL11.glVertex2f(x0, y0);
        GL11.glVertex2f(x0, y1);
        GL11.glVertex2f(x1, y1);
        GL11.glVertex2f(x1, y0);
    }

    /**
     * Extracts the selected resource pack's native pixel cells once per reload.
     *
     * @return whether an ordinary eight-pixel bitmap atlas is available
     */
    private static boolean prepare() {
        long current = MoleculeCategoryStyle.reloadRevision();
        if (revision == current)
            return masks != null;
        revision = current;
        masks = null;
        try (var resource = Minecraft.getMinecraft().getResourceManager().getResource(ATLAS)) {
            BufferedImage atlas = ImageIO.read(resource.getInputStream());
            if (atlas == null || atlas.getWidth() != 128 || atlas.getHeight() != 128)
                return false;
            long[] loaded = new long[256];
            for (int glyph = 0; glyph < loaded.length; glyph++)
                for (int row = 0; row < 8; row++)
                    for (int column = 0; column < 8; column++) {
                        int alpha = atlas.getRGB((glyph % 16) * 8 + column, (glyph / 16) * 8 + row) >>> 24;
                        if (alpha >= 128)
                            loaded[glyph] |= 1L << (row * 8 + column);
                    }
            masks = loaded;
            return true;
        } catch (IOException unavailable) {
            return false;
        }
    }
}
