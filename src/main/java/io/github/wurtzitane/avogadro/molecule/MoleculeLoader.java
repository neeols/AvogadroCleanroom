package io.github.wurtzitane.avogadro.molecule;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.function.Consumer;
import java.util.regex.Pattern;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import net.minecraft.util.ResourceLocation;

import org.apache.logging.log4j.LogManager;

/** Parses a complete merged source layer, so copies may refer to lower-priority sources. */
public final class MoleculeLoader {

    /** Allowed root fields in molecule JSON definitions. Legacy presentation keys are accepted but ignored. */
    private static final Set<String> FIELDS = Set.of("smiles", "atoms", "bonds", "formula", "items", "item_tags",
            "fluids", "fluid_tags", "materials", "match_containers", "copy", "structure", "diagram", "tooltip", "name",
            "generate_item", "elements", "atom_configurations", "components", "inspection", "crystal", "polymer",
            "polymer_network", "edits", "materialize", "atom_annotations", "constraints");

    /** Allowed atom object fields in explicit JSON geometry definitions. */
    private static final Set<String> ATOM_FIELDS = Set.of("e", "x", "y", "z", "nuclear", "label", "radical_electrons");

    /** Regular expression pattern validating resource location identifiers. */
    private static final Pattern IDENTIFIER = Pattern.compile("(?:[a-z0-9_.-]+:)?[a-z0-9_./-]+");

    /** Regular expression pattern validating chemical element symbols. */
    private static final Pattern ELEMENT_SYMBOL = Pattern.compile("[A-Z][a-z]?");

    /** Reusable syntax cache bound by storage size to retain reload work efficiently. */
    private static final ReuseCache<String, Syntax> SYNTAX = new ReuseCache<>(16 * 1024 * 1024);

    /** Reusable definition cache bound by storage size to optimize molecule compilation. */
    private static final ReuseCache<DefinitionKey, MoleculeDef> DEFINITIONS = new ReuseCache<>(64 * 1024 * 1024);

    private MoleculeLoader() {}

    /**
     * Represents loaded molecule definitions along with their raw JSON source strings.
     *
     * @param definitions map of resource locations to parsed molecule definitions
     * @param raw map of resource locations to raw JSON strings
     */
    public record Loaded(Map<ResourceLocation, MoleculeDef> definitions, Map<ResourceLocation, String> raw) {

        /**
         * Canonical constructor ensuring unmodifiable definition and raw source maps.
         *
         * @param definitions map of parsed molecule definitions
         * @param raw map of raw JSON strings
         */
        public Loaded {
            definitions = Collections.unmodifiableMap(new LinkedHashMap<>(definitions));
            raw = Collections.unmodifiableMap(new LinkedHashMap<>(raw));
        }
    }

    /** Represents a candidate JSON definition source and object during resolution. */
    private static final class Candidate {

        /** Raw JSON source string. */
        final String raw;

        /** Parsed JSON object. */
        final JsonObject object;

        /** Rejection reason string if candidate fails validation or cycle checking. */
        String rejection;

        /**
         * Constructs a candidate with the specified raw source and JSON object.
         *
         * @param raw raw JSON string
         * @param object parsed JSON object
         */
        Candidate(String raw, JsonObject object) {
            this.raw = raw;
            this.object = object;
        }
    }

    /** Represents a failure reason for unresolvable molecule definitions. */
    private record Failure(String reason) {
    }

    /** Represents parsed syntax results containing either a JSON object or an error message. */
    private record Syntax(JsonObject object, String error) {
    }

    /** Composite key used for caching compiled molecule definitions. */
    private record DefinitionKey(ResourceLocation id, String raw, MoleculeDef dependency) {
    }

    /**
     * A memory-bounded LRU cache for reuse during molecule reloading and compilation.
     *
     * @param <K> cache key type
     * @param <V> cache value type
     */
    private static final class ReuseCache<K, V> {

        /** Cached entry holding a value and its estimated byte size. */
        private record Entry<V>(V value, long bytes) {
        }

        /** Linked hash map maintaining LRU order. */
        private final LinkedHashMap<K, Entry<V>> entries = new LinkedHashMap<>(64, .75f, true);

        /** Maximum allowed byte storage limit. */
        private final long limit;

        /** Current estimated byte storage used. */
        private long bytes;

        /**
         * Constructs a reuse cache with a specified byte limit.
         *
         * @param limit maximum storage limit in bytes
         */
        ReuseCache(long limit) {
            this.limit = limit;
        }

