package io.github.wurtzitane.avogadro.theme;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.ArrayList;
import java.util.Map;
import java.util.Comparator;

import com.google.gson.JsonParser;

/**
 * Discovers bundled themes and user JSON files. Both the client and Theme Creator use the same loading rules.
 * Bundled files are listed by the build, so adding a JSON to the source themes folder needs no Java registration.
 */
public final class ThemeStore {
    /** Available theme-picker orders, shared with the standalone editor. */
    public enum Order { RAINBOW, NAME, BRIGHTNESS }
    /** Folder containing the editable base theme and the user themes subfolder. */
    private final Path directory;
    private final ThemeDefinition bundledDefault;
    private ThemeDefinition defaults;
    private Map<String, Entry> entries = Map.of();
    private List<String> problems = List.of();

    /**
     * A selectable, validated theme.
     *
     * @param id filename without extension; default_theme identifies the editable base
     * @param definition original authoring data
     * @param compiled resolved inherited colors
     * @param file user file, or null for a bundled theme
     */
    public record Entry(String id, ThemeDefinition definition, ThemeDefinition.Compiled compiled, Path file) {}

    /**
     * Creates the user folders and a default_theme.json if it does not already exist.
     * Existing files are never overwritten.
     *
     * @param directory normally config/avogadro
     * @throws IOException if the folders or bundled default cannot be read
     */
    public ThemeStore(Path directory) throws IOException {
        this.directory = directory.toAbsolutePath().normalize();
        bundledDefault = bundled("default_theme.json");
        Files.createDirectories(themesDirectory());
        if (!Files.exists(defaultFile())) {
            try (var writer = Files.newBufferedWriter(defaultFile(), StandardCharsets.UTF_8)) {
                bundledDefault.write(writer);
            }
        }
        reload();
    }

