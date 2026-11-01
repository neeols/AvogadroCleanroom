package io.github.wurtzitane.avogadro.api;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.github.wurtzitane.avogadro.molecule.MoleculeDef;
import io.github.wurtzitane.avogadro.molecule.MoleculeLoader;
import io.github.wurtzitane.avogadro.molecule.MoleculeStructure;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Objects;

/**
 * Definition-preserving snapshots and edits.
 * <p>Geometry changes never silently rewrite template families.
 */
public final class MoleculeEdits {

    /**
     * Private constructor to prevent instantiation of this utility class.
     */
    private MoleculeEdits() {}

    /**
     * Applies structure edits to a source molecule definition, producing a new modified {@link MoleculeDef}.
     *
     * @param newId       the new resource identifier for the resulting molecule
     * @param source      the source {@link MoleculeDef} to edit
     * @param edits       the {@link StructureEdits} to apply
     * @param materialize whether to materialize templates into concrete structures when edited
     * @return a new {@link MoleculeDef} containing the applied edits
     * @throws IllegalArgumentException if attempting to edit a template preview without materialization
     */
    public static MoleculeDef apply(String newId, MoleculeDef source, StructureEdits edits, boolean materialize) {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(edits, "edits");
        var id = MoleculeLoader.identifier(newId);
        var metadata = new LinkedHashMap<>(source.inspection().metadata());
        boolean template = metadata.containsKey(PolymerDefinition.METADATA_KEY)
                || metadata.containsKey(PolymerNetworkDefinition.METADATA_KEY)
                || metadata.containsKey(CrystalCell.METADATA_KEY);

        if (template && !edits.isEmpty() && !materialize) {
            throw new IllegalArgumentException("Materialize the polymer/crystal preview before editing its preview, or edit its template.");
        }

        StructureFragment sourceFragment = StructureFragment.from(source);
        StructureFragment result = edits.apply(sourceFragment).fragment();
        MoleculeStructure structure = edits.changesGeometry() ? result.toStructure(id.toString()) : source.structure();

        if (materialize && template) {
            metadata.remove(PolymerDefinition.METADATA_KEY);
            metadata.remove(PolymerNetworkDefinition.METADATA_KEY);
            metadata.remove(CrystalCell.METADATA_KEY);
            metadata.remove(MoleculeClassification.METADATA_KEY);
        }

        putAuthoringMetadata(metadata, result);
        String formula = edits.changesComposition() || (materialize && template) ? MoleculeLoader.formula(structure) : source.formula();
        MoleculeInspection inspection = new MoleculeInspection(source.inspection().elements(), result.configurations(),
                source.inspection().components(), metadata);

        return new MoleculeDef(
                id, structure, formula, source.items(), source.itemTags(), source.fluids(),
                source.fluidTags(), source.materials(), source.matchContainers(), source.name(),
                source.generateItem(), inspection
        );
    }

    /**
     * Creates a self-contained source JSON snapshot of a molecule definition,
     * retaining template families when the source has one.
     *
     * @param source the {@link MoleculeDef} to snapshot
     * @return a {@link JsonObject} representing the complete molecule specification
     */
    public static JsonObject snapshot(MoleculeDef source) {
        Objects.requireNonNull(source, "source");
        JsonObject result = new JsonObject();
        var inspection = source.inspection();
        String network = inspection.metadata().get(PolymerNetworkDefinition.METADATA_KEY);
        String polymer = inspection.metadata().get(PolymerDefinition.METADATA_KEY);
        String cell = inspection.metadata().get(CrystalCell.METADATA_KEY);

        if (network != null) {
            result.add("polymer_network", JsonParser.parseString(network).getAsJsonObject());
        } else if (polymer != null) {
            result.add("polymer", JsonParser.parseString(polymer).getAsJsonObject());
        } else {
            result.add("structure", StructureFragment.from(source).toJson());
            if (cell != null) {
                result.add("crystal", JsonParser.parseString(cell).getAsJsonObject());
            }
        }

        result.addProperty("formula", source.formula());
        if (source.name() != null) {
            result.addProperty("name", source.name());
        }
        result.addProperty("generate_item", source.generateItem());
        result.addProperty("match_containers", source.matchContainers());

        strings(result, "items", source.items());
        strings(result, "item_tags", source.itemTags());
        strings(result, "fluids", source.fluids());
        strings(result, "fluid_tags", source.fluidTags());
        strings(result, "materials", source.materials());

        JsonObject elements = new JsonObject();
        inspection.elements().forEach((symbol, configuration) -> elements.add(symbol, configuration.toJson()));
        if (!elements.entrySet().isEmpty()) {
            result.add("elements", elements);
        }

        // Network per-atom configurations belong to its templates; flattened preview indices are unstable.
        if (polymer != null && !inspection.atoms().isEmpty()) {
            JsonObject configurations = new JsonObject();
            inspection.atoms().forEach((index, configuration) -> configurations.add(index.toString(), configuration.toJson()));
            result.add("atom_configurations", configurations);
        }

        strings(result, "components", inspection.components().stream().map(Object::toString).toList());

        JsonObject metadata = new JsonObject();
        inspection.metadata().forEach(metadata::addProperty);
        if (!metadata.entrySet().isEmpty()) {
            result.add("inspection", metadata);
        }

        return result;
    }

    /**
     * Injects authoring metadata (attachments and link capacities) into the metadata map.
     *
     * @param metadata the metadata map to update
     * @param result   the structure fragment providing attachments and capacities
     */
    static void putAuthoringMetadata(java.util.Map<String, String> metadata, StructureFragment result) {
        metadata.remove(StructureFragment.ATTACHMENTS_METADATA_KEY);
        metadata.remove(StructureFragment.CAPACITIES_METADATA_KEY);

        if (!result.attachments().isEmpty()) {
            JsonObject names = new JsonObject();
            result.attachments().forEach(names::addProperty);
            metadata.put(StructureFragment.ATTACHMENTS_METADATA_KEY, names.toString());
        }

        if (!result.randomLinkCapacities().isEmpty()) {
            JsonObject capacities = new JsonObject();
            new java.util.TreeMap<>(result.randomLinkCapacities()).forEach((index, capacity) -> capacities.addProperty(index.toString(), capacity));
            metadata.put(StructureFragment.CAPACITIES_METADATA_KEY, capacities.toString());
        }
    }

    /**
     * Helper method to serialize a list of strings into a JSON array property.
     *
     * @param result the parent {@link JsonObject}
     * @param key    the property key
     * @param values the list of string values
     */
    private static void strings(JsonObject result, String key, List<String> values) {
        JsonArray array = new JsonArray();
        values.forEach(array::add);
        result.add(key, array);
    }
}