        /**
         * Returns a cached value for the given key.
         *
         * @param key cache key
         * @return cached value, or {@code null} if not present
         */
        synchronized V get(K key) {
            Entry<V> value = entries.get(key);
            return value == null ? null : value.value;
        }

        /**
         * Stores a value in the cache, evicting older entries if limits are exceeded. 10 *
         *
         * @param key cache key
         * @param value cache value
         * @param size estimated byte size of the entry
         */
        synchronized void put(K key, V value, long size) {
            if (size > limit)
                return;
            Entry<V> previous = entries.put(key, new Entry<>(value, size));
            if (previous != null)
                bytes -= previous.bytes;
            bytes += size;
            var oldest = entries.entrySet().iterator();
            while (bytes > limit || entries.size() > MoleculeSync.MAX_ENTRIES) {
                bytes -= oldest.next().getValue().bytes;
                oldest.remove();
            }
        }
    }

    /**
     * Parses and validates JSON source syntax.
     *
     * @param source raw JSON source string
     * @return parsed {@link Syntax} result instance
     */
    private static Syntax syntax(String source) {
        Syntax cached = SYNTAX.get(source);
        if (cached != null)
            return cached;
        Syntax parsed;
        try {
            JsonElement json = JsonParser.parseString(source);
            require(json.isJsonObject(), "root must be an object");
            JsonObject object = json.getAsJsonObject();
            for (String key : object.keySet())
                require(FIELDS.contains(key), "unsupported field '" + key + "'");
            if (object.has("edits")) {
                require(object.has("copy"), "edits requires a copy source");
                io.github.wurtzitane.avogadro.api.StructureEdits.fromJson(object.getAsJsonArray("edits"));
            }
            if (object.has("materialize"))
                require(object.has("copy") && object.get("materialize").isJsonPrimitive()
                        && object.getAsJsonPrimitive("materialize").isBoolean(),
                        "materialize requires copy and a boolean value");
            if (object.has("polymer_network")) {
                require(object.get("polymer_network").isJsonObject(), "polymer_network must be an object");
                for (String other : List.of("copy", "atoms", "bonds", "smiles", "structure", "crystal", "polymer",
                        "atom_configurations"))
                    require(!object.has(other), "polymer_network cannot be combined with " + other);
            }
            if (object.has("polymer")) {
                require(object.get("polymer").isJsonObject(), "polymer must be an object");
                for (String other : List.of("copy", "atoms", "bonds", "smiles", "structure", "crystal"))
                    require(!object.has(other), "polymer cannot be combined with " + other);
            }
            if (object.has("structure")) {
                require(object.get("structure").isJsonObject(), "structure must be an object");
                for (String other : List.of("copy", "atoms", "bonds", "smiles")) {
                    require(!object.has(other), "structure cannot be combined with " + other);
                }
            }
            parsed = new Syntax(object, null);
        } catch (RuntimeException bad) {
            parsed = new Syntax(null, message(bad));
        }
        SYNTAX.put(source, parsed, 256L + (source == null ? 0 : source.length() * 8L));
        return parsed;
    }

    /** Represents resolution evaluation frames during dependency tracking and cycle detection. */
    private static final class Frame {

        /** Molecule resource location identifier. */
        final ResourceLocation id;

        /** List of candidate definitions to evaluate. */
        final List<Candidate> candidates;

        /** Current candidate evaluation index. */
        int index;

        /** Current failure reason string. */
        String failure = "no valid definition";

        /**
         * Constructs a resolution frame with an identifier and candidate list.
         *
         * @param id molecule resource location
         * @param candidates list of candidate definitions
         */
        Frame(ResourceLocation id, List<Candidate> candidates) {
            this.id = id;
            this.candidates = candidates;
        }
    }

    /**
     * Parses all raw molecule definitions with default logging diagnostics.
     *
     * @param raw map of resource locations to raw JSON source strings
     * @return map of resource locations to parsed molecule definitions
     */
    public static Map<ResourceLocation, MoleculeDef> parseAll(Map<ResourceLocation, String> raw) {
        return parseAll(raw, text -> LogManager.getLogger("avogadro").warn(text));
    }

    /**
     * Parses all raw molecule definitions with a custom diagnostics consumer.
     *
     * @param raw map of resource locations to raw JSON source strings
     * @param diagnostics diagnostics consumer for error reporting
     * @return map of resource locations to parsed molecule definitions
     */
    public static Map<ResourceLocation, MoleculeDef> parseAll(Map<ResourceLocation, String> raw,
            Consumer<String> diagnostics) {
        return load(raw, Map.of(), diagnostics).definitions();
    }

