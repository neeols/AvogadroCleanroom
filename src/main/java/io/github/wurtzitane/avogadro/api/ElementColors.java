package io.github.wurtzitane.avogadro.api;

import java.io.IOException;
import java.io.StringReader;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;

/**
 * Reloadable, owner-scoped element colors, independent of molecular chemistry and graphics classes.
 * <p>All mutations validate and publish one immutable snapshot atomically. They are safe from any thread;
 * renderers pick up the latest snapshot on their next draw. No graphics resources are touched here.
 * Higher priorities win, followed by lexicographically later owner IDs for ties. Client resource packs
 * supply the base; these layers override packs; the user's local palette overrides these layers.
 * <p>Registrations are process-local, are not synchronized from a server, survive world disconnect,
 * and must be registered again after restarting. Dedicated-server calls are harmless but have no visual
 * effect on clients. Keep common registration here and client resource reloading in
 * {@link io.github.wurtzitane.avogadro.api.client.AvogadroClientApi#reloadElementColors()}.
 * At most 64 owners and 512 colors per owner are retained. See
 * <a href="doc-files/colors.html">the element-color guide</a> for Java, Groovy and JSON examples.
 */
public final class ElementColors {
    /** Owner contributions; accessed only under this class's mutation lock. */
    private static final Map<String, Layer> LAYERS = new LinkedHashMap<>();
    /** Immutable published value; unchanged effective colors retain their revision and identity. */
    private static volatile Snapshot current = new Snapshot(Map.of(), 0);
    /** One owner's priority and defensively copied RGB values. */
    private record Layer(int priority, Map<String, Integer> colors) {}
    /** No instances: this is a common-side registration service. */
    private ElementColors() {}

    /**
     * Immutable effective programmatic overrides, before resource-pack/local-file composition.
     * @param colors element symbols mapped to opaque 24-bit RGB (without an alpha byte)
     * @param revision monotonically increasing revision, changed only when effective colors change
     */
    public record Snapshot(Map<String, Integer> colors, long revision) {
        /** Defensively copies the supplied map so callers cannot mutate a published palette. */
        public Snapshot { colors = Map.copyOf(colors); }
    }

    /**
     * Reads the currently published overrides without locking or allocating.
     * @return immutable snapshot; retain it if a multi-step operation needs consistent colors
     */
    public static Snapshot snapshot() { return current; }

    /**
     * Atomically replaces all colors for one owner. An empty map removes that contribution.
     * No file, resource pack, other owner, or existing export snapshot is changed.
     * Unknown but syntactically valid symbols may be registered before a custom element exists;
     * a color entry does not itself register a chemical element.
     * @param owner namespaced identifier, for example {@code mymod:atom_colors}, at most 128 characters
     * @param priority higher numbers override lower numbers; ties sort by owner ID
     * @param colors at most 512 symbol-to-RGB entries, each value in {@code 0x000000..0xFFFFFF}
     * @return the resulting effective programmatic snapshot
     * @throws IllegalArgumentException for invalid IDs, symbols, alpha values, or capacity overflow
     */
    public static synchronized Snapshot replace(String owner, int priority, Map<String, Integer> colors) {
        owner(owner);
        Map<String, Integer> checked = validate(colors);
        if (checked.isEmpty()) LAYERS.remove(owner);
        else {
            if (!LAYERS.containsKey(owner) && LAYERS.size() == 64)
                throw new IllegalArgumentException("At most 64 element-color owners may be registered");
            LAYERS.put(owner, new Layer(priority, checked));
        }
        return publish();
    }

    /**
     * Merges entries into an existing owner while retaining its priority. New owners start at priority zero.
     * Use {@link #replace} to remove omitted entries or change priority. Invalid patches retain all old values.
     * @param owner contribution identifier
     * @param colors replacement values for selected symbols
     * @return resulting effective snapshot
     */
    public static synchronized Snapshot patch(String owner, Map<String, Integer> colors) {
        owner(owner);
        Layer previous = LAYERS.get(owner);
        Map<String, Integer> merged = new LinkedHashMap<>(previous == null ? Map.of() : previous.colors);
        merged.putAll(validate(colors));
        return replace(owner, previous == null ? 0 : previous.priority, merged);
    }

