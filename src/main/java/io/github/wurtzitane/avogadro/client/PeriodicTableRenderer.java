package io.github.wurtzitane.avogadro.client;

import io.github.wurtzitane.avogadro.api.ElementCatalog;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.FontRenderer;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.resources.I18n;

import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL20;

/** One code-drawn table, including its legend, is shared by all three presentations. */
public final class PeriodicTableRenderer {

    /** Ink text color constant. */
    public static final int INK = 0xFF000000 | StructureStyle.INK;

    /** Muted text color constant. */
    public static final int MUTED = 0xFF000000 | StructureStyle.MUTED;

    /** Grid line border color constant. */
    public static final int GRID = StructureStyle.BORDER;

    /** Panel background color constant. */
    public static final int PANEL = StructureStyle.PANEL;

    /** Border color constant. */
    public static final int BORDER = StructureStyle.BORDER;

    /** Accent highlight color constant. */
    public static final int ACCENT = 0xFF000000 | StructureStyle.ACCENT;

    /** Array of category classification strings. */
    private static final String[] CATEGORIES = {"alkali", "alkaline", "transition", "metal", "metalloid", "nonmetal",
            "halogen", "noble", "rare-earth", "actinoid"};

    /** Precalculated array of category color values. */
    private static final int[] CATEGORY_COLORS = categoryColors();

    private PeriodicTableRenderer() {}

    /**
     * Renders the periodic table layout, elements, legend, and highlights.
     *
     * @param layout periodic table layout configuration
     * @param hovered atomic number of the currently hovered element cell
     * @param selected atomic number of the currently selected element cell
     */
    public static void draw(PeriodicTableLayout.Layout layout, int hovered, int selected) {
        try (var ignored = new PeriodicTableGraphics()) {
            GL20.glUseProgram(0);
            GL11.glDisable(GL11.GL_LIGHTING);
            GL11.glDisable(GL11.GL_DEPTH_TEST);
            GL11.glDepthMask(false);
            GL11.glDisable(GL11.GL_CULL_FACE);
            PeriodicTableGraphics.activeTexture(GL13.GL_TEXTURE1);
            GL11.glDisable(GL11.GL_TEXTURE_2D);
            PeriodicTableGraphics.activeTexture(GL13.GL_TEXTURE0);
            float size = layout.cellSize();
            float border = Math.max(.5f, size * .025f);
            rect(0, 0, layout.width(), layout.height(), StructureStyle.BORDER());
            rect(border, border, layout.width() - border * 2, layout.height() - border * 2, StructureStyle.PANEL());
            rect(layout.x(.7f), layout.y(.8f), size * 18.2f, size * 9.9f, StructureStyle.VIEW_BORDER());
            rect(layout.x(.7f) + border, layout.y(.8f) + border, size * 18.2f - border * 2, size * 9.9f - border * 2,
                    StructureStyle.PAPER());
            text(I18n.format("avogadro.periodic_table.title"), layout.x(.8f), layout.y(.24f), size * .43f, size * 16.6f,
                    UiThemes.color("text.title", StructureStyle.INK));
            for (int group = 1; group <= 18; group++) {
                int topRow = group <= 2 || group == 18 ? 0 : group <= 12 ? 3 : 1;
                float y = layout.y(PeriodicTableLayout.GRID_TOP + topRow - .32f);
                centered(Integer.toString(group), layout.x(PeriodicTableLayout.GRID_LEFT + group - .5f), y, size * .24f,
                        StructureStyle.MUTED());
            }
            for (int period = 1; period <= 7; period++)
                centered(Integer.toString(period), layout.x(.38f),
                        layout.y(PeriodicTableLayout.GRID_TOP + period - .63f), size * .27f, StructureStyle.MUTED());
            for (int row = 7; row <= 8; row++) {
                float y = layout.y(PeriodicTableLayout.GRID_TOP + row + PeriodicTableLayout.SERIES_GAP + .36f);
                centered(Integer.toString(row - 1), layout.x(.38f), y, size * .25f, StructureStyle.MUTED());
                text(I18n
                        .format(row == 7 ? "avogadro.periodic_table.lanthanoids" : "avogadro.periodic_table.actinoids"),
                        layout.x(.8f), y, size * .25f, size * 2.7f, StructureStyle.MUTED());
            }
            for (var cell : layout.cells()) {
                var element = ElementCatalog.byAtomicNumber(cell.atomicNumber());
                try (var ignoredCell = UiThemes.element("element/" + element.symbol())) {
                int tile = ElementCard.color(element.symbol());
                var box = cell.bounds();
                float line = Math.max(.42f, size * .025f);
                rect(box.x(), box.y(), box.width(), box.height(), UiThemes.color("table.grid", GRID));
                rect(box.x() + line, box.y() + line, box.width() - line * 2, box.height() - line * 2, tile);
                text(Integer.toString(element.atomicNumber()), box.x() + size * .075f, box.y() + size * .055f,
                        size * .23f, size * .82f, ElementCard.numberColor(tile));
                centered(element.symbol(), box.x() + size * .5f, box.y() + size * .365f, size * .48f,
                        ElementCard.symbolColor(tile));
                }
            }
            legend(layout);
            outline(highlightedCell(layout, selected), size * .065f, StructureStyle.ACCENT());
            if (hovered != selected)
                hoverOutline(layout, hovered);
        }
    }