    /**
     * Loads and resolves molecule definitions given raw sources and fallbacks.
     *
     * @param raw map of resource locations to primary raw JSON strings
     * @param fallbacks map of resource locations to fallback raw JSON strings
     * @return loaded definitions and raw sources wrapper
     */
    public static Loaded load(Map<ResourceLocation, String> raw, Map<ResourceLocation, List<String>> fallbacks) {
        return load(raw, fallbacks, text -> LogManager.getLogger("avogadro").warn(text));
    }

    /**
     * Retain earlier same-id definitions only when a later override cannot be resolved.
     *
     * @param raw map of resource locations to primary raw JSON strings
     * @param fallbacks map of resource locations to fallback raw JSON strings
     * @param diagnostics diagnostics consumer for warning and error reporting
     * @return loaded definitions and raw sources wrapper
     */
    public static Loaded load(Map<ResourceLocation, String> raw, Map<ResourceLocation, List<String>> fallbacks,
            Consumer<String> diagnostics) {
        Map<ResourceLocation, List<Candidate>> objects = new LinkedHashMap<>();
        for (Map.Entry<ResourceLocation, String> entry : raw.entrySet()) {
            checkCancelled();
            List<String> sources = new ArrayList<>();
            sources.add(entry.getValue());
            sources.addAll(fallbacks.getOrDefault(entry.getKey(), List.of()));
            List<Candidate> candidates = new ArrayList<>();
            for (String source : sources) {
                Syntax syntax = syntax(source);
                if (syntax.error == null)
                    candidates.add(new Candidate(source, syntax.object));
                else
                    diagnostics.accept(entry.getKey() + ": " + syntax.error);
            }
            if (!candidates.isEmpty())
                objects.put(entry.getKey(), candidates);
        }
        Map<ResourceLocation, MoleculeDef> resolved = new LinkedHashMap<>();
        Map<ResourceLocation, String> selected = new LinkedHashMap<>();
        Map<ResourceLocation, Failure> failed = new LinkedHashMap<>();
        for (ResourceLocation id : objects.keySet()) {
            checkCancelled();
            try {
                resolve(id, objects, resolved, selected, failed, diagnostics);
            } catch (CancellationException cancelled) {
                throw cancelled;
            } catch (RuntimeException bad) {
                diagnostics.accept(id + ": " + message(bad));
            }
        }
        // Copy dependencies are resolved first internally, but association priority follows source order.
        Map<ResourceLocation, MoleculeDef> ordered = new LinkedHashMap<>();
        Map<ResourceLocation, String> orderedRaw = new LinkedHashMap<>();
        for (ResourceLocation id : raw.keySet()) {
            if (resolved.containsKey(id)) {
                ordered.put(id, resolved.get(id));
                orderedRaw.put(id, selected.get(id));
            }
        }
        return new Loaded(ordered, orderedRaw);
    }

