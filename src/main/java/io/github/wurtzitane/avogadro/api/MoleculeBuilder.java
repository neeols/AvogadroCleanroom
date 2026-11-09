package io.github.wurtzitane.avogadro.api;

import java.util.Collection;
import java.util.Map;
import java.util.Objects;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import io.github.wurtzitane.avogadro.molecule.BondType;
import io.github.wurtzitane.avogadro.molecule.MoleculeLoader;

import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.util.ResourceLocation;
import net.minecraftforge.fluids.Fluid;
import net.minecraftforge.fluids.FluidStack;

/**
 * Builds a molecule definition: its shape, display name, matching items and fluids, and optional atom details.
 *
 * <p>
 * Call {@link #build()} to validate the definition. To make it available in game, register it through
 * {@link AvogadroApi#owner(String)}.
 *
 * <pre>{@code
 * var water = AvogadroApi.builder("example:water")
 *     .name("Water")
 *     .smiles("O");
 * AvogadroApi.owner("example").register(water);
 * }</pre>
 */
public final class MoleculeBuilder {

    /** The unique resource location identifier of the molecule being built. */
    private final ResourceLocation id;

    /** The internal JSON object accumulating molecule fields and properties. */
    private final JsonObject fields;

    /**
     * Creates a molecule builder from a string identifier.
     *
     * @param id the string identifier for the molecule
     */
    public MoleculeBuilder(String id) {
        this(MoleculeLoader.identifier(id));
    }

    /**
     * Creates a molecule builder from a resource location identifier.
     *
     * @param id the {@link ResourceLocation} identifier for the molecule
     */
    public MoleculeBuilder(ResourceLocation id) {
        this(id, new JsonObject());
    }

    /**
     * Assigns an external-group label to an existing wildcard atom after geometry loading.
     * @param atom zero-based wildcard index in the loaded geometry
     * @param label 1–8 character group name, such as R1
     * @return this builder
     */
    public MoleculeBuilder rGroup(int atom, String label) {
        return annotate(atom, new io.github.wurtzitane.avogadro.molecule.AtomAnnotation(label, 0));
    }

    /**
     * Records explicit unpaired electrons, independently of valence and examiner shell data.
     * @param atom zero-based real atom index in the loaded geometry
     * @param electrons count from zero through four; zero explicitly clears an inherited radical
     * @return this builder
     */
    public MoleculeBuilder radical(int atom, int electrons) {
        return annotate(atom, new io.github.wurtzitane.avogadro.molecule.AtomAnnotation("", electrons));
    }

    /**
     * Stores one annotation using the common JSON representation.
     * @param atom nonnegative atom index
     * @param annotation validated metadata
     * @return this builder
     */
    private MoleculeBuilder annotate(int atom, io.github.wurtzitane.avogadro.molecule.AtomAnnotation annotation) {
        if (atom < 0) throw new IllegalArgumentException("Atom index cannot be negative");
        JsonObject values = fields.has("atom_annotations") ? fields.getAsJsonObject("atom_annotations") : new JsonObject();
        JsonObject entry = new JsonObject(); annotation.write(entry);
        if (annotation.label().isEmpty()) entry.addProperty("radical_electrons", annotation.radicalElectrons());
        values.add(Integer.toString(atom), entry); fields.add("atom_annotations", values);
        return this;
    }

    /**
     * Sets load-time bond-length and torsion constraints; replaces the previous sequence.
     * @param constraints immutable geometric targets, applied after the selected geometry source
     * @return this builder
     */
    public MoleculeBuilder constraints(StructureConstraints constraints) {
        fields.add("constraints", Objects.requireNonNull(constraints, "constraints").toJson());
        return this;
    }

    /**
     * Private constructor initializing the builder with an ID and an existing fields object.
     *
     * @param id the {@link ResourceLocation} identifier
     * @param fields the backing {@link JsonObject}
     */
    private MoleculeBuilder(ResourceLocation id, JsonObject fields) {
        this.id = Objects.requireNonNull(id, "id");
        this.fields = fields;
    }

    /**
     * Creates a molecule builder from a resource ID and a serialized JSON string.
     *
     * @param id the {@link ResourceLocation} identifier
     * @param json the JSON string representing the molecule specification
     * @return a new {@link MoleculeBuilder}
     */
    static MoleculeBuilder fromJson(ResourceLocation id, String json) {
        return new MoleculeBuilder(id, JsonParser.parseString(json).getAsJsonObject());
    }

