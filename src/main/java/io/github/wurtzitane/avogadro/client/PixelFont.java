package io.github.wurtzitane.avogadro.client;

import com.cleanroommc.modularui.screen.ModularScreen;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.FontRenderer;
import net.minecraft.util.ResourceLocation;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Minecraft pixel glyphs rendered by Kirino, with the caller's layout and GL transform. */
public final class PixelFont {

    /** Set of registered ModularUI screen owners opting into pixel font rendering. */
    private static final Set<String> UI_OWNERS = new HashSet<>(Set.of("avogadro"));

    /** Cached font renderer adapter instance. */
    private static Adapter adapter;

    /** Private constructor to prevent instantiation of utility class. */
    private PixelFont() {}

    /**
     * Retrieves the wrapped default font renderer instance.
     *
     * @return wrapped {@link FontRenderer} instance
     */
    public static FontRenderer renderer() { return wrap(Minecraft.getMinecraft().fontRenderer); }

    /**
     * Custom item fonts retain their own renderer; a null font selects the game font.
     *
     * @param font font renderer to wrap
     * @return wrapped font renderer instance
     */
    public static FontRenderer wrap(FontRenderer font) {
        FontRenderer vanilla = Minecraft.getMinecraft().fontRenderer;
        if (font == null) font = vanilla;
        if (font instanceof Adapter value) return value.refresh();
        if (font != vanilla || font == null) return font;
        if (adapter == null || adapter.original != font) adapter = new Adapter(font);
        return adapter.refresh();
    }

    /**
     * Checks whether the pixel font backend is available.
     *
     * @return {@code true} if available, {@code false} otherwise
     */
    public static boolean available() { return PixelFontBackend.available(); }

    /** Framebuffer pixels per GUI unit, or zero for a rotated, perspective or nonuniform transform. */
    public static float physicalScale() { return PixelFontBackend.physicalScale(); }

    /**
     * Opt an owner's ModularUI screens into the same font used by its custom widgets.
     *
     * @param owner UI owner identifier string
     */
    public static void registerUiOwner(String owner) { UI_OWNERS.add(owner); }

    /**
     * Wraps the font renderer for UI screens if the current screen owner is registered.
     *
     * @param font font renderer
     * @return wrapped or original font renderer
     */
    public static FontRenderer forUi(FontRenderer font) {
        ModularScreen screen = ModularScreen.getCurrent();
        return screen != null && UI_OWNERS.contains(screen.getOwner()) ? wrap(font) : font;
    }

    /** Releases pixel font backend resources. */
    public static void release() { PixelFontBackend.release(); }

    /**
     * Font renderer adapter delegating string drawing and measurement to backend pixel font rendering.
     */
    private static final class Adapter extends FontRenderer {

        /** Original vanilla font renderer instance being adapted. */
        private final FontRenderer original;

        /**
         * Constructs a new font renderer adapter.
         *
         * @param original original font renderer
         */
        private Adapter(FontRenderer original) {
            super(Minecraft.getMinecraft().gameSettings, new ResourceLocation("textures/font/ascii.png"),
                    Minecraft.getMinecraft().getTextureManager(), original.getUnicodeFlag());
            this.original = original;
        }

        /**
         * Refreshes font height metrics and returns this adapter instance.
         *
         * @return refreshed adapter instance
         */
        private Adapter refresh() { FONT_HEIGHT = original.FONT_HEIGHT; return this; }

        /**
         * Renders a text string at integer coordinates without shadow.
         *
         * @param text  text string
         * @param x     x coordinate
         * @param y     y coordinate
         * @param color text color value
         * @return string width in pixels
         */
        @Override public int drawString(String text, int x, int y, int color) {
            return drawString(text, (float) x, (float) y, color, false);
        }

        /**
         * Renders a text string at floating-point coordinates with shadow.
         *
         * @param text  text string
         * @param x     x coordinate
         * @param y     y coordinate
         * @param color text color value
         * @return string width in pixels
         */
        @Override public int drawStringWithShadow(String text, float x, float y, int color) {
            return drawString(text, x, y, color, true);
        }

