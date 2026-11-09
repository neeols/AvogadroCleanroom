package io.github.wurtzitane.avogadro.client;

import io.github.wurtzitane.avogadro.molecule.MoleculeStructure;
import io.github.wurtzitane.avogadro.molecule.MoleculeStructure.Atom;

/** Rotation-independent bounds keep a dragged model inside a fixed recipe viewport. */
public final class MoleculeViewport {

    /** Precalculated diagonal cube radius multiplier constant. */
    private static final float CUBE_RADIUS = (float) Math.sqrt(3.0) * 0.9f;

    private MoleculeViewport() {}

    /**
     * Calculates fitted render size using default molecule styles.
     *
     * @param molecule molecule structure
     * @param requestedSize requested target render size
     * @param viewportWidth viewport width in pixels
     * @param viewportHeight viewport height in pixels
     * @param cubic whether cubic rendering is enabled
     * @return fitted render size in pixels
     */
    public static int fitSize(MoleculeStructure molecule, int requestedSize, int viewportWidth, int viewportHeight,
            boolean cubic) {
        return fitSize(molecule, requestedSize, viewportWidth, viewportHeight, cubic, MoleculeStyle.DEFAULT);
    }

    /**
     * Calculates fitted render size honoring explicit style scaling options.
     *
     * @param molecule molecule structure
     * @param requestedSize requested target render size
     * @param viewportWidth viewport width in pixels
     * @param viewportHeight viewport height in pixels
     * @param cubic whether cubic rendering is enabled
     * @param style molecule style configuration
     * @return fitted render size in pixels
     */
    static int fitSize(MoleculeStructure molecule, int requestedSize, int viewportWidth, int viewportHeight,
            boolean cubic, MoleculeStyle style) {
        if (requestedSize <= 0 || viewportWidth <= 0 || viewportHeight <= 0)
            return 0;
        float bound = 0f;
        for (Atom atom : molecule.atoms()) {
            bound = Math.max(bound,
                    distance(molecule, atom) + atom.element().radius() * (cubic ? CUBE_RADIUS : 0.9f) * style.atoms());
        }
        for (MoleculeStructure.Bond bond : molecule.bonds()) {
            Atom from = molecule.atoms().get(bond.from());
            Atom to = molecule.atoms().get(bond.to());
            // Multi-bond offsets lie at most 0.28 units from the bond axis.
            float cylinderRadius = cubic ? (float) Math.sqrt(2) : 1f;
            float radius = switch (bond.type().order()) {
                case 1 -> 0.15f * cylinderRadius;
                case 2 -> 0.14f + 0.10f * cylinderRadius;
                default -> 0.28f + 0.10f * cylinderRadius;
            };
            bound = Math.max(bound,
                    Math.max(distance(molecule, from), distance(molecule, to)) + radius * style.bonds());
        }
        float available = Math.max(1f, Math.min(viewportWidth, viewportHeight) * 0.5f - 1f);
        float originalRadius = molecule.radius() > 0.001f ? molecule.radius() : 1f;
        int fitted = bound > 0f ? (int) Math.floor(available * originalRadius / (0.45f * bound)) : requestedSize;

        return Math.max(1, Math.min(requestedSize, fitted));
    }

    /**
     * Computes the Euclidean distance from the molecule center to a given atom position.
     *
     * @param molecule molecule structure reference
     * @param atom target atom
     * @return distance in world units
     */
    private static float distance(MoleculeStructure molecule, Atom atom) {
        float x = atom.x() - molecule.centerX();
        float y = atom.y() - molecule.centerY();
        float z = atom.z() - molecule.centerZ();
        return (float) Math.sqrt(x * x + y * y + z * z);
    }
}
