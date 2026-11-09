package io.github.wurtzitane.avogadro.api;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.*;
import java.util.concurrent.CancellationException;

import com.google.gson.*;

import io.github.wurtzitane.avogadro.molecule.BondType;
import io.github.wurtzitane.avogadro.molecule.StructureRecipe;

/**
 * A recipe for placing and connecting copies of molecule fragments.
 *
 * <p>
 * Give each part a name, choose its position, repeat it if needed, and add bonds through named attachment points. An
 * attachment point is a name for an atom that can be connected to another part. Parts keep the positions you supply;
 * making a bond does not move them.
 *
 * <p>
 * Call {@link #build()} for a fixed-size structure. For adjustable counts, branch chances, or random crosslinks, put
 * the recipe in a {@link PolymerNetworkDefinition}.
 */
public final class StructureAssembly {

    /** Maximum allowed nodes in an assembly hierarchy. */
    public static final int MAX_NODES = 64;

    /** Maximum allowed nesting depth for assemblies. */
    public static final int MAX_DEPTH = 16;

    /** Maximum allowed candidate links or generated entities. */
    public static final int MAX_CANDIDATES = 65536;

    /** The internal JSON recipe representation of this assembly. */
    private final JsonObject recipe;

    /** Cache of parsed structure fragments keyed by their fragment JSON node objects. */
    private final Map<JsonObject, StructureFragment> fragments = new IdentityHashMap<>();

    /** Map tracking parameter names to their declared kinds ("count" or "probability"). */
    private final Map<String, String> parameterKinds = new LinkedHashMap<>();

    /** Standard 3x4 identity affine transformation matrix. */
    private static final double[][] IDENTITY = {{1, 0, 0, 0}, {0, 1, 0, 0}, {0, 0, 1, 0}};

    /**
     * Creates a {@link StructureAssembly} from a JSON recipe object.
     *
     * @param recipe the {@link JsonObject} recipe
     * @throws IllegalArgumentException if the recipe is null, exceeds depth/entry limits, or has invalid syntax
     */
    private StructureAssembly(JsonObject recipe) {
        if (recipe == null) {
            throw bad("An assembly object is required");
        }
        bounded(recipe, 0, new int[1]);
        this.recipe = recipe.deepCopy();
        try {
            validate(this.recipe, 0, new int[1]);
        } catch (ClassCastException | IllegalStateException malformed) {
            throw bad("Invalid assembly syntax: " + malformed.getMessage());
        }
    }

    /**
     * Creates a new assembly builder.
     *
     * @return a new {@link Builder}
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Creates a structure assembly from a JSON object.
     *
     * @param json the {@link JsonObject} recipe
     * @return a new {@link StructureAssembly}
     */
    public static StructureAssembly fromJson(JsonObject json) {
        return new StructureAssembly(json);
    }

    /**
     * Serializes this assembly into a deep-copied JSON object.
     *
     * @return a new {@link JsonObject} representation
     */
    public JsonObject toJson() {
        return recipe.deepCopy();
    }

    /**
     * Returns an immutable map of parameter names to their declared kinds.
     *
     * @return map of parameter kinds
     */
    Map<String, String> parameterKinds() {
        return Map.copyOf(parameterKinds);
    }

    /**
     * Expands a finite assembly with literal counts and probabilities, using seed zero.
     *
     * <p>
     * Named parameters require a {@link PolymerNetworkDefinition}; this method does not supply parameter values or
     * network defaults.
     *
     * @return the expanded {@link StructureFragment}
     * @throws IllegalArgumentException if the assembly references a named parameter or exceeds expansion limits
     */
    public StructureFragment build() {
        return expand(new NetworkPreviewRequest(Map.of(), Map.of(), 0)).fragment();
    }

    /**
     * Expands the assembly tree given a network preview request, producing a structure fragment and diagnostics.
     *
     * @param request the {@link NetworkPreviewRequest} containing parameters and seed
     * @return an {@link Expansion} result container
     */
    Expansion expand(NetworkPreviewRequest request) {
        Context context = new Context(request);
        context.declare(recipe, "");
        context.validatePaths();
        context.group(recipe, "", IDENTITY, 0);
        context.connect();
        return new Expansion(new StructureFragment(context.atoms, context.bonds, null, List.of(), context.attachments,
                context.configurations, context.capacities), Map.copyOf(context.stats));
    }

    /**
     * Represents the result of an assembly expansion, including the generated fragment and diagnostic statistics.
     *
     * @param fragment the generated {@link StructureFragment}
     * @param diagnostics map of diagnostic metrics
     */
    record Expansion(StructureFragment fragment, Map<String, Integer> diagnostics) {
    }

    /**
     * Adds named parts, repeats, and connections to an assembly recipe.
     */
    public static final class Builder {
        private final JsonObject json = new JsonObject();
        private final JsonArray nodes = new JsonArray(), links = new JsonArray(), random = new JsonArray();

        /**
         * Private constructor initializing assembly lists.
         */
        private Builder() {
            json.add("nodes", nodes);
            json.add("links", links);
            json.add("random_links", random);
        }

        /**
         * Adds a part fragment node with an identity transform.
         *
         * @param name the node name
         * @param fragment the {@link StructureFragment} part
         * @return this builder
         */
        public Builder part(String name, StructureFragment fragment) {
            return part(name, fragment, IDENTITY);
        }

        /**
         * Adds a part fragment node with a specified affine transform matrix.
         *
         * @param name the node name
         * @param fragment the {@link StructureFragment} part
         * @param transform the 3x4 affine transform matrix
         * @return this builder
         */
        public Builder part(String name, StructureFragment fragment, double[][] transform) {
            JsonObject node = node(name, "fragment", transform);
            node.add("structure", fragment.toJson());
            nodes.add(node);
            return this;
        }