    /**
     * Creates a molecule builder from a string identifier and a map of property fields.
     *
     * @param id the string identifier
     * @param fields a map of fields to populate
     * @return a new {@link MoleculeBuilder}
     */
    public static MoleculeBuilder from(String id, Map<?, ?> fields) {
        MoleculeBuilder result = new MoleculeBuilder(id);
        fields.forEach((key, value) -> {
            String field = key.toString();
            switch (field) {
                case "items" -> result.items(elements(value));
                case "fluids" -> result.fluids(elements(value));
                case "item_tags" -> result.itemTags(elements(value));
                case "fluid_tags" -> result.fluidTags(elements(value));
                case "materials" -> result.materials(elements(value));
                default -> result.fields.add(field, new Gson().toJsonTree(value));
            }
        });
        return result;
    }

    /**
     * Returns the identifier of this molecule.
     *
     * @return the {@link ResourceLocation} identifier
     */
    public ResourceLocation id() {
        return id;
    }

    /**
     * Sets the SMILES string for the molecule definition.
     *
     * @param value the SMILES string
     * @return this builder
     */
    public MoleculeBuilder smiles(String value) {
        fields.addProperty("smiles", value);
        return this;
    }

    /**
     * Sets a copy reference target for the molecule definition.
     *
     * @param value the target string reference
     * @return this builder
     */
    public MoleculeBuilder copy(String value) {
        fields.addProperty("copy", value);
        return this;
    }

    /**
     * Sets a copy reference target using a resource location.
     *
     * @param value the target {@link ResourceLocation}
     * @return this builder
     */
    public MoleculeBuilder copy(ResourceLocation value) {
        return copy(value.toString());
    }

    /**
     * Sets the chemical formula display string.
     *
     * @param value the formula string
     * @return this builder
     */
    public MoleculeBuilder formula(String value) {
        fields.addProperty("formula", value);
        return this;
    }

    /**
     * Configures the crystal cell parameters for this molecule and sets its classification to crystal.
     *
     * @param cell the {@link CrystalCell}
     * @return this builder
     */
    public MoleculeBuilder crystalCell(CrystalCell cell) {
        fields.add("crystal", Objects.requireNonNull(cell).toJson());
        return classification(MoleculeClassification.CRYSTAL);
    }

    /**
     * Configures the polymer definition for this molecule and sets its classification to polymer.
     *
     * @param polymer the {@link PolymerDefinition}
     * @return this builder
     */
    public MoleculeBuilder polymer(PolymerDefinition polymer) {
        fields.add("polymer", Objects.requireNonNull(polymer, "polymer").toJson());
        return classification(MoleculeClassification.POLYMER);
    }

    /**
     * Configures the polymer network definition for this molecule and sets its classification to polymer.
     *
     * @param network the {@link PolymerNetworkDefinition}
     * @return this builder
     */
    public MoleculeBuilder polymerNetwork(PolymerNetworkDefinition network) {
        fields.add("polymer_network", Objects.requireNonNull(network).toJson());
        return classification(MoleculeClassification.POLYMER);
    }

    /**
     * Adds editing steps to apply after resolving the molecule named by {@link #copy(String)}.
     *
     * @param edits the {@link StructureEdits}
     * @return this builder
     */
    public MoleculeBuilder edits(StructureEdits edits) {
        fields.add("edits", Objects.requireNonNull(edits).toJson());
        return this;
    }

    /**
     * Converts the copied polymer or crystal preview into a fixed collection of atoms before editing it. The result no
     * longer keeps the adjustable polymer or crystal recipe.
     *
     * @param value whether to turn the copied preview into a fixed structure
     * @return this builder
     */
    public MoleculeBuilder materialize(boolean value) {
        fields.addProperty("materialize", value);
        return this;
    }

    /**
     * Sets a structure assembly recipe for the molecule.
     *
     * @param assembly the {@link StructureAssembly} to use
     * @return this builder
     * @throws IllegalArgumentException if combined with conflicting geometry fields
     */
    public MoleculeBuilder structure(StructureAssembly assembly) {
        JsonObject recipe = new JsonObject();
        recipe.add("assembly", assembly.toJson());
        for (String field : new String[]{"copy", "smiles", "structure", "atoms", "bonds", "polymer",
                "polymer_network"}) {
            if (fields.has(field)) {
                throw new IllegalArgumentException("structure cannot be combined with " + field);
            }
        }
        fields.add("structure", recipe);
        return this;
    }

