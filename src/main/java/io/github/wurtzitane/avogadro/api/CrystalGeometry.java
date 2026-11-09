package io.github.wurtzitane.avogadro.api;

import io.github.wurtzitane.avogadro.molecule.MoleculeStructure;

import org.joml.Vector3f;

/**
 * Builds a crystal preview by repeating the atoms in a unit cell.
 *
 * <p>
 * A unit cell is the block that repeats to form a crystal. Positions and bonds come from the author; this class copies
 * them to the requested neighboring cells.
 */
public final class CrystalGeometry {

    /** Maximum allowed crystal cells per axis during repetition. */
    public static final int MAX_CELLS_PER_AXIS = 5;

    /** Maximum absolute atom limit allowed in a repeated crystal structure preview. */
    public static final int MAX_ATOMS = 65536;

    private CrystalGeometry() {}

    /**
     * Repeats a source molecule structure across a crystal lattice cell up to a specified count, optionally duplicating
     * internal bonds.
     *
     * @param source the base molecule structure to repeat
     * @param cell the {@link CrystalCell} defining the lattice geometry
     * @param count the number of cells per axis (must be between 1 and {@link #MAX_CELLS_PER_AXIS})
     * @param bonds {@code true} to duplicate internal bonds within each cell, {@code false} for atoms only
     * @return a new {@link MoleculeStructure} representing the repeated crystal lattice
     * @throws IllegalArgumentException if the cell count is out of bounds or the resulting atom count exceeds
     *     {@link #MAX_ATOMS}
     * @throws java.util.concurrent.CancellationException if the current thread is interrupted during generation
     */
    public static MoleculeStructure repeat(MoleculeStructure source, CrystalCell cell, int count, boolean bonds) {
        if (count < 1 || count > MAX_CELLS_PER_AXIS) {
            throw new IllegalArgumentException("Crystal preview requires 1–5 cells per axis");
        }
        if ((long) source.atoms().size() * count * count * count > MAX_ATOMS) {
            throw new IllegalArgumentException("Crystal preview exceeds its atom limit of " + MAX_ATOMS);
        }

        var result = MoleculeStructure.builder(source.id() + "/crystal/" + count + "/" + bonds, source.formula());
        Vector3f origin = cell.position(0, 0, 0);
        int offset = 0;

        for (int i = 0; i < count; i++) {
            for (int j = 0; j < count; j++) {
                for (int k = 0; k < count; k++) {
                    if (Thread.currentThread().isInterrupted()) {
                        throw new java.util.concurrent.CancellationException();
                    }

                    Vector3f shift = cell.position(i, j, k).sub(origin);

                    for (var atom : source.atoms()) {
                        result.addAtom(atom.element(), atom.x() + shift.x, atom.y() + shift.y, atom.z() + shift.z);
                    }

                    if (bonds) {
                        for (var bond : source.bonds()) {
                            result.bond(bond.from() + offset, bond.to() + offset, bond.type());
                        }
                    }

                    offset += source.atoms().size();
                }
            }
        }

        return result.build();
    }
}
