package io.github.wurtzitane.avogadro.molecule;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import io.github.wurtzitane.avogadro.Avogadro;
import net.minecraft.util.ResourceLocation;
import net.minecraftforge.fml.common.Loader;
import net.minecraftforge.fml.common.ModContainer;

import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/** File-backed molecule definitions and the item/fluid tags used by their bindings. */
public final class MoleculeResources {

    /** Maximum allowed file size in bytes for loaded resource definitions (256 KB). */
    public static final int MAX_FILE_BYTES = 256 * 1024;

    /** Regular expression pattern validating resource paths for molecule definitions and tags. */
    private static final Pattern RESOURCE_PATH = Pattern.compile("[^/]+/(molecules|tags/items|tags/fluids)/.+\\.json");

    /** Private constructor to prevent instantiation of utility class. */
    private MoleculeResources() {}

    /**
     * Generated references are defaults; installed mod resources may replace them.
     *
     * @return constructed common defaults {@link Data} instance
     */
    public static Data readCommonDefaults() {
        return commonDefaults(readMods("data"));
    }

    /**
     * Combines common element/isotope references with installed mod resource layers.
     *
     * @param mods mod resource data instance
     * @return merged {@link Data} instance
     */
    static Data commonDefaults(Data mods) {
        return mergeAll(ElementReferences.data(io.github.wurtzitane.avogadro.Config.AUTO_LOAD_ELEMENTS.get(),
                io.github.wurtzitane.avogadro.Config.AUTO_LOAD_ISOTOPES.get()), mods);
    }

    /** Container record holding molecule definitions, item tags, fluid tags, and fallback hierarchies. */
    public record Data(Map<ResourceLocation, String> molecules,
                       Map<ResourceLocation, String> itemTags,
                       Map<ResourceLocation, String> fluidTags,
                       Map<ResourceLocation, List<String>> moleculeFallbacks) {

        /**
         * Constructs data without fallback lists.
         *
         * @param molecules map of molecule resource locations to JSON strings
         * @param itemTags  map of item tag resource locations to JSON strings
         * @param fluidTags map of fluid tag resource locations to JSON strings
         */
        public Data(Map<ResourceLocation, String> molecules, Map<ResourceLocation, String> itemTags,
                    Map<ResourceLocation, String> fluidTags) {
            this(molecules, itemTags, fluidTags, Map.of());
        }

        /** Canonical constructor ensuring unmodifiable ordered maps and fallback lists. */
        public Data {
            molecules = immutableOrderedCopy(molecules);
            itemTags = immutableOrderedCopy(itemTags);
            fluidTags = immutableOrderedCopy(fluidTags);
            Map<ResourceLocation, List<String>> fallbacks = new LinkedHashMap<>();
            moleculeFallbacks.forEach((id, candidates) -> fallbacks.put(id, List.copyOf(candidates)));
            moleculeFallbacks = Collections.unmodifiableMap(fallbacks);
        }

        /**
         * Creates an empty data instance.
         *
         * @return empty {@link Data} instance
         */
        public static Data empty() {
            return new Data(Map.of(), Map.of(), Map.of());
        }

        /**
         * Merges this data layer with a later data layer.
         *
         * @param later later resource data layer
         * @return merged {@link Data} instance
         */
        public Data merge(Data later) {
            if (later.molecules.isEmpty() && later.itemTags.isEmpty() && later.fluidTags.isEmpty()
                    && later.moleculeFallbacks.isEmpty()) return this;
            return mergeAll(this, later);
        }

        /**
         * Creates an unmodifiable ordered copy of a map.
         *
         * @param source source map
         * @param <K>    key type
         * @param <V>    value type
         * @return unmodifiable ordered map copy
         */
        private static <K, V> Map<K, V> immutableOrderedCopy(Map<K, V> source) {
            return Collections.unmodifiableMap(new LinkedHashMap<>(source));
        }
    }

    /**
     * Reads and collects resource data from all active mod containers and classpath roots.
     *
     * @param root root resource directory path string
     * @return aggregated mod resource {@link Data} instance
     */
    public static Data readMods(String root) {
        Set<Path> sources = new LinkedHashSet<>();
        for (ModContainer mod : Loader.instance().getModList()) {
            if (mod.getSource() != null) sources.add(mod.getSource().toPath());
        }
        // In development, resources may be in a separate classpath directory.
        try {
            Enumeration<URL> urls = MoleculeResources.class.getClassLoader().getResources(root);
            while (urls.hasMoreElements()) {
                URL url = urls.nextElement();
                if (url.getProtocol().equals("file")) sources.add(Path.of(url.toURI()).getParent());
            }
        } catch (Exception e) {
            Avogadro.LOGGER.warn("Cannot enumerate {} resources", root, e);
        }

        Accumulator data = new Accumulator(Data.empty());
        for (Path source : sources) {
            data.add(readSource(source, root));
        }
        return data.toData();
    }