    /**
     * Recursively resolves a molecule definition and its dependencies, handling cycles and candidate fallbacks.
     *
     * @param id molecule resource location identifier
     * @param objects map of all candidate objects grouped by resource location
     * @param resolved map of successfully resolved molecule definitions
     * @param selected map of selected raw JSON strings
     * @param failed map of failed resolution states
     * @param diagnostics diagnostics consumer for reporting rejections
     */
    private static void resolve(ResourceLocation id, Map<ResourceLocation, List<Candidate>> objects,
            Map<ResourceLocation, MoleculeDef> resolved, Map<ResourceLocation, String> selected,
            Map<ResourceLocation, Failure> failed, Consumer<String> diagnostics) {
        if (resolved.containsKey(id))
            return;
        Failure cached = failed.get(id);
        if (cached != null)
            throw new IllegalArgumentException(cached.reason);
        ArrayDeque<Frame> pending = new ArrayDeque<>();
        Set<ResourceLocation> visiting = new HashSet<>();
        pending.addLast(new Frame(id, objects.get(id)));
        visiting.add(id);
        while (!pending.isEmpty()) {
            checkCancelled();
            Frame frame = pending.getLast();
            if (frame.index >= frame.candidates.size()) {
                // Every candidate has been exhausted. Missing sources and rejected
                // cycle candidates cannot become valid later in this fixed input.
                failed.put(frame.id, new Failure(frame.failure));
                pending.removeLast();
                visiting.remove(frame.id);
                if (pending.isEmpty())
                    throw new IllegalArgumentException(frame.failure);
                continue;
            }
            Candidate candidate = frame.candidates.get(frame.index);
            try {
                require(candidate.rejection == null, candidate.rejection);
                if (candidate.object.has("copy")) {
                    ResourceLocation source = identifier(requiredString(candidate.object.get("copy"), "copy"));
                    if (visiting.contains(source)) {
                        // Reject every definition participating in this cycle, then retry
                        // its earliest frame with an older candidate. Ancestors outside
                        // the cycle may still validly copy the repaired definition.
                        boolean cycle = false;
                        for (Frame active : pending) {
                            if (active.id.equals(source))
                                cycle = true;
                            if (cycle)
                                active.candidates.get(active.index).rejection = "cyclic copy reference to " + source;
                        }
                        while (!pending.getLast().id.equals(source))
                            visiting.remove(pending.removeLast().id);
                        continue;
                    }
                    if (!resolved.containsKey(source)) {
                        require(!failed.containsKey(source), "copy refers to invalid molecule " + source);
                        List<Candidate> dependencies = objects.get(source);
                        require(dependencies != null, "copy refers to missing molecule " + source);
                        visiting.add(source);
                        pending.addLast(new Frame(source, dependencies));
                        continue;
                    }
                }
                MoleculeDef dependency = candidate.object.has("copy")
                        ? resolved.get(identifier(requiredString(candidate.object.get("copy"), "copy")))
                        : null;
                DefinitionKey key = new DefinitionKey(frame.id, candidate.raw, dependency);
                MoleculeDef definition = DEFINITIONS.get(key);
                if (definition == null) {
                    definition = definition(frame.id, candidate.object, resolved);
                    long bytes = 512L + candidate.raw.length() * 2L + definition.structure().atoms().size() * 64L
                            + definition.structure().bonds().size() * 32L;
                    DEFINITIONS.put(key, definition, bytes);
                }
                resolved.put(frame.id, definition);
                selected.put(frame.id, candidate.raw);
                pending.removeLast();
                visiting.remove(frame.id);
            } catch (CancellationException cancelled) {
                throw cancelled;
            } catch (RuntimeException bad) {
                frame.failure = message(bad);
                frame.index++;
                if (frame.index < frame.candidates.size())
                    diagnostics.accept(frame.id + ": rejected override (" + frame.failure
                            + "); using an earlier definition if valid");
            }
        }
    }

    /** Checks whether the current thread has been interrupted and throws a cancellation exception if so. */
    private static void checkCancelled() {
        if (Thread.currentThread().isInterrupted())
            throw new CancellationException("Molecule loading cancelled");
    }

