package io.github.wurtzitane.avogadro.api;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import io.github.wurtzitane.avogadro.molecule.BondType;

/**
 * Builds a piece of a molecule from atom positions and bonds.
 *
 * <p>
 * Atoms receive indices starting at zero, in the order you add them. Name important atoms with
 * {@link #attachment(String, int)} so you can connect pieces without tracking indices.
 */
public final class StructureBuilder {

    /** List of atoms comprising the fragment being built. */
    private final List<StructureFragment.Atom> atoms = new ArrayList<>();

    /** List of bonds connecting atoms within the fragment. */
    private final List<StructureFragment.Bond> bonds = new ArrayList<>();

    /** List of periodic cell links connecting atoms across lattice boundaries. */
    private final List<StructureFragment.CellLink> links = new ArrayList<>();

    /** Optional lattice vectors defining unit cell periodicity. */
    private double[][] vectors;

    /** Map of named attachment points to their corresponding zero-based atom indices. */
    private final Map<String, Integer> attachments = new LinkedHashMap<>();

    /** Map of atom indices to custom atomic configurations. */
    private final Map<Integer, AtomicConfiguration> configurations = new LinkedHashMap<>();

    /** Map of atom indices to maximum random link capacities. */
    private final Map<Integer, Integer> capacities = new LinkedHashMap<>();

    /**
     * Constructs a new, empty {@link StructureBuilder}.
     */
    public StructureBuilder() {}

    /**
     * Adds an atom with the specified element symbol and 3D Cartesian coordinates.
     *
     * @param element the element symbol, such as C or O
     * @param x the x coordinate, in the structure's units
     * @param y the y coordinate, in the structure's units
     * @param z the z coordinate, in the structure's units
     * @return the zero-based index of the added atom
     * @throws IllegalArgumentException if the fragment has reached the maximum atom limit
     */
    public int addAtom(String element, double x, double y, double z) {
        if (atoms.size() == StructureFragment.MAX_ATOMS) {
            throw new IllegalArgumentException(
                    "A generated fragment may contain at most " + StructureFragment.MAX_ATOMS + " atoms.");
        }
        atoms.add(new StructureFragment.Atom(element, x, y, z));
        return atoms.size() - 1;
    }

    /**
     * Adds an atom with the specified element symbol and coordinates, returning this builder for method chaining.
     *
     * @param element the element symbol, such as C or O
     * @param x the x coordinate, in the structure's units
     * @param y the y coordinate, in the structure's units
     * @param z the z coordinate, in the structure's units
     * @return this {@link StructureBuilder}
     */
    public StructureBuilder atom(String element, double x, double y, double z) {
        addAtom(element, x, y, z);
        return this;
    }

    /**
     * Adds a labeled external group without pretending it is an element or a known mass.
     * @param label visible R-group label, such as R1 or Ar
     * @param x horizontal coordinate
     * @param y vertical coordinate
     * @param z depth coordinate
     * @return zero-based placeholder index, usable with bonds and attachment points
     */
    public int addRGroup(String label, double x, double y, double z) {
        var value = new io.github.wurtzitane.avogadro.molecule.AtomAnnotation(label, 0);
        int index = addAtom("*", x, y, z);
        annotation(index, value);
        return index;
    }

    /**
     * Records an explicit unpaired-electron count on a real atom. Does not change charge or bonds.
     * @param index zero-based atom index
     * @param electrons unpaired electrons, from zero through four
     * @return this builder
     */
    public StructureBuilder radical(int index, int electrons) {
        return annotation(index, new io.github.wurtzitane.avogadro.molecule.AtomAnnotation("", electrons));
    }

    /**
     * Replaces atom-local authoring metadata, preserving its position and connectivity.
     * @param index zero-based atom index
     * @param annotation external-group or radical annotation
     * @return this builder
     */
    public StructureBuilder annotation(int index, io.github.wurtzitane.avogadro.molecule.AtomAnnotation annotation) {
        var atom = atoms.get(index);
        atoms.set(index, new StructureFragment.Atom(atom.element(), atom.x(), atom.y(), atom.z(), annotation));
        return this;
    }

    /**
     * Adds a single bond between two atoms specified by index.
     *
     * @param from the zero-based index of the source atom
     * @param to the zero-based index of the target atom
     * @return this {@link StructureBuilder}
     */
    public StructureBuilder bond(int from, int to) {
        return bond(from, to, BondType.SINGLE);
    }