    /**
     * Sets the display name of the molecule.
     *
     * @param value the display name string
     * @return this builder
     */
    public MoleculeBuilder name(String value) {
        fields.addProperty("name", value);
        return this;
    }

    /**
     * Sets whether an item should be automatically generated for this molecule.
     *
     * @param value {@code true} to generate an item, {@code false} otherwise
     * @return this builder
     */
    public MoleculeBuilder generateItem(boolean value) {
        fields.addProperty("generate_item", value);
        return this;
    }

    /**
     * Sets whether container matching is enabled.
     *
     * @param value {@code true} to match containers, {@code false} otherwise
     * @return this builder
     */
    public MoleculeBuilder matchContainers(boolean value) {
        fields.addProperty("match_containers", value);
        return this;
    }

    /**
     * Associates item objects with this molecule.
     *
     * @param values the item association values
     * @return this builder
     */
    public MoleculeBuilder items(Object... values) {
        return associations("items", values, Kind.ITEM);
    }

    /**
     * Associates fluid objects with this molecule.
     *
     * @param values the fluid association values
     * @return this builder
     */
    public MoleculeBuilder fluids(Object... values) {
        return associations("fluids", values, Kind.FLUID);
    }

    /**
     * Associates item tag strings with this molecule.
     *
     * @param values the item tag values
     * @return this builder
     */
    public MoleculeBuilder itemTags(Object... values) {
        return associations("item_tags", values, Kind.TAG);
    }

    /**
     * Associates fluid tag strings with this molecule.
     *
     * @param values the fluid tag values
     * @return this builder
     */
    public MoleculeBuilder fluidTags(Object... values) {
        return associations("fluid_tags", values, Kind.TAG);
    }

    /**
     * Associates material identifiers with this molecule.
     *
     * @param values the material values
     * @return this builder
     */
    public MoleculeBuilder materials(Object... values) {
        return associations("materials", values, Kind.ID);
    }

    /**
     * Defines exact nuclear and electron populations for atoms of this symbol in this molecule.
     *
     * @param symbol the element symbol (e.g., "H")
     * @param atomicNumber the atomic number (number of protons)
     * @param neutrons the number of neutrons
     * @param shells variable array of electron counts per shell
     * @return this builder
     * @throws IllegalArgumentException if the element symbol is invalid
     */
    public MoleculeBuilder element(String symbol, int atomicNumber, int neutrons, int... shells) {
        if (!symbol.matches("[A-Z][a-z]?")) {
            throw new IllegalArgumentException("Invalid element symbol");
        }
        if (!fields.has("elements")) {
            fields.add("elements", new JsonObject());
        }
        fields.getAsJsonObject("elements").add(symbol,
                new AtomicConfiguration(atomicNumber, neutrons, 0, java.util.Arrays.stream(shells).boxed().toList())
                        .toJson());
        return this;
    }

    /**
     * Configures the atomic properties for a specific atom index.
     *
     * @param index the atom index
     * @param atomicNumber the atomic number
     * @param neutrons the neutron count
     * @param charge the net electrical charge
     * @param shells variable array of electron counts per shell
     * @return this builder
     * @throws IllegalArgumentException if the index is negative
     */
    public MoleculeBuilder atomConfiguration(int index, int atomicNumber, int neutrons, int charge, int... shells) {
        if (index < 0) {
            throw new IllegalArgumentException("Negative atom index");
        }
        if (!fields.has("atom_configurations")) {
            fields.add("atom_configurations", new JsonObject());
        }
        fields.getAsJsonObject("atom_configurations").add(Integer.toString(index), new AtomicConfiguration(atomicNumber,
                neutrons, charge, java.util.Arrays.stream(shells).boxed().toList()).toJson());
        return this;
    }

    /**
     * Adds a component molecule dependency identifier.
     *
     * @param id the component molecule identifier string
     * @return this builder
     */
    public MoleculeBuilder component(String id) {
        array("components").add(MoleculeLoader.identifier(id).toString());
        return this;
    }

    /**
     * Assigns a creative-tab grouping explicitly.
     * <p>
     * AUTO classifies the rendered element symbols.
     *
     * @param value the {@link MoleculeClassification} to apply
     * @return this builder
     */
    public MoleculeBuilder classification(MoleculeClassification value) {
        return inspectionMetadata(MoleculeClassification.METADATA_KEY,
                Objects.requireNonNull(value).name().toLowerCase(java.util.Locale.ROOT));
    }

