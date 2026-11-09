package io.github.wurtzitane.avogadro.client.export;

import java.util.ArrayList;
import java.util.List;
import java.util.function.ToIntFunction;

import io.github.wurtzitane.avogadro.client.MoleculeText;
import io.github.wurtzitane.avogadro.client.MoleculeViewport;
import io.github.wurtzitane.avogadro.molecule.MoleculeDef;

import net.minecraft.client.gui.FontRenderer;

/**
 * Validated layout configurations for rendering item cards.
 * <p>
 * Native GUI coordinates; the capture target applies one integer pixel scale.
 *
 * @param width total card width in logical pixels
 * @param height total card height in logical pixels
 * @param modelLeft model viewport left offset in logical pixels
 * @param modelTop model viewport top offset in logical pixels
 * @param modelWidth model viewport width in logical pixels
 * @param modelHeight model viewport height in logical pixels
 * @param imageSize rendered image size dimension
 * @param labels list of card text labels
 */
public record ItemCardLayout(int width, int height, int modelLeft, int modelTop, int modelWidth, int modelHeight,
        int imageSize, List<Label> labels) {

    /**
     * Represents a single rendered text label on the item card.
     *
     * @param line the formatted text line content
     * @param left horizontal coordinate offset
     * @param top vertical coordinate offset
     * @param textScale scale factor applied to the text
     * @param formula {@code true} if this label represents a chemical formula
     */
    public record Label(MoleculeText.Line line, float left, float top, float textScale, boolean formula) {
    }

    /** Compact constructor ensuring label lists are immutable copies. */
    public ItemCardLayout {
        labels = List.copyOf(labels);
    }

    /**
     * Creates and computes an {@link ItemCardLayout} for the given export options and molecule definition.
     *
     * @param options export configuration options
     * @param scale pixel scaling factor
     * @param definition molecule definition
     * @param title display title string
     * @param measure function measuring text width in pixels
     * @param fontHeight standard font height
     * @return a new validated {@link ItemCardLayout}
     * @throws IllegalArgumentException if the card dimensions or space allocation are invalid
     */
    public static ItemCardLayout create(ExportOptions options, int scale, MoleculeDef definition, String title,
            ToIntFunction<String> measure, int fontHeight) {
        int width = options.width() / scale, height = options.height() / scale;
        int modelWidth = width - 32;
        if (modelWidth < 16) {
            throw new IllegalArgumentException(
                    "The item card needs at least 48 logical pixels of width. Increase --width or reduce --scale.");
        }
        List<Label> labels = labels(options, title, MediaLayout.displayFormula(definition), width, measure, fontHeight);
        int modelTop = modelTop(labels, fontHeight, width);
        int modelHeight = height - modelTop - 10;
        if (modelHeight < 16) {
            throw new IllegalArgumentException(
                    "The labels leave less than 16 logical pixels for the model. Increase --height, reduce --text-scale or hide a label.");
        }
        int imageSize = MoleculeViewport.fitSize(definition.structure(), 1_000_000, modelWidth, modelHeight,
                options.cubic());
        return new ItemCardLayout(width, height, 16, modelTop, modelWidth, modelHeight, imageSize, labels);
    }

    /**
     * Calculates the default card height based on labels and options.
     *
     * @param options export options
     * @param scale pixel scale factor
     * @param title card title
     * @param formula chemical formula string
     * @param measure text measurement function
     * @param fontHeight standard font height
     * @return default height in pixels
     */
    static int defaultHeight(ExportOptions options, int scale, String title, String formula,
            ToIntFunction<String> measure, int fontHeight) {
        List<Label> labels = labels(options, title, formula, options.width() / scale, measure, fontHeight);
        return Math.max(96, modelTop(labels, fontHeight, options.width() / scale) + 64 + 10) * scale;
    }

    /** Generates wrapped and positioned label list entries. */
    private static List<Label> labels(ExportOptions options, String title, String formula, int width,
            ToIntFunction<String> measure, int fontHeight) {
        float textScale = (float) options.textScale();
        int left = iconSize(width) + 10;
        float available = (width - left - 4) / textScale;
        if (available < 1) {
            throw new IllegalArgumentException("The item card is too narrow for its labels.");
        }
        List<Label> result = new ArrayList<>();
        float cursor = 4;
        if (options.showName()) {
            for (String line : wrap(title, available, measure)) {
                result.add(new Label(MoleculeText.plain(line, measure, fontHeight), left, cursor, textScale, false));
                cursor += fontHeight * textScale;
            }
        }
        if (options.showFormula() && !formula.isBlank()) {
            MoleculeText.Line line = MoleculeText.formula(formula, measure, fontHeight);
            if (!result.isEmpty()) {
                cursor += textScale;
            }
            float fit = Math.min(textScale, (width - left - 4) / Math.max(1f, line.width()));
            result.add(new Label(line, left, cursor, fit, true));
        }
        return List.copyOf(result);
    }

    /** Calculates the vertical top coordinate where the model viewport should begin. */
    private static int modelTop(List<Label> labels, int fontHeight, int width) {
        int iconBottom = iconSize(width) + 6;
        if (labels.isEmpty()) {
            return iconBottom;
        }
        Label last = labels.getLast();
        float height = last.formula() ? last.line().height() : Math.max(1, fontHeight - 1);
        return Math.max(iconBottom, 14 + (int) Math.ceil(last.top() - 4 + height * last.textScale()));
    }

    /** Returns the header's item artwork size in logical pixels, using whole native sprite scales. */
    public int iconSize() {
        return iconSize(width);
    }

    private static int iconSize(int width) {
        return width >= 128 ? 32 : 16;
    }

    /**
     * Preserves native formatting across every wrapped line; never split a section-code pair.
     *
     * @param text text string to wrap
     * @param available available horizontal width
     * @param measure text measurement function
     * @return list of wrapped text lines
     */
    static List<String> wrap(String text, float available, ToIntFunction<String> measure) {
        List<String> result = new ArrayList<>();
        String remaining = text;
        while (!remaining.isEmpty()) {
            if (measure.applyAsInt(remaining) <= available) {
                result.add(remaining);
                break;
            }
            int end = 0, space = -1;
            boolean glyph = false;
            for (int index = 0; index < remaining.length();) {
                if (remaining.charAt(index) == '\u00a7' && index + 1 < remaining.length()) {
                    index += 2;
                    continue;
                }
                int next = index + Character.charCount(remaining.codePointAt(index));
                if (measure.applyAsInt(remaining.substring(0, next)) > available) {
                    break;
                }
                if (remaining.charAt(index) == ' ') {
                    space = index;
                }
                end = next;
                glyph = true;
                index = next;
            }
            if (!glyph) {
                throw new IllegalArgumentException(
                        "A title glyph is wider than this item card. Increase --width or reduce --text-scale.");
            }
            int split = space > 0 ? space : end;
            String line = remaining.substring(0, split).stripTrailing();
            result.add(line);
            remaining = FontRenderer.getFormatFromString(line) + remaining.substring(split).stripLeading();
        }
        return List.copyOf(result);
    }
}