    /**
     * Adds a single bond between two named attachment points.
     *
     * @param from the source attachment name string
     * @param to the target attachment name string
     * @return this {@link StructureBuilder}
     */
    public StructureBuilder bond(String from, String to) {
        return bond(from, to, BondType.SINGLE);
    }

    /**
     * Adds a bond of specified type between two named attachment points.
     *
     * @param from the source attachment name string
     * @param to the target attachment name string
     * @param bondType the {@link BondType} of the connection
     * @return this {@link StructureBuilder}
     */
    public StructureBuilder bond(String from, String to, BondType bondType) {
        return bond(attachmentIndex(from), attachmentIndex(to), bondType);
    }

    /**
     * Adds a bond of specified string type name between two named attachment points.
     *
     * @param from the source attachment name string
     * @param to the target attachment name string
     * @param type the string name representing the bond type
     * @return this {@link StructureBuilder}
     */
    public StructureBuilder bond(String from, String to, String type) {
        return bond(from, to, bondType(type));
    }

    /**
     * Adds a bond of specified string type name between two atoms specified by index.
     *
     * @param from the zero-based index of the source atom
     * @param to the zero-based index of the target atom
     * @param type the string name representing the bond type
     * @return this {@link StructureBuilder}
     */
    public StructureBuilder bond(int from, int to, String type) {
        return bond(from, to, bondType(type));
    }

    /**
     * Adds a bond of specified type between two atoms specified by index.
     *
     * @param from the zero-based index of the source atom
     * @param to the zero-based index of the target atom
     * @param type the {@link BondType} of the connection
     * @return this {@link StructureBuilder}
     * @throws IllegalArgumentException if the fragment has reached the maximum bond limit
     */
    public StructureBuilder bond(int from, int to, BondType type) {
        if (bonds.size() == StructureFragment.MAX_BONDS) {
            throw new IllegalArgumentException("Too many fragment bonds.");
        }
        bonds.add(new StructureFragment.Bond(from, to, type));
        return this;
    }

    /**
     * Configures the unit cell lattice vectors for periodic structures.
     *
     * @param vectors a 2D array of 1 to 3 lattice vectors, each containing 3 coordinates
     * @return this {@link StructureBuilder}
     * @throws IllegalArgumentException if vectors are null or have invalid dimensions
     */
    public StructureBuilder cell(double[][] vectors) {
        if (vectors == null || vectors.length < 1 || vectors.length > 3) {
            throw new IllegalArgumentException("A cell requires one to three lattice vectors.");
        }
        for (double[] vector : vectors) {
            if (vector == null || vector.length != 3) {
                throw new IllegalArgumentException("A lattice vector requires three coordinates.");
            }
        }
        this.vectors = Arrays.stream(vectors).map(double[]::clone).toArray(double[][]::new);
        return this;
    }

    /**
     * Adds a single periodic cell link between atoms across lattice offsets.
     *
     * @param from source atom index
     * @param to target atom index
     * @param offset integer offset array across lattice boundaries
     * @return this {@link StructureBuilder}
     */
    public StructureBuilder link(int from, int to, int[] offset) {
        return link(from, to, offset, BondType.SINGLE);
    }

    /**
     * Adds a periodic cell link with a specified string bond type name.
     *
     * @param from source atom index
     * @param to target atom index
     * @param offset integer offset array across lattice boundaries
     * @param type string name representing the bond type
     * @return this {@link StructureBuilder}
     */
    public StructureBuilder link(int from, int to, int[] offset, String type) {
        return link(from, to, offset, bondType(type));
    }

    /**
     * Adds a periodic cell link with a specified bond type.
     *
     * @param from source atom index
     * @param to target atom index
     * @param offset integer offset array across lattice boundaries
     * @param type the {@link BondType} of the connection
     * @return this {@link StructureBuilder}
     * @throws IllegalArgumentException if the link limit is exceeded or offsets are invalid
     */
    public StructureBuilder link(int from, int to, int[] offset, BondType type) {
        if (links.size() == StructureFragment.MAX_BONDS) {
            throw new IllegalArgumentException("Too many relative cell links.");
        }
        if (offset == null || offset.length < 1 || offset.length > 3) {
            throw new IllegalArgumentException("A cell link requires one to three integer offsets.");
        }
        links.add(new StructureFragment.CellLink(from, to, Arrays.stream(offset).boxed().toList(), type));
        return this;
    }

