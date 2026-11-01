package io.github.wurtzitane.avogadro.api;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.github.wurtzitane.avogadro.molecule.AtomElement;
import io.github.wurtzitane.avogadro.molecule.BondType;
import io.github.wurtzitane.avogadro.molecule.MoleculeDef;
import io.github.wurtzitane.avogadro.molecule.MoleculeStructure;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Immutable indexed geometry.
 * <p>Operations retain explicit connectivity and never infer bonds.
 */
public final class StructureFragment {

    /** Maximum allowed atoms in a structure fragment. */
    public static final int MAX_ATOMS = 4096;

    /** Maximum allowed bonds in a structure fragment. */
    public static final int MAX_BONDS = 8192;

    /** Metadata key for structure attachment points. */
    public static final String ATTACHMENTS_METADATA_KEY = "structure_attachments";

    /** Metadata key for random link capacities. */
    public static final String CAPACITIES_METADATA_KEY = "structure_random_link_capacities";

    /** Regular expression pattern for validating chemical element symbols. */
    private static final Pattern ELEMENT_SYMBOL = Pattern.compile("[A-Z][a-z]?");

    /**
     * Represents an individual atom within a structure fragment with its element symbol and 3D coordinates.
     *
     * @param element the element symbol (e.g. "C", "Cl")
     * @param x       the x-axis coordinate in angstroms
     * @param y       the y-axis coordinate in angstroms
     * @param z       the z-axis coordinate in angstroms
     */
    public record Atom(String element, double x, double y, double z) {

        /**
         * Compact constructor for {@link Atom} that validates element symbol format and coordinate finiteness.
         *
         * @throws IllegalArgumentException if the element symbol is invalid or coordinates are non-finite
         */
        public Atom {
            if (element == null || !ELEMENT_SYMBOL.matcher(element).matches()) {
                throw invalid("An atom requires an element symbol such as C or Cl.");
            }
            coordinate(x);
            coordinate(y);
            coordinate(z);
        }
    }

    /**
     * Represents a chemical bond connecting two atoms by their indices.
     *
     * @param from the zero-based index of the source atom
     * @param to   the zero-based index of the target atom
     * @param type the {@link BondType} of the connection
     */
    public record Bond(int from, int to, BondType type) {

        /**
         * Compact constructor for {@link Bond} that validates indices and bond type.
         *
         * @throws IllegalArgumentException if indices are negative, identical, or type is null
         */
        public Bond {
            if (from < 0 || to < 0 || from == to) {
                throw invalid("An atom cannot form a bond with itself or use negative indices.");
            }
            if (type == null) {
                throw invalid("A bond type is required.");
            }
        }
    }

    /**
     * Represents a periodic unit-cell link connecting atoms across lattice boundaries.
     *
     * @param from   source atom index
     * @param to     target atom index
     * @param offset integer offset list across unit cell axes
     * @param type   the {@link BondType}
     */
    public record CellLink(int from, int to, List<Integer> offset, BondType type) {

        /**
         * Compact constructor for {@link CellLink} that validates endpoints, bond type, and lattice offsets.
         *
         * @throws IllegalArgumentException if link parameters are invalid or self-connecting within the same cell
         */
        public CellLink {
            if (from < 0 || to < 0 || type == null || offset == null || offset.isEmpty() || offset.size() > 3) {
                throw invalid("A cell link requires valid atom indices, an offset vector, and a bond type.");
            }
            if (offset.stream().anyMatch(Objects::isNull)) {
                throw invalid("Cell link offset list cannot contain null elements.");
            }
            offset = List.copyOf(offset);
            if (from == to && offset.stream().allMatch(value -> value == 0)) {
                throw invalid("A cell link cannot connect an atom to itself across a zero lattice offset.");
            }
        }
    }

    /**
     * Internal 3D vector helper for spatial and lattice calculations.
     *
     * @param x x coordinate
     * @param y y coordinate
     * @param z z coordinate
     */
    private record Vector(double x, double y, double z) {

        /**
         * Compact constructor for {@link Vector} validating coordinate finiteness.
         */
        Vector {
            coordinate(x);
            coordinate(y);
            coordinate(z);
        }

        /**
         * Computes the Euclidean length (magnitude) of this vector.
         *
         * @return the vector magnitude
         */
        double length() {
            return Math.hypot(Math.hypot(x, y), z);
        }

        /**
         * Computes the dot product between this vector and another.
         *
         * @param other the other {@link Vector}
         * @return the dot product value
         */
        double dot(Vector other) {
            return x * other.x + y * other.y + z * other.z;
        }

        /**
         * Computes the cross product between this vector and another.
         *
         * @param other the other {@link Vector}
         * @return the resulting cross product {@link Vector}
         */
        Vector cross(Vector other) {
            return new Vector(y * other.z - z * other.y, z * other.x - x * other.z, x * other.y - y * other.x);
        }