    /**
     * Compiles a molecule definition from a parsed JSON object and resolved base dependencies.
     *
     * @param id molecule resource location identifier
     * @param object JSON object containing definition data
     * @param resolved map of already resolved molecule definitions
     * @return constructed {@link MoleculeDef}
     */
    private static MoleculeDef definition(ResourceLocation id, JsonObject object,
            Map<ResourceLocation, MoleculeDef> resolved) {
        String formula = optionalString(object, "formula");
        String name = optionalString(object, "name");
        boolean generateItem = false;
        if (object.has("generate_item")) {
            JsonElement flag = object.get("generate_item");
            require(flag.isJsonPrimitive() && flag.getAsJsonPrimitive().isBoolean(), "generate_item must be a boolean");
            generateItem = flag.getAsBoolean();
        }
        List<String> items = strings(object, "items", false);
        List<String> itemTags = strings(object, "item_tags", true);
        List<String> fluids = strings(object, "fluids", false);
        List<String> fluidTags = strings(object, "fluid_tags", true);
        List<String> materials = strings(object, "materials", false);
        boolean matchContainers = true;
        if (object.has("match_containers")) {
            JsonElement flag = object.get("match_containers");
            require(flag.isJsonPrimitive() && flag.getAsJsonPrimitive().isBoolean(),
                    "match_containers must be a boolean");
            matchContainers = flag.getAsBoolean();
        }
        MoleculeStructure structure;
        io.github.wurtzitane.avogadro.api.MoleculeInspection inherited = io.github.wurtzitane.avogadro.api.MoleculeInspection.EMPTY;
        // Original precedence is copy > atoms > smiles. Lower-priority geometry is intentionally unused.
        if (object.has("polymer_network")) {
            var network = io.github.wurtzitane.avogadro.api.PolymerNetworkDefinition
                    .fromJson(object.getAsJsonObject("polymer_network"));
            var preview = network.preview(network.defaults());
            structure = preview.fragment().toStructure(id.toString());
            if (formula == null)
                formula = preview.formula();
            object = fragmentMetadata(object, preview.fragment());
            object.getAsJsonObject("inspection").addProperty(
                    io.github.wurtzitane.avogadro.api.PolymerNetworkDefinition.METADATA_KEY,
                    network.toJson().toString());
            object.getAsJsonObject("inspection").addProperty("classification", "polymer");
        } else if (object.has("polymer")) {
            var polymer = io.github.wurtzitane.avogadro.api.PolymerDefinition
                    .fromJson(object.getAsJsonObject("polymer"));
            structure = io.github.wurtzitane.avogadro.api.PolymerGeometry.chain(polymer, polymer.previewUnits());
            if (formula == null)
                formula = polymer.formula();
            object = fragmentMetadata(object,
                    io.github.wurtzitane.avogadro.api.PolymerGeometry.chainFragment(polymer, polymer.previewUnits()));
            JsonObject metadata = object.has("inspection")
                    ? object.getAsJsonObject("inspection").deepCopy()
                    : new JsonObject();
            metadata.addProperty(io.github.wurtzitane.avogadro.api.PolymerDefinition.METADATA_KEY,
                    polymer.toJson().toString());
            metadata.addProperty("classification", "polymer");
            object = object.deepCopy();
            object.add("inspection", metadata);
        } else if (object.has("copy")) {
            ResourceLocation source = identifier(requiredString(object.get("copy"), "copy"));
            MoleculeDef base = resolved.get(source);
            require(base != null, "copy refers to invalid molecule " + source);
            if (object.has("edits") || object.has("materialize") && object.get("materialize").getAsBoolean())
                base = io.github.wurtzitane.avogadro.api.MoleculeEdits.apply(id.toString(), base,
                        object.has("edits")
                                ? io.github.wurtzitane.avogadro.api.StructureEdits
                                        .fromJson(object.getAsJsonArray("edits"))
                                : io.github.wurtzitane.avogadro.api.StructureEdits.builder().build(),
                        object.has("materialize") && object.get("materialize").getAsBoolean());
            require(!object.has("atom_configurations") || !base.inspection().metadata()
                    .containsKey(io.github.wurtzitane.avogadro.api.PolymerNetworkDefinition.METADATA_KEY),
                    "Network atom configurations belong to template-local atoms; materialize before overriding preview indices");
            inherited = base.inspection();
            structure = base.structure();
            if (formula == null)
                formula = base.formula();
            if (name == null)
                name = base.name();
            if (!object.has("generate_item"))
                generateItem = base.generateItem();
            items = union(base.items(), items);
            itemTags = union(base.itemTags(), itemTags);
            fluids = union(base.fluids(), fluids);
            fluidTags = union(base.fluidTags(), fluidTags);
            materials = union(base.materials(), materials);
            if (!object.has("match_containers"))
                matchContainers = base.matchContainers();
        } else if (object.has("structure")) {
            var fragment = StructureRecipe.parse(object.getAsJsonObject("structure"));
            structure = fragment.toStructure(id.toString());
            object = fragmentMetadata(object, fragment);
        } else if (object.has("atoms")) {
            structure = explicit(id, object);
        } else if (object.has("smiles")) {
            require(!object.has("bonds"), "bonds requires explicit atoms geometry");
            String smiles = requiredString(object.get("smiles"), "smiles");
            require(!smiles.isBlank(), "smiles must not be empty");
            structure = SmilesParser.parse(id.toString(), smiles);
        } else {
            throw new IllegalArgumentException("one of copy, atoms, smiles, structure, or polymer is required");
        }
        if (object.has("atom_annotations")) {
            require(object.get("atom_annotations").isJsonObject(), "atom_annotations must be an object keyed by atom index");
            Map<Integer, AtomAnnotation> annotations = new LinkedHashMap<>();
            for (var entry : object.getAsJsonObject("atom_annotations").entrySet()) {
                require(entry.getKey().matches("0|[1-9][0-9]*"), "atom_annotations keys must be zero-based atom indices");
                int index = Integer.parseInt(entry.getKey());
                require(index < structure.atoms().size(), "atom_annotations index outside the structure: " + index);
                require(entry.getValue().isJsonObject(), "Each atom annotation must be an object");
                JsonObject annotation = entry.getValue().getAsJsonObject();
                for (String field : annotation.keySet())
                    require(field.equals("label") || field.equals("radical_electrons"), "Unsupported atom annotation: " + field);
                annotations.put(index, AtomAnnotation.fromJson(annotation, structure.atoms().get(index).element().symbol()));
            }
            structure = structure.withAtomAnnotations(annotations);
        }
        if (object.has("constraints")) {
            require(object.get("constraints").isJsonArray(), "constraints must be an array");
            structure = io.github.wurtzitane.avogadro.api.StructureConstraints.fromJson(object.getAsJsonArray("constraints")).apply(structure);
        }
        if (object.has("crystal")) {
            var cell = io.github.wurtzitane.avogadro.api.CrystalCell.fromJson(object.getAsJsonObject("crystal"));
            JsonObject metadata = object.has("inspection")
                    ? object.getAsJsonObject("inspection").deepCopy()
                    : new JsonObject();
            metadata.addProperty(io.github.wurtzitane.avogadro.api.CrystalCell.METADATA_KEY, cell.toJson().toString());
            metadata.addProperty("classification", "crystal");
            object = object.deepCopy();
            object.add("inspection", metadata);
        }
        if (formula == null)
            formula = formula(structure);
        return new MoleculeDef(id, structure, formula, items, itemTags, fluids, fluidTags, materials, matchContainers,
                name, generateItem, inspection(object, structure, inherited));
    }

