package io.github.wurtzitane.avogadro.client.export;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.function.ToIntFunction;

import io.github.wurtzitane.avogadro.molecule.MoleculeDef;

/**
 * Validated configuration options for exporting item cards.
 * <p>
 * Item cards share the encoder/timing settings while retaining native GUI proportions.
 *
 * @param media the underlying export media options
 * @param scale GUI pixel scale factor (1..8)
 * @param selection the item export selection details
 * @param layout computed item card layout configuration
 */
public record ItemExportOptions(ExportOptions media, int scale, ItemExportSelection selection, ItemCardLayout layout) {

    /** Compact constructor validating options, scale, and layout fit. */
    public ItemExportOptions {
        java.util.Objects.requireNonNull(media);
        java.util.Objects.requireNonNull(selection);
        java.util.Objects.requireNonNull(layout);
        if (scale < 1 || scale > 8 || media.moleculeCount() != 1 || media.background() == null
                || media.width() % scale != 0 || media.height() % scale != 0) {
            throw new IllegalArgumentException(
                    "Item cards require one molecule, an opaque background, scale 1..8 and dimensions divisible by scale");
        }
        if (layout.width() != media.width() / scale || layout.height() != media.height() / scale
                || layout.modelWidth() < 16 || layout.modelHeight() < 16 || layout.modelLeft() < 0
                || layout.modelTop() < 0 || layout.modelLeft() + layout.modelWidth() > layout.width()
                || layout.modelTop() + layout.modelHeight() > layout.height() || layout.imageSize() <= 0) {
            throw new IllegalArgumentException("Item card layout does not fit its export settings");
        }
    }

    /**
     * Creates an {@link ItemExportOptions} for a given selection, media options, and scale.
     *
     * @param selection item export selection
     * @param media export options
     * @param scale pixel scale factor
     * @return a new validated {@link ItemExportOptions}
     * @throws IllegalArgumentException if scale is out of range
     */
    public static ItemExportOptions of(ItemExportSelection selection, ExportOptions media, int scale) {
        if (scale < 1 || scale > 8) {
            throw new IllegalArgumentException("Item scale must be 1..8");
        }
        return new ItemExportOptions(media, scale, selection,
                ItemCardLayout.create(media, scale, selection.definition(), selection.title(),
                        selection.font()::getStringWidth, selection.font().FONT_HEIGHT));
    }

    /** Internal settings record holding media, scale, and layout during parsing. */
    record Settings(ExportOptions media, int scale, ItemCardLayout layout) {
    }

    /**
     * Parses command-line arguments to construct an {@link ItemExportOptions}.
     *
     * @param selection item export selection
     * @param arguments command-line arguments array
     * @return a new parsed {@link ItemExportOptions}
     */
    public static ItemExportOptions parse(ItemExportSelection selection, String[] arguments) {
        Settings settings = settings(arguments, selection.definition(), selection.title(),
                selection.font()::getStringWidth, selection.font().FONT_HEIGHT);
        return new ItemExportOptions(settings.media(), settings.scale(), selection, settings.layout());
    }

    /**
     * Parses the optional metadata flag value from arguments.
     *
     * @param arguments command-line arguments array
     * @return parsed metadata integer or {@code null}
     */
    static Integer metadata(String[] arguments) {
        String value = itemFlags(arguments).get("meta");
        return value == null ? null : integer(value, "meta", 0, 32767);
    }

    /** Parses and builds item export settings from arguments and molecule definitions. */
    static Settings settings(String[] arguments, MoleculeDef definition, String title, ToIntFunction<String> measure,
            int fontHeight) {
        Map<String, String> flags = new LinkedHashMap<>(itemFlags(arguments));
        if (flags.containsKey("columns")) {
            throw new IllegalArgumentException("--columns is only available for molecule galleries.");
        }
        if (flags.containsKey("meta")) {
            integer(flags.remove("meta"), "meta", 0, 32767);
        }
        int scale = flags.containsKey("scale") ? integer(flags.remove("scale"), "scale", 1, 8) : 2;
        boolean explicitHeight = flags.containsKey("height");
        flags.putIfAbsent("width", Integer.toString(96 * scale));
        flags.putIfAbsent("height", Integer.toString(96 * scale));
        flags.putIfAbsent("background", "#100010");
        flags.putIfAbsent("name", "true");
        flags.putIfAbsent("formula", "false");
        flags.putIfAbsent("text-scale", "1");
        ExportOptions media = media(flags);
        if (media.background() == null) {
            throw new IllegalArgumentException("Item cards need a solid fill. Set --background \"#RRGGBB\".");
        }
        if (media.width() % scale != 0 || media.height() % scale != 0) {
            throw new IllegalArgumentException(
                    "--width and --height must be multiples of --scale for crisp GUI pixels.");
        }
        if (!explicitHeight) {
            int height = ItemCardLayout.defaultHeight(media, scale, title, MediaLayout.displayFormula(definition),
                    measure, fontHeight);
            if (media.format() == ExportOptions.Format.MP4 && (height & 1) != 0) {
                height += scale;
            }
            flags.put("height", Integer.toString(height));
            media = media(flags);
        }
        ItemCardLayout layout = ItemCardLayout.create(media, scale, definition, title, measure, fontHeight);
        return new Settings(media, scale, layout);
    }

    /** Parses item-specific flags including meta and scale. */
    private static Map<String, String> itemFlags(String[] arguments) {
        return ExportOptions.flags(arguments, Set.of("meta", "scale"));
    }

    /** Constructs base ExportOptions from a map of parsed flags. */
    private static ExportOptions media(Map<String, String> flags) {
        ArrayList<String> arguments = new ArrayList<>(flags.size() * 2);
        flags.forEach((name, value) -> {
            arguments.add("--" + name);
            arguments.add(value.indexOf(' ') < 0 ? value : '"' + value + '"');
        });
        return ExportOptions.parse(arguments.toArray(String[]::new), 1);
    }

    /** Parses and validates an integer flag within bounds. */
    private static int integer(String value, String name, int minimum, int maximum) {
        try {
            int parsed = Integer.parseInt(value);
            if (parsed >= minimum && parsed <= maximum) {
                return parsed;
            }
        } catch (NumberFormatException ignored) {
        }
        throw new IllegalArgumentException(
                "--" + name + " must be a whole number from " + minimum + " to " + maximum + ".");
    }
}