        /**
         * Computes the normalized unit vector in this direction.
         *
         * @return the normalized {@link Vector}
         * @throws IllegalArgumentException if this vector has zero length
         */
        Vector unit() {
            double length = length();
            if (!(length > 0)) {
                throw invalid("A direction vector must not be zero.");
            }
            return new Vector(x / length, y / length, z / length);
        }
    }

    /**
     * Internal unit cell representation containing lattice vectors and periodic links.
     *
     * @param vectors list of lattice vectors
     * @param links   list of periodic cell links
     */
    private record Cell(List<Vector> vectors, List<CellLink> links) {}

    /** List of atoms in this fragment. */
    private final List<Atom> atoms;

    /** List of bonds in this fragment. */
    private final List<Bond> bonds;

    /** Optional periodic unit cell definition. */
    private final Cell cell;

    /** Map of named attachment point names to atom indices. */
    private final Map<String, Integer> attachments;

    /** Map of atom indices to custom atomic configurations. */
    private final Map<Integer, AtomicConfiguration> configurations;

    /** Map of atom indices to maximum random link capacities. */
    private final Map<Integer, Integer> randomLinkCapacities;

    /**
     * Constructs a new {@link StructureFragment} without unit cell vectors or metadata.
     *
     * @param atoms   list of atoms
     * @param bonds   list of bonds
     * @param vectors lattice vectors array (can be null)
     * @param links   list of cell links
     */
    public StructureFragment(List<Atom> atoms, List<Bond> bonds, double[][] vectors, List<CellLink> links) {
        this(atoms, bonds, vectors, links, Map.of(), Map.of(), Map.of());
    }

    /**
     * Constructs a new {@link StructureFragment} with attachments, configurations, and capacities.
     *
     * @param atoms          list of atoms
     * @param bonds          list of bonds
     * @param attachments    map of named attachments
     * @param configurations map of atomic configurations
     * @param capacities     map of random link capacities
     */
    public StructureFragment(List<Atom> atoms, List<Bond> bonds, Map<String, Integer> attachments,
                             Map<Integer, AtomicConfiguration> configurations, Map<Integer, Integer> capacities) {
        this(atoms, bonds, null, List.of(), attachments, configurations, capacities);
    }

    /**
     * Constructs a fully specified {@link StructureFragment} with all atoms, bonds, lattice vectors, links, and metadata.
     *
     * @param atoms          list of atoms
     * @param bonds          list of bonds
     * @param vectors        lattice vectors array
     * @param links          list of cell links
     * @param attachments    map of named attachments
     * @param configurations map of atomic configurations
     * @param capacities     map of random link capacities
     */
    public StructureFragment(List<Atom> atoms, List<Bond> bonds, double[][] vectors, List<CellLink> links,
                             Map<String, Integer> attachments, Map<Integer, AtomicConfiguration> configurations,
                             Map<Integer, Integer> capacities) {
        checkSize(atoms.size(), bonds.size());
        this.atoms = List.copyOf(atoms);
        this.bonds = List.copyOf(bonds);
        var checkedAttachments = new LinkedHashMap<String, Integer>();
        attachments.forEach((name, index) -> {
            attachmentName(name);
            atomIndex(index, this.atoms.size());
            checkedAttachments.put(name, index);
        });
        this.attachments = java.util.Collections.unmodifiableMap(checkedAttachments);
        configurations.forEach((index, value) -> {
            atomIndex(index, this.atoms.size());
            Objects.requireNonNull(value, "configuration");
        });
        capacities.forEach((index, value) -> {
            atomIndex(index, this.atoms.size());
            if (value == null || value < 0 || value > MAX_BONDS) {
                throw invalid("Random link capacity must be between 0 and " + MAX_BONDS + ".");
            }
        });
        this.configurations = Map.copyOf(configurations);
        this.randomLinkCapacities = Map.copyOf(capacities);
        Map<Long, BondType> seen = new HashMap<>();
        for (Bond bond : this.bonds) {
            indices(bond.from(), bond.to(), this.atoms.size());
            uniqueBond(seen, bond.from(), bond.to(), bond.type());
        }
        if (vectors == null) {
            if (!links.isEmpty()) {
                throw invalid("Cell links require cell vectors.");
            }
            cell = null;
        } else {
            if (vectors.length < 1 || vectors.length > 3) {
                throw invalid("A cell requires one to three lattice vectors.");
            }
            if (links.size() > MAX_BONDS) {
                throw invalid("A cell may contain at most " + MAX_BONDS + " relative links.");
            }
            List<Vector> copy = new ArrayList<>(vectors.length);
            for (double[] vector : vectors) {
                copy.add(vector(vector));
            }
            independent(copy);
            Set<String> linkKeys = new HashSet<>();
            for (CellLink link : links) {
                indices(link.from(), link.to(), this.atoms.size());
                if (link.offset().size() != copy.size()) {
                    throw invalid("A cell link offset must match the number of lattice vectors.");
                }
                if (link.offset().stream().allMatch(value -> value == 0)) {
                    uniqueBond(seen, link.from(), link.to(), link.type());
                }
                String forward = linkKey(link, false), reverse = linkKey(link, true);
                if (!linkKeys.add(forward.compareTo(reverse) < 0 ? forward : reverse)) {
                    throw invalid("Duplicate or conflicting relative cell link.");
                }
            }
            cell = new Cell(List.copyOf(copy), List.copyOf(links));
        }
        compile("avogadro:fragment", this.atoms, this.bonds);
    }