    /** Reads a directory or archive containing data/ or assets/. */
    public static Data readSource(Path source, String root) {
        CollectedResources resources = new CollectedResources();
        try {
            if (Files.isDirectory(source)) {
                readDirectory(source.resolve(root), resources);
            } else if (Files.isRegularFile(source)) {
                readArchive(source, root, resources);
            }
        } catch (IOException e) {
            warn(source.toString(), e);
        }
        return resources.toData();
    }

    /**
     * Reads relevant JSON resource files recursively from a directory base.
     *
     * @param base      base directory path
     * @param resources collected resources accumulator instance
     * @throws IOException if directory traversal fails
     */
    private static void readDirectory(Path base, CollectedResources resources) throws IOException {
        if (!Files.isDirectory(base)) return;
        try (var paths = Files.walk(base)) {
            List<Path> files = paths.filter(Files::isRegularFile)
                    .filter(path -> relevant(relativePath(base, path)))
                    .sorted()
                    .toList();
            for (Path path : files) {
                try (InputStream in = Files.newInputStream(path)) {
                    resources.add(relativePath(base, path), read(in));
                } catch (Exception e) {
                    warn(path.toString(), e);
                }
            }
        }
    }

    /**
     * Reads relevant resource entries from a zip archive file.
     *
     * @param source    archive file path
     * @param root      root prefix string
     * @param resources collected resources accumulator instance
     * @throws IOException if archive processing fails
     */
    private static void readArchive(Path source, String root, CollectedResources resources) throws IOException {
        String prefix = root + "/";
        try (ZipFile archive = new ZipFile(source.toFile())) {
            // Most mod entries are unrelated. Filter before sorting or retaining them.
            var entries = archive.stream()
                    .filter(entry -> !entry.isDirectory() && entry.getName().startsWith(prefix))
                    .filter(entry -> relevant(entry.getName().substring(prefix.length())))
                    .sorted(Comparator.comparing(ZipEntry::getName))
                    .toList();
            for (ZipEntry entry : entries) {
                try (InputStream in = archive.getInputStream(entry)) {
                    resources.add(entry.getName().substring(prefix.length()), read(in));
                } catch (Exception e) {
                    warn(source + "!/" + entry.getName(), e);
                }
            }
        }
    }

    /**
     * Reads local molecule definition files from a specified directory path.
     *
     * @param moleculesDirectory local molecules directory path
     * @return loaded local resource {@link Data} instance
     */
    public static Data readLocal(Path moleculesDirectory) {
        Map<ResourceLocation, String> molecules = new LinkedHashMap<>();
        if (!Files.isDirectory(moleculesDirectory)) return Data.empty();

        try (var paths = Files.walk(moleculesDirectory)) {
            var files = paths.filter(Files::isRegularFile)
                    .filter(path -> path.toString().endsWith(".json"))
                    .sorted()
                    .toList();
            for (Path path : files) {
                try (InputStream in = Files.newInputStream(path)) {
                    String name = relativePath(moleculesDirectory, path);
                    ResourceLocation id = MoleculeLoader.identifier("avogadro:" + name.substring(0, name.length() - 5));
                    molecules.put(id, read(in));
                } catch (Exception e) {
                    warn(path.toString(), e);
                }
            }
        } catch (IOException e) {
            warn(moleculesDirectory.toString(), e);
        }
        return new Data(molecules, Map.of(), Map.of());
    }

    /**
     * Computes the relative path string between a root path and a target path.
     *
     * @param root root directory path
     * @param path target file path
     * @return normalized relative path string with forward slashes
     */
    private static String relativePath(Path root, Path path) {
        return root.relativize(path).toString().replace('\\', '/');
    }

    /**
     * Checks whether a resource path matches relevant molecule or tag patterns.
     *
     * @param name resource path string
     * @return {@code true} if relevant, {@code false} otherwise
     */
    private static boolean relevant(String name) {
        return RESOURCE_PATH.matcher(name).matches();
    }