    /**
     * Enriches structural fragment metadata into the JSON object representation.
     *
     * @param source source JSON object
     * @param fragment structure fragment instance
     * @return enriched {@link JsonObject}
     */
    private static JsonObject fragmentMetadata(JsonObject source,
            io.github.wurtzitane.avogadro.api.StructureFragment fragment) {
        JsonObject result = source.deepCopy();
        JsonObject metadata = result.has("inspection") ? result.getAsJsonObject("inspection") : new JsonObject();
        result.add("inspection", metadata);
        JsonObject attachments = new JsonObject(), capacities = new JsonObject(), configurations = new JsonObject();
        fragment.attachments().forEach(attachments::addProperty);
        new java.util.TreeMap<>(fragment.randomLinkCapacities())
                .forEach((index, value) -> capacities.addProperty(Integer.toString(index), value));
        fragment.configurations()
                .forEach((index, value) -> configurations.add(Integer.toString(index), value.toJson()));
        if (result.has("atom_configurations"))
            result.getAsJsonObject("atom_configurations").entrySet()
                    .forEach(e -> configurations.add(e.getKey(), e.getValue()));
        result.add("atom_configurations", configurations);
        if (!attachments.entrySet().isEmpty())
            metadata.addProperty(io.github.wurtzitane.avogadro.api.StructureFragment.ATTACHMENTS_METADATA_KEY,
                    attachments.toString());
        if (!capacities.entrySet().isEmpty())
            metadata.addProperty(io.github.wurtzitane.avogadro.api.StructureFragment.CAPACITIES_METADATA_KEY,
                    capacities.toString());
        return result;
    }

    /**
     * Validates source JSON syntax without compiling full definitions.
     *
     * @param source raw JSON source string
     * @throws IllegalArgumentException if syntax is invalid
     */
    public static void validateSourceSyntax(String source) {
        Syntax result = syntax(source);
        require(result.error() == null, result.error());
    }

    /**
     * Builds molecule inspection metadata from JSON definitions and structural contexts.
     *
     * @param object JSON object containing inspection data
     * @param structure molecule structure instance
     * @param base base molecule inspection instance to inherit from
     * @return constructed {@link io.github.wurtzitane.avogadro.api.MoleculeInspection}
     */
    private static io.github.wurtzitane.avogadro.api.MoleculeInspection inspection(JsonObject object,
            MoleculeStructure structure, io.github.wurtzitane.avogadro.api.MoleculeInspection base) {
        var elements = new LinkedHashMap<>(base.elements());
        var atoms = new LinkedHashMap<>(base.atoms());
        var metadata = new LinkedHashMap<>(base.metadata());
        var components = new LinkedHashSet<>(base.components());
        if (object.has("elements"))
            object.getAsJsonObject("elements").entrySet().forEach(entry -> {
                require(ELEMENT_SYMBOL.matcher(entry.getKey()).matches(), "Invalid inspection element symbol");
                elements.put(entry.getKey(), io.github.wurtzitane.avogadro.api.AtomicConfiguration
                        .fromJson(entry.getValue().getAsJsonObject()));
            });
        if (object.has("atom_configurations"))
            object.getAsJsonObject("atom_configurations").entrySet().forEach(entry -> {
                int index = Integer.parseInt(entry.getKey());
                require(index >= 0 && index < structure.atoms().size(), "Atomic configuration index outside molecule");
                atoms.put(index, io.github.wurtzitane.avogadro.api.AtomicConfiguration
                        .fromJson(entry.getValue().getAsJsonObject()));
            });
        if (!object.has("copy") && object.has("atoms")) {
            JsonArray explicit = object.getAsJsonArray("atoms");
            for (int i = 0; i < explicit.size(); i++) {
                var atom = explicit.get(i).getAsJsonObject();
                if (atom.has("nuclear"))
                    atoms.put(i, io.github.wurtzitane.avogadro.api.AtomicConfiguration
                            .fromJson(atom.getAsJsonObject("nuclear")));
            }
        }
        if (object.has("inspection"))
            object.getAsJsonObject("inspection").entrySet().forEach(
                    entry -> metadata.put(entry.getKey(), requiredString(entry.getValue(), "inspection metadata")));
        for (String id : strings(object, "components", false))
            components.add(identifier(id));
        for (int index : atoms.keySet())
            require(!structure.atoms().get(index).element().symbol().equals("*"),
                    "An unresolved external group cannot have a nuclear/electron configuration; resolve it first");
        return new io.github.wurtzitane.avogadro.api.MoleculeInspection(elements, atoms, List.copyOf(components),
                metadata);
    }

