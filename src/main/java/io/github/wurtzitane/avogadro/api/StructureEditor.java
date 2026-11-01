package io.github.wurtzitane.avogadro.api;

import io.github.wurtzitane.avogadro.molecule.BondType;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Mutable editing session over a private copy.
 * <p>Indices refer to the current geometry after each operation.
 */
public final class StructureEditor {

    /** Mutable list of atoms in the editing session. */
    private final List<StructureFragment.Atom> atoms;

    /** Mutable list of bonds in the editing session. */
    private final List<StructureFragment.Bond> bonds;

    /** Map of named attachment points to their corresponding atom indices. */
    private final Map<String, Integer> attachments;

    /** Map of atom indices to custom atomic configurations. */
    private final Map<Integer, AtomicConfiguration> configurations;

    /** Map of atom indices to maximum random link capacities. */
    private final Map<Integer, Integer> capacities;

    /** Mapping array tracking original atom indices to their current indices. */
    private final int[] originalToCurrent;

    /**
     * Constructs a new {@link StructureEditor} session from a source structure fragment.
     *
     * @param source the source {@link StructureFragment} to copy and edit
     * @throws IllegalArgumentException if the source fragment is a unit cell
     * @throws NullPointerException     if the source is null
     */
    public StructureEditor(StructureFragment source) {
        Objects.requireNonNull(source, "source");
        if (source.isUnitCell()) {
            throw new IllegalArgumentException("Repeat a unit cell before editing its topology.");
        }
        atoms = new ArrayList<>(source.atoms());
        bonds = new ArrayList<>(source.bonds());
        attachments = new LinkedHashMap<>(source.attachments());
        configurations = new LinkedHashMap<>(source.configurations());
        capacities = new LinkedHashMap<>(source.randomLinkCapacities());
        originalToCurrent = java.util.stream.IntStream.range(0, atoms.size()).toArray();
    }

    /**
     * Replaces an atom's element at the specified index while preserving its 3D coordinates.
     *
     * @param index   the zero-based atom index
     * @param element the new element symbol string
     * @return this {@link StructureEditor} instance for chaining
     * @throws IllegalArgumentException if the index is out of bounds
     */
    public StructureEditor replaceAtom(int index, String element) {
        check(index);
        var previous = atoms.get(index);
        var replacement = new StructureFragment.Atom(element, previous.x(), previous.y(), previous.z());
        if (!previous.element().equals(element)) {
            configurations.remove(index);
        }
        atoms.set(index, replacement);
        return this;
    }

    /**
     * Moves an existing atom to new Cartesian coordinates.
     *
     * @param index the zero-based atom index
     * @param x     the new x-axis coordinate in angstroms
     * @param y     the new y-axis coordinate in angstroms
     * @param z     the new z-axis coordinate in angstroms
     * @return this {@link StructureEditor} instance for chaining
     * @throws IllegalArgumentException if the index is out of bounds
     */
    public StructureEditor moveAtom(int index, double x, double y, double z) {
        check(index);
        atoms.set(index, new StructureFragment.Atom(atoms.get(index).element(), x, y, z));
        return this;
    }

    /**
     * Adds a new atom with the specified element symbol and Cartesian coordinates.
     *
     * @param element the element symbol string
     * @param x       the x-axis coordinate in angstroms
     * @param y       the y-axis coordinate in angstroms
     * @param z       the z-axis coordinate in angstroms
     * @return the zero-based integer index of the newly added atom
     * @throws IllegalArgumentException if the atom count exceeds the maximum limit
     */
    public int addAtom(String element, double x, double y, double z) {
        StructureFragment.checkSize((long) atoms.size() + 1, bonds.size());
        atoms.add(new StructureFragment.Atom(element, x, y, z));
        return atoms.size() - 1;
    }

    /**
     * Adds a new atom with the specified element and coordinates, returning this editor for chaining.
     *
     * @param element the element symbol string
     * @param x       the x-axis coordinate in angstroms
     * @param y       the y-axis coordinate in angstroms
     * @param z       the z-axis coordinate in angstroms
     * @return this {@link StructureEditor} instance for chaining
     */
    public StructureEditor atom(String element, double x, double y, double z) {
        addAtom(element, x, y, z);
        return this;
    }

    /**
     * Adds or changes exactly one undirected bond with a specified bond type.
     *
     * @param from the source atom index
     * @param to   the target atom index
     * @param type the {@link BondType} of the connection
     * @return this {@link StructureEditor} instance for chaining
     * @throws IllegalArgumentException if indices are out of bounds or bond limits are exceeded
     */
    public StructureEditor setBond(int from, int to, BondType type) {
        check(from);
        check(to);
        var replacement = new StructureFragment.Bond(from, to, type);
        int existing = bondIndex(from, to);
        if (existing >= 0) {
            bonds.set(existing, replacement);
        } else {
            StructureFragment.checkSize(atoms.size(), (long) bonds.size() + 1);
            bonds.add(replacement);
        }
        return this;
    }