    /**
     * Registers a named attachment point pointing to a specific atom index.
     *
     * @param name the attachment name string
     * @param atom the zero-based target atom index
     * @return this {@link StructureBuilder}
     * @throws IllegalArgumentException if the name is invalid, the atom index is negative, or a duplicate attachment
     *     exists
     */
    public StructureBuilder attachment(String name, int atom) {
        StructureFragment.attachmentName(name);
        if (atom < 0) {
            throw new IllegalArgumentException("Negative attachment atom index.");
        }
        if (attachments.putIfAbsent(name, atom) != null) {
            throw new IllegalArgumentException("Duplicate attachment '" + name + "'.");
        }
        return this;
    }

    /**
     * Associates a custom atomic configuration with a specific atom index.
     *
     * @param atom the zero-based atom index
     * @param configuration the {@link AtomicConfiguration} to apply
     * @return this {@link StructureBuilder}
     * @throws IllegalArgumentException if the atom index is negative
     * @throws NullPointerException if the configuration is null
     */
    public StructureBuilder atomConfiguration(int atom, AtomicConfiguration configuration) {
        if (atom < 0) {
            throw new IllegalArgumentException("Negative configuration atom index.");
        }
        configurations.put(atom, Objects.requireNonNull(configuration));
        return this;
    }

    /**
     * Sets the maximum random link capacity for a specific atom index.
     *
     * @param atom the zero-based atom index
     * @param capacity the maximum random link capacity
     * @return this {@link StructureBuilder}
     * @throws IllegalArgumentException if index or capacity is invalid, or if conflicting capacities clash
     */
    public StructureBuilder randomLinkCapacity(int atom, int capacity) {
        if (atom < 0 || capacity < 0 || capacity > StructureFragment.MAX_BONDS) {
            throw new IllegalArgumentException("Invalid random link capacity.");
        }
        Integer previous = capacities.putIfAbsent(atom, capacity);
        if (previous != null && !previous.equals(capacity)) {
            throw new IllegalArgumentException("Attachment aliases must share the same atom capacity.");
        }
        return this;
    }

    /**
     * Sets the maximum random link capacity for a named attachment point.
     *
     * @param attachment the attachment name string
     * @param capacity the maximum random link capacity
     * @return this {@link StructureBuilder}
     */
    public StructureBuilder randomLinkCapacity(String attachment, int capacity) {
        return randomLinkCapacity(attachmentIndex(attachment), capacity);
    }

    /**
     * Builds and returns an immutable {@link StructureFragment} containing all accumulated atoms, bonds, cell links,
     * attachments, configurations, and capacities.
     *
     * @return a new {@link StructureFragment}
     */
    public StructureFragment build() {
        return new StructureFragment(atoms, bonds, vectors, links, attachments, configurations, capacities);
    }

    /**
     * Resolves an attachment name to its corresponding atom index.
     *
     * @param name the attachment name string
     * @return the zero-based atom index
     * @throws IllegalArgumentException if the attachment name is unknown
     */
    private int attachmentIndex(String name) {
        Integer index = attachments.get(name);
        if (index == null) {
            throw new IllegalArgumentException("Unknown attachment '" + name + "'.");
        }
        return index;
    }

    /**
     * Parses a string representation into a corresponding {@link BondType}.
     *
     * @param type the bond type string name or symbol
     * @return the resolved {@link BondType}
     * @throws IllegalArgumentException if the type string is null or unsupported
     */
    private static BondType bondType(String type) {
        if (type == null) {
            throw new IllegalArgumentException("A bond type is required.");
        }
        return switch (type.trim().toUpperCase(java.util.Locale.ROOT)) {
            case "SINGLE", "-", "1" -> BondType.SINGLE;
            case "DOUBLE", "=", "2" -> BondType.DOUBLE;
            case "TRIPLE", "#", "3" -> BondType.TRIPLE;
            case "QUADRUPLE", "4" -> BondType.QUADRUPLE;
            default -> throw new IllegalArgumentException("Unsupported bond type '" + type + "'.");
        };
    }
}
