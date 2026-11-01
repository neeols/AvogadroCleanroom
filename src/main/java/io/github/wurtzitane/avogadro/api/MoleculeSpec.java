package io.github.wurtzitane.avogadro.api;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.github.wurtzitane.avogadro.molecule.MoleculeLoader;
import net.minecraft.util.ResourceLocation;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * An immutable registration source.
 * <p>Copy references resolve after source layers are merged.
 */
public final class MoleculeSpec {

    /** The unique resource location identifier for this molecule specification. */
    private final ResourceLocation id;

    /** The serialized JSON string representing the molecule specification. */
    private final String json;

    /**
     * Private constructor to instantiate an immutable {@link MoleculeSpec}.
     *
     * @param id   the {@link ResourceLocation} identifier
     * @param json the serialized JSON specification string
     */
    private MoleculeSpec(ResourceLocation id, String json) {
        this.id = id;
        this.json = json;
    }

    /**
     * Parses and validates a molecule specification from a string identifier and JSON content string.
     *
     * @param id   the string identifier for the molecule
     * @param json the raw JSON specification string
     * @return a new validated {@link MoleculeSpec} instance
     * @throws IllegalArgumentException if syntax validation or dependency parsing fails
     */
    public static MoleculeSpec fromJson(String id, String json) {
        return fromJson(MoleculeLoader.identifier(id), json);
    }

    /**
     * Asynchronously parses and validates a molecule specification from a string identifier and JSON string.
     * <p>Completion callbacks run on the completing thread, not a Minecraft authority thread.
     *
     * @param id   the string identifier for the molecule
     * @param json the raw JSON specification string
     * @return a {@link java.util.concurrent.CompletableFuture} resolving to the {@link MoleculeSpec}
     */
    public static java.util.concurrent.CompletableFuture<MoleculeSpec> fromJsonAsync(String id, String json) {
        return io.github.wurtzitane.avogadro.molecule.MoleculeTasks.async(() -> fromJson(id, json));
    }

    /**
     * Asynchronously parses and validates a molecule specification from a resource location and JSON string.
     * <p>Completion callbacks run on the completing thread, not a Minecraft authority thread.
     *
     * @param id   the {@link ResourceLocation} identifier for the molecule
     * @param json the raw JSON specification string
     * @return a {@link java.util.concurrent.CompletableFuture} resolving to the {@link MoleculeSpec}
     */
    public static java.util.concurrent.CompletableFuture<MoleculeSpec> fromJsonAsync(ResourceLocation id, String json) {
        return io.github.wurtzitane.avogadro.molecule.MoleculeTasks.async(() -> fromJson(id, json));
    }

    /**
     * Parses and validates a molecule specification from a resource location identifier and JSON content string.
     *
     * @param id   the {@link ResourceLocation} identifier
     * @param json the raw JSON specification string
     * @return a new validated {@link MoleculeSpec} instance
     * @throws IllegalArgumentException if syntax validation, copy reference checks, or parsing fails
     */
    public static MoleculeSpec fromJson(ResourceLocation id, String json) {
        Objects.requireNonNull(id, "id");
        JsonObject object = JsonParser.parseString(Objects.requireNonNull(json, "json")).getAsJsonObject();
        MoleculeLoader.validateSourceSyntax(object.toString());
        JsonObject checked = object;
        Map<ResourceLocation, String> validation = new LinkedHashMap<>();

        if (object.has("copy")) {
            // Geometry-dependent item validation waits for the real copy source, not this placeholder.
            if (object.has("generate_item") && (!object.get("generate_item").isJsonPrimitive()
                    || !object.getAsJsonPrimitive("generate_item").isBoolean())) {
                throw new IllegalArgumentException("generate_item must be a boolean");
            }
            checked = object.deepCopy();
            checked.addProperty("generate_item", false);
            if (checked.has("edits")) {
                checked.remove("edits");
                checked.remove("materialize");
            }
            if (checked.has("atom_configurations")) {
                checked.getAsJsonObject("atom_configurations").entrySet().forEach(entry -> {
                    if (Integer.parseInt(entry.getKey()) < 0) {
                        throw new IllegalArgumentException("Negative atomic index");
                    }
                    AtomicConfiguration.fromJson(entry.getValue().getAsJsonObject());
                });
                checked.remove("atom_configurations");
            }
            checked.remove("materialize");
            ResourceLocation source = MoleculeLoader.identifier(object.get("copy").getAsString());
            if (source.equals(id)) {
                throw new IllegalArgumentException("A molecule cannot copy itself: " + id);
            }
            validation.put(source, "{\"smiles\":\"[C]\"}");
        }

        String normalized = object.toString();
        validation.put(id, checked.toString());
        var errors = new ArrayList<String>();
        if (!MoleculeLoader.parseAll(validation, errors::add).containsKey(id)) {
            throw new IllegalArgumentException(String.join("; ", errors));
        }
        return new MoleculeSpec(id, normalized);
    }

    /**
     * Creates a molecule specification from an existing compiled molecule definition.
     *
     * @param id     the string identifier for the specification
     * @param source the source {@link io.github.wurtzitane.avogadro.molecule.MoleculeDef} definition
     * @return a new {@link MoleculeSpec} instance
     */
    public static MoleculeSpec fromDefinition(String id, io.github.wurtzitane.avogadro.molecule.MoleculeDef source) {
        return fromJson(id, MoleculeEdits.snapshot(source).toString());
    }

    /**
     * Retrieves the resource location identifier of this specification.
     *
     * @return the {@link ResourceLocation} identifier
     */
    public ResourceLocation id() {
        return id;
    }

    /**
     * Retrieves the serialized JSON string of this specification.
     *
     * @return the JSON string
     */
    public String json() {
        return json;
    }

    /**
     * Converts this specification into a mutable {@link MoleculeBuilder} for further editing.
     *
     * @return a new {@link MoleculeBuilder} instance initialized with this spec's data
     */
    public MoleculeBuilder toBuilder() {
        return MoleculeBuilder.fromJson(id, json);
    }
}