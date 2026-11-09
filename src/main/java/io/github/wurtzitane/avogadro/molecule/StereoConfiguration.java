package io.github.wurtzitane.avogadro.molecule;

/**
 * Absolute CIP descriptor for a supported tetrahedral center. These labels are not SMILES
 * {@code @}/{@code @@} tokens: those encode local neighbor ordering, not CIP priority.
 * Unspecified centers are absent from {@link MoleculeStructure#stereochemistry()}.
 * Lowercase pseudoasymmetric descriptors and non-tetrahedral stereochemistry are not supported.
 */
public enum StereoConfiguration {
    /** Rectus: descending priorities turn clockwise with the lowest priority away. */
    R,
    /** Sinister: descending priorities turn anticlockwise with the lowest priority away. */
    S
}