    /**
     * Removes only this owner's override, revealing lower-priority contributions or resource-pack colors.
     * @param owner contribution identifier
     * @return resulting effective snapshot; unchanged when the owner was absent
     */
    public static synchronized Snapshot remove(String owner) {
        owner(owner); LAYERS.remove(owner); return publish();
    }

    /**
     * Reloads an owner's complete palette from strict JSON, such as {@code {"C":"#405060"}}.
     * Read an application-owned file before calling; this method performs no filesystem access.
     * Duplicate keys, malformed colors, trailing data, and input larger than 32 KiB are rejected
     * before publication. Failure leaves the previous owner's contribution untouched.
     * @param owner contribution identifier
     * @param priority contribution precedence
     * @param json partial element-color JSON object, using six-digit hex strings
     * @return resulting effective snapshot
     * @throws IOException if JSON or a color value is invalid
     */
    public static Snapshot replaceJson(String owner, int priority, String json) throws IOException {
        Objects.requireNonNull(json, "json");
        if (json.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 32768)
            throw new IOException("Element-color JSON exceeds 32 KiB");
        Map<String, Integer> colors = new LinkedHashMap<>();
        try (JsonReader reader = new JsonReader(new StringReader(json))) {
            reader.setLenient(false); reader.beginObject();
            while (reader.hasNext()) {
                String symbol = reader.nextName();
                if (colors.containsKey(symbol)) throw new IOException("Duplicate element color: " + symbol);
                if (reader.peek() != JsonToken.STRING) throw new IOException(symbol + " must be a #RRGGBB string");
                String value = reader.nextString();
                if (!value.matches("#[0-9a-fA-F]{6}")) throw new IOException(symbol + " must be a #RRGGBB string");
                colors.put(symbol, Integer.parseInt(value.substring(1), 16));
            }
            reader.endObject();
            if (reader.peek() != JsonToken.END_DOCUMENT) throw new IOException("Unexpected data after element colors");
        } catch (IllegalStateException failure) { throw new IOException("Expected a JSON object of element colors", failure); }
        return replace(owner, priority, colors);
    }

    /** Validates an owner ID before any state can change. @param owner proposed owner ID */
    private static void owner(String owner) {
        if (owner == null || owner.length() > 128 || !owner.matches("[a-z0-9_.-]+:[a-z0-9_./-]+"))
            throw new IllegalArgumentException("Element-color owner must be a namespaced identifier");
    }

    /**
     * Validates and freezes values before changing an owner.
     * @param colors proposed RGB values
     * @return immutable validated entries
     */
    private static Map<String, Integer> validate(Map<String, Integer> colors) {
        Objects.requireNonNull(colors, "colors");
        if (colors.size() > 512) throw new IllegalArgumentException("At most 512 colors may be registered per owner");
        colors.forEach((symbol, rgb) -> {
            if (symbol == null || !symbol.matches("[A-Z][a-z]?") || rgb == null || rgb < 0 || rgb > 0xFFFFFF)
                throw new IllegalArgumentException("Element colors require element symbols and opaque 24-bit RGB values");
        });
        return Map.copyOf(colors);
    }

    /** Rebuilds effective values only after a mutation; never runs as routine render work. @return current snapshot */
    private static Snapshot publish() {
        Map<String, Integer> effective = new LinkedHashMap<>();
        LAYERS.entrySet().stream().sorted(Comparator.<Map.Entry<String, Layer>>comparingInt(e -> e.getValue().priority)
                .thenComparing(Map.Entry::getKey)).forEach(e -> effective.putAll(e.getValue().colors));
        if (!effective.equals(current.colors)) current = new Snapshot(effective, current.revision + 1);
        return current;
    }
}