    /**
     * Reads up to the maximum permitted byte limit from an input stream into a UTF-8 string.
     *
     * @param in input stream source
     * @return decoded string content
     * @throws IOException if file exceeds maximum byte limits or read fails
     */
    private static String read(InputStream in) throws IOException {
        byte[] bytes = in.readNBytes(MAX_FILE_BYTES + 1);
        if (bytes.length > MAX_FILE_BYTES) {
            throw new IOException("Definition exceeds " + MAX_FILE_BYTES + " bytes");
        }
        return new String(bytes, StandardCharsets.UTF_8);
    }

    /** Merges ordered source layers with one mutable accumulator and a single final freeze. */
    public static Data mergeAll(Data... layers) {
        if (layers.length == 0) return Data.empty();
        if (layers.length == 1) return layers[0];
        boolean changed = false;
        for (int i = 1; i < layers.length; i++) {
            Data next = layers[i];
            if (!next.molecules.isEmpty() || !next.itemTags.isEmpty() || !next.fluidTags.isEmpty()
                    || !next.moleculeFallbacks.isEmpty()) { changed = true; break; }
        }
        if (!changed) return layers[0];
        Accumulator accumulator = new Accumulator(layers[0]);
        for (int i = 1; i < layers.length; i++) accumulator.add(layers[i]);
        return accumulator.toData();
    }

    /** Accumulator for merging multiple resource data layers efficiently. */
    public static final class Accumulator {

        /** Map of molecule resource locations to JSON strings. */
        private final Map<ResourceLocation, String> molecules = new LinkedHashMap<>();

        /** Map of molecule fallback candidate lists. */
        private final Map<ResourceLocation, List<String>> fallbacks = new LinkedHashMap<>();

        /** Map of item tag resource locations to tag values. */
        private final Map<ResourceLocation, TagValue> itemTags = new LinkedHashMap<>();

        /** Map of fluid tag resource locations to tag values. */
        private final Map<ResourceLocation, TagValue> fluidTags = new LinkedHashMap<>();

        /**
         * Constructs an accumulator initialized with initial resource data.
         *
         * @param first initial resource data instance
         */
        public Accumulator(Data first) {
            molecules.putAll(first.molecules);
            fallbacks.putAll(first.moleculeFallbacks);
            first.itemTags.forEach((id, json) -> itemTags.put(id, new TagValue(json)));
            first.fluidTags.forEach((id, json) -> fluidTags.put(id, new TagValue(json)));
        }

        /**
         * Adds and merges later resource data into the accumulator.
         *
         * @param later later resource data instance
         */
        public void add(Data later) {
            later.molecules.forEach((id, newest) -> {
                Set<String> earlier = new LinkedHashSet<>(later.moleculeFallbacks.getOrDefault(id, List.of()));
                if (molecules.containsKey(id)) earlier.add(molecules.get(id));
                earlier.addAll(fallbacks.getOrDefault(id, List.of()));
                earlier.remove(newest);
                if (earlier.isEmpty()) fallbacks.remove(id);
                else fallbacks.put(id, List.copyOf(earlier));
                molecules.put(id, newest);
            });
            mergeTags(itemTags, later.itemTags);
            mergeTags(fluidTags, later.fluidTags);
        }

        /**
         * Finalizes accumulated state into an immutable data instance.
         *
         * @return constructed {@link Data} instance
         */
        public Data toData() {
            return new Data(molecules, tagJson(itemTags), tagJson(fluidTags), fallbacks);
        }
    }

    /** Represents a lazy-parsed tag value holding raw JSON or parsed objects. */
    private static final class TagValue {

        /** Raw JSON string. */
        private final String raw;

        /** Parsed JSON object representation. */
        private JsonObject parsed;

        /**
         * Constructs a tag value with a raw JSON string.
         *
         * @param raw raw JSON string
         */
        TagValue(String raw) { this.raw = raw; }

        /**
         * Constructs a tag value with a pre-parsed JSON object.
         *
         * @param parsed parsed JSON object
         */
        TagValue(JsonObject parsed) { this.raw = null; this.parsed = parsed; }

        /**
         * Retrieves the parsed JSON object, parsing it lazily if necessary.
         *
         * @return parsed {@link JsonObject} instance
         */
        JsonObject object() {
            if (parsed == null) parsed = tagObject(raw);
            return parsed;
        }

        /**
         * Retrieves the string representation of the tag value.
         *
         * @return JSON string
         */
        String json() { return raw == null ? parsed.toString() : raw; }
    }

    /**
     * Converts a map of tag values into a map of JSON strings.
     *
     * @param tags map of tag resource locations to tag values
     * @return map of resource locations to JSON strings
     */
    private static Map<ResourceLocation, String> tagJson(Map<ResourceLocation, TagValue> tags) {
        Map<ResourceLocation, String> result = new LinkedHashMap<>();
        tags.forEach((id, value) -> result.put(id, value.json()));
        return result;
    }