    /**
     * Retrieves the unmodifiable list of atoms in this fragment.
     *
     * @return list of {@link Atom} instances
     */
    public List<Atom> atoms() {
        return atoms;
    }

    /**
     * Retrieves the unmodifiable list of bonds in this fragment.
     *
     * @return list of {@link Bond} instances
     */
    public List<Bond> bonds() {
        return bonds;
    }

    /**
     * Checks whether this fragment represents an unrepeated unit cell.
     *
     * @return {@code true} if it is a unit cell, {@code false} otherwise
     */
    public boolean isUnitCell() {
        return cell != null;
    }

    /**
     * Retrieves the map of named attachment point names to atom indices.
     *
     * @return unmodifiable map of attachment names and indices
     */
    public Map<String, Integer> attachments() {
        return attachments;
    }

    /**
     * Retrieves the map of atom indices to custom atomic configurations.
     *
     * @return map of configurations
     */
    public Map<Integer, AtomicConfiguration> configurations() {
        return configurations;
    }

    /**
     * Retrieves the map of atom indices to maximum random link capacities.
     *
     * @return map of capacities
     */
    public Map<Integer, Integer> randomLinkCapacities() {
        return randomLinkCapacities;
    }

    /**
     * Resolves an attachment name to its corresponding atom index.
     *
     * @param name the attachment name string
     * @return the zero-based atom index
     * @throws IllegalArgumentException if the attachment name is unknown
     */
    public int attachment(String name) {
        Integer index = attachments.get(name);
        if (index == null) {
            throw invalid("Unknown attachment '" + name + "'.");
        }
        return index;
    }

    /**
     * Creates a mutable editing session over this fragment.
     *
     * @return a new {@link StructureEditor} session
     * @throws IllegalArgumentException if this fragment is an unrepeated unit cell
     */
    public StructureEditor edit() {
        requireExpanded();
        return new StructureEditor(this);
    }

    /**
     * Imports a structure fragment from a molecule structure.
     *
     * @param structure the {@link MoleculeStructure}
     * @return a new {@link StructureFragment}
     * @throws NullPointerException if structure is null
     */
    public static StructureFragment from(MoleculeStructure structure) {
        Objects.requireNonNull(structure, "structure");
        return new StructureFragment(structure.atoms().stream().map(atom -> new Atom(atom.element().symbol(),
                atom.x(), atom.y(), atom.z())).toList(), structure.bonds().stream()
                .map(bond -> new Bond(bond.from(), bond.to(), bond.type())).toList(), null, List.of());
    }

    /**
     * Imports geometry and explicit per-atom authoring metadata from a molecule definition.
     * Definition-wide defaults remain on the definition.
     *
     * @param definition the {@link MoleculeDef}
     * @return a new {@link StructureFragment}
     * @throws NullPointerException if definition is null
     */
    public static StructureFragment from(MoleculeDef definition) {
        Objects.requireNonNull(definition, "definition");
        StructureFragment geometry = from(definition.structure());
        var names = new LinkedHashMap<String, Integer>();
        var capacities = new LinkedHashMap<Integer, Integer>();
        String encodedNames = definition.inspection().metadata().get(ATTACHMENTS_METADATA_KEY);
        if (encodedNames != null) {
            JsonParser.parseString(encodedNames).getAsJsonObject().entrySet()
                    .forEach(entry -> names.put(entry.getKey(), metadataInteger(entry.getValue())));
        }
        String encodedCapacities = definition.inspection().metadata().get(CAPACITIES_METADATA_KEY);
        if (encodedCapacities != null) {
            JsonParser.parseString(encodedCapacities).getAsJsonObject().entrySet()
                    .forEach(entry -> capacities.put(Integer.parseInt(entry.getKey()), metadataInteger(entry.getValue())));
        }
        return new StructureFragment(geometry.atoms, geometry.bonds, names, definition.inspection().atoms(), capacities);
    }

