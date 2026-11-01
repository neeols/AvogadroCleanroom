package io.github.wurtzitane.avogadro.client;

import io.github.wurtzitane.avogadro.api.ElementCatalog;
import java.util.ArrayList;
import java.util.List;

/** The screen, item and wall poster use these same square element cells. */
public final class PeriodicTableLayout {

    /** Base width units for the periodic table layout grid. */
    public static final float WIDTH_UNITS = 19.2f;

    /** Base height units for the periodic table layout grid. */
    public static final float HEIGHT_UNITS = 10.8f;

    /** Left offset padding in units for the element grid. */
    public static final float GRID_LEFT = .8f;

    /** Top offset padding in units for the element grid. */
    public static final float GRID_TOP = 1.25f;

    /** Gap spacing units between main periodic table series rows. */
    public static final float SERIES_GAP = .4f;

    /** Private constructor to prevent instantiation of utility class. */
    private PeriodicTableLayout() {}

    /**
     * Record representing a 2D rectangular screen boundary.
     *
     * @param x      x coordinate of the top-left corner
     * @param y      y coordinate of the top-left corner
     * @param width  rectangle width
     * @param height rectangle height
     */
    public record Rect(float x, float y, float width, float height) {

        /**
         * Checks whether the specified coordinates fall within this rectangle.
         *
         * @param px x coordinate to test
         * @param py y coordinate to test
         * @return {@code true} if points are contained inside, {@code false} otherwise
         */
        public boolean contains(float px, float py) {
            return px >= x && px < x + width && py >= y && py < y + height;
        }
    }

    /**
     * Record representing an individual element cell within the periodic table layout.
     *
     * @param atomicNumber atomic number of the element
     * @param column       grid column index
     * @param row          grid row index
     * @param bounds       rectangular screen bounds of the cell
     */
    public record Cell(int atomicNumber, int column, int row, Rect bounds) {}

    /**
     * Record holding the complete fitted layout metrics and cell listings for the periodic table.
     *
     * @param width    total layout pixel width
     * @param height   total layout pixel height
     * @param left     left layout offset coordinate
     * @param top      top layout offset coordinate
     * @param cellSize size of an individual element cell in pixels
     * @param cells    immutable list of all table element cells
     * @param legend   rectangular bounds for the table legend
     */
    public record Layout(int width, int height, float left, float top, float cellSize,
                         List<Cell> cells, Rect legend) {

        /** Compact constructor copying and immunizing the cell list collection. */
        public Layout {
            cells = List.copyOf(cells);
        }

        /**
         * Retrieves the layout cell corresponding to the given atomic number.
         *
         * @param atomicNumber atomic number to look up
         * @return matching {@link Cell} instance, or {@code null} if not found
         */
        public Cell cell(int atomicNumber) {
            if (atomicNumber >= 1 && atomicNumber <= 118) return cells.get(atomicNumber - 1);
            for (int i = 118; i < cells.size(); i++)
                if (cells.get(i).atomicNumber() == atomicNumber) return cells.get(i);
            return null;
        }

        /**
         * Performs a hit test on the layout at specified pixel coordinates.
         *
         * @param x x pixel coordinate
         * @param y y pixel coordinate
         * @return atomic number of the hit cell, or {@code -1} if none matched
         */
        public int hit(float x, float y) {
            for (Cell cell : cells) if (cell.bounds.contains(x, y)) return cell.atomicNumber;
            return -1;
        }

        /**
         * Converts layout grid units to x pixel coordinates.
         *
         * @param units horizontal units
         * @return corresponding x pixel coordinate
         */
        public float x(float units) { return left + units * cellSize; }

        /**
         * Converts layout grid units to y pixel coordinates.
         *
         * @param units vertical units
         * @return corresponding y pixel coordinate
         */
        public float y(float units) { return top + units * cellSize; }
    }

    /**
     * Calculates the total height units required, including any additional element rows.
     *
     * @return total height in units
     */
    public static float heightUnits() {
        int rows = (ElementCatalog.additionalElements().size() + 17) / 18;
        return HEIGHT_UNITS + (rows == 0 ? 0 : rows + .7f);
    }

    /**
     * Fits the periodic table layout within specified pixel dimensions.
     *
     * @param width  target container width
     * @param height target container height
     * @return generated {@link Layout} instance
     */
    public static Layout fit(int width, int height) {
        if (width < 1 || height < 1) throw new IllegalArgumentException("Table dimensions must be positive");
        float heightUnits = heightUnits();
        float size = Math.min(width / WIDTH_UNITS, height / heightUnits);
        float left = (width - WIDTH_UNITS * size) / 2;
        float top = (height - heightUnits * size) / 2;
        List<Cell> cells = new ArrayList<>(118);
        for (int z = 1; z <= 118; z++) {
            int row, column;
            if (z == 1 || z == 2) {
                row = 0; column = z == 1 ? 0 : 17;
            } else if (z <= 10) {
                row = 1; column = z <= 4 ? z - 3 : z + 7;
            } else if (z <= 18) {
                row = 2; column = z <= 12 ? z - 11 : z - 1;
            } else if (z <= 36) {
                row = 3; column = z - 19;
            } else if (z <= 54) {
                row = 4; column = z - 37;
            } else if (z <= 57) {
                row = 5; column = z - 55;
            } else if (z <= 71) {
                row = 7; column = z - 55;
            } else if (z <= 86) {
                row = 5; column = z - 69;
            } else if (z <= 89) {
                row = 6; column = z - 87;
            } else if (z <= 103) {
                row = 8; column = z - 87;
            } else {
                row = 6; column = z - 101;
            }
            float y = GRID_TOP + row + (row >= 7 ? SERIES_GAP : 0);
            cells.add(new Cell(z, column, row,
                    new Rect(left + (GRID_LEFT + column) * size, top + y * size, size, size)));
        }
        List<ElementCatalog.Element> additional = ElementCatalog.additionalElements();
        for (int i = 0; i < additional.size(); i++) {
            int column = i % 18, row = 9 + i / 18;
            cells.add(new Cell(additional.get(i).atomicNumber(), column, row,
                    new Rect(left + (GRID_LEFT + column) * size,
                            top + (GRID_TOP + row + SERIES_GAP + .5f) * size, size, size)));
        }
        return new Layout(width, height, left, top, size, cells,
                new Rect(left + 3.8f * size, top + 1.43f * size, 8.35f * size, 2.55f * size));
    }
}