    /**
     * Merges later tag definitions into a target tag map.
     *
     * @param result target tag value map
     * @param later  later tag JSON map
     */
    private static void mergeTags(Map<ResourceLocation, TagValue> result, Map<ResourceLocation, String> later) {
        later.forEach((id, json) -> {
            try {
                JsonObject next = tagObject(json);
                boolean replace = next.has("replace") && next.get("replace").getAsBoolean();
                TagValue previous = result.get(id);
                if (previous != null && !replace) {
                    JsonArray values = new JsonArray();
                    previous.object().getAsJsonArray("values").forEach(values::add);
                    next.getAsJsonArray("values").forEach(values::add);
                    next.add("values", values);
                }
                result.put(id, new TagValue(next));
            } catch (RuntimeException e) {
                warn("tag " + id, e);
            }
        });
    }

    /**
     * Parses and validates a tag JSON string structure and its contained values.
     *
     * @param json tag JSON string
     * @return validated {@link JsonObject} instance
     * @throws JsonParseException if tag syntax, fields, or values are invalid
     */
    private static JsonObject tagObject(String json) {
        JsonObject object = JsonParser.parseString(json).getAsJsonObject();
        for (String key : object.keySet()) {
            if (!key.equals("values") && !key.equals("replace")) {
                throw new JsonParseException("Unsupported tag field: " + key);
            }
        }
        if (object.has("replace") && (!object.get("replace").isJsonPrimitive()
                || !object.get("replace").getAsJsonPrimitive().isBoolean())) {
            throw new JsonParseException("Tag replace must be a boolean");
        }
        if (!object.has("values") || !object.get("values").isJsonArray()) {
            throw new JsonParseException("Tag requires a values array");
        }
        for (JsonElement value : object.getAsJsonArray("values")) {
            if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()) {
                throw new JsonParseException("Only string registry ids and #tag references are supported");
            }
            String id = value.getAsString();
            MoleculeLoader.identifier(id.startsWith("#") ? id.substring(1) : id);
        }
        return object;
    }

    /**
     * Parses raw tag JSON maps into structured value lists.
     *
     * @param raw map of resource locations to raw JSON strings
     * @return map of resource locations to parsed string value lists
     */
    public static Map<ResourceLocation, List<String>> parseTags(Map<ResourceLocation, String> raw) {
        Map<ResourceLocation, List<String>> result = new LinkedHashMap<>();
        raw.forEach((id, json) -> {
            try {
                List<String> values = new ArrayList<>();
                tagObject(json).getAsJsonArray("values").forEach(value -> values.add(value.getAsString()));
                result.put(id, List.copyOf(values));
            } catch (RuntimeException e) {
                warn("tag " + id, e);
            }
        });
        return result;
    }

    /**
     * Logs a warning message when resource loading fails.
     *
     * @param source source identifier string
     * @param e      exception instance
     */
    private static void warn(String source, Exception e) {
        Avogadro.LOGGER.warn("Cannot load {}: {}", source, e.getMessage());
    }

    /** Helper class for collecting and categorizing discovered resource files. */
    private static final class CollectedResources {

        /** Collected molecule definitions map. */
        private final Map<ResourceLocation, String> molecules = new LinkedHashMap<>();

        /** Collected item tags map. */
        private final Map<ResourceLocation, String> itemTags = new LinkedHashMap<>();

        /** Collected fluid tags map. */
        private final Map<ResourceLocation, String> fluidTags = new LinkedHashMap<>();

        /**
         * Adds a resource entry based on its relative path name and content JSON.
         *
         * @param name relative path name string
        3 * @param json JSON content string
         */
        void add(String name, String json) {
            int slash = name.indexOf('/');
            String namespace = name.substring(0, slash);
            String path = name.substring(slash + 1);
            String prefix;
            Map<ResourceLocation, String> target;
            if (path.startsWith("molecules/")) {
                prefix = "molecules/";
                target = molecules;
            } else if (path.startsWith("tags/items/")) {
                prefix = "tags/items/";
                target = itemTags;
            } else {
                prefix = "tags/fluids/";
                target = fluidTags;
            }
            String moleculePath = path.substring(prefix.length(), path.length() - 5);
            target.put(MoleculeLoader.identifier(namespace + ":" + moleculePath), json);
        }

        /**
         * Converts collected resources into a data container instance.
         *
         * @return constructed {@link Data} instance
         */
        Data toData() {
            return new Data(molecules, itemTags, fluidTags);
        }
    }
}