    /**
     * Qualifies every named attachment with a prefix while retaining atom identity.
     *
     * @param prefix the qualifier prefix string
     * @return a new qualified {@link StructureFragment}
     * @throws IllegalArgumentException if the prefix is invalid
     */
    public StructureFragment qualified(String prefix) {
        attachmentName(prefix);
        var names = new LinkedHashMap<String, Integer>();
        attachments.forEach((name, index) -> names.put(prefix + "/" + name, index));
        double[][] vectors = cell == null ? null : cell.vectors().stream()
                                                   .map(vector -> new double[]{vector.x(), vector.y(), vector.z()}).toArray(double[][]::new);
        return new StructureFragment(atoms, bonds, vectors, cell == null ? List.of() : cell.links(), names,
                configurations, randomLinkCapacities);
    }

    /**
     * Translates this fragment by specified Cartesian offsets.
     *
     * @param x x offset in angstroms
     * @param y y offset in angstroms
     * @param z z offset in angstroms
     * @return a new translated {@link StructureFragment}
     */
    public StructureFragment translate(double x, double y, double z) {
        return affine(new double[][]{{1, 0, 0, x}, {0, 1, 0, y}, {0, 0, 1, z}});
    }

    /**
     * Scales this fragment uniformly in all dimensions.
     *
     * @param scale the uniform scale factor
     * @return a new scaled {@link StructureFragment}
     */
    public StructureFragment scale(double scale) {
        return scale(scale, scale, scale);
    }

    /**
     * Scales this fragment by independent axis factors.
     *
     * @param x x scale factor
     * @param y y scale factor
     * @param z z scale factor
     * @return a new scaled {@link StructureFragment}
     */
    public StructureFragment scale(double x, double y, double z) {
        return affine(new double[][]{{x, 0, 0, 0}, {0, y, 0, 0}, {0, 0, z, 0}});
    }

    /**
     * Rotates this fragment around a specified rotation axis by an angle in degrees.
     *
     * @param axisX   x component of rotation axis
     * @param axisY   y component of rotation axis
     * @param axisZ   z component of rotation axis
     * @param degrees angle of rotation in degrees
     * @return a new rotated {@link StructureFragment}
     * @throws IllegalArgumentException if degrees are non-finite or axis is zero
     */
    public StructureFragment rotate(double axisX, double axisY, double axisZ, double degrees) {
        if (!Double.isFinite(degrees)) {
            throw invalid("Rotation degrees must be finite.");
        }
        Vector axis = new Vector(axisX, axisY, axisZ).unit();
        double angle = Math.toRadians(degrees % 360), cosine = Math.cos(angle), sine = Math.sin(angle), t = 1 - cosine;
        double x = axis.x(), y = axis.y(), z = axis.z();
        return affine(new double[][]{
                {t * x * x + cosine, t * x * y - sine * z, t * x * z + sine * y, 0},
                {t * x * y + sine * z, t * y * y + cosine, t * y * z - sine * x, 0},
                {t * x * z - sine * y, t * y * z + sine * x, t * z * z + cosine, 0}});
    }

    /**
     * Transforms this fragment using a 3x4 affine transformation matrix.
     * <p>Three rows of four coefficients, applied to column vectors [x, y, z, 1].
     *
     * @param matrix the 3x4 transformation matrix array
     * @return a new transformed {@link StructureFragment}
     * @throws IllegalArgumentException if the matrix dimensions or coefficients are invalid
     */
    public StructureFragment affine(double[][] matrix) {
        double[][] transform = matrix(matrix);
        List<Atom> transformed = new ArrayList<>(atoms.size());
        for (Atom atom : atoms) {
            transformed.add(transform(atom, transform));
        }
        double[][] vectors = null;
        if (cell != null) {
            vectors = new double[cell.vectors().size()][3];
            for (int index = 0; index < vectors.length; index++) {
                Vector vector = cell.vectors().get(index);
                for (int axis = 0; axis < 3; axis++) {
                    vectors[index][axis] = transform[axis][0] * vector.x()
                            + transform[axis][1] * vector.y() + transform[axis][2] * vector.z();
                }
            }
        }
        return new StructureFragment(transformed, bonds, vectors, cell == null ? List.of() : cell.links(),
                attachments, configurations, randomLinkCapacities);
    }