        /**
         * Adds a nested group assembly node with a specified transform.
         *
         * @param name the node name
         * @param assembly the nested {@link StructureAssembly} body
         * @param transform the 3x4 affine transform matrix
         * @return this builder
         */
        public Builder group(String name, StructureAssembly assembly, double[][] transform) {
            JsonObject node = node(name, "group", transform);
            node.add("body", assembly.toJson());
            nodes.add(node);
            return this;
        }

        /**
         * Adds a repeat loop node with a fixed integer count.
         *
         * @param name the node name
         * @param body the repeated {@link StructureAssembly} body
         * @param count the repetition count
         * @param step the step transform matrix between iterations
         * @return this builder
         */
        public Builder repeat(String name, StructureAssembly body, int count, double[][] step) {
            return repeat(name, body, new JsonPrimitive(count), step);
        }

        /**
         * Adds a repeat loop node driven by a parameter string name.
         *
         * @param name the node name
         * @param body the repeated {@link StructureAssembly} body
         * @param countParameter the parameter name string governing count
         * @param step the step transform matrix between iterations
         * @return this builder
         */
        public Builder repeat(String name, StructureAssembly body, String countParameter, double[][] step) {
            return repeat(name, body, new JsonPrimitive(countParameter), step);
        }

        /**
         * Internal helper to add a repeat loop node with a JSON element count.
         *
         * @param name the node name
         * @param body the body assembly
         * @param count the count element
         * @param step the step transform matrix
         * @return this builder
         */
        private Builder repeat(String name, StructureAssembly body, JsonElement count, double[][] step) {
            JsonObject node = node(name, "repeat", IDENTITY);
            node.add("body", body.toJson());
            node.add("count", count);
            node.add("step", matrixJson(step));
            nodes.add(node);
            return this;
        }

        /**
         * Configures the last repeat's neighbor links; out-of-bound neighbors are omitted.
         *
         * @param from source attachment path
         * @param to target attachment path
         * @param offset repetition step offset
         * @param type the {@link BondType}
         * @return this builder
         */
        public Builder neighbor(String from, String to, int offset, BondType type) {
            JsonObject node = lastRepeat();
            if (!node.has("neighbor_links")) {
                node.add("neighbor_links", new JsonArray());
            }
            JsonObject edge = edge(from, to, type);
            edge.addProperty("offset", offset);
            node.getAsJsonArray("neighbor_links").add(edge);
            return this;
        }

        /**
         * End-group placement uses first/last repeat frames. Connect through name/head or name/tail paths.
         *
         * @param head the head assembly
         * @param tail the tail assembly
         * @return this builder
         */
        public Builder endGroups(StructureAssembly head, StructureAssembly tail) {
            JsonObject node = lastRepeat();
            node.add("head", head.toJson());
            node.add("tail", tail.toJson());
            return this;
        }

        /**
         * Sets the transform of the last added node, replacing any transform previously assigned to it.
         *
         * @param transform the transform matrix
         * @return this builder
         * @throws IllegalArgumentException if no node has been added yet
         */
        public Builder transformLast(double[][] transform) {
            if (nodes.isEmpty()) {
                throw bad("No node to transform");
            }
            nodes.get(nodes.size() - 1).getAsJsonObject().add("transform", matrixJson(transform));
            return this;
        }

        /**
         * Chooses between two groups each time this part appears. For example, include a branch with a chance of 0.3,
         * or a cap otherwise.
         *
         * @param name the node name
         * @param ruleId the rule identifier for random sampling
         * @param probability the probability value (0.0 to 1.0)
         * @param present the fragment or group to include when selected
         * @param absent the replacement to include when not selected; may be empty
         * @return this builder
         */
        public Builder choice(String name, String ruleId, double probability, StructureAssembly present,
                StructureAssembly absent) {
            return choice(name, ruleId, new JsonPrimitive(probability), present, absent);
        }

        /**
         * Chooses between two groups using a named chance setting from the network definition.
         *
         * @param name the node name
         * @param ruleId the rule identifier
         * @param probabilityParameter the parameter name string
         * @param present the fragment or group to include when selected
         * @param absent the replacement to include when not selected; may be empty
         * @return this builder
         */
        public Builder choice(String name, String ruleId, String probabilityParameter, StructureAssembly present,
                StructureAssembly absent) {
            return choice(name, ruleId, new JsonPrimitive(probabilityParameter), present, absent);
        }

        /**
         * Internal helper to add a choice node.
         */
        private Builder choice(String name, String ruleId, JsonElement probability, StructureAssembly present,
                StructureAssembly absent) {
            JsonObject node = node(name, "choice", IDENTITY);
            node.addProperty("rule_id", ruleId);
            node.add("probability", probability);
            node.add("present", present.toJson());
            node.add("absent", absent.toJson());
            nodes.add(node);
            return this;
        }

        /**
         * Adds a bond between named attachment points. The bond does not move either fragment.
         *
         * @param from source path
         * @param to target path
         * @param type the {@link BondType}
         * @return this builder
         */
        public Builder link(String from, String to, BondType type) {
            links.add(edge(from, to, type));
            return this;
        }

        /**
         * Adds a random link rule with a fixed probability.
         *
         * @param ruleId the rule identifier
         * @param from source path expression
         * @param to target path expression
         * @param pairing pairing mode ("paired", "broadcast", "cartesian")
         * @param probability probability value
         * @param type the {@link BondType}
         * @return this builder
         */
        public Builder randomLinks(String ruleId, String from, String to, String pairing, double probability,
                BondType type) {
            return randomLinks(ruleId, from, to, pairing, new JsonPrimitive(probability), type);
        }