    /**
     * Registry-wide strings remain valid; @metadata and #compound-SNBT add narrower predicates.
     *
     * @param text item association selector string
     * @return validated item association string
     */
    public static String itemAssociation(String text) {
        ItemAssociation.parse(text);
        return text;
    }

    /**
     * Parses explicit atom and bond arrays into a molecule structure.
     *
     * @param id molecule resource location identifier
     * @param object JSON object containing atoms and optional bonds
     * @return constructed {@link MoleculeStructure}
     */
    private static MoleculeStructure explicit(ResourceLocation id, JsonObject object) {
        JsonElement atomJson = object.get("atoms");
        require(atomJson.isJsonArray(), "atoms must be an array");
        JsonArray atoms = atomJson.getAsJsonArray();
        require(atoms.size() > 0, "atoms must not be empty");
        MoleculeStructure.Builder builder = MoleculeStructure.builder(id.toString(), "");
        for (JsonElement element : atoms) {
            require(element.isJsonObject(), "each atom must be an object");
            JsonObject atom = element.getAsJsonObject();
            for (String key : atom.keySet())
                require(ATOM_FIELDS.contains(key), "unsupported atom field '" + key + "'");
            String symbol = requiredString(atom.get("e"), "atom.e");
            require(symbol.equals("*") || ELEMENT_SYMBOL.matcher(symbol).matches(), "atom.e must be an element symbol or *");
            builder.addAtom(AtomElement.bySymbol(symbol), coordinate(atom, "x"), coordinate(atom, "y"),
                    coordinate(atom, "z"), AtomAnnotation.fromJson(atom, symbol));
        }
        if (!object.has("bonds"))
            return builder.autoBond().build();
        JsonElement bondJson = object.get("bonds");
        require(bondJson.isJsonArray(), "bonds must be an array");
        for (JsonElement element : bondJson.getAsJsonArray()) {
            require(element.isJsonArray(), "each bond must be an array");
            JsonArray bond = element.getAsJsonArray();
            require(bond.size() == 2 || bond.size() == 3, "each bond requires two indices and optional type");
            builder.bond(index(bond.get(0)), index(bond.get(1)),
                    bond.size() == 3 ? bondType(bond.get(2)) : BondType.SINGLE);
        }
        return builder.build();
    }

    /**
     * Extracts a finite coordinate float value from an atom JSON object.
     *
     * @param object atom JSON object
     * @param key coordinate key string (e.g., "x", "y", "z")
     * @return coordinate float value
     */
    private static float coordinate(JsonObject object, String key) {
        JsonElement element = object.get(key);
        require(element != null && element.isJsonPrimitive() && element.getAsJsonPrimitive().isNumber(),
                "atom." + key + " must be a finite number");
        float value = element.getAsFloat();
        require(Float.isFinite(value), "atom." + key + " must be finite");
        return value;
    }

    /**
     * Parses and validates a nonnegative integer index from a JSON element.
     *
     * @param element JSON element containing index
     * @return validated integer index
     */
    private static int index(JsonElement element) {
        require(element.isJsonPrimitive() && element.getAsJsonPrimitive().isNumber(), "bond index must be an integer");
        double value = element.getAsDouble();
        require(Double.isFinite(value) && value == Math.floor(value) && value >= 0 && value <= Integer.MAX_VALUE,
                "bond index must be a nonnegative integer");
        return (int) value;
    }