    /**
     * Appends another structure fragment into this one.
     *
     * @param other the other {@link StructureFragment} to append
     * @return a new combined {@link StructureFragment}
     * @throws NullPointerException     if other is null
     * @throws IllegalArgumentException if limits are exceeded or duplicate attachment names collide
     */
    public StructureFragment append(StructureFragment other) {
        requireExpanded();
        Objects.requireNonNull(other, "other").requireExpanded();
        checkSize((long) atoms.size() + other.atoms.size(), (long) bonds.size() + other.bonds.size());
        List<Atom> combinedAtoms = new ArrayList<>(atoms.size() + other.atoms.size());
        combinedAtoms.addAll(atoms);
        combinedAtoms.addAll(other.atoms);
        List<Bond> combinedBonds = new ArrayList<>(bonds.size() + other.bonds.size());
        combinedBonds.addAll(bonds);
        for (Bond bond : other.bonds) {
            combinedBonds.add(new Bond(bond.from() + atoms.size(), bond.to() + atoms.size(), bond.type()));
        }
        var names = new LinkedHashMap<>(attachments);
        other.attachments.forEach((name, index) -> {
            if (names.putIfAbsent(name, index + atoms.size()) != null) {
                throw invalid("Duplicate attachment '" + name + "'; qualify the appended fragment.");
            }
        });
        var nuclear = new LinkedHashMap<>(configurations);
        other.configurations.forEach((index, value) -> nuclear.put(index + atoms.size(), value));
        var capacities = new LinkedHashMap<>(randomLinkCapacities);
        other.randomLinkCapacities.forEach((index, value) -> capacities.put(index + atoms.size(), value));
        return new StructureFragment(combinedAtoms, combinedBonds, names, nuclear, capacities);
    }

    /**
     * Appends a qualified instance of another fragment.
     *
     * @param instance the instance qualifier prefix
     * @param other    the other {@link StructureFragment}
     * @return a new combined {@link StructureFragment}
     */
    public StructureFragment append(String instance, StructureFragment other) {
        return append(other.qualified(instance));
    }

    /**
     * Generates disjoint transformed copies of this fragment.
     * <p>This does not invent connections between instances.
     *
     * @param transforms list of 3x4 affine transform matrices
     * @return a new multi-instance {@link StructureFragment}
     */
    public StructureFragment instances(List<double[][]> transforms) {
        return instances("instance", transforms);
    }

    /**
     * Generates multiple transformed instances with a specified prefix.
     *
     * @param prefix     the naming prefix for instances
     * @param transforms list of 3x4 affine transform matrices
     * @return a new multi-instance {@link StructureFragment}
     * @throws IllegalArgumentException if transforms list is null or empty
     */
    public StructureFragment instances(String prefix, List<double[][]> transforms) {
        requireExpanded();
        attachmentName(prefix);
        if (transforms == null || transforms.isEmpty()) {
            throw invalid("At least one instance transform is required.");
        }
        checkSize((long) atoms.size() * transforms.size(), (long) bonds.size() * transforms.size());
        List<double[][]> matrices = transforms.stream().map(StructureFragment::matrix).toList();
        List<Atom> resultAtoms = new ArrayList<>(atoms.size() * matrices.size());
        List<Bond> resultBonds = new ArrayList<>(bonds.size() * matrices.size());
        var names = new LinkedHashMap<String, Integer>();
        var nuclear = new LinkedHashMap<Integer, AtomicConfiguration>();
        var capacities = new LinkedHashMap<Integer, Integer>();
        int instance = 0;
        for (double[][] transform : matrices) {
            int offset = resultAtoms.size();
            copyMetadata(prefix + "[" + instance++ + "]", offset, names, nuclear, capacities);
            for (Atom atom : atoms) {
                resultAtoms.add(transform(atom, transform));
            }
            for (Bond bond : bonds) {
                resultBonds.add(new Bond(bond.from() + offset, bond.to() + offset, bond.type()));
            }
        }
        return new StructureFragment(resultAtoms, resultBonds, names, nuclear, capacities);
    }

