package io.github.wurtzitane.avogadro.client.export;

import java.util.ArrayList;
import java.util.List;
import java.util.function.ToIntFunction;

import io.github.wurtzitane.avogadro.client.MoleculeText;
import io.github.wurtzitane.avogadro.client.MoleculeViewport;
import io.github.wurtzitane.avogadro.molecule.MoleculeDef;

/**
 * Layout calculator for multi-molecule media export galleries.
 * <p>
 * Measured once, so labels, scale and viewport remain fixed for the whole loop.
 */
public final class MediaLayout {

    /**
     * Represents a rendered text label inside a gallery cell.
     *
     * @param line the formatted text line content
     * @param scale scale factor applied to the label
     * @param top vertical coordinate offset
     * @param formula {@code true} if this label represents a chemical formula
     */
    public record Label(MoleculeText.Line line, float scale, float top, boolean formula) {

        /** Returns the list of text glyphs for this label. */
        public List<MoleculeText.Glyph> glyphs() {
            return line.glyphs();
        }

        /** Returns the measured width of this label in pixels. */
        public float width() {
            return line.width();
        }
    }

    /**
     * Represents a single layout cell within a gallery grid.
     *
     * @param definition molecule definition
     * @param left cell left coordinate
     * @param top cell top coordinate
     * @param width cell width in pixels
     * @param height cell height in pixels
     * @param modelLeft model viewport left coordinate
     * @param modelTop model viewport top coordinate
     * @param modelWidth model viewport width in pixels
     * @param modelHeight model viewport height in pixels
     * @param imageSize rendered image size dimension
     * @param labels list of cell text labels
     */
    public record Cell(MoleculeDef definition, int left, int top, int width, int height, int modelLeft, int modelTop,
            int modelWidth, int modelHeight, int imageSize, List<Label> labels) {
    }

    private MediaLayout() {}

    /**
     * Creates and computes a list of gallery cells for all exported molecules.
     *
     * @param options export configuration options
     * @param definitions list of molecule definitions
     * @param names function mapping molecule definitions to name strings
     * @param measure text measurement function for width calculation
     * @param fontHeight standard font height
     * @return an immutable list of layout {@link Cell} objects
     */
    public static List<Cell> create(ExportOptions options, List<MoleculeDef> definitions,
            java.util.function.Function<MoleculeDef, String> names, ToIntFunction<String> measure, int fontHeight) {
        List<Cell> cells = new ArrayList<>();
        int columns = Math.min(options.columns(), definitions.size());
        int rows = (definitions.size() + columns - 1) / columns;
        for (int index = 0; index < definitions.size(); index++) {
            int column = index % columns, row = index / columns;
            int left = column * options.width() / columns, top = row * options.height() / rows;
            int width = (column + 1) * options.width() / columns - left;
            int height = (row + 1) * options.height() / rows - top;
            MoleculeDef definition = definitions.get(index);
            int padding = Math.max(5, Math.min(width, height) / 20);
            int textWidth = width - padding * 2;
            float cursor = top + padding;
            float nameScale = Math.clamp(Math.min(width, height) / 200f, 1f, 3.5f) * (float) options.textScale();
            float formulaScale = nameScale * .8f;
            String name = options.showName() ? names.apply(definition) : "";
            List<String> nameLines = MoleculeText.wrap(name, textWidth / nameScale, measure);
            MoleculeText.Line formula = options.showFormula() && !definition.formula().isBlank()
                    ? MoleculeText.plain(displayFormula(definition), measure, fontHeight)
                    : null;
            if (formula != null) {
                formulaScale = Math.min(formulaScale, textWidth / Math.max(1f, formula.width()));
            }
            float formulaHeight = formula == null ? 0 : Math.max(fontHeight + 3, formula.height() + 2);
            float textHeight = nameLines.size() * (fontHeight + 2) * nameScale + formulaHeight * formulaScale;
            float textBudget = (height - padding * 2) * .5f;
            if (textHeight > textBudget) {
                float fit = textBudget / textHeight;
                nameScale *= fit;
                formulaScale *= fit;
                nameLines = MoleculeText.wrap(name, textWidth / nameScale, measure);
            }
            List<Label> labels = new ArrayList<>();
            for (String line : nameLines) {
                labels.add(new Label(MoleculeText.plain(line, measure, fontHeight), nameScale, cursor, false));
                cursor += (fontHeight + 2) * nameScale;
            }
            if (formula != null) {
                labels.add(new Label(formula, formulaScale, cursor, true));
                cursor += formulaHeight * formulaScale;
            }
            int modelTop = labels.isEmpty() ? top + padding : (int) Math.ceil(cursor) + padding;
            int modelHeight = Math.max(1, top + height - padding - modelTop);
            int modelWidth = width - padding * 2;
            int size = MoleculeViewport.fitSize(definition.structure(), 1_000_000, modelWidth, modelHeight,
                    options.cubic());
            cells.add(new Cell(definition, left, top, width, height, left + padding, modelTop, modelWidth, modelHeight,
                    size, List.copyOf(labels)));
        }
        return List.copyOf(cells);
    }

    /**
     * Resolves and formats the display formula string for a molecule definition.
     *
     * @param definition the molecule definition
     * @return formatted chemical formula string
     */
    static String displayFormula(MoleculeDef definition) {
        if (definition.inspection().metadata()
                .containsKey(io.github.wurtzitane.avogadro.api.PolymerNetworkDefinition.METADATA_KEY)) {
            return io.github.wurtzitane.avogadro.item.MoleculeItems.displayFormula(definition);
        }
        return io.github.wurtzitane.avogadro.api.ChemicalFormula.display(definition.formula());
    }
}
