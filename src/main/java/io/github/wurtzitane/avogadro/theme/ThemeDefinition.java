package io.github.wurtzitane.avogadro.theme;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * A UI theme shared by the Minecraft client and the standalone Theme Creator.
 *
 * <p>Colors are {@code #RRGGBB}, {@code #AARRGGBB}, or palette references such as {@code $primary}.
 * A theme inherits omitted colors and palette entries from {@code default_theme}. Rules can override a color
 * for a particular menu or control without copying the rest of the theme.
 *
 * @param name theme name shown in the selector
 * @param description optional author guidance
 * @param palette named colors that can be reused by several roles
 * @param colors semantic roles, such as {@code button.background}
 * @param rules menu and element overrides in declaration order
 */
public record ThemeDefinition(String name, String description, Map<String, String> palette,
        Map<String, String> colors, List<Rule> rules) {

    /**
     * One set of color overrides. An asterisk matches any part of a menu name or element path.
     *
     * @param screen menu name, or {@code *} for every menu
     * @param elements matching control paths; {@code ["*"]} selects all controls
     * @param colors overridden semantic roles
     */
    public record Rule(String screen, List<String> elements, Map<String, String> colors) {
        /** Copies and validates selectors and color expressions. */
        public Rule {
            screen = selector(screen);
            elements = List.copyOf(elements);
            if (elements.isEmpty())
                throw new IllegalArgumentException("A theme rule needs at least one element selector");
            elements.forEach(ThemeDefinition::selector);
            colors = expressions(colors);
        }
    }

    /** Copies all collections; editing a source map cannot change an installed theme. */
    public ThemeDefinition {
        if (name == null || name.isBlank() || name.length() > 128)
            throw new IllegalArgumentException("Theme name must contain 1–128 characters");
        description = Objects.requireNonNullElse(description, "");
        palette = expressions(palette);
        colors = expressions(colors);
        rules = List.copyOf(rules);
    }

    /**
     * Reads a strict version-1 theme. This method uses no Minecraft or desktop classes.
     *
     * @param reader JSON input; ownership stays with the caller
     * @return validated authoring document
     * @throws IllegalArgumentException for unsupported fields, malformed colors, or invalid selectors
     */
    public static ThemeDefinition read(Reader reader) {
        JsonObject root = object(JsonParser.parseReader(reader), "theme");
        fields(root, "version", "name", "description", "palette", "colors", "rules");
        if (!root.has("version") || !root.get("version").isJsonPrimitive()
                || !root.getAsJsonPrimitive("version").isNumber() || root.get("version").getAsDouble() != 1)
            throw new IllegalArgumentException("Theme version must be 1");
        List<Rule> rules = new ArrayList<>();
        if (root.has("rules")) {
            if (!root.get("rules").isJsonArray())
                throw new IllegalArgumentException("Theme rules must be an array");
            for (JsonElement value : root.getAsJsonArray("rules")) {
                JsonObject rule = object(value, "rule");
                fields(rule, "screen", "elements", "colors");
                List<String> elements = new ArrayList<>();
                if (rule.has("elements")) {
                    if (!rule.get("elements").isJsonArray())
                        throw new IllegalArgumentException("Rule elements must be an array");
                    rule.getAsJsonArray("elements").forEach(element -> elements.add(string(element, "element")));
                } else {
                    elements.add("*");
                }
                rules.add(new Rule(text(rule, "screen", "*"), elements, map(rule, "colors")));
            }
        }
        return new ThemeDefinition(text(root, "name", ""), text(root, "description", ""),
                map(root, "palette"), map(root, "colors"), rules);
    }

    /**
     * Writes editable, indented JSON, preserving the internal name and authoring references.
     *
     * @param writer destination; ownership stays with the caller
     * @throws IOException if writing fails
     */
    public void write(Writer writer) throws IOException {
        JsonObject root = new JsonObject();
        root.addProperty("version", 1);
        root.addProperty("name", name);
        if (!description.isEmpty()) root.addProperty("description", description);
        root.add("palette", json(palette));
        root.add("colors", json(colors));
        JsonArray entries = new JsonArray();
        for (Rule rule : rules) {
            JsonObject entry = new JsonObject();
            entry.addProperty("screen", rule.screen());
            JsonArray elements = new JsonArray();
            rule.elements().forEach(elements::add);
            entry.add("elements", elements);
            entry.add("colors", json(rule.colors()));
            entries.add(entry);
        }
        root.add("rules", entries);
        writer.write(new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create().toJson(root));
        writer.write('\n');
    }

    /**
     * Resolves references once, ready for fast draws. Invalid themes fail before becoming active.
     * More-specific menu/element selectors override broader rules; later rules win ties.
     *
     * @param defaults complete default theme
     * @return immutable resolved colors
     */
    public Compiled compile(ThemeDefinition defaults) {
        Map<String, String> tokens = new LinkedHashMap<>(defaults.palette());
        tokens.putAll(palette);
        Map<String, String> roles = new LinkedHashMap<>(defaults.colors());
        roles.putAll(colors);
        Map<String, Integer> resolvedPalette = new LinkedHashMap<>();
        for (String token : tokens.keySet()) resolvedPalette.put(token, resolve("$" + token, tokens, new ArrayList<>()));
        Map<String, Integer> resolved = new LinkedHashMap<>();
        roles.forEach((role, value) -> resolved.put(role, resolve(value, tokens, new ArrayList<>())));
        List<CompiledRule> compiledRules = new ArrayList<>();
        List<Rule> allRules = new ArrayList<>(defaults.rules());
        if (this != defaults) allRules.addAll(rules);
        for (Rule rule : allRules) {
            Map<String, Integer> values = new LinkedHashMap<>();
            rule.colors().forEach((role, value) -> values.put(role, resolve(value, tokens, new ArrayList<>())));
            for (String element : rule.elements())
                compiledRules.add(new CompiledRule(glob(rule.screen()), glob(element),
                        specificity(rule.screen()) * 1024 + specificity(element), Map.copyOf(values)));
        }
        compiledRules.sort(Comparator.comparingInt(CompiledRule::specificity));
        return new Compiled(Map.copyOf(resolved), Map.copyOf(resolvedPalette), List.copyOf(compiledRules));
    }

    /** Resolved theme. No JSON parsing or palette-reference traversal happens while drawing. */
    public static final class Compiled {
        private final Map<String, Integer> colors;
        private final Map<String, Integer> palette;
        private final List<CompiledRule> rules;

        private Compiled(Map<String, Integer> colors, Map<String, Integer> palette, List<CompiledRule> rules) {
            this.colors = colors;
            this.palette = palette;
            this.rules = rules;
        }

        /** Returns a resolved palette token, or null if that token is undeclared. */
        public Integer paletteColor(String name) { return palette.get(name); }

        /**
         * Finds a role for one control. The caller can cache this result for a theme revision.
         *
         * @param role semantic color role
         * @param screen menu identifier
         * @param element stable element path within the menu
         * @param fallback ARGB for a role absent from the base theme
         * @return resolved ARGB color
         */
        public int color(String role, String screen, String element, int fallback) {
            int result = colors.getOrDefault(role, fallback);
            for (CompiledRule rule : rules)
                if (rule.colors().containsKey(role) && rule.screen().matcher(screen).matches()
                        && rule.element().matcher(element).matches())
                    result = rule.colors().get(role);
            return result;
        }
    }

    private record CompiledRule(Pattern screen, Pattern element, int specificity, Map<String, Integer> colors) {}

    /**
     * Parses a color shown in the creator's hex field. Six digits imply an opaque color.
     *
     * @param value {@code #RRGGBB} or {@code #AARRGGBB}
     * @return ARGB bits
     */
    public static int hex(String value) {
        if (value == null || !value.matches("#[0-9a-fA-F]{6}([0-9a-fA-F]{2})?"))
            throw new IllegalArgumentException("Color must be #RRGGBB or #AARRGGBB: " + value);
        long bits = Long.parseLong(value.substring(1), 16);
        return (int) (value.length() == 7 ? bits | 0xff000000L : bits);
    }

    /**
     * Formats ARGB for saving or copying; opaque values use the shorter six-digit form.
     *
     * @param argb color bits
     * @return uppercase hex color
     */
    public static String hex(int argb) {
        return argb >>> 24 == 255 ? String.format(java.util.Locale.ROOT, "#%06X", argb & 0xffffff)
                : String.format(java.util.Locale.ROOT, "#%08X", argb);
    }

    private static int resolve(String expression, Map<String, String> palette, List<String> path) {
        if (!expression.startsWith("$")) return hex(expression);
        String key = expression.substring(1);
        if (!palette.containsKey(key)) throw new IllegalArgumentException("Unknown palette color: " + key);
        if (path.contains(key)) throw new IllegalArgumentException("Palette reference cycle: " + path + " → " + key);
        path.add(key);
        int result = resolve(palette.get(key), palette, path);
        path.removeLast();
        return result;
    }

    private static Map<String, String> expressions(Map<String, String> source) {
        Map<String, String> copy = new LinkedHashMap<>();
        source.forEach((key, value) -> {
            if (key == null || !key.matches("[a-zA-Z][a-zA-Z0-9_.-]*"))
                throw new IllegalArgumentException("Invalid color role or palette name: " + key);
            if (value == null || !(value.matches("\\$[a-zA-Z][a-zA-Z0-9_.-]*"))) hex(value);
            copy.put(key, value);
        });
        return Collections.unmodifiableMap(copy);
    }

    private static String selector(String value) {
        if (value == null || value.isBlank() || !value.matches("[a-zA-Z0-9_./*\\[\\]-]+"))
            throw new IllegalArgumentException("Invalid theme selector: " + value);
        return value;
    }

    private static Pattern glob(String value) {
        return Pattern.compile("\\Q" + value.replace("*", "\\E.*\\Q") + "\\E(?:/.*)?");
    }

    private static int specificity(String value) { return value.replace("*", "").length(); }

    private static JsonObject object(JsonElement value, String where) {
        if (value == null || !value.isJsonObject()) throw new IllegalArgumentException(where + " must be an object");
        return value.getAsJsonObject();
    }

    private static String string(JsonElement value, String where) {
        if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString())
            throw new IllegalArgumentException(where + " must be a string");
        return value.getAsString();
    }

    private static String text(JsonObject root, String key, String fallback) {
        return root.has(key) ? string(root.get(key), key) : fallback;
    }

    private static Map<String, String> map(JsonObject root, String key) {
        Map<String, String> values = new LinkedHashMap<>();
        if (root.has(key)) object(root.get(key), key).entrySet()
                .forEach(entry -> values.put(entry.getKey(), string(entry.getValue(), key + "." + entry.getKey())));
        return values;
    }

    private static JsonObject json(Map<String, String> values) {
        JsonObject result = new JsonObject();
        values.forEach(result::addProperty);
        return result;
    }

    private static void fields(JsonObject object, String... allowed) {
        for (String key : object.keySet())
            if (!List.of(allowed).contains(key)) throw new IllegalArgumentException("Unknown theme field: " + key);
    }
}
