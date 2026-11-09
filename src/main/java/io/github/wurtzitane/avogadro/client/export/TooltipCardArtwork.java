package io.github.wurtzitane.avogadro.client.export;

import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Generates pre-rendered native tooltip card artwork for item exports.
 * <p>
 * Native tooltip colors composited over a solid fill, independent of the current screen.
 */
final class TooltipCardArtwork {

    /** Top border color value for tooltip cards. */
    private static final int BORDER_TOP = 0x505000FF;

    /** Bottom border color value for tooltip cards. */
    private static final int BORDER_BOTTOM = 0x5028007F;

    /**
     * Represents generated tooltip card artwork containing raw pixel data and protected colors.
     *
     * @param pixels raw ARGB pixel color array
     * @param protectedColors array of protected color integers
     */
    record Image(int[] pixels, int[] protectedColors) {
    }

    private TooltipCardArtwork() {}

    /**
     * Creates and renders a tooltip card image for the specified dimensions, scale, and background.
     *
     * @param width card width in pixels
     * @param height card height in pixels
     * @param scale GUI scale factor
     * @param background background RGB color integer
     * @return a new {@link Image} containing pixels and protected colors
     */
    static Image create(int width, int height, int scale, int background) {
        int fill = 0xFF000000 | background & 0xFFFFFF;
        int[] pixels = new int[Math.multiplyExact(width, height)];
        Arrays.fill(pixels, fill);
        Set<Integer> colors = new LinkedHashSet<>();
        colors.add(fill);
        int borderTop = io.github.wurtzitane.avogadro.client.UiThemes.color("tooltip.border.top", BORDER_TOP);
        int borderBottom = io.github.wurtzitane.avogadro.client.UiThemes.color("tooltip.border.bottom", BORDER_BOTTOM);
        int top = composite(borderTop, fill), bottom = composite(borderBottom, fill);
        for (int y = scale; y < 2 * scale; y++) {
            Arrays.fill(pixels, y * width + scale, (y + 1) * width - scale, top);
        }
        for (int y = height - 2 * scale; y < height - scale; y++) {
            Arrays.fill(pixels, y * width + scale, (y + 1) * width - scale, bottom);
        }
        colors.add(top);
        colors.add(bottom);
        int start = 2 * scale, end = height - 2 * scale;
        for (int y = start; y < end; y++) {
            int color = composite(interpolate(borderTop, borderBottom, (y - start + .5) / (end - start)), fill);
            Arrays.fill(pixels, y * width + scale, y * width + 2 * scale, color);
            Arrays.fill(pixels, (y + 1) * width - 2 * scale, (y + 1) * width - scale, color);
            colors.add(color);
        }
        // Minecraft's sixteen text colors and their shadow variants stay exact too.
        for (int index = 0; index < 16; index++) {
            int bright = (index >> 3 & 1) * 85;
            int red = (index >> 2 & 1) * 170 + bright + (index == 6 ? 85 : 0);
            int green = (index >> 1 & 1) * 170 + bright;
            int blue = (index & 1) * 170 + bright;
            colors.add(0xFF000000 | red << 16 | green << 8 | blue);
            colors.add(0xFF000000 | red / 4 << 16 | green / 4 << 8 | blue / 4);
        }
        return new Image(pixels, colors.stream().mapToInt(Integer::intValue).toArray());
    }

    /** Interpolates between two color integers by a given fraction. */
    private static int interpolate(int first, int last, double fraction) {
        int result = 0;
        for (int shift = 0; shift < 32; shift += 8) {
            int start = first >>> shift & 255, end = last >>> shift & 255;
            result |= (int) Math.round(start + (end - start) * fraction) << shift;
        }
        return result;
    }

    /** Composites a foreground color over a background fill color. */
    private static int composite(int color, int background) {
        int alpha = color >>> 24;
        int result = 0xFF000000;
        for (int shift = 0; shift < 24; shift += 8) {
            int source = color >>> shift & 255, matte = background >>> shift & 255;
            result |= ((source * alpha + matte * (255 - alpha) + 127) / 255) << shift;
        }
        return result;
    }
}