        /**
         * Renders a text string with specified coordinates, color, and shadow settings.
         *
         * @param text   text string
         * @param x      x coordinate
         * @param y      y coordinate
         * @param color  text color value
         * @param shadow whether to render shadow
         * @return string width in pixels
         */
        @Override public int drawString(String text, float x, float y, int color, boolean shadow) {
            if (text == null || text.isEmpty()) return 0;
            if (!original.getBidiFlag() && !original.getUnicodeFlag()
                    && PixelFontBackend.draw(original, text, x, y, color, shadow))
                return (int) (x + original.getStringWidth(text) + (shadow ? 1 : 0));
            return original.drawString(text, x, y, color, shadow);
        }

        /**
         * Renders a split multi-line string constrained to a maximum width.
         *
         * @param text  text string
         * @param x     x coordinate
         * @param y     y coordinate
         * @param width maximum line width
         * @param color text color value
         */
        @Override public void drawSplitString(String text, int x, int y, int width, int color) {
            for (String line : original.listFormattedStringToWidth(text, width)) {
                drawString(line, x, y, color);
                y += original.FONT_HEIGHT;
            }
        }

        /**
         * Computes the display width of a text string.
         *
         * @param text text string
         * @return width in pixels
         */
        @Override public int getStringWidth(String text) { return original.getStringWidth(text); }

        /**
         * Computes the display width of an individual character.
         *
         * @param character character
         * @return width in pixels
         */
        @Override public int getCharWidth(char character) { return original.getCharWidth(character); }

        /**
         * Trims a string to fit within a specified maximum pixel width.
         *
         * @param text  text string
         * @param width maximum width
         * @return trimmed string
         */
        @Override public String trimStringToWidth(String text, int width) { return original.trimStringToWidth(text, width); }

        /**
         * Trims a string to fit within a specified maximum pixel width, with optional reverse direction.
         *
         * @param text    text string
         * @param width   maximum width
         * @param reverse whether to trim from the end
         * @return trimmed string
         */
        @Override public String trimStringToWidth(String text, int width, boolean reverse) {
            return original.trimStringToWidth(text, width, reverse);
        }

        /**
         * Formats and wraps a string into lines fitting a specified width.
         *
         * @param text  text string
         * @param width maximum width
         * @return list of formatted string lines
         */
        @Override public List<String> listFormattedStringToWidth(String text, int width) {
            return original.listFormattedStringToWidth(text, width);
        }

        /**
         * Computes the total height of a word-wrapped string.
         *
         * @param text  text string
         * @param width maximum width
         * @return total height in pixels
         */
        @Override public int getWordWrappedHeight(String text, int width) { return original.getWordWrappedHeight(text, width); }

        /**
         * Retrieves the color code integer for a formatting character.
         *
         * @param code format code character
         * @return color integer value
         */
        @Override public int getColorCode(char code) { return original.getColorCode(code); }

        /**
         * Checks whether the unicode font flag is enabled.
         *
         * @return {@code true} if unicode flag is enabled, {@code false} otherwise
         */
        @Override public boolean getUnicodeFlag() { return original == null ? super.getUnicodeFlag() : original.getUnicodeFlag(); }

        /**
         * Checks whether the bidirectional text flag is enabled.
         *
         * @return {@code true} if bidi flag is enabled, {@code false} otherwise
         */
        @Override public boolean getBidiFlag() { return original == null ? super.getBidiFlag() : original.getBidiFlag(); }

        /**
         * Sets the unicode font flag state.
         *
         * @param value unicode flag value
         */
        @Override public void setUnicodeFlag(boolean value) {
            if (original == null) super.setUnicodeFlag(value); else original.setUnicodeFlag(value);
        }

        /**
         * Sets the bidirectional text flag state.
         *
         * @param value bidi flag value
         */
        @Override public void setBidiFlag(boolean value) {
            if (original == null) super.setBidiFlag(value); else original.setBidiFlag(value);
        }
    }
}