    /**
     * Parses and validates a bond type from a JSON element.
     *
     * @param element JSON element representing bond type
     * @return resolved {@link BondType}
     */
    private static BondType bondType(JsonElement element) {
        require(element.isJsonPrimitive(), "bond type must be a string or number");
        if (element.getAsJsonPrimitive().isNumber()) {
            int order = index(element);
            return order >= 4
                    ? BondType.QUADRUPLE
                    : order == 3 ? BondType.TRIPLE : order == 2 ? BondType.DOUBLE : BondType.SINGLE;
        }
        String text = requiredString(element, "bond type").trim().toUpperCase(Locale.ROOT);
        return switch (text) {
            case "SINGLE", "-", "1" -> BondType.SINGLE;
            case "DOUBLE", "=", "2" -> BondType.DOUBLE;
            case "TRIPLE", "#", "3" -> BondType.TRIPLE;
            case "QUADRUPLE", "4" -> BondType.QUADRUPLE;
            default -> throw new IllegalArgumentException("unsupported bond type '" + text + "'");
        };
    }

    /**
     * Parses and validates a resource location identifier string.
     *
     * @param text resource location string
     * @return parsed {@link ResourceLocation}
     */
    public static ResourceLocation identifier(String text) {
        require(text != null && IDENTIFIER.matcher(text).matches(), "invalid resource id '" + text + "'");
        return new ResourceLocation(text);
    }

    /**
     * Parses a list of strings from a JSON object array field.
     *
     * @param object parent JSON object
     * @param key array field key
     * @param tags {@code true} if entries represent tags, {@code false} otherwise
     * @return list of parsed string values
     */
    private static List<String> strings(JsonObject object, String key, boolean tags) {
        if (!object.has(key))
            return List.of();
        require(object.get(key).isJsonArray(), key + " must be an array of strings");
        List<String> values = new ArrayList<>();
        for (JsonElement element : object.getAsJsonArray(key)) {
            String value = requiredString(element, key + " entry");
            if (tags && value.startsWith("#"))
                value = value.substring(1);
            if (key.equals("items"))
                itemAssociation(value);
            else if (key.equals("fluids"))
                value = FluidAssociation.parse(value).selector();
            else
                identifier(value);
            values.add(value);
        }
        return values;
    }

    /**
     * Returns an optional string value from a JSON object.
     *
     * @param object parent JSON object
     * @param key string field key
     * @return string value, or {@code null} if absent
     */
    private static String optionalString(JsonObject object, String key) {
        return object.has(key) ? requiredString(object.get(key), key) : null;
    }

    /**
     * Returns a required string value from a JSON element.
     *
     * @param element JSON element
     * @param label descriptive label for error reporting
     * @return string value
     */
    private static String requiredString(JsonElement element, String label) {
        require(element != null && element.isJsonPrimitive() && element.getAsJsonPrimitive().isString(),
                label + " must be a string");
        return element.getAsString();
    }

    /**
     * Computes the union of two string lists while preserving insertion order and uniqueness.
     *
     * @param first first string list
     * @param second second string list
     * @return combined unmodifiable list
     */
    private static List<String> union(List<String> first, List<String> second) {
        if (first.isEmpty() && second.isEmpty())
            return List.of();
        LinkedHashSet<String> values = new LinkedHashSet<>(first);
        values.addAll(second);
        return List.copyOf(values);
    }

    /**
     * Computes the chemical formula string for a molecule structure.
     *
     * @param structure molecule structure instance
     * @return formatted chemical formula string with subscript characters
     */
    public static String formula(MoleculeStructure structure) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (MoleculeStructure.Atom atom : structure.atoms())
            counts.merge(atom.element().symbol().equals("*") ? atom.annotation().label() : atom.element().symbol(), 1, Integer::sum);
        StringBuilder result = new StringBuilder();
        take(counts, result, "C");
        take(counts, result, "H");
        counts.keySet().stream().sorted().toList().forEach(key -> take(counts, result, key));
        return result.toString();
    }

    /**
     * Appends an element symbol and its subscripted atom count to the formula string builder.
     *
     * @param counts atom count map
     * @param out target string builder
     * @param key element symbol key
     */
    private static void take(Map<String, Integer> counts, StringBuilder out, String key) {
        Integer count = counts.remove(key);
        if (count == null)
            return;
        out.append(key);
        if (count > 1)
            for (char digit : count.toString().toCharArray())
                out.append((char) (0x2080 + digit - '0'));
    }

    /**
     * Asserts that a condition is true, throwing an illegal argument exception with the specified message otherwise.
     *
     * @param condition boolean condition to test
     * @param message error message string
     */
    private static void require(boolean condition, String message) {
        if (!condition)
            throw new IllegalArgumentException(message);
    }

    /**
     * Extracts an error message string from a runtime exception.
     *
     * @param error runtime exception instance
     * @return error message string or class simple name
     */
    private static String message(RuntimeException error) {
        return error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage();
    }
}