    /**
     * Refreshes discovery. A malformed file is reported and does not replace its last valid entry.
     * Local files override bundled themes with the same filename.
     */
    public void reload() {
        List<String> errors = new ArrayList<>();
        ThemeDefinition base = bundledDefault;
        try (Reader reader = Files.newBufferedReader(defaultFile(), StandardCharsets.UTF_8)) {
            base = ThemeDefinition.read(reader);
            base.compile(bundledDefault);
        } catch (IOException | RuntimeException failure) {
            errors.add(defaultFile() + ": " + failure.getMessage());
            if (defaults != null) base = defaults;
        }
        // Newly introduced roles inherit bundled defaults even in an older edited base file.
        Map<String, String> palette = new LinkedHashMap<>(bundledDefault.palette());
        palette.putAll(base.palette());
        Map<String, String> colors = new LinkedHashMap<>(bundledDefault.colors());
        colors.putAll(base.colors());
        defaults = new ThemeDefinition(base.name(), base.description(), palette, colors, base.rules());
        Map<String, Entry> found = new LinkedHashMap<>();
        found.put("default_theme", new Entry("default_theme", defaults, defaults.compile(defaults), defaultFile()));
        try (Reader reader = resource("/assets/avogadro/theme-index.json")) {
            for (var name : JsonParser.parseReader(reader).getAsJsonArray()) {
                String filename = name.getAsString();
                if (filename.equals("default_theme.json")) continue;
                String id = filename.substring(0, filename.length() - 5);
                try {
                    ThemeDefinition definition = bundled(filename);
                    found.put(id, new Entry(id, definition, definition.compile(defaults), null));
                } catch (IOException | RuntimeException failure) {
                    errors.add("Bundled " + filename + ": " + failure.getMessage());
                }
            }
        } catch (IOException | RuntimeException failure) {
            errors.add("Bundled theme index: " + failure.getMessage());
        }
        try (var files = Files.list(themesDirectory())) {
            for (Path file : files.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().endsWith(".json")).sorted().toList()) {
                String filename = file.getFileName().toString();
                String id = filename.substring(0, filename.length() - 5);
                if (id.equals("default_theme")) {
                    errors.add(file + ": edit " + defaultFile() + " to change the default theme");
                    continue;
                }
                try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
                    ThemeDefinition definition = ThemeDefinition.read(reader);
                    found.put(id, new Entry(id, definition, definition.compile(defaults), file));
                } catch (IOException | RuntimeException failure) {
                    errors.add(file + ": " + failure.getMessage());
                    Entry previous = entries.get(id);
                    if (previous != null) found.put(id, previous);
                }
            }
        } catch (IOException failure) {
            errors.add(themesDirectory() + ": " + failure.getMessage());
        }
        entries = Collections.unmodifiableMap(found);
        problems = List.copyOf(errors);
    }

    /** Returns the editable base theme path. */
    public Path defaultFile() { return directory.resolve("default_theme.json"); }

    /** Returns the folder scanned for user themes. */
    public Path themesDirectory() { return directory.resolve("themes"); }

    /** Returns the current complete default theme, including inherited new roles. */
    public ThemeDefinition defaults() { return defaults; }

    /** Returns validated themes in deterministic discovery order. */
    public Map<String, Entry> entries() { return entries; }

    /**
     * Returns a stable display order, including local and inherited palettes. Rainbow order follows primary-color
     * hue from red through violet and pink, then groups neutral palettes from light to dark. Equal colors use the
     * displayed name and finally the filename, so filesystem order cannot shuffle the picker.
     * @param order requested display order
     * @return immutable ordered map keyed by theme ID
     */
    public Map<String, Entry> ordered(Order order) {
        return ordered(order, false);
    }

    /**
     * Opens a picker with the two built-in base choices first when requested. Cycling the sort uses
     * {@code pinDefaults=false}; this leaves the user's next Rainbow choice as an ordinary hue sort.
     * @param order requested display order
     * @param pinDefaults put Theme 01/light and Theme 02/dark before the sorted remainder
     * @return immutable ordered map keyed by theme ID
     */
    public Map<String, Entry> ordered(Order order, boolean pinDefaults) {
        Map<String, Entry> sorted = new LinkedHashMap<>();
        if (pinDefaults) for (String id : List.of("default_theme", "theme_02"))
            if (entries.containsKey(id)) sorted.put(id, entries.get(id));
        entries.values().stream().sorted(comparator(order)).forEach(entry -> sorted.put(entry.id(), entry));
        return Collections.unmodifiableMap(sorted);
    }

    private static Comparator<Entry> comparator(Order order) {
        Comparator<Entry> name = Comparator.comparing(entry -> entry.definition().name(), String.CASE_INSENSITIVE_ORDER);
        Comparator<Entry> color = switch (order) {
            case NAME -> name;
            case BRIGHTNESS -> Comparator.comparingDouble(entry -> -luminance(primary(entry)));
            case RAINBOW -> Comparator.<Entry>comparingInt(entry -> neutral(primary(entry)) ? 1 : 0)
                    .thenComparingDouble(entry -> neutral(primary(entry)) ? 0 : hue(primary(entry)))
                    .thenComparingDouble(entry -> -luminance(primary(entry)));
        };
        return color.thenComparing(name).thenComparing(Entry::id);
    }

    private static int primary(Entry entry) { return entry.compiled().paletteColor("primary"); }

    private static boolean neutral(int color) {
        int red = color >> 16 & 255, green = color >> 8 & 255, blue = color & 255;
        return Math.max(red, Math.max(green, blue)) - Math.min(red, Math.min(green, blue)) < 20;
    }

    /** HSV hue, calculated from RGB channels rather than lexicographic hex spelling. */
    private static double hue(int color) {
        double red = color >> 16 & 255, green = color >> 8 & 255, blue = color & 255;
        double maximum = Math.max(red, Math.max(green, blue)), minimum = Math.min(red, Math.min(green, blue));
        double chroma = maximum - minimum;
        if (chroma == 0) return 0;
        double sector = maximum == red ? (green - blue) / chroma
                : maximum == green ? 2 + (blue - red) / chroma : 4 + (red - green) / chroma;
        return (sector + 6) % 6;
    }

    private static double luminance(int color) {
        return .2126 * channel(color >> 16 & 255) + .7152 * channel(color >> 8 & 255) + .0722 * channel(color & 255);
    }

    private static double channel(int value) {
        double normalized = value / 255.;
        return normalized <= .04045 ? normalized / 12.92 : Math.pow((normalized + .055) / 1.055, 2.4);
    }

    /** Returns errors from the latest discovery, suitable for a log or an editor notice. */
    public List<String> problems() { return problems; }

    /** Reads a bundled theme, also used when the standalone creator has no user directory yet. */
    public static ThemeDefinition bundled(String filename) throws IOException {
        if (filename.contains("/") || filename.contains("\\"))
            throw new IllegalArgumentException("Expected a theme filename");
        try (Reader reader = resource("/assets/avogadro/themes/" + filename)) {
            return ThemeDefinition.read(reader);
        }
    }

    private static Reader resource(String name) throws IOException {
        InputStream stream = ThemeStore.class.getResourceAsStream(name);
        if (stream == null) throw new IOException("Missing bundled resource: " + name);
        return new InputStreamReader(stream, StandardCharsets.UTF_8);
    }
}
