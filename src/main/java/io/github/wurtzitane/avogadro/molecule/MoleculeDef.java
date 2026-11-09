package io.github.wurtzitane.avogadro.molecule;

import java.util.List;
import java.util.Objects;

import net.minecraft.util.ResourceLocation;

/** Molecule geometry, associations and optional generated item metadata. */
public record MoleculeDef(ResourceLocation id, MoleculeStructure structure, String formula, List<String> items,
        List<String> itemTags, List<String> fluids, List<String> fluidTags, List<String> materials,
        boolean matchContainers, String name, boolean generateItem,
        io.github.wurtzitane.avogadro.api.MoleculeInspection inspection) {

    /**
     * Constructs a molecule definition with specified properties, generation flags, and inspection metadata.
     *
     * @param id resource location identifier
     *
     * @param structure molecule structure instance
     * @param formula chemical formula string
     * @param items associated item selectors list
     * @param itemTags associated item tags list
     * @param fluids associated fluid selectors list
     * @param fluidTags associated fluid tags list
     * @param materials associated material names list
     * @param matchContainers {@code true} to match container items, {@code false} otherwise
     * @param name optional custom name string
     * @param generateItem {@code true} to generate an item representation, {@code false} otherwise
     */
    public MoleculeDef(ResourceLocation id, MoleculeStructure structure, String formula, List<String> items,
            List<String> itemTags, List<String> fluids, List<String> fluidTags, List<String> materials,
            boolean matchContainers, String name, boolean generateItem) {
        this(id, structure, formula, items, itemTags, fluids, fluidTags, materials, matchContainers, name, generateItem,
                io.github.wurtzitane.avogadro.api.MoleculeInspection.EMPTY);
    }

    /**
     * Constructs a molecule definition with specified properties and default item generation settings.
     *
     * @param id resource location identifier
     * @param structure molecule structure instance
     * @param formula chemical formula string
     * @param items associated item selectors list
     * @param itemTags associated item tags list
     * @param fluids associated fluid selectors list
     * @param fluidTags associated fluid tags list
     * @param materials associated material names list
     * @param matchContainers {@code true} to match container items, {@code false} otherwise
     */
    public MoleculeDef(ResourceLocation id, MoleculeStructure structure, String formula, List<String> items,
            List<String> itemTags, List<String> fluids, List<String> fluidTags, List<String> materials,
            boolean matchContainers) {
        this(id, structure, formula, items, itemTags, fluids, fluidTags, materials, matchContainers, null, false);
    }

    /**
     * Canonical constructor that validates all molecule properties, classification rules, crystal parameters, polymer
     * definitions, and composition consistency.
     *
     * @throws NullPointerException if {@code id}, {@code inspection}, {@code structure}, or {@code formula} is null
     * @throws IllegalArgumentException if name bounds, classification requirements, crystal parameters, polymer
     *     definitions, or composition rules are violated
     */
    public MoleculeDef {
        Objects.requireNonNull(id, "Molecule ID cannot be null");
        Objects.requireNonNull(inspection, "Molecule inspection metadata cannot be null");
        Objects.requireNonNull(structure, "Molecule structure cannot be null");
        Objects.requireNonNull(formula, "Chemical formula cannot be null");

        // Validate custom display name length and format if present
        if (name != null && (name.isBlank() || name.length() > 256)) {
            throw new IllegalArgumentException("Molecule name must contain between 1 and 256 characters");
        }

        // Extract classification and structural metadata
        String classification = inspection.metadata().getOrDefault("classification", "auto")
                .toLowerCase(java.util.Locale.ROOT);

        String cellJson = inspection.metadata().get(io.github.wurtzitane.avogadro.api.CrystalCell.METADATA_KEY);
        boolean crystal = cellJson != null;

        if (crystal) {
            io.github.wurtzitane.avogadro.api.CrystalCell
                    .fromJson(com.google.gson.JsonParser.parseString(cellJson).getAsJsonObject());
        }

        if ("crystal".equals(classification) && !crystal) {
            throw new IllegalArgumentException("Crystal classification requires valid cell parameters");
        }

        // Extract polymer and network definition JSON payloads
        String polymerJson = inspection.metadata()
                .get(io.github.wurtzitane.avogadro.api.PolymerDefinition.METADATA_KEY);
        String networkJson = inspection.metadata()
                .get(io.github.wurtzitane.avogadro.api.PolymerNetworkDefinition.METADATA_KEY);
        boolean network = networkJson != null;
        boolean polymer = polymerJson != null;

        // Validate polymer networks
        if (network) {
            if (polymer || crystal) {
                throw new IllegalArgumentException(
                        "Network, linear polymer, and crystal templates are mutually exclusive");
            }
            var template = io.github.wurtzitane.avogadro.api.PolymerNetworkDefinition
                    .fromJson(com.google.gson.JsonParser.parseString(networkJson).getAsJsonObject());
            var preview = template.preview(template.defaults());
            if (!io.github.wurtzitane.avogadro.api.MoleculeWeight.composition(formula)
                    .equals(io.github.wurtzitane.avogadro.api.MoleculeWeight.composition(preview.structure()))
                    || !io.github.wurtzitane.avogadro.api.MoleculeWeight.composition(structure).equals(
                            io.github.wurtzitane.avogadro.api.MoleculeWeight.composition(preview.structure()))) {
                throw new IllegalArgumentException(
                        "Network formula and geometry must precisely describe the default finite preview");
            }
        }

        if ("polymer".equals(classification) && !polymer && !network) {
            throw new IllegalArgumentException("Polymer classification requires repeat and terminal groups");
        }

        // Validate linear polymers
        if (polymer) {
            if (crystal) {
                throw new IllegalArgumentException("A molecule definition cannot be both a polymer and a crystal");
            }
            var chain = io.github.wurtzitane.avogadro.api.PolymerDefinition
                    .fromJson(com.google.gson.JsonParser.parseString(polymerJson).getAsJsonObject());
            if (!formula.equals(chain.formula())) {
                throw new IllegalArgumentException("Polymer formula must describe its variable repeat unit");
            }
            if (!io.github.wurtzitane.avogadro.api.MoleculeWeight.composition(structure)
                    .equals(io.github.wurtzitane.avogadro.api.MoleculeWeight
                            .composition(chain.chainFormula(chain.previewUnits())))) {
                throw new IllegalArgumentException("Polymer preview must match its repeat units and terminal groups");
            }
        }

        // Validate item generation and crystal stoichiometry constraints
        if (crystal && structure.hasExternalGroups())
            throw new IllegalArgumentException("Resolve external groups before defining a crystal formula unit");
        if ((generateItem || crystal) && !polymer && !structure.hasExternalGroups()) {
            if ("mixture".equals(classification)) {
                throw new IllegalArgumentException("Variable mixtures cannot be generated as molecule samples");
            }
            var unit = io.github.wurtzitane.avogadro.api.MoleculeWeight.composition(formula);
            var actual = io.github.wurtzitane.avogadro.api.MoleculeWeight.composition(structure);

            if (!crystal && !unit.equals(actual)) {
                throw new IllegalArgumentException("Generated item formula must match the rendered atom counts");
            }

            if (crystal) {
                long factor = -1;
                if (!unit.keySet().equals(actual.keySet())) {
                    throw new IllegalArgumentException("Cell and formula element sets differ");
                }
                for (var entry : unit.entrySet()) {
                    long count = actual.get(entry.getKey());
                    long amount = entry.getValue();
                    if (count % amount != 0 || (factor != -1 && factor != count / amount)) {
                        throw new IllegalArgumentException(
                                "Cell must contain whole integer multiples of its formula unit");
                    }
                    factor = count / amount;
                }
            }
        }

        // Defensive unmodifiable list copies
        items = List.copyOf(items);
        itemTags = List.copyOf(itemTags);
        fluids = List.copyOf(fluids);
        fluidTags = List.copyOf(fluidTags);
        materials = List.copyOf(materials);
    }
}