    /**
     * Repeats this unit cell across lattice vectors according to specified counts and wrapping flags.
     * <p>Open boundaries omit outward links; wrapped boundaries resolve destination cells modulo their counts.
     *
     * @param counts array of repetition counts per lattice vector
     * @param wrap   array of boolean wrap flags per lattice vector
     * @return a new expanded repetition {@link StructureFragment}
     * @throws IllegalArgumentException if the fragment is not a unit cell or parameters are invalid
     */
    public StructureFragment repeat(int[] counts, boolean[] wrap) {
        if (cell == null) {
            throw invalid("Repeat requires a unit cell with explicit lattice vectors.");
        }
        int dimensions = cell.vectors().size();
        if (counts == null || counts.length != dimensions || wrap == null || wrap.length != dimensions) {
            throw invalid("Repeat counts and wrap flags must match the number of lattice vectors.");
        }
        counts = counts.clone();
        wrap = wrap.clone();
        long copies = 1;
        for (int count : counts) {
            if (count < 1 || count > MAX_ATOMS) {
                throw invalid("Repeat counts must be between 1 and " + MAX_ATOMS + ".");
            }
            copies *= count;
            checkSize(copies * atoms.size(), 0);
        }
        long bondCount = copies * bonds.size();
        for (CellLink link : cell.links()) {
            long available = 1;
            for (int axis = 0; axis < dimensions; axis++) {
                available *= wrap[axis] ? counts[axis]
                        : Math.max(0L, counts[axis] - Math.abs((long) link.offset().get(axis)));
            }
            bondCount += available;
            checkSize(copies * atoms.size(), bondCount);
        }
        checkSize(copies * atoms.size(), bondCount);
        List<Atom> resultAtoms = new ArrayList<>((int) copies * atoms.size());
        List<Bond> resultBonds = new ArrayList<>((int) bondCount);
        var names = new LinkedHashMap<String, Integer>();
        var nuclear = new LinkedHashMap<Integer, AtomicConfiguration>();
        var capacities = new LinkedHashMap<Integer, Integer>();
        int[] location = new int[dimensions];
        int[] destination = new int[dimensions];
        for (int copy = 0; copy < copies; copy++) {
            cellCoordinates(copy, counts, location);
            double x = 0, y = 0, z = 0;
            for (int axis = 0; axis < dimensions; axis++) {
                Vector vector = cell.vectors().get(axis);
                x += location[axis] * vector.x();
                y += location[axis] * vector.y();
                z += location[axis] * vector.z();
            }
            int base = copy * atoms.size();
            String coordinateName = java.util.Arrays.stream(location).mapToObj(Integer::toString)
                    .collect(java.util.stream.Collectors.joining(","));
            copyMetadata("cell[" + coordinateName + "]", base, names, nuclear, capacities);
            for (Atom atom : atoms) {
                resultAtoms.add(new Atom(atom.element(), atom.x() + x, atom.y() + y, atom.z() + z));
            }
            for (Bond bond : bonds) {
                resultBonds.add(new Bond(base + bond.from(), base + bond.to(), bond.type()));
            }
            for (CellLink link : cell.links()) {
                boolean present = true;
                for (int axis = 0; axis < dimensions; axis++) {
                    long next = (long) location[axis] + link.offset().get(axis);
                    if (wrap[axis]) {
                        destination[axis] = (int) Math.floorMod(next, counts[axis]);
                    } else if (next < 0 || next >= counts[axis]) {
                        present = false;
                        break;
                    } else {
                        destination[axis] = (int) next;
                    }
                }
                if (present) {
                    resultBonds.add(new Bond(base + link.from(), cellIndex(destination, counts) * atoms.size() + link.to(), link.type()));
                }
            }
        }
        return new StructureFragment(resultAtoms, resultBonds, names, nuclear, capacities);
    }

    /**
     * Wraps this structure fragment into a cylinder of specified dimensions.
     * <p>Cylindrical coordinates preserve every indexed bond, including seams explicitly created by repeat.
     *
     * @param origin             origin point array [x, y, z]
     * @param circumferenceAxis  circumference axis vector array
     * @param axialAxis          axial direction axis vector array
     * @param circumference      total cylinder circumference value
     * @return a new wrapped cylindrical {@link StructureFragment}
     * @throws IllegalArgumentException if dimensions or axes are invalid
     */
    public StructureFragment wrapCylinder(double[] origin, double[] circumferenceAxis, double[] axialAxis, double circumference) {
        requireExpanded();
        if (!Double.isFinite(circumference) || circumference <= 0) {
            throw invalid("Cylinder circumference must be finite and positive.");
        }
        Vector center = vector(origin), around = vector(circumferenceAxis).unit(), along = vector(axialAxis).unit();
        if (Math.abs(around.dot(along)) > 1e-8) {
            throw invalid("Cylinder circumference and axial directions must be perpendicular.");
        }
        Vector normal = around.cross(along).unit();
        double radius = circumference / (2 * Math.PI);
        List<Atom> wrapped = new ArrayList<>(atoms.size());
        for (Atom atom : atoms) {
            Vector point = new Vector(atom.x() - center.x(), atom.y() - center.y(), atom.z() - center.z());
            double angle = (point.dot(around) % circumference) / circumference * 2 * Math.PI;
            double axial = point.dot(along), radial = radius + point.dot(normal);
            if (!(radial > 0)) {
                throw invalid("Cylinder wrapping places an atom at or beyond the cylinder axis.");
            }
            double cosine = Math.cos(angle) * radial, sine = Math.sin(angle) * radial;
            wrapped.add(new Atom(atom.element(), center.x() + along.x() * axial + around.x() * cosine + normal.x() * sine,
                    center.y() + along.y() * axial + around.y() * cosine + normal.y() * sine,
                    center.z() + along.z() * axial + around.z() * cosine + normal.z() * sine));
        }
        return new StructureFragment(wrapped, bonds, attachments, configurations, randomLinkCapacities);
    }

