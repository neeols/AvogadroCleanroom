package io.github.wurtzitane.avogadro.client;

/** Model-space appearance, deliberately independent of camera and elapsed time. */
record MoleculeStyle(float atoms, float bonds) {

    /** Default molecule style configuration with unmodified atom and bond scale factors. */
    static final MoleculeStyle DEFAULT = new MoleculeStyle(1, 1);

    /**
     * Compact constructor validating that atom and bond scales are finite and positive numbers.
     *
     * @param atoms scale factor for atom radii
     * @param bonds scale factor for bond radii
     */
    MoleculeStyle {
        if (!Float.isFinite(atoms) || !Float.isFinite(bonds) || atoms <= 0 || bonds <= 0)
            throw new IllegalArgumentException("Atom and bond scales must be finite and positive");
    }
}