        /**
         * Adds a random link rule governed by a parameter name string.
         *
         * @param ruleId the rule identifier
         * @param from source path expression
         * @param to target path expression
         * @param pairing pairing mode
         * @param probabilityParameter parameter name string
         * @param type the {@link BondType}
         * @return this builder
         */
        public Builder randomLinks(String ruleId, String from, String to, String pairing, String probabilityParameter,
                BondType type) {
            return randomLinks(ruleId, from, to, pairing, new JsonPrimitive(probabilityParameter), type);
        }

        /**
         * Internal helper to add random links.
         */
        private Builder randomLinks(String ruleId, String from, String to, String pairing, JsonElement probability,
                BondType type) {
            JsonObject edge = edge(from, to, type);
            edge.addProperty("rule_id", ruleId);
            edge.addProperty("pairing", pairing);
            edge.add("probability", probability);
            random.add(edge);
            return this;
        }

        /**
         * Builds and returns the immutable {@link StructureAssembly}.
         *
         * @return a new {@link StructureAssembly}
         */
        public StructureAssembly build() {
            return new StructureAssembly(json);
        }

        /**
         * Returns the last added repeat node, validating that it is indeed a repeat node.
         *
         * @return the repeat {@link JsonObject}
         */
        JsonObject lastRepeat() {
            if (nodes.isEmpty()) {
                throw bad("No repeat");
            }
            JsonObject n = nodes.get(nodes.size() - 1).getAsJsonObject();
            if (!n.get("kind").getAsString().equals("repeat")) {
                throw bad("Last node is not a repeat");
            }
            return n;
        }
    }

    /**
     * Creates a node JSON object with name, kind, and transform.
     */
    private static JsonObject node(String name, String kind, double[][] transform) {
        JsonObject n = new JsonObject();
        n.addProperty("name", name);
        n.addProperty("kind", kind);
        n.add("transform", matrixJson(transform));
        return n;
    }

    /**
     * Creates an edge JSON object.
     */
    private static JsonObject edge(String from, String to, BondType type) {
        JsonObject e = new JsonObject();
        e.addProperty("from", from);
        e.addProperty("to", to);
        e.addProperty("type", type.name());
        return e;
    }

    /**
     * Validates allowed fields in a JSON object.
     */
    static void fields(JsonObject value, String... allowed) {
        if (value == null) {
            throw bad("Missing assembly object");
        }
        Set<String> names = Set.of(allowed);
        for (String key : value.keySet()) {
            if (!names.contains(key)) {
                throw bad("Unsupported assembly field: " + key);
            }
        }
    }

    /**
     * Recursively checks that assembly JSON does not exceed depth or entry limits.
     */
    private static void bounded(JsonElement value, int depth, int[] entries) {
        if (depth > 96 || ++entries[0] > 131072) {
            throw bad("Assembly JSON exceeds its depth or entry limit");
        }
        if (value.isJsonObject()) {
            for (var child : value.getAsJsonObject().entrySet()) {
                bounded(child.getValue(), depth + 1, entries);
            }
        } else if (value.isJsonArray()) {
            for (var child : value.getAsJsonArray()) {
                bounded(child, depth + 1, entries);
            }
        }
    }

    /**
     * Validates assembly group syntax, node types, and limits.
     */
    private void validate(JsonObject group, int depth, int[] count) {
        if (depth > MAX_DEPTH) {
            throw bad("Assembly nesting exceeds " + MAX_DEPTH);
        }
        fields(group, "nodes", "links", "random_links");
        if (!group.has("nodes") || !group.get("nodes").isJsonArray()) {
            throw bad("Assembly nodes must be an array");
        }
        Set<String> names = new HashSet<>();
        for (JsonElement element : group.getAsJsonArray("nodes")) {
            if (++count[0] > MAX_NODES) {
                throw bad("Too many assembly nodes");
            }
            JsonObject n = element.getAsJsonObject();
            String name = string(n, "name"), kind = string(n, "kind");
            if (!name.matches("[A-Za-z_][A-Za-z0-9_.-]*") || !names.add(name)) {
                throw bad("Invalid or duplicate node name: " + name);
            }
            if (n.has("transform")) {
                matrix(n.getAsJsonArray("transform"));
            }
            switch (kind) {
                case "fragment" -> {
                    fields(n, "name", "kind", "transform", "structure");
                    fragments.put(n, StructureRecipe.parse(n.getAsJsonObject("structure")));
                }
                case "group" -> {
                    fields(n, "name", "kind", "transform", "body");
                    validate(n.getAsJsonObject("body"), depth + 1, count);
                }
                case "repeat" -> {
                    fields(n, "name", "kind", "transform", "body", "count", "step", "neighbor_links", "head", "tail");
                    value(n.get("count"), false);
                    matrix(n.getAsJsonArray("step"));
                    validate(n.getAsJsonObject("body"), depth + 1, count);
                    if (n.has("head")) {
                        validate(n.getAsJsonObject("head"), depth + 1, count);
                    }
                    if (n.has("tail")) {
                        validate(n.getAsJsonObject("tail"), depth + 1, count);
                    }
                    for (JsonElement e : array(n, "neighbor_links")) {
                        JsonObject l = e.getAsJsonObject();
                        fields(l, "from", "to", "type", "offset");
                        edgeValidate(l);
                        integer(l.get("offset"), -64, 64);
                    }
                }
                case "choice" -> {
                    fields(n, "name", "kind", "transform", "rule_id", "probability", "present", "absent");
                    string(n, "rule_id");
                    value(n.get("probability"), true);
                    validate(n.getAsJsonObject("present"), depth + 1, count);
                    validate(n.getAsJsonObject("absent"), depth + 1, count);
                }
                default -> throw bad("Unknown node kind: " + kind);
            }
        }
        for (JsonElement e : array(group, "links")) {
            JsonObject l = e.getAsJsonObject();
            fields(l, "from", "to", "type");
            edgeValidate(l);
        }
        for (JsonElement e : array(group, "random_links")) {
            JsonObject l = e.getAsJsonObject();
            fields(l, "from", "to", "type", "rule_id", "probability", "pairing");
            edgeValidate(l);
            string(l, "rule_id");
            value(l.get("probability"), true);
            if (!Set.of("paired", "broadcast", "cartesian").contains(string(l, "pairing"))) {
                throw bad("Unknown random pairing");
            }
        }
        if (array(group, "links").size() + array(group, "random_links").size() > StructureFragment.MAX_BONDS) {
            throw bad("Too many link rules");
        }
    }

