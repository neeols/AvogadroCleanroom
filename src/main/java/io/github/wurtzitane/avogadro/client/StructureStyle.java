package io.github.wurtzitane.avogadro.client;

import java.util.function.BooleanSupplier;
import java.util.function.Supplier;
import java.util.function.IntSupplier;

import com.cleanroommc.modularui.api.drawable.IDrawable;
import com.cleanroommc.modularui.widgets.ButtonWidget;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Gui;

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

    private StructureStyle() {}

    // The constants above remain the legacy defaults; draws resolve the current menu's colors.
    static int INK() { return UiThemes.color("text.primary", INK); }
    static int MUTED() { return UiThemes.color("text.muted", MUTED); }
    static int ACCENT() { return UiThemes.color("text.accent", ACCENT); }
    static int WARNING() { return UiThemes.color("text.warning", WARNING); }
    static int PANEL() { return UiThemes.color("window.background", PANEL); }
    static int BORDER() { return UiThemes.color("window.border", BORDER); }
    static int SURFACE() { return UiThemes.color("surface.background", SURFACE); }
    static int PAPER() { return UiThemes.color("frame.background", PAPER); }
    static int RULE() { return UiThemes.color("rule", RULE); }
    static int VIEW_BORDER() { return UiThemes.color("frame.border", VIEW_BORDER); }
    static int NOTICE() { return UiThemes.color("notice.background", NOTICE); }
    static int NOTICE_BORDER() { return UiThemes.color("notice.border", NOTICE_BORDER); }

    /** Resolves a surface when it is drawn, so an already-open detached viewer can change themes. */
    static IDrawable surface(IntSupplier fill, IntSupplier border) {
        return (context, x, y, width, height, theme) -> {
            Gui.drawRect(x, y, x + width, y + height, border.getAsInt());
            Gui.drawRect(x + 1, y + 1, x + width - 1, y + height - 1, fill.getAsInt());
        };
    }

    /** Draws an input without freezing its colors or focus outline at construction time. */
    static IDrawable inputSurface(BooleanSupplier focused) {
        return surface(() -> UiThemes.color("input.background", PANEL),
                () -> UiThemes.color(focused.getAsBoolean() ? "input.focus.border" : "input.border", BORDER));
    }

    /**
     * Renders a standard header with a title and subtitle.
     *
     * @param title header title string
     * @param subtitle header subtitle string
     * @param x x screen coordinate
     * @param y y screen coordinate
     * @param width header width
     */
    static void header(String title, String subtitle, int x, int y, int width) {
        header(title, subtitle, x, y, width, 60);
    }

    /**
     * Renders a header with a title, subtitle, width, and right inset constraint.
     *
     * @param title header title string
     * @param subtitle header subtitle string
     * @param x x screen coordinate
     * @param y y screen coordinate
     * @param width header width
     * @param rightInset right side inset width
     */
    static void header(String title, String subtitle, int x, int y, int width, int rightInset) {
        int textWidth = width - 10 - rightInset;

        label("title", title, x + 10, y + (subtitle.isEmpty() ? 13 : 8), textWidth,
                UiThemes.color("text.title", INK));
        if (!subtitle.isEmpty())
            label("subtitle", subtitle, x + 10, y + 22, textWidth, MUTED());
    }

    /**
     * Creates a surface drawable with a fill color and border.
     *
     * @param fill fill color value
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
     * @param x x coordinate
     * @param y y coordinate
     * @param width maximum allowed width
     * @param color text color value
     */
    static void text(String value, int x, int y, int width, int color) {
        var font = PixelFont.renderer();
        if (width > 0)
            font.drawString(font.trimStringToWidth(value, width), x, y, color);
    }

    /** Draws a separately selectable label without tying its theme address to scrolling or wrapping. */
    static void label(String name, String value, int x, int y, int width, int color) {
        String role = UiThemes.textRole(color);
        try (var ignored = UiThemes.element(name)) {
            text(value, x, y, width, UiThemes.color(role, UiThemes.originalColor(color)));
        }
    }

    /**
     * Creates a standard action button widget.
     *
     * @param label button label supplier
     * @param action click action runnable
     * @return constructed {@link ButtonWidget}
     */
    static ButtonWidget<?> button(Supplier<String> label, Runnable action) {
        return button(label, action, () -> false, false);
    }

    /**
     * Creates a close button widget with an 'X' icon.
     *
     * @param action close action runnable
     * @return constructed {@link ButtonWidget}
     */
    static ButtonWidget<?> closeButton(Runnable action) {
        ButtonWidget<?> close = new ButtonWidget<>().size(16, 16);
        IDrawable normal = buttonSurface("");
        IDrawable hover = buttonSurface("hover.");
        return close.background(IDrawable.EMPTY).hoverBackground(IDrawable.EMPTY)
                .overlay((context, x, y, width, height, theme) -> {
                    // Keep the surface with the cross so a full-panel chart cannot paint over it.
                    boolean hovered = UiThemes.hovered(close.isHovering());
                    (hovered ? hover : normal).draw(context, x, y, width, height, theme);
                    int left = x + (width - 6) / 2, top = y + (height - 6) / 2;
                    int ink = UiThemes.color(hovered ? "button.hover.text" : "button.text", INK);
                    for (int i = 0; i < 6; i++) {
                        Gui.drawRect(left + i, top + i, left + i + 1, top + i + 1, ink);
                        Gui.drawRect(left + 5 - i, top + i, left + 6 - i, top + i + 1, ink);
                    }
                }).onMousePressed(button -> {
                    if (button != 0)
                        return false;
                    action.run();
                    return true;
                });
    }

    /**
     * Creates a configurable button widget supporting selection state and warning indicators.
     *
     * @param label button label supplier
     * @param action click action runnable
     * @param selected selected state supplier
     * @param warning whether warning indicator is enabled
     * @return constructed {@link ButtonWidget}
     */
    static ButtonWidget<?> button(Supplier<String> label, Runnable action, BooleanSupplier selected, boolean warning) {
        IDrawable normal = buttonSurface("");
        IDrawable active = buttonSurface("selected.");
        IDrawable hover = buttonSurface("hover.");
        ButtonWidget<?> control = new ButtonWidget<>();
        return control.background(IDrawable.EMPTY).hoverBackground(IDrawable.EMPTY)
                .overlay((context, x, y, width, height, theme) -> {
                    boolean enabled = selected.getAsBoolean();
                    // Draw the whole control together, including buttons over a model or dialog.
                    boolean hovered = UiThemes.hovered(control.isHovering());
                    (hovered ? hover : enabled ? active : normal).draw(context, x, y, width, height,
                            theme);
                    if (warning && !enabled)
                        Gui.drawRect(x + width - 5, y + 3, x + width - 3, y + 5, WARNING());
                    var font = PixelFont.renderer();
                    String value = font.trimStringToWidth(label.get(), width - 6);
                    font.drawString(value, x + (width - font.getStringWidth(value)) / 2,
                            y + (height - font.FONT_HEIGHT) / 2 + 1,
                            UiThemes.color("button." + (hovered ? "hover." : enabled ? "selected." : "") + "text", INK));
                }).onMousePressed(button -> {
                    if (button != 0)
                        return false;
                    action.run();
                    return true;
                });
    }

    /** Draws state-specific button colors at the owning widget's theme address. */
    private static IDrawable buttonSurface(String state) {
        return (context, x, y, width, height, theme) -> {
            int fill = UiThemes.color("button." + state + "background", SURFACE);
            int outline = UiThemes.color("button." + state + "border", BUTTON_BORDER);
            Gui.drawRect(x, y, x + width, y + height, outline);
            Gui.drawRect(x + 1, y + 1, x + width - 1, y + height - 1, fill);
        };
    }
}