    /**
     * Serializes this fragment into a JSON object representation.
     *
     * @return a new {@link JsonObject} containing atoms, bonds, attachments, and capacities
     */
    public JsonObject toJson() {
        requireExpanded();
        JsonObject result = new JsonObject();
        JsonArray atomJson = new JsonArray();
        for (int index = 0; index < atoms.size(); index++) {
            Atom atom = atoms.get(index);
            JsonObject value = new JsonObject();
            value.addProperty("e", atom.element());
            value.addProperty("x", atom.x());
            value.addProperty("y", atom.y());
            value.addProperty("z", atom.z());
            if (configurations.containsKey(index)) {
                value.add("nuclear", configurations.get(index).toJson());
            }
            atomJson.add(value);
        }
        JsonArray bondJson = new JsonArray();
        for (Bond bond : bonds) {
            JsonArray value = new JsonArray();
            value.add(bond.from());
            value.add(bond.to());
            value.add(bond.type().name());
            bondJson.add(value);
        }
        result.add("atoms", atomJson);
        result.add("bonds", bondJson);
        if (!attachments.isEmpty()) {
            JsonObject names = new JsonObject();
            attachments.forEach(names::addProperty);
            result.add("attachments", names);
        }
        if (!randomLinkCapacities.isEmpty()) {
            JsonObject capacities = new JsonObject();
            new java.util.TreeMap<>(randomLinkCapacities).forEach((index, value) -> capacities.addProperty(index.toString(), value));
            result.add("random_link_capacities", capacities);
        }
        return result;
    }

    /**
     * Converts this fragment into a compiled molecule structure.
     *
     * @param id the structure identifier string
     * @return a new {@link MoleculeStructure}
     * @throws NullPointerException if id is null
     */
    public MoleculeStructure toStructure(String id) {
        requireExpanded();
        return compile(Objects.requireNonNull(id, "id"), atoms, bonds);
    }

    /** Compiles atoms and bonds into a molecule structure. */
    private static MoleculeStructure compile(String id, List<Atom> atoms, List<Bond> bonds) {
        MoleculeStructure.Builder result = MoleculeStructure.builder(id, "");
        for (Atom atom : atoms) {
            result.addAtom(AtomElement.bySymbol(atom.element()), (float) atom.x(), (float) atom.y(), (float) atom.z());
        }
        for (Bond bond : bonds) {
            result.bond(bond.from(), bond.to(), bond.type());
        }
        MoleculeStructure structure = result.build();
        if (!Float.isFinite(structure.centerX()) || !Float.isFinite(structure.centerY())
                || !Float.isFinite(structure.centerZ()) || !Float.isFinite(structure.radius())) {
            throw invalid("Generated coordinates exceed the renderer's finite center or radius range.");
        }
        return structure;
    }

    /** Ensures the fragment is expanded (not an unrepeated unit cell). */
    private void requireExpanded() {
        if (cell != null) {
            throw invalid("A unit cell must be repeated before it can be exported, combined or wrapped.");
        }
    }

    /** Transforms an atom position with an affine matrix row. */
    private static Atom transform(Atom atom, double[][] matrix) {
        return new Atom(atom.element(), component(atom, matrix[0]), component(atom, matrix[1]), component(atom, matrix[2]));
    }

    /** Computes a single coordinate component using an affine matrix row. */
    private static double component(Atom atom, double[] row) {
        return row[0] * atom.x() + row[1] * atom.y() + row[2] * atom.z() + row[3];
    }

    /** Validates and copies a 3x4 affine transform matrix. */
    private static double[][] matrix(double[][] value) {
        if (value == null || value.length != 3) {
            throw invalid("An affine transform requires three rows of four coefficients.");
        }
        double[][] copy = new double[3][4];
        for (int row = 0; row < 3; row++) {
            if (value[row] == null || value[row].length != 4) {
                throw invalid("An affine transform requires three rows of four coefficients.");
            }
            for (int column = 0; column < 4; column++) {
                double coefficient = value[row][column];
                if (!Double.isFinite(coefficient)) {
                    throw invalid("Affine coefficients must be finite.");
                }
                copy[row][column] = coefficient;
            }
        }
        return copy;
    }

    /** Parses and validates a 3-coordinate vector. */
    private static Vector vector(double[] value) {
        if (value == null || value.length != 3) {
            throw invalid("A vector requires three finite coordinates.");
        }
        return new Vector(value[0], value[1], value[2]);
    }