    /**
     * Renders the periodic table category color legend.
     *
     * @param layout periodic table layout configuration
     */
    private static void legend(PeriodicTableLayout.Layout layout) {
        var box = layout.legend();
        float size = layout.cellSize(), pitch = size * .49f;
        for (int i = 0; i < CATEGORIES.length; i++) {
            int column = i / 5, row = i % 5;
            float x = box.x() + column * size * 4.48f, y = box.y() + row * pitch;
            String category = CATEGORIES[i];
            rect(x, y + size * .025f, size * .27f, size * .27f, UiThemes.color("table.grid", GRID));
            rect(x + size * .02f, y + size * .045f, size * .23f, size * .23f, UiThemes.color("table.category." + category, CATEGORY_COLORS[i]));
            text(I18n.format("avogadro.periodic_table.category." + category), x + size * .4f, y, size * .255f,
                    size * (column == 0 ? 3.9f : 3.5f), StructureStyle.MUTED());
        }
    }

    /**
     * Computes representative color values for each element category.
     *
     * @return array of category color integers
     */
    private static int[] categoryColors() {
        int[] colors = new int[CATEGORIES.length];
        java.util.Arrays.fill(colors, StructureStyle.PAPER());
        for (int i = 0; i < CATEGORIES.length; i++) {
            for (var element : ElementCatalog.elements()) {
                if (!CATEGORIES[i].equals(element.category()))
                    continue;
                colors[i] = element.color();
                break;
            }
        }
        return colors;
    }

    /**
     * Renders an outline highlight around a hovered element cell.
     *
     * @param layout periodic table layout configuration
     * @param hovered atomic number of the hovered element
     */
    static void hoverOutline(PeriodicTableLayout.Layout layout, int hovered) {
        outline(highlightedCell(layout, hovered), layout.cellSize() * .06f, StructureStyle.INK());
    }

    /**
     * Returns the layout cell corresponding to an atomic number highlight.
     *
     * @param layout periodic table layout configuration
     * @param atomicNumber atomic number
     * @return matching cell, or {@code null} if invalid
     */
    static PeriodicTableLayout.Cell highlightedCell(PeriodicTableLayout.Layout layout, int atomicNumber) {
        return atomicNumber >= 0 ? layout.cell(atomicNumber) : null;
    }

    /**
     * Renders a rectangular border outline around an element cell.
     *
     * @param cell target cell
     * @param line line thickness
     * @param color outline color value
     */
    private static void outline(PeriodicTableLayout.Cell cell, float line, int color) {
        if (cell == null)
            return;
        var box = cell.bounds();
        rect(box.x(), box.y(), box.width(), line, color);
        rect(box.x(), box.y() + box.height() - line, box.width(), line, color);
        rect(box.x(), box.y(), line, box.height(), color);
        rect(box.x() + box.width() - line, box.y(), line, box.height(), color);
    }

    /**
     * Renders a solid color rectangle.
     *
     * @param x x screen coordinate
     * @param y y screen coordinate
     * @param width rectangle width
     * @param height rectangle height
     * @param color color value
     */
    static void rect(float x, float y, float width, float height, int color) {
        GlStateManager.disableTexture2D();
        GL11.glDisable(GL11.GL_TEXTURE_2D);
        GlStateManager.color((color >>> 16 & 255) / 255f, (color >>> 8 & 255) / 255f, (color & 255) / 255f,
                (color >>> 24) / 255f);
        GL11.glColor4ub((byte) (color >>> 16), (byte) (color >>> 8), (byte) color, (byte) (color >>> 24));
        GL11.glBegin(GL11.GL_QUADS);
        GL11.glVertex2f(x, y);
        GL11.glVertex2f(x, y + height);
        GL11.glVertex2f(x + width, y + height);
        GL11.glVertex2f(x + width, y);
        GL11.glEnd();
    }

    /**
     * Renders a centered text string.
     *
     * @param value string text value
     * @param center center x coordinate
     * @param y y coordinate
     * @param height font height
     * @param color text color value
     */
    private static void centered(String value, float center, float y, float height, int color) {
        FontRenderer font = PixelFont.renderer();
        float scale = height / font.FONT_HEIGHT;
        text(value, center - font.getStringWidth(value) * scale / 2, y, height, Float.MAX_VALUE, color);
    }

    /**
     * Renders scaled text within constraints.
     *
     * @param value string text value
     * @param x x screen coordinate
     * @param y y screen coordinate
     * @param height font height
     * @param maxWidth maximum allowed width constraint
     * @param color text color value
     */
    private static void text(String value, float x, float y, float height, float maxWidth, int color) {
        FontRenderer font = PixelFont.renderer();
        if (value.isEmpty())
            return;
        float scale = Math.min(height / font.FONT_HEIGHT, maxWidth / Math.max(1, font.getStringWidth(value)));
        PeriodicTableGraphics.textState();
        GlStateManager.pushMatrix();
        GlStateManager.translate(x, y, 0);
        GlStateManager.scale(scale, scale, 1);
        font.drawString(value, 0, 0, color, false);
        GlStateManager.popMatrix();
        GlStateManager.color(1, 1, 1, 1);
    }
}