    /**
     * Adds arbitrary inspection metadata key-value pairs.
     *
     * @param key the metadata key
     * @param value the metadata value
     * @return this builder
     */
    public MoleculeBuilder inspectionMetadata(String key, String value) {
        if (!fields.has("inspection")) {
            fields.add("inspection", new JsonObject());
        }
        fields.getAsJsonObject("inspection").addProperty(Objects.requireNonNull(key), Objects.requireNonNull(value));
        return this;
    }

    /**
     * Imports finished geometry using the ordinary explicit atom/bond format.
     *
     * @param fragment the {@link StructureFragment} containing geometry
     * @return this builder
     * @throws IllegalArgumentException if combined with conflicting structure fields
     */
    public MoleculeBuilder structure(StructureFragment fragment) {
        Objects.requireNonNull(fragment, "fragment");
        for (String field : new String[]{"copy", "smiles", "structure", "atoms", "bonds", "polymer",
                "polymer_network"}) {
            if (fields.has(field)) {
                throw new IllegalArgumentException("structure cannot be combined with " + field);
            }
        }
        JsonObject geometry = fragment.toJson();
        if (!fragment.isUnitCell() && fragment.attachments().isEmpty() && fragment.configurations().isEmpty()
                && fragment.randomLinkCapacities().isEmpty()) {
            // Keep the established explicit shape for plain geometry imports.
            fields.add("atoms", geometry.get("atoms"));
            fields.add("bonds", geometry.get("bonds"));
        } else {
            fields.add("structure", geometry);
        }
        return this;
    }

    /**
     * Imports finished geometry from a structure builder.
     *
     * @param builder the {@link StructureBuilder}
     * @return this builder
     */
    public MoleculeBuilder structure(StructureBuilder builder) {
        return structure(Objects.requireNonNull(builder, "builder").build());
    }

    /**
     * Adds an atom to the raw atom array and returns its assigned index.
     *
     * @param element the element symbol
     * @param x the x-coordinate
     * @param y the y-coordinate
     * @param z the z-coordinate
     * @return the integer index of the newly added atom
     */
    public int addAtom(String element, double x, double y, double z) {
        JsonArray atoms = array("atoms");
        JsonObject atom = new JsonObject();
        atom.addProperty("e", element);
        atom.addProperty("x", x);
        atom.addProperty("y", y);
        atom.addProperty("z", z);
        atoms.add(atom);
        return atoms.size() - 1;
    }

    /**
     * Adds an atom to the structure.
     *
     * @param element the element symbol
     * @param x the x-coordinate
     * @param y the y-coordinate
     * @param z the z-coordinate
     * @return this builder
     */
    public MoleculeBuilder atom(String element, double x, double y, double z) {
        addAtom(element, x, y, z);
        return this;
    }

    /**
     * Adds a single bond between two atom indices.
     *
     * @param from the source atom index
     * @param to the target atom index
     * @return this builder
     */
    public MoleculeBuilder bond(int from, int to) {
        return bond(from, to, BondType.SINGLE);
    }

    /**
     * Adds a bond of a specified type between two atom indices.
     *
     * @param from the source atom index
     * @param to the target atom index
     * @param type the {@link BondType} enum value
     * @return this builder
     */
    public MoleculeBuilder bond(int from, int to, BondType type) {
        JsonArray bond = new JsonArray();
        bond.add(from);
        bond.add(to);
        bond.add(Objects.requireNonNull(type, "type").name());
        array("bonds").add(bond);
        return this;
    }

    /**
     * Adds a bond specified by a string type between two atom indices.
     *
     * @param from the source atom index
     * @param to the target atom index
     * @param type the bond type string
     * @return this builder
     */
    public MoleculeBuilder bond(int from, int to, String type) {
        JsonArray bond = new JsonArray();
        bond.add(from);
        bond.add(to);
        bond.add(type);
        array("bonds").add(bond);
        return this;
    }

    /**
     * Adds a bond specified by an integer type code between two atom indices.
     *
     * @param from the source atom index
     * @param to the target atom index
     * @param type the integer bond type code
     * @return this builder
     */
    public MoleculeBuilder bond(int from, int to, int type) {
        JsonArray bond = new JsonArray();
        bond.add(from);
        bond.add(to);
        bond.add(type);
        array("bonds").add(bond);
        return this;
    }

