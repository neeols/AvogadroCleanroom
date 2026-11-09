package io.github.wurtzitane.avogadro.client;

import java.util.ArrayList;
import java.util.List;
import java.util.function.ToIntFunction;

import net.minecraft.client.gui.FontRenderer;

import org.lwjgl.opengl.GL11;

/** Shared measured text for molecule labels; native item and HUD text remains with its owner. */
public final class MoleculeText {

    /** Vertical spacing gap distance between text groups. */
    public static final float GROUP_GAP = 2f;

    /**
     * Record representing an individual text glyph with positioning and scale metrics.
     *
     * @param text glyph string content
     * @param subscript whether the glyph is rendered as a subscript
     * @param left left offset position
     * @param top top offset position
     * @param scale scale factor applied to the glyph
     */
    public record Glyph(String text, boolean subscript, float left, float top, float scale) {
    }

    /**
     * Record representing a line of text composed of multiple positioned glyphs.
     *
     * @param glyphs list of component glyphs
     * @param width total line width
     * @param height total line height
     */
    public record Line(List<Glyph> glyphs, float width, float height) {

        /** Compact constructor copying and immunizing the glyph list collection. */
        public Line {
            glyphs = List.copyOf(glyphs);
        }

        /**
         * Renders the text line using the specified font renderer and drawing parameters.
         *
         * @param font font renderer instance
         * @param left left render coordinate
         * @param top top render coordinate
         * @param scale global scale factor
         * @param color text color value
         */
        public void draw(FontRenderer font, float left, float top, float scale, int color) {
            font = PixelFont.wrap(font);
            if (font == PixelFont.renderer())
                scale = pixelAlignedScale(scale);
            for (Glyph glyph : glyphs) {
                GL11.glPushMatrix();
                try {
                    GL11.glTranslatef(left + glyph.left() * scale, top + glyph.top() * scale, 0);
                    GL11.glScalef(scale * glyph.scale(), scale * glyph.scale(), 1);
                    font.drawString(glyph.text(), 0, 0, color);
                } finally {
                    GL11.glPopMatrix();
                }
            }
        }
    }

    private MoleculeText() {}

    /**
     * Fits a pixel-font label to whole framebuffer pixels, keeping at least one pixel per authored font cell.
     *
     * <p>
     * Call on the render thread with the label's parent transform active. Use the returned scale for centering as well
     * as drawing. Custom item-font renderers keep their own scaling behavior.
     *
     * @param requested requested GUI scale
     * @return a whole-pixel scale, or the requested scale when the transform has no uniform pixel grid
     */
    public static float pixelAlignedScale(float requested) {
        return pixelAlignedScale(requested, PixelFont.physicalScale());
    }

    /**
     * Computes the scale without reading graphics state.
     *
     * @param requested requested label scale
     * @param physical framebuffer pixels per parent GUI unit; zero means an unsupported transform
     * @return scale whose font cells map to whole framebuffer pixels
     */
    static float pixelAlignedScale(float requested, float physical) {
        return physical > 0 ? Math.max(1, (float) Math.floor(requested * physical + .0001f)) / physical : requested;
    }

    /**
     * Creates a plain single-glyph text line from standard string content.
     *
     * @param text string content
     * @param measure measurement function for string width
     * @param fontHeight font height in pixels
     * @return constructed {@link Line}
     */
    public static Line plain(String text, ToIntFunction<String> measure, int fontHeight) {
        return new Line(List.of(new Glyph(text, false, 0, 0, 1)), measure.applyAsInt(text), fontHeight);
    }

    /** Use the same Unicode chemical typography as native tooltips, honoring resource-pack fonts. */
    public static Line formula(String text, ToIntFunction<String> measure, int fontHeight) {
        return plain(io.github.wurtzitane.avogadro.api.ChemicalFormula.display(text), measure, fontHeight);
    }

    /** Two compact lines, with an ellipsis only when the remaining name still does not fit. */
    public static List<String> wrap(String text, float available, ToIntFunction<String> measure) {
        List<String> lines = new ArrayList<>();
        String remaining = text;
        for (int line = 0; line < 2 && !remaining.isEmpty(); line++) {
            if (measure.applyAsInt(remaining) <= available) {
                lines.add(remaining);
                break;
            }
            String suffix = line == 1 ? "..." : "";
            int end = remaining.length();
            while (end > 1 && measure.applyAsInt(remaining.substring(0, end) + suffix) > available)
                end--;
            int space = remaining.lastIndexOf(' ', end);
            if (line == 0 && space > end / 2)
                end = space;
            lines.add(remaining.substring(0, end).stripTrailing() + suffix);
            remaining = remaining.substring(end).stripLeading();
        }
        return List.copyOf(lines);
    }
}
