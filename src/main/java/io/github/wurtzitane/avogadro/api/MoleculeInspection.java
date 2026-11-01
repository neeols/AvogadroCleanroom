package io.github.wurtzitane.avogadro.api;

import io.github.wurtzitane.avogadro.molecule.MoleculeStructure;
import net.minecraft.util.ResourceLocation;
import java.util.List;
import java.util.Map;

/**
 * Immutable, optional examination data.
 * <p>The host supplies chemistry and provenance.
 *
 * @param elements   map of element symbols to their specific default atomic configurations
 * @param atoms      map of specific atom indices to custom atomic configurations
 * @param components immutable list of component molecule resource identifiers
 * @param metadata   map of arbitrary inspection metadata key-value pairs
 */
public record MoleculeInspection(Map<String, AtomicConfiguration> elements,
                                 Map<Integer, AtomicConfiguration> atoms,
                                 List<ResourceLocation> components, Map<String, String> metadata) {

    /** An empty, default {@link MoleculeInspection} instance with no elements, overrides, components, or metadata. */
    public static final MoleculeInspection EMPTY = new MoleculeInspection(Map.of(), Map.of(), List.of(), Map.of());

    /**
     * Compact constructor for {@link MoleculeInspection} that creates immutable defensive copies
     * of all internal collections and maps.
     */
    public MoleculeInspection {
        elements = Map.copyOf(elements);
        atoms = Map.copyOf(atoms);
        components = List.copyOf(components);
        metadata = Map.copyOf(metadata);
    }

    /**
     * Retrieves the effective atomic configuration for a specific atom index within a given structure,
     * checking for index-specific overrides first and falling back to element-wide defaults.
     *
     * @param structure the {@link MoleculeStructure} containing the atoms
     * @param index     the zero-based index of the atom
     * @return the applicable {@link AtomicConfiguration}, or {@code null} if none is defined
     */
    public AtomicConfiguration atom(MoleculeStructure structure, int index) {
        AtomicConfiguration override = atoms.get(index);
        return override != null ? override : elements.get(structure.atoms().get(index).element().symbol());
    }
}