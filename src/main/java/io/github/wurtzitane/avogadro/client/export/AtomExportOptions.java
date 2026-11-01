package io.github.wurtzitane.avogadro.client.export;

import io.github.wurtzitane.avogadro.Config;
import io.github.wurtzitane.avogadro.molecule.MoleculeDef;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Configuration options for exporting atom-specific details and media visualizations.
 *
 * @param definition the {@link MoleculeDef} being exported
 * @param atom       the zero-based index of the target atom
 * @param details    {@code true} if detailed inspection attributes are included, {@code false} otherwise
 */
public record AtomExportOptions(MoleculeDef definition, int atom, boolean details) {

    /**
     * Compact constructor for {@link AtomExportOptions} validating atom index bounds and structural data availability.
     *
     * @throws IllegalArgumentException if the atom index is out of bounds or lacks inspection data
     */
    public AtomExportOptions {
        if (atom < 0 || atom >= definition.structure().atoms().size()) {
            throw new IllegalArgumentException("Atom index outside molecule.");
        }
        if (definition.inspection().atom(definition.structure(), atom) == null) {
            throw new IllegalArgumentException("This atom has no supplied nuclear/electron data. Add elements or atom_configurations first.");
        }
    }

    /**
     * Parses command-line arguments and flags to produce a {@link Parsed} export configuration.
     *
     * @param definition the {@link MoleculeDef} being exported
     * @param arguments  array of string arguments and flags
     * @return a new {@link Parsed} containing media export options and atom options
     * @throws IllegalArgumentException if argument parsing fails or validation rules are violated
     */
    static Parsed parse(MoleculeDef definition, String[] arguments) {
        Map<String, String> flags = ExportOptions.flags(arguments, Set.of("atom", "details"));
        int index;
        try {
            index = Integer.parseInt(flags.getOrDefault("atom", "1")) - 1;
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("--atom requires a one-based integer.");
        }
        String detail = flags.getOrDefault("details", Config.ATOM_DETAILS.get().toString());
        if (!detail.equalsIgnoreCase("true") && !detail.equalsIgnoreCase("false")) {
            throw new IllegalArgumentException("--details must be true or false.");
        }
        boolean details = Boolean.parseBoolean(detail);
        flags.remove("atom");
        flags.remove("details");
        flags.putIfAbsent("width", Integer.toString(details ? Config.ATOM_WIDTH.get() : Config.ATOM_HEIGHT.get()));
        flags.putIfAbsent("height", Config.ATOM_HEIGHT.get().toString());
        flags.putIfAbsent("background", "#F4F1E9");
        int defaultFps = Config.ATOM_FPS.get();
        if (!"mp4".equalsIgnoreCase(flags.get("format"))) {
            defaultFps = Math.min(defaultFps, 50);
        }
        flags.putIfAbsent("fps", Integer.toString(defaultFps));
        flags.putIfAbsent("text-scale", Config.ATOM_TEXT_SCALE.get().toString());
        List<String> normal = new ArrayList<>();
        flags.forEach((key, value) -> {
            normal.add("--" + key);
            normal.add(value.contains(" ") ? '"' + value + '"' : value);
        });
        ExportOptions media = ExportOptions.parse(normal.toArray(String[]::new), 1);
        if (details && media.background() == null) {
            throw new IllegalArgumentException("Atom details require a solid background; use --details false for transparent GIFs.");
        }
        return new Parsed(media, new AtomExportOptions(definition, index, details));
    }

    /**
     * Container record holding parsed general media export options and atom-specific export options.
     *
     * @param media the general {@link ExportOptions}
     * @param atom  the atom-specific {@link AtomExportOptions}
     */
    public record Parsed(ExportOptions media, AtomExportOptions atom) {}
}