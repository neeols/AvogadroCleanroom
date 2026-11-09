package io.github.wurtzitane.avogadro.client;

import java.util.ArrayList;
import java.util.List;

import com.cleanroommc.modularui.api.UpOrDown;
import com.cleanroommc.modularui.api.widget.Interactable;
import com.cleanroommc.modularui.screen.viewport.ModularGuiContext;
import com.cleanroommc.modularui.theme.WidgetThemeEntry;
import com.cleanroommc.modularui.widget.Widget;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Gui;

import org.lwjgl.opengl.GL11;

/** A measured property sheet shared by the structure examiners. */
abstract class StructureProperties<T extends StructureProperties<T>> extends Widget<T> implements Interactable {

    /**
     * Record representing a rendered text label entry.
     *
     * @param text text content
     * @param x x position
     * @param y y position
     * @param color text color value
     */
    private record Label(String id, String role, String text, int x, int y, int color) {
    }

    /** List of cached text labels to render. */
    private List<Label> labels = List.of();

    /** List of vertical Y positions for horizontal separator lines. */
    private List<Integer> separators = List.of();

    /** Vertical scroll offset in pixels. */
    private int offset;

    /** Cached widget width. */
    private int width = -1;

    /** Cached widget height. */
    private int height = -1;

    /** Calculated total content height in pixels. */
    private int contentHeight;

    /** Cached font revision hash. */
    private long fontRevision = -1;

    /** Cached data revision hash. */
    private long dataRevision = -1;
    private long themeRevision = -1;

    /**
     * Computes the content revision long value for cache invalidation.
     *
     * @return content revision hash
     */
    protected abstract long contentRevision();

    /**
     * Builds the property rows and layout structure.
     *
     * @param rows rows builder helper instance
     */
    protected abstract void build(Rows rows);

    /** Updates and recalculates label placements, layout measurements, and scroll constraints if dirty. */
    private void update() {
        int currentWidth = getArea().width, currentHeight = getArea().height;
        long currentFontRevision = MoleculeCategoryStyle.reloadRevision(), currentDataRevision = contentRevision();
        if (width == currentWidth && height == currentHeight && fontRevision == currentFontRevision
                && dataRevision == currentDataRevision && themeRevision == UiThemes.paintRevision())
            return;
        width = currentWidth;
        height = currentHeight;
        fontRevision = currentFontRevision;
        dataRevision = currentDataRevision;
        themeRevision = UiThemes.paintRevision();
        Rows rows = new Rows(width);
        build(rows);
        labels = List.copyOf(rows.labels);
        separators = List.copyOf(rows.separators);
        contentHeight = rows.y + 10;
        offset = Math.clamp(offset, 0, Math.max(0, contentHeight - height));
    }

    /**
     * Builder helper class for managing property row measurements, text formatting, and line separators.
     */
    protected static final class Rows {

        /** List of accumulated labels. */
        private final List<Label> labels = new ArrayList<>();

        /** List of accumulated separator line positions. */
        private final List<Integer> separators = new ArrayList<>();

        /** Total usable row width. */
        final int width;

        /** Current vertical Y cursor position. */
        int y = 11;
        private int row;

        /**
         * Creates a Rows builder instance.
         *
         * @param panelWidth total container panel width
         */
        Rows(int panelWidth) {
            width = Math.max(1, panelWidth - 24);
        }

        /**
         * Adds an accent-colored heading section label.
         *
         * @param text heading text
         */
        void heading(String text) {
            paragraph(text, StructureStyle.ACCENT());
            y += 5;
        }

        /**
         * Adds a multi-line formatted paragraph of text wrapped to the row width.
         *
         * @param text paragraph text string
         * @param color text color value
         */
        void paragraph(String text, int color) {
            paragraph("row-" + row++, text, color);
        }

        private void paragraph(String id, String text, int color) {
            for (String line : PixelFont.renderer().listFormattedStringToWidth(text, width)) {
                label(id, line, 10, y, color);
                y += 11;
            }
        }

        /** Adds a horizontal separator rule line. */
        void rule() {
            y += 8;
            separators.add(y);
            y += 11;
        }

        /**
         * Adds a key-value property pair entry.
         *
         * @param name property key name
         * @param value property value string
         */
        void property(String name, String value) {
            String id = "row-" + row++;
            var font = PixelFont.renderer();
            if (font.getStringWidth(name) + font.getStringWidth(value) + 8 > width) {
                paragraph(id + "/label", name, StructureStyle.MUTED());
                paragraph(id + "/value", value, StructureStyle.INK());
                y += 3;
            } else {
                label(id + "/label", name, 10, y, StructureStyle.MUTED());
                label(id + "/value", value, 10 + width - font.getStringWidth(value), y, StructureStyle.INK());
                y += 13;
            }
        }

        /**
         * Adds a left-aligned text label.
         *
         * @param text text content
         * @param x x position
         * @param y y position
         * @param color color value
         */
        void label(String text, int x, int y, int color) {
            label("row-" + row++, text, x, y, color);
        }

        private void label(String id, String text, int x, int y, int color) {
            labels.add(new Label(id, UiThemes.textRole(color), text, x, y, UiThemes.originalColor(color)));
        }

        /**
         * Adds a right-aligned text label aligned against the panel boundary.
         *
         * @param text text content
         * @param y y position
         * @param color color value
         */
        void right(String text, int y, int color) {
            label(text, 10 + width - PixelFont.renderer().getStringWidth(text), y, color);
        }
    }

    /**
     * Renders the background, scissor-clipped property rows, rules, and scrollbar.
     *
     * @param context GUI context
     * @param theme widget theme entry
     */
    @Override
    public void draw(ModularGuiContext context, WidgetThemeEntry<?> theme) {
        update();
        Gui.drawRect(0, 0, width, height, StructureStyle.PAPER());
        GL11.glPushAttrib(GL11.GL_SCISSOR_BIT);
        try {
            if (!GuiClip.intersect(1, 1, width - 1, height - 1))
                return;
            for (int y : separators) {
                int position = y - offset;
                if (position >= 0 && position < height)
                    Gui.drawRect(10, position, width - 12, position + 1, StructureStyle.RULE());
            }
            for (Label label : labels) {
                int y = label.y() - offset;
                if (y > -10 && y < height) {
                    try (var ignored = UiThemes.element(label.id())) {
                        StructureStyle.text(label.text(), label.x(), y, width - label.x() - 9,
                                UiThemes.color(label.role(), label.color()));
                    }
                }
            }
        } finally {
            GL11.glPopAttrib();
        }
        if (contentHeight > height) {
            int track = height - 8, thumb = Math.max(14, track * height / contentHeight);
            int top = 4 + (track - thumb) * offset / (contentHeight - height);
            Gui.drawRect(width - 4, 4, width - 2, height - 4, StructureStyle.RULE());
            Gui.drawRect(width - 4, top, width - 2, top + thumb, 0xFF000000 | StructureStyle.ACCENT());
        }
    }

    /**
     * Handles mouse scroll wheel movement to adjust the vertical scroll offset.
     *
     * @param direction scroll direction modifier
     * @param amount scroll movement amount
     * @return {@code true} indicating event handling success
     */
    @Override
    public boolean onMouseScroll(UpOrDown direction, int amount) {
        update();
        offset = Math.clamp(offset - direction.modifier * 26, 0, Math.max(0, contentHeight - height));
        return true;
    }
}