    /**
     * Adds or changes an undirected single bond between two atom indices.
     *
     * @param from the source atom index
     * @param to   the target atom index
     * @return this {@link StructureEditor} instance for chaining
     */
    public StructureEditor setBond(int from, int to) {
        return setBond(from, to, BondType.SINGLE);
    }

    /**
     * Removes an existing bond between two specified atom indices.
     *
     * @param from the source atom index
     * @param to   the target atom index
     * @return this {@link StructureEditor} instance for chaining
     * @throws IllegalArgumentException if indices are out of bounds or no bond exists between the atoms
     */
    public StructureEditor removeBond(int from, int to) {
        check(from);
        check(to);
        int existing = bondIndex(from, to);
        if (existing < 0) {
            throw new IllegalArgumentException("No bond exists between the selected atoms.");
        }
        bonds.remove(existing);
        return this;
    }

    /**
     * Adds a link between two named attachment points, rejecting an existing edge instead of silently changing its order.
     *
     * @param from the source attachment name string
     * @param to   the target attachment name string
     * @param type the {@link BondType} of the connection
     * @return this {@link StructureEditor} instance for chaining
     * @throws IllegalArgumentException if attachment names are unknown or the atoms are already bonded
     */
    public StructureEditor connect(String from, String to, BondType type) {
        int first = attachmentIndex(from), second = attachmentIndex(to);
        if (bondIndex(first, second) >= 0) {
            throw new IllegalArgumentException("The attachment atoms are already bonded.");
        }
        return setBond(first, second, type);
    }

    /**
     * Adds a single bond link between two named attachment points.
     *
     * @param from the source attachment name string
     * @param to   the target attachment name string
     * @return this {@link StructureEditor} instance for chaining
     */
    public StructureEditor connect(String from, String to) {
        return connect(from, to, BondType.SINGLE);
    }

    /**
     * Removes one or more atoms by their indices.
     * <p>The entire selection uses indices from immediately before this operation.
     *
     * @param indices array of zero-based atom indices to remove
     * @return this {@link StructureEditor} instance for chaining
     * @throws IllegalArgumentException if any index is invalid
     * @throws NullPointerException     if indices array is null
     */
    public StructureEditor removeAtoms(int... indices) {
        Objects.requireNonNull(indices, "indices");
        boolean[] removed = new boolean[atoms.size()];
        int count = 0;
        for (int index : indices) {
            check(index);
            if (!removed[index]) {
                removed[index] = true;
                count++;
            }
        }
        StructureFragment.checkSize(atoms.size() - count, 0);
        int[] mapping = new int[atoms.size()];
        Arrays.fill(mapping, -1);
        var remaining = new ArrayList<StructureFragment.Atom>(atoms.size() - count);
        for (int index = 0; index < atoms.size(); index++) {
            if (!removed[index]) {
                mapping[index] = remaining.size();
                remaining.add(atoms.get(index));
            }
        }
        var remainingBonds = new ArrayList<StructureFragment.Bond>();
        for (var bond : bonds) {
            if (!removed[bond.from()] && !removed[bond.to()]) {
                remainingBonds.add(new StructureFragment.Bond(mapping[bond.from()], mapping[bond.to()], bond.type()));
            }
        }
        atoms.clear();
        atoms.addAll(remaining);
        bonds.clear();
        bonds.addAll(remainingBonds);
        attachments.entrySet().removeIf(entry -> removed[entry.getValue()]);
        attachments.replaceAll((name, index) -> mapping[index]);
        remap(configurations, mapping);
        remap(capacities, mapping);
        for (int original = 0; original < originalToCurrent.length; original++) {
            if (originalToCurrent[original] >= 0) {
                originalToCurrent[original] = mapping[originalToCurrent[original]];
            }
        }
        return this;
    }

    /**
     * Removes a single atom by its index.
     *
     * @param index the zero-based atom index to remove
     * @return this {@link StructureEditor} instance for chaining
     */
    public StructureEditor removeAtom(int index) {
        return removeAtoms(index);
    }

    /**
     * Appends a qualified structure fragment instance into this editor session.
     *
     * @param instance  the instance qualifier prefix string
     * @param fragment  the {@link StructureFragment} to qualify and append
     * @return this {@link StructureEditor} instance for chaining
     */
    public StructureEditor append(String instance, StructureFragment fragment) {
        return append(fragment.qualified(instance));
    }

