package io.github.wurtzitane.avogadro.api;

import java.util.Comparator;
import java.util.List;
import java.util.Map;

import io.github.wurtzitane.avogadro.molecule.MoleculeDef;
import io.github.wurtzitane.avogadro.molecule.MoleculeStore;

import net.minecraft.item.ItemStack;
import net.minecraft.util.ResourceLocation;
import net.minecraftforge.fluids.Fluid;
import net.minecraftforge.fluids.FluidStack;

/**
 * A read-only view of molecule definitions and item/fluid matching rules.
 *
 * <p>
 * The view keeps the definitions it was created with. If you retain it across a reload, it still describes the old
 * state; request a new view to see the new definitions.
 */
public final class MoleculeView {

    /** Map of molecule resource location identifiers to their compiled definitions. */
    private final Map<ResourceLocation, MoleculeDef> definitions;

    /** Map of item tag resource locations to their associated string representations. */
    private final Map<ResourceLocation, List<String>> itemTags;

    /** Map of fluid tag resource locations to their associated string representations. */
    private final Map<ResourceLocation, List<String>> fluidTags;

    /** Store lookup helper providing item and fluid association mapping. */
    private final MoleculeStore.Lookup lookup;

    /** Unsorted list of all molecule definitions. */
    private final List<MoleculeDef> all;

    /** Sorted list of all molecule definitions ordered alphabetically by ID string. */
    private final List<MoleculeDef> sortedDefinitions;

    /** Filtered list of molecule definitions that have item generation enabled. */
    private final List<MoleculeDef> generatedItemDefinitions;

    /** Revision version counter for this view snapshot. */
    private final long revision;

    /**
     * Creates a {@link MoleculeView} from raw definitions, tags, and a revision count, compiling them into an immutable
     * snapshot.
     *
     * @param definitions map of molecule definitions
     * @param itemTags map of item tags
     * @param fluidTags map of fluid tags 5 * @param revision the revision tracking number
     */
    public MoleculeView(Map<ResourceLocation, MoleculeDef> definitions, Map<ResourceLocation, List<String>> itemTags,
            Map<ResourceLocation, List<String>> fluidTags, long revision) {
        this(MoleculeStore.compileSnapshot(definitions, itemTags, fluidTags), revision);
    }

    /**
     * Creates a {@link MoleculeView} reusing a store-compiled immutable snapshot without rebuilding its matching
     * indexes.
     *
     * @param compiled the pre-compiled {@link MoleculeStore.CompiledSnapshot}
     * @param revision the revision tracking number
     */
    public MoleculeView(MoleculeStore.CompiledSnapshot compiled, long revision) {
        this.definitions = compiled.definitions();
        this.itemTags = compiled.itemTags();
        this.fluidTags = compiled.fluidTags();
        all = compiled.all();
        sortedDefinitions = all.stream().sorted(Comparator.comparing(def -> def.id().toString())).toList();
        generatedItemDefinitions = sortedDefinitions.stream().filter(MoleculeDef::generateItem).toList();
        this.revision = revision;
        lookup = compiled;
    }

    /**
     * Creates an empty, default {@link MoleculeView} with zeroed definitions, tags, and revision.
     *
     * @return an empty {@link MoleculeView}
     */
    public static MoleculeView empty() {
        return new MoleculeView(Map.of(), Map.of(), Map.of(), 0);
    }

    /**
     * Returns the revision version number of this view.
     *
     * @return the revision counter
     */
    public long revision() {
        return revision;
    }

    /**
     * Returns a molecule definition by its resource location identifier.
     *
     * @param id the {@link ResourceLocation} identifier
     * @return the corresponding {@link MoleculeDef}, or {@code null} if not found
     */
    public MoleculeDef get(ResourceLocation id) {
        return definitions.get(id);
    }

    /**
     * Returns a molecule definition by its string identifier.
     *
     * @param id the string identifier
     * @return the corresponding {@link MoleculeDef}, or {@code null} if not found
     */
    public MoleculeDef get(String id) {
        return get(new ResourceLocation(id));
    }

    /**
     * Returns the complete map of molecule definitions.
     *
     * @return an immutable map of resource locations to molecule definitions
     */
    public Map<ResourceLocation, MoleculeDef> definitions() {
        return definitions;
    }

    /**
     * Returns an unsorted list of all molecule definitions in this view.
     *
     * @return a list of all {@link MoleculeDef} instances
     */
    public List<MoleculeDef> all() {
        return all;
    }

    /**
     * Returns a sorted list of all molecule definitions ordered by their identifier strings.
     *
     * @return a sorted list of {@link MoleculeDef} instances
     */
    public List<MoleculeDef> sortedDefinitions() {
        return sortedDefinitions;
    }

    /**
     * Returns a list of molecule definitions that have item generation enabled.
     *
     * @return a list of generated item {@link MoleculeDef} instances
     */
    public List<MoleculeDef> generatedItemDefinitions() {
        return generatedItemDefinitions;
    }

    /**
     * Returns the map of item tags.
     *
     * @return an immutable map of item tag resource locations to string lists
     */
    public Map<ResourceLocation, List<String>> itemTags() {
        return itemTags;
    }

    /**
     * Returns the map of fluid tags.
     *
     * @return an immutable map of fluid tag resource locations to string lists
     */
    public Map<ResourceLocation, List<String>> fluidTags() {
        return fluidTags;
    }

    /**
     * Finds the molecule definition associated with a given item stack.
     *
     * @param stack the {@link ItemStack} to look up
     * @return the matching {@link MoleculeDef}, or {@code null} if none match
     */
    public MoleculeDef find(ItemStack stack) {
        return definitions.get(lookup.item(stack));
    }

    /**
     * Finds a molecule using unqualified fluid rules; NBT-qualified selectors are not considered.
     *
     * @param fluid the {@link Fluid} to look up
     * @return the matching {@link MoleculeDef}, or {@code null} if none match
     */
    public MoleculeDef find(Fluid fluid) {
        return definitions.get(lookup.fluid(fluid));
    }

    /**
     * Finds a molecule using the fluid and its NBT, preferring more-specific matching selectors. Empty stacks do not
     * match; a positive amount does not otherwise affect matching.
     *
     * @param fluid the {@link FluidStack} to look up
     * @return the matching {@link MoleculeDef}, or {@code null} if none match
     */
    public MoleculeDef find(FluidStack fluid) {
        return definitions.get(lookup.fluid(fluid));
    }
}
