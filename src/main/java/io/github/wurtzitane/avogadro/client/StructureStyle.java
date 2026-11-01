package io.github.wurtzitane.avogadro.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Gui;

import com.cleanroommc.modularui.api.drawable.IDrawable;
import com.cleanroommc.modularui.widgets.ButtonWidget;

import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

/** Shared colors, surfaces and controls for Avogadro menus. */
final class StructureStyle {

    /** Ink text color constant. */
    static final int INK = 0x3F3A30;

    /** Muted text color constant. */
    static final int MUTED = 0x766F60;

    /** Accent highlight color constant. */
    static final int ACCENT = 0x3D7979;

    /** Warning color constant. */
    static final int WARNING = 0x906025;

    /** Panel background color constant. */
    static final int PANEL = 0xFFF4F1E8;

    /** Border color constant. */
    static final int BORDER = 0xFFA9B2A6;

    /** Surface background color constant. */
    static final int SURFACE = 0xFFEBECE2;

    /** Paper color constant. */
    static final int PAPER = ElementCard.PAPER;

    /** Rule line color constant. */
    static final int RULE = 0xFFE1E3DA;

    /** Viewport border color constant. */
    static final int VIEW_BORDER = 0xFFD8DED2;

    /** Button border color constant. */
    private static final int BUTTON_BORDER = 0xFFBEC9B9;

    /** Notice box fill color constant. */
    static final int NOTICE = 0xFFFFF1D6;

    /** Notice box border color constant. */
    static final int NOTICE_BORDER = 0xFFCCB486;

    /** Private constructor to prevent instantiation of utility class. */
    private StructureStyle() {}

    /**
     * Renders a standard header with a title and subtitle.
     *
     * @param title    header title string
     * @param subtitle header subtitle string
     * @param x        x screen coordinate
     * @param y        y screen coordinate
     * @param width    header width
     */
    static void header(String title, String subtitle, int x, int y, int width) {
        header(title, subtitle, x, y, width, 60);
    }

    /**
     * Renders a header with a title, subtitle, width, and right inset constraint.
     *
     * @param title      header title string
     * @param subtitle   header subtitle string
     * @param x          x screen coordinate
     * @param y          y screen coordinate
     * @param width      header width
     * @param rightInset right side inset width
     */
    static void header(String title, String subtitle, int x, int y, int width, int rightInset) {
        int textWidth = width - 10 - rightInset;

        text(title, x + 10, y + (subtitle.isEmpty() ? 13 : 8), textWidth, INK);
        if (!subtitle.isEmpty()) text(subtitle, x + 10, y + 22, textWidth, MUTED);
    }

    /**
     * Creates a surface drawable with a fill color and border.
     *
     * @param fill   fill color value
     * @param border border color value
     * @return constructed {@link IDrawable} surface instance
     */
    static IDrawable surface(int fill, int border) {
        return (context, x, y, width, height, theme) -> {
            Gui.drawRect(x, y, x + width, y + height, border);
            Gui.drawRect(x + 1, y + 1, x + width - 1, y + height - 1, fill);
        };
    }

    /**
     * Renders trimmed text string at specified coordinates.
     *
     * @param value text string
     * @param x     x coordinate
     * @param y     y coordinate
     * @param width maximum allowed width
     * @param color text color value
     */
    static void text(String value, int x, int y, int width, int color) {
        var font = PixelFont.renderer();
        if (width > 0) font.drawString(font.trimStringToWidth(value, width), x, y, color);
    }

    /**
     * Creates a standard action button widget.
     *
     * @param label  button label supplier
     * @param action click action runnable
     * @return constructed {@link ButtonWidget} instance
     */
    static ButtonWidget<?> button(Supplier<String> label, Runnable action) {
        return button(label, action, () -> false, false);
    }

    /**
     * Creates a close button widget with an 'X' icon.
     *
     * @param action close action runnable
     * @return constructed {@link ButtonWidget} instance
     */
    static ButtonWidget<?> closeButton(Runnable action) {
        ButtonWidget<?> close = new ButtonWidget<>().size(16, 16);
        IDrawable normal = surface(SURFACE, BUTTON_BORDER);
        IDrawable hover = surface(0xFFFDFCF7, 0xFF000000 | ACCENT);
        return close.background(IDrawable.EMPTY).hoverBackground(IDrawable.EMPTY)
                .overlay((context, x, y, width, height, theme) -> {
                    // Keep the surface with the cross so a full-panel chart cannot paint over it.
                    (close.isHovering() ? hover : normal).draw(context, x, y, width, height, theme);
                    int left = x + (width - 6) / 2, top = y + (height - 6) / 2;
                    int ink = 0xFF000000 | (close.isHovering() ? ACCENT : INK);
                    for (int i = 0; i < 6; i++) {
                        Gui.drawRect(left + i, top + i, left + i + 1, top + i + 1, ink);
                        Gui.drawRect(left + 5 - i, top + i, left + 6 - i, top + i + 1, ink);
                    }
                }).onMousePressed(button -> {
                    if (button != 0) return false;
                    action.run();
                    return true;
                });
    }

    /**
     * Creates a configurable button widget supporting selection state and warning indicators.
     *
     * @param label    button label supplier
     * @param action   click action runnable
     * @param selected selected state supplier
     * @param warning  whether warning indicator is enabled
     * @return constructed {@link ButtonWidget} instance
     */
    static ButtonWidget<?> button(Supplier<String> label, Runnable action, BooleanSupplier selected,
                                  boolean warning) {
        IDrawable normal = surface(SURFACE, BUTTON_BORDER);
        IDrawable active = surface(0xFFDCE9E2, 0xFF000000 | ACCENT);
        IDrawable hover = surface(0xFFFDFCF7, 0xFF000000 | ACCENT);
        ButtonWidget<?> control = new ButtonWidget<>();
        return control.background(IDrawable.EMPTY).hoverBackground(IDrawable.EMPTY)
                .overlay((context, x, y, width, height, theme) -> {
                    boolean enabled = selected.getAsBoolean();
                    // Draw the whole control together, including buttons over a model or dialog.
                    (control.isHovering() ? hover : enabled ? active : normal).draw(context, x, y, width, height, theme);
                    if (warning && !enabled)
                        Gui.drawRect(x + width - 5, y + 3, x + width - 3, y + 5, 0xFF000000 | WARNING);
                    var font = PixelFont.renderer();
                    String value = font.trimStringToWidth(label.get(), width - 6);
                    font.drawString(value, x + (width - font.getStringWidth(value)) / 2,
                            y + (height - font.FONT_HEIGHT) / 2 + 1, enabled ? ACCENT : INK);
                }).onMousePressed(button -> {
                    if (button != 0) return false;
                    action.run();
                    return true;
                });
    }
}