    /**
     * Appends another structure fragment into this editor session.
     *
     * @param fragment the {@link StructureFragment} to append
     * @return this {@link StructureEditor} instance for chaining
     */
    public StructureEditor append(StructureFragment fragment) {
        // Validate the complete operation before changing this session.
        StructureFragment result = fragment().append(fragment);
        atoms.clear();
        atoms.addAll(result.atoms());
        bonds.clear();
        bonds.addAll(result.bonds());
        attachments.clear();
        attachments.putAll(result.attachments());
        configurations.clear();
        configurations.putAll(result.configurations());
        capacities.clear();
        capacities.putAll(result.randomLinkCapacities());
        return this;
    }

    /**
     * Registers a named attachment point pointing to a specific atom index.
     *
     * @param name  the attachment name string
     * @param index the zero-based atom index
     * @return this {@link StructureEditor} instance for chaining
     * @throws IllegalArgumentException if the name is invalid, the index is out of bounds, or a duplicate attachment exists
     */
    public StructureEditor attachment(String name, int index) {
        StructureFragment.attachmentName(name);
        check(index);
        if (attachments.putIfAbsent(name, index) != null) {
            throw new IllegalArgumentException("Duplicate attachment '" + name + "'.");
        }
        return this;
    }

    /**
     * Removes a named attachment point.
     *
     * @param name the attachment name string to remove
     * @return this {@link StructureEditor} instance for chaining
     * @throws IllegalArgumentException if the attachment name is unknown
     */
    public StructureEditor removeAttachment(String name) {
        if (attachments.remove(name) == null) {
            throw new IllegalArgumentException("Unknown attachment '" + name + "'.");
        }
        return this;
    }

    /**
     * Associates a custom atomic configuration with an atom at the specified index.
     *
     * @param index         the zero-based atom index
     * @param configuration the {@link AtomicConfiguration} to apply
     * @return this {@link StructureEditor} instance for chaining
     * @throws IllegalArgumentException if the index is out of bounds
     * @throws NullPointerException     if the configuration is null
     */
    public StructureEditor atomConfiguration(int index, AtomicConfiguration configuration) {
        check(index);
        configurations.put(index, Objects.requireNonNull(configuration));
        return this;
    }

    /**
     * Clears the custom atomic configuration at the specified atom index.
     *
     * @param index the zero-based atom index
     * @return this {@link StructureEditor} instance for chaining
     * @throws IllegalArgumentException if the index is out of bounds
     */
    public StructureEditor clearAtomConfiguration(int index) {
        check(index);
        configurations.remove(index);
        return this;
    }

    /**
     * Sets the maximum random link capacity for an atom at the specified index.
     *
     * @param index    the zero-based atom index
     * @param capacity the maximum random link capacity
     * @return this {@link StructureEditor} instance for chaining
     * @throws IllegalArgumentException if the index or capacity is invalid
     */
    public StructureEditor randomLinkCapacity(int index, int capacity) {
        check(index);
        if (capacity < 0 || capacity > StructureFragment.MAX_BONDS) {
            throw new IllegalArgumentException("Invalid random link capacity.");
        }
        capacities.put(index, capacity);
        return this;
    }

    /**
     * Builds and returns an immutable {@link StructureFragment} representing the current edited state.
     *
     * @return a new {@link StructureFragment} instance
     */
    public StructureFragment fragment() {
        return new StructureFragment(atoms, bonds, attachments, configurations, capacities);
    }

    /**
     * Finalizes the editing session into a structured result containing the fragment and index remapping.
     *
     * @return a new {@link StructureEditResult} instance
     */
    public StructureEditResult build() {
        return new StructureEditResult(fragment(), Arrays.stream(originalToCurrent).boxed().toList());
    }

    /**
     * Validates that an atom index is within bounds.
     *
     * @param index the atom index to check
     * @throws IllegalArgumentException if the index is out of bounds
     */
    private void check(int index) {
        StructureFragment.atomIndex(index, atoms.size());
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
     * Finds the index of a bond between two atoms, checking both directions.
     *
     * @param from source atom index
     * @param to   target atom index
     * @return the zero-based bond index, or {@code -1} if not found
     */
    private int bondIndex(int from, int to) {
        for (int index = 0; index < bonds.size(); index++) {
            var bond = bonds.get(index);
            if (bond.from() == from && bond.to() == to || bond.from() == to && bond.to() == from) {
                return index;
            }
        }
        return -1;
    }

    /**
     * Remaps configuration or capacity index maps following atom deletion and reindexing.
     *
     * @param values  the map of integer indices to values
     * @param mapping the index remapping array
     * @param <T>     the value type
     */
    private static <T> void remap(Map<Integer, T> values, int[] mapping) {
        var copy = new LinkedHashMap<>(values);
        values.clear();
        copy.forEach((index, value) -> {
            if (mapping[index] >= 0) {
                values.put(mapping[index], value);
            }
        });
    }
}