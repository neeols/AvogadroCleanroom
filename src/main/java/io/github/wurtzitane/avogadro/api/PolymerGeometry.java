package io.github.wurtzitane.avogadro.api;

import io.github.wurtzitane.avogadro.molecule.AtomElement;
import io.github.wurtzitane.avogadro.molecule.MoleculeStructure;

/**
 * Builds a fixed-length polymer chain from its repeat unit and end groups.
 *
 * <p>
 * The author chooses the bonds between units and the placement of each copy.
 */
public final class PolymerGeometry {

    /** Maximum allowed repeat units in a polymer preview chain. */
    public static final int MAX_UNITS = 64;

    /** Maximum absolute atom limit allowed in a polymer preview chain. */
    public static final int MAX_ATOMS = 4096;

    private PolymerGeometry() {}

    /**
     * Generates a complete molecule structure for a finite polymer chain based on the specified definition and unit
     * count.
     *
     * @param polymer the {@link PolymerDefinition} describing the repeat units and caps
     * @param units the number of repeat units in the chain (must be between 1 and {@link #MAX_UNITS})
     * @return a new {@link MoleculeStructure} representing the finite polymer chain
     * @throws IllegalArgumentException if the unit count or resulting size exceeds limits
     * @throws java.util.concurrent.CancellationException if the current thread is interrupted during generation
     */
    public static MoleculeStructure chain(PolymerDefinition polymer, int units) {
        StructureFragment fragment = chainFragment(polymer, units);
        var result = MoleculeStructure.builder("avogadro:polymer/" + units, polymer.chainFormula(units));
        append(result, fragment, 0, 0, 0, 0);
        return result.build();
    }

    /**
     * Generates a finite authored structure fragment for a polymer chain, including repeat units, head and tail
     * attachment paths, and remapped local metadata.
     *
     * @param polymer the {@link PolymerDefinition} describing the repeat units and caps
     * @param units the number of repeat units in the chain
     * @return a new {@link StructureFragment} representing the assembled polymer chain fragment
     * @throws IllegalArgumentException if size limits are exceeded
     * @throws java.util.concurrent.CancellationException if the current thread is interrupted during generation
     */
    public static StructureFragment chainFragment(PolymerDefinition polymer, int units) {
        validateSize(polymer.repeatUnit(), polymer.headCap(), polymer.tailCap(), units);
        var result = new StructureBuilder();
        PolymerDefinition.Vector shift = polymer.translation();
        int unitSize = polymer.repeatUnit().atoms().size();

        for (int unit = 0; unit < units; unit++) {
            if (Thread.currentThread().isInterrupted()) {
                throw new java.util.concurrent.CancellationException();
            }
            append(result, polymer.repeatUnit(), unit * shift.x(), unit * shift.y(), unit * shift.z(), unit * unitSize,
                    "repeat[" + unit + "]");
            if (unit > 0) {
                result.bond((unit - 1) * unitSize + polymer.tail(), unit * unitSize + polymer.head(),
                        polymer.linkType());
            }
        }

        int capOffset = unitSize * units;
        append(result, polymer.headCap().structure(), 0, 0, 0, capOffset, "head");
        result.bond(polymer.head(), capOffset + polymer.headCap().attachment(), polymer.headCap().bondType());

        capOffset += polymer.headCap().structure().atoms().size();
        append(result, polymer.tailCap().structure(), (units - 1) * shift.x(), (units - 1) * shift.y(),
                (units - 1) * shift.z(), capOffset, "tail");
        result.bond((units - 1) * unitSize + polymer.tail(), capOffset + polymer.tailCap().attachment(),
                polymer.tailCap().bondType());

        return result.build();
    }

    /**
     * Appends atoms, bonds, attachments, configurations, and link capacities from a source fragment into a structure
     * builder with positional offsets and instance naming.
     *
     * @param target the target {@link StructureBuilder}
     * @param fragment the source {@link StructureFragment} to append
     * @param x the x-axis coordinate shift
     * @param y the y-axis coordinate shift
     * @param z the z-axis coordinate shift
     * @param offset the atom index offset
     * @param instance the instance prefix string for metadata remapping
     */
    private static void append(StructureBuilder target, StructureFragment fragment, double x, double y, double z,
            int offset, String instance) {
        for (var atom : fragment.atoms()) {
            target.atom(atom.element(), atom.x() + x, atom.y() + y, atom.z() + z);
        }
        for (var bond : fragment.bonds()) {
            target.bond(bond.from() + offset, bond.to() + offset, bond.type());
        }
        fragment.attachments().forEach((name, atom) -> target.attachment(instance + "/" + name, atom + offset));
        fragment.configurations()
                .forEach((atom, configuration) -> target.atomConfiguration(atom + offset, configuration));
        fragment.randomLinkCapacities().forEach((atom, capacity) -> target.randomLinkCapacity(atom + offset, capacity));
    }

    /**
     * Validates that the repeat unit, terminal caps, and unit count do not exceed defined atom or bond limits.
     *
     * @param repeatUnit the repeat unit fragment
     * @param headCap the head terminal cap
     * @param tailCap the tail terminal cap
     * @param units the number of repeat units
     * @throws IllegalArgumentException if units are out of bounds or atom/bond limits are exceeded
     */
    static void validateSize(StructureFragment repeatUnit, PolymerDefinition.Cap headCap, PolymerDefinition.Cap tailCap,
            int units) {
        if (units < 1 || units > MAX_UNITS) {
            throw new IllegalArgumentException("Polymer preview requires 1–" + MAX_UNITS + " repeat units");
        }
        long atoms = (long) repeatUnit.atoms().size() * units + headCap.structure().atoms().size()
                + tailCap.structure().atoms().size();
        long bonds = (long) repeatUnit.bonds().size() * units + units + 1 + headCap.structure().bonds().size()
                + tailCap.structure().bonds().size();
        if (atoms > MAX_ATOMS || bonds > StructureFragment.MAX_BONDS) {
            throw new IllegalArgumentException("Polymer preview exceeds its atom or bond limit");
        }
    }

    /**
     * Appends atoms and bonds from a source fragment into a molecule structure builder with positional offsets.
     *
     * @param target the target {@link MoleculeStructure.Builder}
     * @param fragment the source {@link StructureFragment} to append
     * @param x the x-axis coordinate shift
     * @param y the y-axis coordinate shift
     * @param z the z-axis coordinate shift
     * @param offset the atom index offset
     */
    private static void append(MoleculeStructure.Builder target, StructureFragment fragment, double x, double y,
            double z, int offset) {
        for (var atom : fragment.atoms()) {
            target.addAtom(AtomElement.bySymbol(atom.element()), (float) (atom.x() + x), (float) (atom.y() + y),
                    (float) (atom.z() + z));
        }
        for (var bond : fragment.bonds()) {
            target.bond(bond.from() + offset, bond.to() + offset, bond.type());
        }
    }
}
