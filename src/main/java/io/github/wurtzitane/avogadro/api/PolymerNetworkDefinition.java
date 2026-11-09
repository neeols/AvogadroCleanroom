package io.github.wurtzitane.avogadro.api;

import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.*;

import com.google.gson.*;

import io.github.wurtzitane.avogadro.molecule.MoleculeDef;

/**
 * A recipe for polymer chains connected by branches or crosslinks.
 *
 * <p>
 * A crosslink is a bond joining different chains or different parts of a chain. For example, several long chains joined
 * by occasional crosslinks form a network.
 *
 * <p>
 * The recipe can expose settings such as chain length and the chance of adding each branch or crosslink. Call
 * {@link #preview(NetworkPreviewRequest)} to build one model with chosen settings and a seed. The same recipe,
 * settings, and seed produce the same model.
 *
 * <p>
 * Each model has its own atom counts and formula. The recipe does not have one formula or mass that applies to every
 * setting. Coordinates and chemical details remain the author's responsibility.
 */
public final class PolymerNetworkDefinition {

    /** Metadata key used to store or retrieve polymer network definitions. */
    public static final String METADATA_KEY = "polymer_network";

    /** Synchronized cache map tracking parsed network definitions with a maximum capacity of 32 entries. */
    private static final Map<String, PolymerNetworkDefinition> PARSED = Collections
            .synchronizedMap(new LinkedHashMap<>(16, .75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, PolymerNetworkDefinition> entry) {
                    return size() > 32;
                }
            });

    /**
     * Represents a configurable parameter for a polymer network definition.
     *
     * @param name the unique identifier name of the parameter
     * @param label display text or a language key. The client translates known keys; other labels appear as written.
     *     When label equals name, the viewer also checks {@code avogadro.network.parameter.<name>}.
     * @param kind the parameter type ("count" or "probability")
     * @param min the minimum allowable value
     * @param max the maximum allowable value
     * @param defaultValue the default parameter value
     */
    public record Parameter(String name, String label, String kind, double min, double max, double defaultValue) {

        /**
         * Compact constructor for {@link Parameter} that validates name format, label length, parameter kind, numerical
         * bounds, and type-specific constraints.
         *
         * @throws IllegalArgumentException if parameter names, labels, kinds, or bounds are invalid
         */
        public Parameter {
            if (name == null || !name.matches("[A-Za-z_][A-Za-z0-9_]*") || label == null || label.isBlank()
                    || label.length() > 128) {
                throw new IllegalArgumentException("Invalid network parameter name or label");
            }
            if (kind == null || !Set.of("count", "probability").contains(kind) || !Double.isFinite(min)
                    || !Double.isFinite(max) || !Double.isFinite(defaultValue) || min > max || defaultValue < min
                    || defaultValue > max) {
                throw new IllegalArgumentException("Invalid network parameter bounds");
            }
            if (kind.equals("count") && (min < 1 || max > 64 || min != Math.rint(min) || max != Math.rint(max)
                    || defaultValue != Math.rint(defaultValue))) {
                throw new IllegalArgumentException("Count parameters require integers in 1..64");
            }
            if (kind.equals("probability") && (min < 0 || max > 1)) {
                throw new IllegalArgumentException("Probability bounds require 0..1");
            }
        }

        /**
         * Creates a new count parameter with specified bounds and default value.
         *
         * @param name the parameter name
         * @param min the minimum count integer
         * @param max the maximum count integer
         * @param value the default count integer
         * @return a new count {@link Parameter}
         */
        public static Parameter count(String name, int min, int max, int value) {
            return new Parameter(name, name, "count", min, max, value);
        }

        /**
         * Creates a new probability parameter with standard bounds [0, 1] and a default value.
         *
         * @param name the parameter name
         * @param value the default probability value
         * @return a new probability {@link Parameter}
         */
        public static Parameter probability(String name, double value) {
            return new Parameter(name, name, "probability", 0, 1, value);
        }
    }

    /** List of configurable parameters for this network family. */
    private final List<Parameter> parameters;

    /** Structure assembly rules governing network expansion. */
    private final StructureAssembly assembly;

    /** Random generation seed. */
    private final long seed;

    /** Cryptographic SHA-256 fingerprint hash of the serialized JSON definition. */
    private final String fingerprint;

    /**
     * Creates a {@link PolymerNetworkDefinition} with parameters, assembly, and seed.
     *
     * @param parameters list of network parameters
     * @param assembly the {@link StructureAssembly} rules
     * @param seed the random seed
     * @throws IllegalArgumentException if parameter count exceeds limits, names clash, or parameters mismatch assembly
     */
    public PolymerNetworkDefinition(List<Parameter> parameters, StructureAssembly assembly, long seed) {
        this.parameters = List.copyOf(parameters);
        this.assembly = Objects.requireNonNull(assembly);
        this.seed = seed;

        if (parameters.size() > 8 || parameters.stream().map(Parameter::name).distinct().count() != parameters.size()) {
            throw new IllegalArgumentException("At most eight uniquely named network parameters are allowed");
        }

        Map<String, String> declared = new HashMap<>();
        parameters.forEach(parameter -> declared.put(parameter.name(), parameter.kind()));
        assembly.parameterKinds().forEach((name, kind) -> {
            if (!kind.equals(declared.get(name))) {
                throw new IllegalArgumentException("Missing or wrong-kind network parameter: " + name);
            }
        });

        fingerprint = hash(toJson().toString());
        preview(defaults());
    }

    /**
     * Returns the list of configurable parameters.
     *
     * @return an immutable list of parameters
     */
    public List<Parameter> parameters() {
        return parameters;
    }

    /**
     * Returns the structure assembly rules.
     *
     * @return the {@link StructureAssembly}
     */
    public StructureAssembly assembly() {
        return assembly;
    }

    /**
     * Returns a stable identifier for this recipe. Use it to detect whether the recipe changed.
     *
     * @return the fingerprint string
     */
    public String fingerprint() {
        return fingerprint;
    }

    /**
     * Returns this recipe's starting settings and seed.
     *
     * @return a new {@link NetworkPreviewRequest}
     */
    public NetworkPreviewRequest defaults() {
        Map<String, Integer> counts = new LinkedHashMap<>();
        Map<String, Double> probabilities = new LinkedHashMap<>();
        for (Parameter p : parameters) {
            if (p.kind().equals("count")) {
                counts.put(p.name(), (int) p.defaultValue());
            } else {
                probabilities.put(p.name(), p.defaultValue());
            }
        }
        return new NetworkPreviewRequest(counts, probabilities, seed);
    }

    /**
     * Builds one model using the requested counts, chances, and seed. Omitted settings use this recipe's defaults.
     *
     * @param request the {@link NetworkPreviewRequest} containing counts, probabilities, and seed
     * @return a {@link PolymerPreview} containing the generated fragment and reproduction data
     * @throws IllegalArgumentException if parameter keys are unknown or values fall outside bounds
     */
    public PolymerPreview preview(NetworkPreviewRequest request) {
        Objects.requireNonNull(request);
        Map<String, Integer> counts = new LinkedHashMap<>(defaults().counts());
        Map<String, Double> probabilities = new LinkedHashMap<>(defaults().probabilities());

        for (String key : request.counts().keySet()) {
            if (!counts.containsKey(key)) {
                throw new IllegalArgumentException("Unknown count parameter: " + key);
            }
        }
        for (String key : request.probabilities().keySet()) {
            if (!probabilities.containsKey(key)) {
                throw new IllegalArgumentException("Unknown probability parameter: " + key);
            }
        }

        counts.putAll(request.counts());
        probabilities.putAll(request.probabilities());

        for (Parameter p : parameters) {
            double value = p.kind().equals("count") ? counts.get(p.name()) : probabilities.get(p.name());
            if (value < p.min() || value > p.max()) {
                throw new IllegalArgumentException("Parameter outside bounds: " + p.name());
            }
        }

        NetworkPreviewRequest resolved = new NetworkPreviewRequest(counts, probabilities, request.seed());
        StructureAssembly.Expansion expansion = assembly.expand(resolved);

        JsonObject reproduction = new JsonObject();
        reproduction.addProperty("random_version", 1);
        reproduction.addProperty("template_fingerprint", fingerprint);
        reproduction.add("polymer_network", toJson());

        JsonObject input = new JsonObject();
        JsonObject c = new JsonObject();
        JsonObject p = new JsonObject();
        counts.forEach(c::addProperty);
        probabilities.forEach(p::addProperty);
        input.add("counts", c);
        input.add("probabilities", p);
        input.addProperty("seed", Long.toString(resolved.seed()));
        reproduction.add("request", input);

        return new PolymerPreview(expansion.fragment(), resolved, expansion.diagnostics(), reproduction);
    }

    /**
     * Validates a network preview request against this definition, returning any error messages.
     *
     * @param request the {@link NetworkPreviewRequest} to validate
     * @return a list of error message strings, or an empty list if valid
     */
    public List<String> validate(NetworkPreviewRequest request) {
        try {
            preview(request);
            return List.of();
        } catch (IllegalArgumentException invalid) {
            return List.of(invalid.getMessage());
        }
    }

    /**
     * Serializes this polymer network definition into a JSON object.
     *
     * @return a {@link JsonObject} representation of the definition
     */
    public JsonObject toJson() {
        JsonObject json = new JsonObject();
        json.addProperty("random_version", 1);
        json.addProperty("seed", Long.toString(seed));

        JsonArray params = new JsonArray();
        for (Parameter p : parameters) {
            JsonObject value = new JsonObject();
            value.addProperty("name", p.name());
            value.addProperty("label", p.label());
            value.addProperty("kind", p.kind());
            value.addProperty("min", p.min());
            value.addProperty("max", p.max());
            value.addProperty("default", p.defaultValue());
            params.add(value);
        }
        json.add("parameters", params);
        json.add("assembly", assembly.toJson());
        return json;
    }

    /**
     * Parses a polymer network definition from a JSON object.
     *
     * @param json the {@link JsonObject} to parse
     * @return a new parsed {@link PolymerNetworkDefinition}
     * @throws IllegalArgumentException if version, seed, parameters, or assembly data are invalid
     */
    public static PolymerNetworkDefinition fromJson(JsonObject json) {
        StructureAssembly.fields(json, "random_version", "seed", "parameters", "assembly");
        if (StructureAssembly.integer(json.get("random_version"), 1, 1) != 1) {
            throw new IllegalArgumentException("Unsupported random algorithm");
        }

        String encodedSeed = StructureAssembly.string(json, "seed");
        if (!encodedSeed.matches("-?(0|[1-9][0-9]*)")) {
            throw new IllegalArgumentException("Seed must be a decimal 64-bit integer string");
        }

        long seed = Long.parseLong(encodedSeed);
        List<Parameter> parameters = new ArrayList<>();

        if (!json.has("parameters") || !json.get("parameters").isJsonArray()) {
            throw new IllegalArgumentException("Network parameters must be an array");
        }

        for (JsonElement e : json.getAsJsonArray("parameters")) {
            JsonObject p = e.getAsJsonObject();
            StructureAssembly.fields(p, "name", "label", "kind", "min", "max", "default");
            parameters.add(new Parameter(StructureAssembly.string(p, "name"), StructureAssembly.string(p, "label"),
                    StructureAssembly.string(p, "kind"), StructureAssembly.number(p.get("min")),
                    StructureAssembly.number(p.get("max")), StructureAssembly.number(p.get("default"))));
        }

        if (!json.has("assembly") || !json.get("assembly").isJsonObject()) {
            throw new IllegalArgumentException("Network assembly must be an object");
        }

        return new PolymerNetworkDefinition(parameters, StructureAssembly.fromJson(json.getAsJsonObject("assembly")),
                seed);
    }

    /**
     * Returns and caches the polymer network definition from a molecule definition's metadata if present.
     *
     * @param definition the {@link MoleculeDef} to inspect
     * @return the {@link PolymerNetworkDefinition}, or {@code null} if not present
     */
    public static PolymerNetworkDefinition of(MoleculeDef definition) {
        String json = definition.inspection().metadata().get(METADATA_KEY);
        if (json == null) {
            return null;
        }
        PolymerNetworkDefinition cached = PARSED.get(json);
        if (cached != null) {
            return cached;
        }
        PolymerNetworkDefinition parsed = fromJson(JsonParser.parseString(json).getAsJsonObject());
        // Content identity avoids retaining stale definitions across reloads or owner changes.
        PARSED.put(json, parsed);
        return parsed;
    }

    /**
     * Computes the SHA-256 cryptographic hash fingerprint of a string value.
     *
     * @param value the string to hash
     * @return the hexadecimal hash string
     */
    private static String hash(String value) {
        try {
            return HexFormat.of()
                    .formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }
}
