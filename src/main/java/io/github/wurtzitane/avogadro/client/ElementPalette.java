package io.github.wurtzitane.avogadro.client;

import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import io.github.wurtzitane.avogadro.Avogadro;
import net.minecraft.client.resources.IResource;
import net.minecraft.client.resources.IResourceManager;
import net.minecraft.client.resources.IResourceManagerReloadListener;
import net.minecraft.util.ResourceLocation;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Pattern;

/**
 * Manages client-side element color palettes, configuration file loading, and resource reloading.
 * <p>Client colors are independent of atom sizes and of the synchronized molecule definitions.
 */
public final class ElementPalette implements IResourceManagerReloadListener {

    /** Singleton instance of the element palette reload listener. */
    public static final ElementPalette INSTANCE = new ElementPalette();

    /** Resource location for element colors JSON asset. */
    public static final ResourceLocation RESOURCE = Avogadro.rl("element-colors.json");

    /** Regular expression pattern for validating #RRGGBB hex color codes. */
    private static final Pattern HEX = Pattern.compile("#[0-9a-fA-F]{6}");

    /** Maximum file byte size limit (32 KiB). */
    private static final int MAX_FILE_BYTES = 32 * 1024;

    /** Atomic revision counter for tracking palette updates. */
    private static final AtomicLong REVISIONS = new AtomicLong();

    /** Default bundled element colors map. */
    private static final Map<String, Integer> DEFAULTS = readDefaults();

    /** Current volatile palette snapshot instance. */
    private static volatile Snapshot current = new Snapshot(DEFAULTS, REVISIONS.incrementAndGet());

    /** Private constructor to prevent instantiation of utility class. */
    private ElementPalette() {}

    /**
     * Retrieves the current palette snapshot.
     * <p>Retain this immutable value for an entire draw or media export.
     *
     * @return current {@link Snapshot} instance
     */
    public static Snapshot snapshot() {
        return current;
    }