    private static void edgeValidate(JsonObject e) {
        string(e, "from");
        string(e, "to");
        BondType.valueOf(string(e, "type"));
    }

    private void value(JsonElement e, boolean probability) {
        if (e == null || !e.isJsonPrimitive()) {
            throw bad("Expected a number or parameter name");
        }
        if (e.getAsJsonPrimitive().isString()) {
            if (!e.getAsString().matches("[A-Za-z_][A-Za-z0-9_]*")) {
                throw bad("Invalid parameter name");
            }
            String kind = probability ? "probability" : "count";
            String previous = parameterKinds.putIfAbsent(e.getAsString(), kind);
            if (previous != null && !previous.equals(kind)) {
                throw bad("Parameter used as both count and probability: " + e.getAsString());
            }
        } else if (probability) {
            double v = number(e);
            if (!Double.isFinite(v) || v < 0 || v > 1) {
                throw bad("Invalid probability");
            }
        } else {
            integer(e, 1, 64);
        }
    }

    /**
     * Extracts and validates a finite number from a JSON element.
     */
    static double number(JsonElement value) {
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()) {
            throw bad("Expected a finite JSON number");
        }
        double result = value.getAsDouble();
        if (!Double.isFinite(result)) {
            throw bad("Expected a finite JSON number");
        }
        return result;
    }

    /**
     * Extracts and validates an integer from a JSON element within bounds.
     */
    static int integer(JsonElement e, int min, int max) {
        if (e == null || !e.isJsonPrimitive() || !e.getAsJsonPrimitive().isNumber()) {
            throw bad("Expected integer");
        }
        double d = e.getAsDouble();
        if (!Double.isFinite(d) || d != Math.rint(d) || d < min || d > max) {
            throw bad("Integer outside " + min + ".." + max);
        }
        return (int) d;
    }

    /**
     * Extracts a non-blank string property from a JSON object.
     */
    static String string(JsonObject o, String key) {
        if (o == null || !o.has(key) || !o.get(key).isJsonPrimitive() || !o.get(key).getAsJsonPrimitive().isString()
                || o.get(key).getAsString().isBlank()) {
            throw bad("Missing string " + key);
        }
        return o.get(key).getAsString();
    }

    private static JsonArray array(JsonObject o, String key) {
        if (!o.has(key)) {
            return new JsonArray();
        }
        if (!o.get(key).isJsonArray()) {
            throw bad(key + " must be an array");
        }
        return o.getAsJsonArray(key);
    }

    /**
     * Represents a link or connection rule between assembly attachment paths.
     *
     * @param from source path expression
     * @param to target path expression
     * @param type the {@link BondType}
     * @param random optional JSON object for random link configuration
     * @param scope the scoping path prefix
     */
    private record Link(String from, String to, BondType type, JsonObject random, String scope) {
    }

    /**
     * Represents a candidate random crosslink connection.
     *
     * @param from source atom index
     * @param to target atom index
     * @param type the {@link BondType}
     * @param identity string identity hash for determinism
     * @param priority sampling priority double
     */
    private record Candidate(int from, int to, BondType type, String identity, double priority) {
    }

    /**
     * Context class managing assembly expansion, traversal, and connection resolution.
     */
    private final class Context {

        /** The network preview request containing counts, probabilities, and random seed. */
        final NetworkPreviewRequest request;

        /** Accumulated list of atoms in the expanded structure. */
        final List<StructureFragment.Atom> atoms = new ArrayList<>();

        /** Accumulated list of bonds in the expanded structure. */
        final List<StructureFragment.Bond> bonds = new ArrayList<>();

        /** Map of attachment paths to absolute atom indices. */
        final Map<String, Integer> attachments = new LinkedHashMap<>();

        /** Map of repeat loop paths to their expansion counts. */
        final Map<String, Integer> repeatCounts = new HashMap<>();

        /** Map of absolute atom indices to custom atomic configurations. */
        final Map<Integer, AtomicConfiguration> configurations = new LinkedHashMap<>();

        /** Map of absolute atom indices to random link capacities. */
        final Map<Integer, Integer> capacities = new LinkedHashMap<>();

        /** List of string identities for atoms used in random crosslink deterministic hashing. */
        final List<String> identities = new ArrayList<>();

        /** Set of all declared attachment paths across all realization alternatives. */
        final Set<String> declaredAttachments = new LinkedHashSet<>();

        /** List of all declared link rules across all realization alternatives. */
        final List<Link> declaredLinks = new ArrayList<>();

        /** List of active links for the current realization. */
        final List<Link> links = new ArrayList<>();

        /** Map of diagnostic counters and statistics. */
        final Map<String, Integer> stats = new LinkedHashMap<>();

        /** Counter tracking expanded assembly node instances. */
        int instances;

        /** Counter tracking declared assembly node instances during path validation. */
        int declaredInstances;

        /** Counter tracking candidate random links. */
        int candidateCount;

        /**
         * Creates a expansion context for the specified preview request, initializing all diagnostic statistics
         * counters to zero.
         *
         * @param request the {@link NetworkPreviewRequest} providing parameters and seed
         */
        Context(NetworkPreviewRequest request) {
            this.request = request;
            for (String k : List.of("eligible_branches", "selected_branches", "eligible_crosslinks",
                    "passing_crosslinks", "accepted_crosslinks", "capacity_blocked", "omitted_links")) {
                stats.put(k, 0);
            }
        }

        /**
         * Increments a specific diagnostic statistic counter by one.
         *
         * 5 * @param key the statistics key string
         */
        void add(String key) {
            stats.merge(key, 1, Integer::sum);
        }

        /**
         * Recursively traverses and expands an assembly node group under a given transform and path prefix.
         *
         * @param group the group {@link JsonObject}
         * @param prefix the path prefix string
         * @param transform the 3x4 affine transform matrix
         * @param depth the current recursion depth
         */
        void group(JsonObject group, String prefix, double[][] transform, int depth) {
            cancelled();
            if (++instances > 65536) {
                throw bad("Expanded assembly instance limit exceeded");
            }
            for (JsonElement e : group.getAsJsonArray("nodes")) {
                JsonObject node = e.getAsJsonObject();
                String path = join(prefix, string(node, "name"));
                double[][] local = multiply(transform,
                        node.has("transform") ? matrix(node.getAsJsonArray("transform")) : IDENTITY);
                switch (string(node, "kind")) {
                    case "fragment" -> append(fragments.get(node).affine(local), path);
                    case "group" -> group(node.getAsJsonObject("body"), path, local, depth + 1);
                    case "choice" -> {
                        add("eligible_branches");
                        boolean selected = sample(request.seed(), "branch", string(node, "rule_id"),
                                path) < probability(node.get("probability"));
                        if (selected) {
                            add("selected_branches");
                        }
                        JsonObject chosen = node.getAsJsonObject(selected ? "present" : "absent");
                        group(chosen, path, local, depth + 1);
                    }
                    case "repeat" -> {
                        int count = count(node.get("count"));
                        repeatCounts.put(path, count);
                        double[][] step = matrix(node.getAsJsonArray("step"));
                        double[][] current = local;
                        for (int i = 0; i < count; i++) {
                            group(node.getAsJsonObject("body"), path + "[" + i + "]", current, depth + 1);
                            for (JsonElement edge : array(node, "neighbor_links")) {
                                JsonObject l = edge.getAsJsonObject();
                                int target = i + integer(l.get("offset"), -64, 64);
                                if (target >= 0 && target < count) {
                                    links.add(new Link(join(path + "[" + i + "]", string(l, "from")),
                                            join(path + "[" + target + "]", string(l, "to")),
                                            BondType.valueOf(string(l, "type")), null, path));
                                }
                            }
                            if (i == 0 && node.has("head")) {
                                group(node.getAsJsonObject("head"), path + "/head", current, depth + 1);
                            }
                            if (i == count - 1 && node.has("tail")) {
                                group(node.getAsJsonObject("tail"), path + "/tail", current, depth + 1);
                            }
                            current = multiply(current, step);
                        }
                    }
                    default -> throw bad("Unknown node kind");
                }
            }
            for (JsonElement e : array(group, "links")) {
                link(e.getAsJsonObject(), prefix, false);
            }
            for (JsonElement e : array(group, "random_links")) {
                link(e.getAsJsonObject(), prefix, true);
            }
        }

        /**
         * Adds an expanded link rule to the active links list.
         *
         * @param edge the edge {@link JsonObject}
         * @param prefix the path prefix
         * @param random {@code true} if stochastic random link, {@code false} otherwise
         */
        void link(JsonObject edge, String prefix, boolean random) {
            links.add(new Link(join(prefix, string(edge, "from")), join(prefix, string(edge, "to")),
                    BondType.valueOf(string(edge, "type")), random ? edge : null, prefix));
            if (links.size() > 65536) {
                throw bad("Too many expanded link rules");
            }
        }

        /**
         * Resolves a repetition count from a JSON element, handling parameter lookups or literal integers.
         *
         * @param value the JSON element defining the count
         * @return the resolved integer count
         */
        int count(JsonElement value) {
            if (value.getAsJsonPrimitive().isString()) {
                Integer n = request.counts().get(value.getAsString());
                if (n == null) {
                    throw bad("Unknown count parameter: " + value);
                }
                return integer(new JsonPrimitive(n), 1, 64);
            }
            return integer(value, 1, 64);
        }

        /**
         * Resolves a probability value from a JSON element, handling parameter lookups or literal numbers.
         *
         * @param value the JSON element defining the probability
         * @return the resolved probability double value
         */
        double probability(JsonElement value) {
            Double p = value.getAsJsonPrimitive().isString()
                    ? request.probabilities().get(value.getAsString())
                    : value.getAsDouble();
            if (p == null || !Double.isFinite(p) || p < 0 || p > 1) {
                throw bad("Unknown or invalid probability: " + value);
            }
            return p;
        }

        /**
         * Appends a structure fragment into the context with positional and index offsets, recording atoms, bonds,
         * attachments, configurations, and capacities.
         *
         * @param f the {@link StructureFragment} to append
         * @param path the path prefix string
         */
        void append(StructureFragment f, String path) {
            int offset = atoms.size();
            StructureFragment.checkSize((long) offset + f.atoms().size(), (long) bonds.size() + f.bonds().size());
            atoms.addAll(f.atoms());
            for (int i = 0; i < f.atoms().size(); i++) {
                identities.add(path + "@" + i);
            }
            f.bonds().forEach(b -> bonds.add(new StructureFragment.Bond(offset + b.from(), offset + b.to(), b.type())));
            f.attachments().forEach((name, index) -> {
                if (attachments.putIfAbsent(join(path, name), offset + index) != null) {
                    throw bad("Duplicate attachment path");
                }
            });
            f.configurations().forEach((index, c) -> configurations.put(offset + index, c));
            f.randomLinkCapacities().forEach((index, c) -> capacities.put(offset + index, c));
        }

        /**
         * Processes all deterministic links and stochastic random crosslinks, sampling probabilities deterministically
         * and enforcing capacity constraints.
         */
        void connect() {
            Set<Long> edges = new HashSet<>();
            Set<Long> candidatesSeen = new HashSet<>();
            for (var b : bonds) {
                edges.add(pair(b.from(), b.to()));
            }
            for (Link l : links) {
                if (l.random() == null) {
                    for (int[] pair : pairs(l, "paired")) {
                        bond(pair[0], pair[1], l.type(), edges);
                    }
                }
            }
            List<Candidate> candidates = new ArrayList<>();
            for (Link l : links) {
                if (l.random() != null) {
                    String rule = string(l.random(), "rule_id");
                    double probability = probability(l.random().get("probability"));
                    for (int[] pair : pairs(l, string(l.random(), "pairing"))) {
                        int a = pair[0], b = pair[1];
                        long key = pair(a, b);
                        if (a == b || edges.contains(key) || !candidatesSeen.add(key)) {
                            throw bad("Self, duplicate or already bonded random candidate");
                        }
                        if (!capacities.containsKey(a) || !capacities.containsKey(b)) {
                            throw bad("Random link endpoints need explicit capacities");
                        }
                        add("eligible_crosslinks");
                        String ai = identities.get(a), bi = identities.get(b);
                        String identity = ai.compareTo(bi) < 0 ? ai + "|" + bi : bi + "|" + ai;
                        if (sample(request.seed(), "crosslink", join(l.scope(), rule), identity) < probability) {
                            add("passing_crosslinks");
                            candidates.add(new Candidate(a, b, l.type(), identity,
                                    sample(request.seed(), "crosslink-order", join(l.scope(), rule), identity)));
                        }
                    }
                }
            }
            candidates.sort(Comparator.comparingDouble(Candidate::priority).thenComparing(Candidate::identity));
            Map<Integer, Integer> used = new HashMap<>();
            for (Candidate c : candidates) {
                cancelled();
                if (used.getOrDefault(c.from(), 0) >= capacities.get(c.from())
                        || used.getOrDefault(c.to(), 0) >= capacities.get(c.to())) {
                    add("capacity_blocked");
                    continue;
                }
                bond(c.from(), c.to(), c.type(), edges);
                used.merge(c.from(), 1, Integer::sum);
                used.merge(c.to(), 1, Integer::sum);
                add("accepted_crosslinks");
            }
            stats.put("atoms", atoms.size());
            stats.put("bonds", bonds.size());
        }

        /**
         * Adds a bond between two atom indices, validating against duplicate edges and bond limits.
         *
         * @param a first atom index
         * @param b second atom index
         * @param type the {@link BondType}
         * @param edges set tracking existing bonded pairs
         */
        void bond(int a, int b, BondType type, Set<Long> edges) {
            if (!edges.add(pair(a, b))) {
                throw bad("Duplicate or conflicting assembly bond");
            }
            if (bonds.size() == StructureFragment.MAX_BONDS) {
                throw bad("Assembly bond limit exceeded");
            }
            bonds.add(new StructureFragment.Bond(a, b, type));
        }

        /**
         * Enumerates declared attachment slots for a link rule based on the specified pairing mode.
         * <p>
         * Enumerate declared slots before discarding absent choices, so pairing never shifts.
         *
         * @param link the {@link Link} rule
         * @param mode the pairing mode ("paired", "broadcast", "cartesian")
         * @return list of integer atom index pairs
         */
        List<int[]> pairs(Link link, String mode) {
            List<String> a = select(link.from()), b = select(link.to());
            long count = mode.equals("cartesian") ? (long) a.size() * b.size() : Math.max(a.size(), b.size());
            if (count > MAX_CANDIDATES) {
                throw bad("Candidate selection exceeds limit");
            }
            if (link.random() != null) {
                candidateCount += (int) count;
                if (candidateCount > MAX_CANDIDATES) {
                    throw bad("Random candidate limit exceeded");
                }
            }
            List<int[]> result = new ArrayList<>((int) count);
            if (mode.equals("cartesian")) {
                for (String x : a) {
                    for (String y : b) {
                        addPair(result, x, y);
                    }
                }
            } else {
                if (mode.equals("broadcast") && a.size() != 1 && b.size() != 1) {
                    throw bad("Broadcast requires a single endpoint");
                }
                if (a.size() != b.size() && a.size() != 1 && b.size() != 1) {
                    throw bad("Attachment selections have different lengths");
                }
                for (int i = 0; i < count; i++) {
                    addPair(result, a.get(a.size() == 1 ? 0 : i), b.get(b.size() == 1 ? 0 : i));
                }
            }
            return result;
        }

        /**
         * Adds a resolved atom pair to the results list if both attachment paths exist in the current realization.
         *
         * @param result the target pair list
         * @param first first attachment path string
         * @param second second attachment path string
         */
        void addPair(List<int[]> result, String first, String second) {
            cancelled();
            Integer a = attachments.get(first), b = attachments.get(second);
            if (a == null || b == null) {
                add("omitted_links");
                return;
            }
            result.add(new int[]{a, b});
        }

        /**
         * Selects and sorts matching declared attachment paths for an expression selector.
         *
         * @param expression the selector expression string
         * @return sorted list of matching attachment paths
         * @throws IllegalArgumentException if no attachment paths match the expression
         */
        List<String> select(String expression) {
            List<String> result = declaredAttachments.stream().filter(path -> matches(expression, path))
                    .sorted(StructureAssembly::comparePaths).toList();
            if (result.isEmpty()) {
                throw bad("Unknown attachment path: " + expression);
            }
            return result;
        }

        /**
         * Checks whether an attachment path matches an expression selector supporting wildcards and indices.
         *
         * @param expression the selector expression
         * @param path the actual attachment path
         * @return {@code true} if matching, {@code false} otherwise
         */
        boolean matches(String expression, String path) {
            String[] pieces = expression.split("/", -1), actual = path.split("/", -1);
            if (pieces.length != actual.length) {
                return false;
            }
            String prefix = "";
            for (int i = 0; i < pieces.length; i++) {
                String p = pieces[i], a = actual[i];
                if (!p.equals(a)) {
                    int bracket = p.indexOf('['), actualBracket = a.indexOf('[');
                    if (bracket < 0 || actualBracket < 0 || !p.endsWith("]") || !a.endsWith("]")
                            || !p.substring(0, bracket).equals(a.substring(0, actualBracket))) {
                        return false;
                    }
                    String index = p.substring(bracket + 1, p.length() - 1);
                    int n;
                    try {
                        n = Integer.parseInt(a.substring(actualBracket + 1, a.length() - 1));
                    } catch (NumberFormatException invalid) {
                        return false;
                    }
                    Integer total = repeatCounts.get(join(prefix, p.substring(0, bracket)));
                    if (!(index.equals("*") || index.equals("first") && n == 0
                            || index.equals("last") && total != null && n == total - 1)) {
                        return false;
                    }
                }
                prefix = join(prefix, a);
            }
            return true;
        }

        /**
         * Recursively declares all potential attachment paths and link rules across all realization branches.
         *
         * @param group the group {@link JsonObject}
         * @param prefix the path prefix string
         */
        void declare(JsonObject group, String prefix) {
            cancelled();
            if (++declaredInstances > MAX_CANDIDATES) {
                throw bad("Declared assembly instance limit exceeded");
            }
            for (JsonElement element : group.getAsJsonArray("nodes")) {
                JsonObject node = element.getAsJsonObject();
                String path = join(prefix, string(node, "name"));
                switch (string(node, "kind")) {
                    case "fragment" -> {
                        for (String attachment : fragments.get(node).attachments().keySet()) {
                            String endpoint = join(path, attachment);
                            declaredAttachments.add(endpoint);
                            if (declaredAttachments.size() > MAX_CANDIDATES) {
                                throw bad("Declared attachment limit exceeded");
                            }
                            inferRepeatExtents(endpoint);
                        }
                    }
                    case "group" -> declare(node.getAsJsonObject("body"), path);
                    case "choice" -> {
                        // Both alternatives are valid authored topology even when one is absent in this realization.
                        probability(node.get("probability"));
                        declare(node.getAsJsonObject("present"), path);
                        declare(node.getAsJsonObject("absent"), path);
                    }
                    case "repeat" -> {
                        int amount = count(node.get("count"));
                        Integer previous = repeatCounts.putIfAbsent(path, amount);
                        if (previous != null && previous != amount) {
                            throw bad("Choice alternatives disagree on shared repeat extent: " + path);
                        }
                        for (int i = 0; i < amount; i++) {
                            declare(node.getAsJsonObject("body"), path + "[" + i + "]");
                            for (JsonElement e : array(node, "neighbor_links")) {
                                JsonObject edge = e.getAsJsonObject();
                                int target = i + integer(edge.get("offset"), -64, 64);
                                if (target >= 0 && target < amount) {
                                    declaredLinks.add(new Link(join(path + "[" + i + "]", string(edge, "from")),
                                            join(path + "[" + target + "]", string(edge, "to")),
                                            BondType.valueOf(string(edge, "type")), null, path));
                                }
                            }
                        }
                        if (node.has("head")) {
                            declare(node.getAsJsonObject("head"), path + "/head");
                        }
                        if (node.has("tail")) {
                            declare(node.getAsJsonObject("tail"), path + "/tail");
                        }
                    }
                    default -> throw bad("Unknown node kind");
                }
            }
            for (String key : List.of("links", "random_links")) {
                for (JsonElement e : array(group, key)) {
                    JsonObject edge = e.getAsJsonObject();
                    if (key.equals("random_links")) {
                        probability(edge.get("probability"));
                    }
                    declaredLinks.add(new Link(join(prefix, string(edge, "from")), join(prefix, string(edge, "to")),
                            BondType.valueOf(string(edge, "type")), key.equals("random_links") ? edge : null, prefix));
                }
            }
            if (declaredLinks.size() > MAX_CANDIDATES) {
                throw bad("Declared link rule limit exceeded");
            }
        }

        /**
         * Infers repeat loop iteration bounds from attachment path strings.
         *
         * @param path the attachment path string
         */
        void inferRepeatExtents(String path) {
            String prefix = "";
            for (String part : path.split("/")) {
                int bracket = part.indexOf('[');
                if (bracket >= 0 && part.endsWith("]")) {
                    try {
                        int index = Integer.parseInt(part.substring(bracket + 1, part.length() - 1));
                        repeatCounts.merge(join(prefix, part.substring(0, bracket)), index + 1, Math::max);
                    } catch (NumberFormatException ignored) {
                    }
                }
                prefix = join(prefix, part);
            }
        }

        /**
         * Validates all declared link selector expressions and pairing constraints.
         */
        void validatePaths() {
            for (Link edge : declaredLinks) {
                cancelled();
                List<String> first = select(edge.from()), second = select(edge.to());
                String mode = edge.random() == null ? "paired" : string(edge.random(), "pairing");
                if (mode.equals("cartesian")) {
                    if ((long) first.size() * second.size() > MAX_CANDIDATES) {
                        throw bad("Candidate selection exceeds limit");
                    }
                } else {
                    if (mode.equals("broadcast") && first.size() != 1 && second.size() != 1) {
                        throw bad("Broadcast requires a single endpoint");
                    }
                    if (first.size() != second.size() && first.size() != 1 && second.size() != 1) {
                        throw bad("Attachment selections have different lengths");
                    }
                }
            }
        }
    }

    /**
     * Natural ordering keeps repeat 2 before repeat 10 without depending on map iteration.
     */
    private static int comparePaths(String first, String second) {
        int a = 0, b = 0;
        while (a < first.length() && b < second.length()) {
            char x = first.charAt(a), y = second.charAt(b);
            if (Character.isDigit(x) && Character.isDigit(y)) {
                int ae = a, be = b;
                while (ae < first.length() && Character.isDigit(first.charAt(ae))) {
                    ae++;
                }
                while (be < second.length() && Character.isDigit(second.charAt(be))) {
                    be++;
                }
                String an = first.substring(a, ae), bn = second.substring(b, be);
                int compared = Integer.compare(an.length(), bn.length());
                if (compared == 0) {
                    compared = an.compareTo(bn);
                }
                if (compared != 0) {
                    return compared;
                }
                a = ae;
                b = be;
            } else {
                if (x != y) {
                    return Character.compare(x, y);
                }
                a++;
                b++;
            }
        }
        return Integer.compare(first.length() - a, second.length() - b);
    }

    /**
     * Samples pseudo-random values using SHA-256 hashing.
     */
    static double sample(long seed, String phase, String rule, String identity) {
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            DataOutputStream out = new DataOutputStream(bytes);
            out.writeInt(1);
            out.writeLong(seed);
            for (String s : List.of(phase, rule, identity)) {
                byte[] b = s.getBytes(StandardCharsets.UTF_8);
                out.writeInt(b.length);
                out.write(b);
            }
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(bytes.toByteArray());
            long bits = 0;
            for (int i = 0; i < 7; i++) {
                bits = (bits << 8) | (hash[i] & 255L);
            }
            return (bits >>> 3) * 0x1.0p-53;
        } catch (IOException | NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    /**
     * Creates a 3x4 translation matrix.
     *
     * @param x x translation
     * @param y y translation
     * @param z z translation
     * @return 3x4 double array
     */
    public static double[][] translation(double x, double y, double z) {
        return new double[][]{{1, 0, 0, x}, {0, 1, 0, y}, {0, 0, 1, z}};
    }

    /**
     * Converts a 3x4 matrix into a JSON array.
     */
    static JsonArray matrixJson(double[][] matrix) {
        JsonArray rows = new JsonArray();
        for (double[] row : matrix) {
            JsonArray r = new JsonArray();
            for (double v : row) {
                r.add(v);
            }
            rows.add(r);
        }
        matrix(rows);
        return rows;
    }

    /**
     * Parses and validates a 3x4 matrix from a JSON array.
     */
    private static double[][] matrix(JsonArray json) {
        if (json == null || json.size() != 3) {
            throw bad("Transform needs three rows");
        }
        double[][] m = new double[3][4];
        for (int i = 0; i < 3; i++) {
            JsonArray row = json.get(i).getAsJsonArray();
            if (row.size() != 4) {
                throw bad("Transform needs four columns");
            }
            for (int j = 0; j < 4; j++) {
                m[i][j] = number(row.get(j));
                if (!Double.isFinite(m[i][j])) {
                    throw bad("Nonfinite transform");
                }
            }
        }
        return m;
    }

    /**
     * Multiplies two 3x4 affine transformation matrices.
     */
    private static double[][] multiply(double[][] a, double[][] b) {
        double[][] r = new double[3][4];
        for (int i = 0; i < 3; i++) {
            for (int j = 0; j < 4; j++) {
                for (int k = 0; k < 3; k++) {
                    r[i][j] += a[i][k] * b[k][j];
                }
                if (j == 3) {
                    r[i][j] += a[i][3];
                }
                if (!Double.isFinite(r[i][j])) {
                    throw bad("Transform overflow");
                }
            }
        }
        return r;
    }

    /**
     * Combines two atom indices into a sorted 64-bit pair key.
     */
    private static long pair(int a, int b) {
        return (long) Math.min(a, b) << 32 | (Math.max(a, b) & 0xffffffffL);
    }

    /**
     * Joins two path segments with a slash separator.
     */
    private static String join(String a, String b) {
        return a.isEmpty() ? b : a + "/" + b;
    }

    /**
     * Checks for thread cancellation.
     */
    private static void cancelled() {
        if (Thread.currentThread().isInterrupted()) {
            throw new CancellationException("Assembly cancelled");
        }
    }

    /**
     * Helper to create an illegal argument exception.
     */
    private static IllegalArgumentException bad(String message) {
        return new IllegalArgumentException(message);
    }
}
