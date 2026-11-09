package io.github.wurtzitane.avogadro.api;

import java.util.LinkedHashMap;
import java.util.Map;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import io.github.wurtzitane.avogadro.molecule.MoleculeLoader;
import io.github.wurtzitane.avogadro.molecule.MoleculeResources;

import net.minecraft.util.ResourceLocation;

/**
 * Collects several registration changes so they can be applied together.
 *
 * <p>
 * A batch changes only one owner's definitions. Removing that owner's entry can reveal a definition supplied by another
 * source, rather than removing the molecule everywhere.
 */
public final class MoleculeBatch {

    /** Map of molecule definition identifiers to their serialized JSON representations. */
    private final Map<ResourceLocation, String> definitions = new LinkedHashMap<>();

    /** Map of item tag identifiers to their serialized JSON tag representations. */
    private final Map<ResourceLocation, String> itemTags = new LinkedHashMap<>();

    /** Map of fluid tag identifiers to their serialized JSON tag representations. */
    private final Map<ResourceLocation, String> fluidTags = new LinkedHashMap<>();

    /**
     * Constructs a new, empty {@link MoleculeBatch}.
     */
    public MoleculeBatch() {}

    /**
     * Creates a {@link MoleculeBatch} initialized with existing resource data.
     *
     * @param initial the initial {@link MoleculeResources.Data} to populate this batch with
     */
    public MoleculeBatch(MoleculeResources.Data initial) {
        definitions.putAll(initial.molecules());
        itemTags.putAll(initial.itemTags());
        fluidTags.putAll(initial.fluidTags());
    }

    /**
     * Registers a molecule specification in this batch.
     *
     * @param definition the {@link MoleculeSpec} to register
     * @return this batch instance for method chaining
     */
    public MoleculeBatch register(MoleculeSpec definition) {
        definitions.put(definition.id(), definition.json());
        return this;
    }

    /**
     * Builds and registers a molecule definition from a builder.
     *
     * @param definition the {@link MoleculeBuilder} containing the definition
     * @return this batch instance for method chaining
     */
    public MoleculeBatch register(MoleculeBuilder definition) {
        return register(definition.build());
    }

    /**
     * Registers a molecule definition using its string identifier and raw property fields map.
     *
     * @param id the string identifier of the molecule
     * @param fields a map of property fields for the molecule definition
     * @return this batch instance for method chaining
     */
    public MoleculeBatch register(String id, Map<?, ?> fields) {
        return register(MoleculeBuilder.from(id, fields));
    }

    /**
     * Removes a molecule definition entry by its string identifier.
     *
     * @param id the string identifier of the molecule to remove
     * @return this batch instance for method chaining
     */
    public MoleculeBatch remove(String id) {
        return remove(MoleculeLoader.identifier(id));
    }

    /**
     * Removes a molecule definition entry by its resource location identifier.
     *
     * @param id the {@link ResourceLocation} identifier of the molecule to remove
     * @return this batch instance for method chaining
     */
    public MoleculeBatch remove(ResourceLocation id) {
        definitions.remove(id);
        return this;
    }

    /**
     * Adds or updates an item tag entry in this batch.
     *
     * @param id the item tag identifier string
     * @param replace whether this tag list replaces existing tags or appends to them
     * @param values an array of tag values or resource location strings
     * @return this batch instance for method chaining
     */
    public MoleculeBatch itemTag(String id, boolean replace, String... values) {
        return tag(itemTags, id, replace, values);
    }

    /**
     * Adds or updates a fluid tag entry in this batch.
     *
     * @param id the fluid tag identifier string
     * @param replace whether this tag list replaces existing tags or appends to them
     * @param values an array of tag values or resource location strings
     * @return this batch instance for method chaining
     */
    public MoleculeBatch fluidTag(String id, boolean replace, String... values) {
        return tag(fluidTags, id, replace, values);
    }

    /**
     * Removes an item tag entry by its string identifier.
     *
     * @param id the string identifier of the item tag to remove
     * @return this batch instance for method chaining
     */
    public MoleculeBatch removeItemTag(String id) {
        itemTags.remove(MoleculeLoader.identifier(id));
        return this;
    }

    /**
     * Removes a fluid tag entry by its string identifier.
     *
     * @param id the string identifier of the fluid tag to remove
     * @return this batch instance for method chaining
     */
    public MoleculeBatch removeFluidTag(String id) {
        fluidTags.remove(MoleculeLoader.identifier(id));
        return this;
    }

    /**
     * Clears all definitions, item tags, and fluid tags currently stored in this batch.
     *
     * @return this batch instance for method chaining
     */
    public MoleculeBatch clear() {
        definitions.clear();
        itemTags.clear();
        fluidTags.clear();
        return this;
    }

    /**
     * Exports the accumulated changes as an immutable {@link MoleculeResources.Data} structure.
     *
     * @return a new {@link MoleculeResources.Data} representing this batch's contents
     */
    public MoleculeResources.Data data() {
        return new MoleculeResources.Data(definitions, itemTags, fluidTags);
    }

    /**
     * Helper method to construct and store a serialized JSON tag entry into the specified target map.
     *
     * @param target the target map (item tags or fluid tags)
     * @param id the tag identifier string
     * @param replace whether the tag should replace previous entries
     * @param values the array of string values for the tag
     * @return this batch instance for method chaining
     */
    private MoleculeBatch tag(Map<ResourceLocation, String> target, String id, boolean replace, String[] values) {
        JsonArray entries = new JsonArray();
        for (String value : values) {
            MoleculeLoader.identifier(value.startsWith("#") ? value.substring(1) : value);
            entries.add(value);
        }
        JsonObject tag = new JsonObject();
        tag.addProperty("replace", replace);
        tag.add("values", entries);
        target.put(MoleculeLoader.identifier(id), tag.toString());
        return this;
    }
}
