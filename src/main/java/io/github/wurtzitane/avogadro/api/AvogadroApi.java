package io.github.wurtzitane.avogadro.api;

import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Predicate;

import io.github.wurtzitane.avogadro.molecule.MoleculeRegistrations;
import io.github.wurtzitane.avogadro.molecule.MoleculeServerEvents;

import net.minecraft.item.ItemStack;
import net.minecraft.util.ResourceLocation;

/**
 * Registers molecules and looks up the server's molecule definitions.
 *
 * <p>
 * Use this API in code that can run on either a client or a dedicated server. For drawing models or exporting images,
 * use the separate client API.
 */
public final class AvogadroApi {

    /** Set of explicit item resource locations excluded from tooltips. */
    private static final Set<ResourceLocation> TOOLTIP_EXCLUSIONS = ConcurrentHashMap.newKeySet();

    /** Set of namespaces excluded from item tooltips. */
    private static final Set<String> TOOLTIP_NAMESPACE_EXCLUSIONS = ConcurrentHashMap.newKeySet();

    /** List of dynamic predicates determining item tooltip exclusions. */
    private static final List<Predicate<ItemStack>> TOOLTIP_PREDICATES = new CopyOnWriteArrayList<>();

    private AvogadroApi() {}

    /**
     * Registers a fast, read-only host predicate for automatic item previews.
     * <p>
     * HEI and store associations remain unchanged.
     *
     * @param predicate the predicate to evaluate item stacks
     */
    public static void excludeItemTooltipWhen(Predicate<ItemStack> predicate) {
        TOOLTIP_PREDICATES.add(java.util.Objects.requireNonNull(predicate, "predicate"));
    }

    /**
     * Checks whether an item stack's automatic tooltip preview is excluded.
     *
     * @param stack the item stack to check
     * @return {@code true} if excluded, {@code false} otherwise
     */
    public static boolean isItemTooltipExcluded(ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return false;
        }
        if (isItemTooltipExcluded(stack.getItem().getRegistryName())) {
            return true;
        }
        for (var predicate : TOOLTIP_PREDICATES) {
            if (predicate.test(stack)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Suppresses automatic item previews for an entire namespace, retaining HEI and molecule lookup.
     *
     * @param namespace the namespace string to exclude
     */
    public static void excludeItemTooltipNamespace(String namespace) {
        var checked = io.github.wurtzitane.avogadro.molecule.MoleculeLoader
                .identifier(java.util.Objects.requireNonNull(namespace) + ":_");
        TOOLTIP_NAMESPACE_EXCLUSIONS.add(checked.getNamespace());
    }

    /**
     * Suppresses only the automatic item tooltip preview for a specific item, retaining molecule and HEI associations.
     *
     * @param itemId the resource location of the item ID
     */
    public static void excludeItemTooltip(ResourceLocation itemId) {
        TOOLTIP_EXCLUSIONS.add(java.util.Objects.requireNonNull(itemId, "itemId"));
    }

    /**
     * Checks whether a specific item resource location or its namespace is excluded from tooltips.
     *
     * @param itemId the resource location to check
     * @return {@code true} if excluded, {@code false} otherwise
     */
    public static boolean isItemTooltipExcluded(ResourceLocation itemId) {
        return itemId != null && (TOOLTIP_EXCLUSIONS.contains(itemId)
                || TOOLTIP_NAMESPACE_EXCLUSIONS.contains(itemId.getNamespace()));
    }

    /**
     * Calculates the molecular weight from a formula string.
     *
     * @param formula the chemical formula string
     * @return the {@link MoleculeWeight.Result} calculation
     */
    public static MoleculeWeight.Result weight(String formula) {
        return MoleculeWeight.calculate(formula);
    }

    /**
     * Calculates the molecular weight for a molecule definition.
     *
     * @param molecule the molecule definition
     * @return the {@link MoleculeWeight.Result} calculation
     */
    public static MoleculeWeight.Result weight(io.github.wurtzitane.avogadro.molecule.MoleculeDef molecule) {
        return MoleculeWeight.calculate(molecule);
    }

    /**
     * Returns the item stack associated with a molecule definition.
     *
     * @param molecule the molecule definition
     * @return the corresponding {@link ItemStack}
     */
    public static ItemStack moleculeItem(io.github.wurtzitane.avogadro.molecule.MoleculeDef molecule) {
        return io.github.wurtzitane.avogadro.item.MoleculeItems.stack(molecule);
    }

    /**
     * Creates a new molecule builder with the specified ID.
     *
     * @param id the molecule identifier
     * @return a new {@link MoleculeBuilder}
     */
    public static MoleculeBuilder builder(String id) {
        return new MoleculeBuilder(id);
    }

    /**
     * Creates a new structure assembly builder.
     *
     * @return a new {@link StructureAssembly.Builder}
     */
    public static StructureAssembly.Builder assemblyBuilder() {
        return StructureAssembly.builder();
    }

    /**
     * Creates a structure editor for a source molecule definition.
     *
     * @param source the source molecule definition
     * @return a new {@link StructureEditor}
     */
    public static StructureEditor edit(io.github.wurtzitane.avogadro.molecule.MoleculeDef source) {
        return StructureFragment.from(source).edit();
    }

    /**
     * Creates a new structure edits builder.
     *
     * @return a new {@link StructureEdits.Builder}
     */
    public static StructureEdits.Builder edits() {
        return StructureEdits.builder();
    }

    /**
     * Creates a new general structure builder.
     *
     * @return a new {@link StructureBuilder}
     */
    public static StructureBuilder structureBuilder() {
        return new StructureBuilder();
    }

    /**
     * Returns the molecule registration owner handle.
     *
     * @param owner the owner identifier string
     * @return the corresponding {@link MoleculeRegistration}
     */
    public static MoleculeRegistration owner(String owner) {
        return MoleculeRegistrations.owner(owner, false);
    }

    /**
     * Returns the server-side molecule view.
     * <p>
     * Empty until a server world has started; this never returns the integrated client's local overrides.
     *
     * @return the authoritative server {@link MoleculeView}
     */
    public static MoleculeView serverView() {
        return MoleculeServerEvents.view();
    }

    /**
     * Returns the registered common startup definitions.
     * <p>
     * Includes retained registrations and excludes world-specific JSON.
     *
     * @return the registered {@link MoleculeView}
     */
    public static MoleculeView registeredView() {
        return MoleculeRegistrations.view();
    }
}