    /**
     * Explicitly defines an empty bond array, disabling automatic bonding as in JSON specification.
     *
     * @return this builder
     */
    public MoleculeBuilder noBonds() {
        fields.add("bonds", new JsonArray());
        return this;
    }

    /**
     * Builds and returns an immutable {@link MoleculeSpec}.
     *
     * @return a new {@link MoleculeSpec}
     */
    public MoleculeSpec build() {
        return MoleculeSpec.fromJson(id, fields.toString());
    }

    /**
     * Snapshots this builder now and builds asynchronously; validation runs on bounded CPU workers.
     *
     * @return a {@link java.util.concurrent.CompletableFuture} resolving to the {@link MoleculeSpec}
     */
    public java.util.concurrent.CompletableFuture<MoleculeSpec> buildAsync() {
        return MoleculeSpec.fromJsonAsync(id, fields.toString());
    }

    /**
     * Returns or creates a JSON array field by name.
     *
     * @param field the field name string
     * @return the {@link JsonArray}
     */
    private JsonArray array(String field) {
        if (!fields.has(field)) {
            fields.add(field, new JsonArray());
        }
        return fields.getAsJsonArray(field);
    }

    /**
     * Helper method to populate association arrays.
     *
     * @param field the field name
     * @param values array of association objects
     * @param kind the association {@link Kind}
     * @return this builder
     */
    private MoleculeBuilder associations(String field, Object[] values, Kind kind) {
        JsonArray entries = new JsonArray();
        for (Object value : values) {
            entries.add(identifier(value, kind));
        }
        fields.add(field, entries);
        return this;
    }

    /**
     * Converts a collection or object array into an array of elements.
     *
     * @param value the input collection or array object
     * @return an array of objects
     * @throws IllegalArgumentException if the input is neither a collection nor an array
     */
    private static Object[] elements(Object value) {
        if (value instanceof Collection<?> collection) {
            return collection.toArray();
        }
        if (value instanceof Object[] array) {
            return array;
        }
        throw new IllegalArgumentException("Association fields require a list");
    }

    /**
     * Resolves and formats an association object into its string identifier based on its kind.
     *
     * @param value the association object
     * @param kind the association {@link Kind}
     * @return the formatted identifier string
     * @throws IllegalArgumentException if the association is invalid or unsupported
     */
    private static String identifier(Object value, Kind kind) {
        Objects.requireNonNull(value, "association");
        String name;
        if (kind == Kind.ITEM && value instanceof ItemStack stack) {
            if (stack.isEmpty()) {
                throw new IllegalArgumentException("An empty ItemStack is not an association");
            }
            name = Objects.requireNonNull(stack.getItem().getRegistryName(), "Item registry name").toString();
            if (stack.getMetadata() != net.minecraftforge.oredict.OreDictionary.WILDCARD_VALUE) {
                name += "@" + stack.getMetadata();
            }
            if (stack.hasTagCompound()) {
                name += "#" + stack.getTagCompound();
            }
        } else if (kind == Kind.ITEM && value instanceof Item item) {
            name = Objects.requireNonNull(item.getRegistryName(), "Item registry name").toString();
        } else if (kind == Kind.FLUID && value instanceof FluidStack stack) {
            if (stack.amount <= 0 || stack.getFluid() == null) {
                throw new IllegalArgumentException("An empty FluidStack is not an association");
            }
            name = stack.getFluid().getName() + (stack.tag == null ? "" : "#" + stack.tag);
        } else if (kind == Kind.FLUID && value instanceof Fluid fluid) {
            name = fluid.getName();
        } else if (value instanceof ResourceLocation || value instanceof CharSequence) {
            name = value.toString();
        } else {
            throw new IllegalArgumentException(
                    "Unsupported " + kind.name().toLowerCase() + " association: " + value.getClass().getName());
        }
        if (kind == Kind.FLUID) {
            return io.github.wurtzitane.avogadro.molecule.FluidAssociation.parse(name).selector();
        }
        if (kind == Kind.ITEM) {
            MoleculeLoader.itemAssociation(name);
        } else {
            MoleculeLoader.identifier(kind == Kind.TAG && name.startsWith("#") ? name.substring(1) : name);
        }
        return name;
    }

    /**
     * Represents the category of association being parsed.
     */
    private enum Kind {
        ITEM, FLUID, TAG, ID
    }
}