    /** Validates that lattice vectors are linearly independent. */
    private static void independent(List<Vector> vectors) {
        List<Vector> normalized = vectors.stream().map(Vector::unit).toList();
        if (normalized.size() >= 2 && normalized.get(0).cross(normalized.get(1)).length() < 1e-10) {
            throw invalid("Lattice vectors must be linearly independent.");
        }
        if (normalized.size() == 3 && Math.abs(normalized.get(0).cross(normalized.get(1)).dot(normalized.get(2))) < 1e-10) {
            throw invalid("Lattice vectors must be linearly independent.");
        }
    }

    /** Calculates linear cell index from multi-axis coordinates and counts. */
    private static int cellIndex(int[] coordinate, int[] counts) {
        int index = 0;
        for (int axis = 0; axis < counts.length; axis++) {
            index = index * counts[axis] + coordinate[axis];
        }
        return index;
    }

    /** Decodes a linear copy index into multi-axis cell coordinates. */
    private static void cellCoordinates(int index, int[] counts, int[] result) {
        for (int axis = counts.length - 1; axis >= 0; axis--) {
            result[axis] = index % counts[axis];
            index /= counts[axis];
        }
    }

    /** Validates that bond endpoint indices fall within fragment atom bounds. */
    private static void indices(int first, int second, int atoms) {
        if (first >= atoms || second >= atoms) {
            throw invalid("A bond index is outside the fragment atom array.");
        }
    }

    /** Ensures uniqueness of undirected bonds in the fragment. */
    private static void uniqueBond(Map<Long, BondType> seen, int from, int to, BondType type) {
        long key = (long) Math.min(from, to) << 32 | Math.max(from, to) & 0xFFFFFFFFL;
        BondType previous = seen.putIfAbsent(key, type);
        if (previous != null) {
            throw invalid(previous == type ? "Duplicate undirected bond." : "Conflicting bond types for the same atom pair.");
        }
    }

    /** Generates a canonical lookup key for a cell link. */
    private static String linkKey(CellLink link, boolean reverse) {
        StringBuilder result = new StringBuilder().append(reverse ? link.to() : link.from()).append(':')
                .append(reverse ? link.from() : link.to());
        for (int value : link.offset()) {
            result.append(':').append(reverse ? -(long) value : value);
        }
        return result.toString();
    }

    /** Validates atom and bond counts against size limits. */
    static void checkSize(long atoms, long bonds) {
        if (atoms < 1 || atoms > MAX_ATOMS) {
            throw invalid("A generated fragment requires 1.." + MAX_ATOMS + " atoms.");
        }
        if (bonds < 0 || bonds > MAX_BONDS) {
            throw invalid("A generated fragment may contain at most " + MAX_BONDS + " bonds.");
        }
    }

    /** Validates coordinate finiteness and numeric range. */
    private static void coordinate(double value) {
        if (!Double.isFinite(value) || !Float.isFinite((float) value)) {
            throw invalid("Coordinates must be finite and fit the renderer's floating-point range.");
        }
    }

    /** Copies metadata entries (attachments, configurations, capacities) with an index offset. */
    private void copyMetadata(String prefix, int offset, Map<String, Integer> names,
                              Map<Integer, AtomicConfiguration> nuclear, Map<Integer, Integer> capacities) {
        attachments.forEach((name, index) -> names.put(prefix + "/" + name, index + offset));
        configurations.forEach((index, value) -> nuclear.put(index + offset, value));
        randomLinkCapacities.forEach((index, value) -> capacities.put(index + offset, value));
    }

    /** Validates attachment name syntax and path segments. */
    static void attachmentName(String name) {
        if (name == null || name.length() > 1024 || !name.matches("[A-Za-z0-9_.-]+(?:\\[[0-9]+(?:,[0-9]+)*\\])?(?:/[A-Za-z0-9_.-]+(?:\\[[0-9]+(?:,[0-9]+)*\\])?)*")) {
            throw invalid("Attachment names require nonempty path segments without wildcards.");
        }
        for (String part : name.split("/")) {
            if (part.equals(".") || part.equals("..")) {
                throw invalid("Attachment names cannot contain dot path segments.");
            }
        }
    }

    /** Validates atom index bounds. */
    static void atomIndex(Integer index, int size) {
        if (index == null || index < 0 || index >= size) {
            throw invalid("An atom index is outside the fragment atom array.");
        }
    }

    /** Parses and validates metadata integer values from JSON. */
    private static int metadataInteger(com.google.gson.JsonElement value) {
        if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()) {
            throw invalid("Attachment metadata requires integer values.");
        }
        double n = value.getAsDouble();
        if (!Double.isFinite(n) || n != Math.rint(n) || n < 0 || n > Integer.MAX_VALUE) {
            throw invalid("Attachment metadata requires nonnegative integer values.");
        }
        return (int) n;
    }

    /** Helper to create an invalid argument exception. */
    private static IllegalArgumentException invalid(String message) {
        return new IllegalArgumentException(message);
    }
}