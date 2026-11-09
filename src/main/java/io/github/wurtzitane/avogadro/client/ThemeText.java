package io.github.wurtzitane.avogadro.client;

/** Theme colors for Minecraft-format labels, without changing their glyphs, style flags, or measurements. */
final class ThemeText {
    private ThemeText() {}

    /** Returns per-codepoint colors, or null when drawing outside an Avogadro-owned scope. */
    static int[] colors(String text, int base) {
        if (!UiThemes.isScoped()) return null;
        int[] colors = new int[text.codePointCount(0, text.length())];
        int next = 0, ink = base;
        for (int i = 0; i < text.length();) {
            char value = text.charAt(i);
            if (value == '\u00a7' && i + 1 < text.length()) {
                char code = Character.toLowerCase(text.charAt(i + 1));
                if ("0123456789abcdef".indexOf(code) >= 0) ink = formatted(code, base);
                else if (code == 'r') ink = base;
                i += 2;
            } else {
                colors[next++] = ink;
                i += Character.charCount(text.codePointAt(i));
            }
        }
        return java.util.Arrays.copyOf(colors, next);
    }

    static int formatted(char code, int fallback) {
        int index = "0123456789abcdef".indexOf(code);
        if (index < 0 || !UiThemes.isScoped()) return fallback;
        int bright = (index >> 3 & 1) * 85;
        int red = (index >> 2 & 1) * 170 + bright + (index == 6 ? 85 : 0);
        int green = (index >> 1 & 1) * 170 + bright;
        int blue = (index & 1) * 170 + bright;
        return UiThemes.color((UiThemes.isTooltip() ? "tooltip.format." : "text.format.") + code,
                0xff000000 | red << 16 | green << 8 | blue);
    }

    static int shadow(int ink) {
        int legacy = ink & 0xff000000 | (ink & 0xfcfcfc) >> 2;
        return UiThemes.isScoped() ? UiThemes.color(UiThemes.isTooltip() ? "tooltip.shadow" : "text.shadow", legacy) : legacy;
    }
}