    /**
     * Creates an independent palette snapshot with custom overrides.
     * <p>Identical overrides preserve the original cache identity.
     *
     * @param base      base snapshot
     * @param overrides map of symbol-to-color overrides
     * @return new or reused {@link Snapshot} instance
     */
    public static Snapshot withOverrides(Snapshot base, Map<String, Integer> overrides) {
        java.util.Objects.requireNonNull(base);
        Map<String, Integer> colors = new LinkedHashMap<>(base.colors);
        overrides.forEach((symbol, color) -> {
            if (symbol == null || !symbol.matches("[A-Z][a-z]?") || color == null || (color & 0xFF000000) != 0) {
                throw new IllegalArgumentException("Palette overrides require element symbols and RGB colors");
            }
            colors.put(symbol, color | 0xFF000000);
        });
        return colors.equals(base.colors) ? base : new Snapshot(colors, REVISIONS.incrementAndGet());
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void onResourceManagerReload(IResourceManager manager) {
        Path local = Avogadro.configDirectory == null ? null : Avogadro.configDirectory.resolve("element-colors.json");
        try {
            Map<String, Integer> colors = new LinkedHashMap<>(DEFAULTS);
            var layers = manager.getAllResources(RESOURCE);
            try {
                for (IResource resource : layers) {
                    try (Reader reader = new InputStreamReader(resource.getInputStream(), StandardCharsets.UTF_8)) {
                        colors.putAll(parse(reader));
                    }
                }
            } finally {
                for (IResource resource : layers) {
                    try {
                        resource.close();
                    } catch (IOException ignored) {}
                }
            }
            if (local != null) {
                Files.createDirectories(local.getParent());
                if (!Files.exists(local)) {
                    // A partial file lets a resource pack keep controlling every unedited element.
                    try {
                        Files.writeString(local, "{}\n", StandardCharsets.UTF_8, java.nio.file.StandardOpenOption.CREATE_NEW);
                    } catch (java.nio.file.FileAlreadyExistsException ignored) {}
                }
                if (Files.size(local) > MAX_FILE_BYTES) {
                    throw new IOException("Palette file exceeds 32 KiB: " + local);
                }
                try (Reader reader = Files.newBufferedReader(local, StandardCharsets.UTF_8)) {
                    colors.putAll(parse(reader));
                }
            }
            if (!current.colors.equals(colors)) {
                current = new Snapshot(colors, REVISIONS.incrementAndGet());
            }
        } catch (Exception failure) {
            Avogadro.LOGGER.warn("Cannot reload element colors from {} or {}; keeping the previous palette: {}",
                    RESOURCE, local, failure.getMessage());
        }
    }

    /** Parses element colors from a JSON reader against default elements. */
    static Map<String, Integer> parse(Reader source) throws IOException {
        return parse(source, DEFAULTS);
    }

    /** Parses element colors from a JSON reader against known elements. */
    private static Map<String, Integer> parse(Reader source, Map<String, Integer> known) throws IOException {
        JsonReader reader = new JsonReader(source);
        reader.setLenient(false);
        Map<String, Integer> colors = new LinkedHashMap<>();
        reader.beginObject();
        int characters = 0;
        while (reader.hasNext()) {
            String symbol = reader.nextName();
            if (known != null && !known.containsKey(symbol) &&
                    io.github.wurtzitane.avogadro.api.ElementCatalog.additionalElements().stream()
                            .noneMatch(element -> element.symbol().equals(symbol))) {
                throw new IOException("Unknown element color: " + symbol);
            }
            if (colors.containsKey(symbol)) {
                throw new IOException("Duplicate element color: " + symbol);
            }
            if (reader.peek() != JsonToken.STRING) {
                throw new IOException(symbol + " must be a #RRGGBB string");
            }
            String value = reader.nextString();
            characters += symbol.length() + value.length();
            if (characters > MAX_FILE_BYTES || colors.size() >= 512) {
                throw new IOException("Palette contains too much data");
            }
            if (!HEX.matcher(value).matches()) {
                throw new IOException(symbol + " must be a #RRGGBB color");
            }
            colors.put(symbol, 0xFF000000 | Integer.parseInt(value.substring(1), 16));
        }
        reader.endObject();
        if (reader.peek() != JsonToken.END_DOCUMENT) {
            throw new IOException("Unexpected data after element colors");
        }
        return colors;
    }

    /** Reads bundled default element colors from resources. */
    private static Map<String, Integer> readDefaults() {
        try (InputStream stream = ElementPalette.class.getResourceAsStream("/assets/avogadro/element-colors.json")) {
            if (stream == null) {
                throw new IOException("Missing bundled element palette");
            }
            Map<String, Integer> result = parse(new InputStreamReader(stream, StandardCharsets.UTF_8), null);
            if (result.size() != 118) {
                throw new IOException("Bundled element palette must contain all 118 elements");
            }
            return Collections.unmodifiableMap(result);
        } catch (IOException failure) {
            throw new ExceptionInInitializerError(failure);
        }
    }

    /**
     * Immutable snapshot of element colors and revision identifiers.
     */
    public static final class Snapshot {
        private final Map<String, Integer> colors;
        private final long revision;

        /** Constructs a new {@link Snapshot}. */
        private Snapshot(Map<String, Integer> colors, long revision) {
            this.colors = Collections.unmodifiableMap(new LinkedHashMap<>(colors));
            this.revision = revision;
        }

        /** Retrieves the color for an element symbol. */
        public int color(String symbol) {
            Integer color = colors.get(symbol);
            return color == null ? io.github.wurtzitane.avogadro.api.ElementCatalog.color(symbol) : color;
        }

        /**
         * Retrieves the color for an element symbol with a fallback value.
         * <p>Unknown custom elements retain their existing explicitly assigned color.
         */
        public int color(String symbol, int fallback) {
            return colors.getOrDefault(symbol, fallback);
        }

        /** Returns the unmodifiable map of element colors. */
        public Map<String, Integer> colors() {
            return colors;
        }

        /** Returns the revision timestamp or identifier of this snapshot. */
        public long revision() {
            return revision;
        }
    }
}