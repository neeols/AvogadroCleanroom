package io.github.wurtzitane.avogadro.molecule;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import org.joml.Vector3f;

/**
 * Represents a complete molecular structure consisting of atoms, chemical bonds, 3D coordinates, and spatial bounds.
 */
public final class MoleculeStructure {

    /** Unique resource identifier for the structure. */
    private final String id;

    /** Chemical formula string representing the molecule. */
    private final String formula;

    /** Immutable list of constituent atoms. */
    private final List<Atom> atoms;

    /** Immutable list of chemical bonds. */
    private final List<Bond> bonds;

    /** Validated tetrahedral assignments; absence means unspecified, never an inferred R/S label. */
    private final Map<Integer, StereoConfiguration> stereochemistry;
    /** Preselected annotation labels; ordinary structures pay no per-atom label traversal cost. */
    private final List<Atom> annotatedAtoms;

    /** X coordinate of the geometric center. */
    private final float centerX;

    /** Y coordinate of the geometric center. */
    private final float centerY;

    /** Z coordinate of the geometric center. */
    private final float centerZ;

    /** Maximum bounding sphere radius enclosing all atoms. */
    private final float radius;

    /**
     * Constructs a molecule structure with a specified identifier, formula, atoms, and bonds, calculating its center
     * and radius.
     *
     * @param id unique resource identifier
     * @param formula chemical formula string
     * @param atoms list of constituent atoms
     * @param bonds list of chemical bonds
     */
    private MoleculeStructure(String id, String formula, List<Atom> atoms, List<Bond> bonds) {
        this(id, formula, atoms, bonds, Map.of());
    }

    /**
     * Freezes geometry and its already validated load-time assignments.
     * @param id structure identifier
     * @param formula display formula
     * @param atoms complete atom list
     * @param bonds explicit connectivity
     * @param stereochemistry atom indices and absolute tetrahedral assignments
     */
    private MoleculeStructure(String id, String formula, List<Atom> atoms, List<Bond> bonds,
            Map<Integer, StereoConfiguration> stereochemistry) {
        this.id = id;
        this.formula = formula;
        this.atoms = List.copyOf(atoms);
        this.bonds = List.copyOf(bonds);
        this.stereochemistry = Map.copyOf(stereochemistry);
        annotatedAtoms = this.atoms.stream().filter(atom -> !atom.annotation().label().isEmpty()
                || atom.annotation().radicalElectrons() != 0).toList();

        float cx = 0f, cy = 0f, cz = 0f;
        for (Atom atom : this.atoms) {
            cx += atom.x();
            cy += atom.y();
            cz += atom.z();
        }
        int count = this.atoms.size();
        centerX = cx / count;
        centerY = cy / count;
        centerZ = cz / count;

        float max = 0f;
        for (Atom atom : this.atoms) {
            float dx = atom.x() - centerX;
            float dy = atom.y() - centerY;
            float dz = atom.z() - centerZ;
            float distance = (float) Math.sqrt(dx * dx + dy * dy + dz * dz) + atom.element().radius();
            if (distance > max)
                max = distance;
        }
        radius = max;
    }

    /**
     * Returns the structure identifier.
     *
     * @return resource identifier string
     */
    public String id() {
        return id;
    }

    /**
     * Constructs a copied molecule structure instance with a new identifier while retaining source geometry and bounds.
     *
     * @param id new resource identifier
     * @param source source molecule structure
     */
    private MoleculeStructure(String id, MoleculeStructure source) {
        this.id = id;
        formula = source.formula;
        atoms = source.atoms;
        bonds = source.bonds;
        stereochemistry = source.stereochemistry;
        annotatedAtoms = source.annotatedAtoms;
        centerX = source.centerX;
        centerY = source.centerY;
        centerZ = source.centerZ;
        radius = source.radius;
    }

    /**
     * Geometry and bounds are immutable and can be shared by differently named definitions.
     *
     * @param requestedId requested resource identifier string
     * @return current structure if identifiers match, or a new structure copy with the requested ID
     */
    MoleculeStructure withId(String requestedId) {
        return Objects.equals(id, requestedId) ? this : new MoleculeStructure(requestedId, this);
    }

    /**
     * Returns the chemical formula string.
     *
     * @return chemical formula string
     */
    public String formula() {
        return formula;
    }

    /**
     * Returns the list of constituent atoms.
     *
     * @return unmodifiable list of {@link Atom} instances
     */
    public List<Atom> atoms() {
        return atoms;
    }

    /**
     * Returns the list of chemical bonds.
     *
     * @return unmodifiable list of {@link Bond} instances
     */
    public List<Bond> bonds() {
        return bonds;
    }

    /**
     * Returns immutable, load-time CIP assignments keyed by index in {@link #atoms()}.
     * Unspecified centers and explicit-coordinate structures have no entries. A visible
     * tetrahedral arrangement alone does not establish an authored configuration.
     * @return supplied, validated R/S assignments
     */
    public Map<Integer, StereoConfiguration> stereochemistry() {
        return stereochemistry;
    }

    /**
     * Returns the load-time selection of atoms needing an external-group or radical label.
     * @return immutable subset of the atom list
     */
    public List<Atom> annotatedAtoms() { return annotatedAtoms; }

    /**
     * Reports unresolved wildcard groups, whose mass and internal chemistry are unknown.
     * @return whether any wildcard placeholder remains
     */
    public boolean hasExternalGroups() {
        return annotatedAtoms.stream().anyMatch(atom -> !atom.annotation().label().isEmpty());
    }

    /**
     * Copies atom-local annotations without changing geometry or validated stereochemistry.
     * @param annotations replacement annotations keyed by existing atom index
     * @return immutable annotated structure
     */
    public MoleculeStructure withAtomAnnotations(Map<Integer, AtomAnnotation> annotations) {
        List<Atom> result = new ArrayList<>(atoms);
        annotations.forEach((index, annotation) -> {
            var atom = atoms.get(index);
            result.set(index, new Atom(atom.element(), atom.position(), annotation));
        });
        return new MoleculeStructure(id, formula, result, bonds, stereochemistry);
    }

    /**
     * Repositions the same graph while preserving annotations and absolute configurations.
     * A specified tetrahedron may not become flat or change handedness. This supports
     * rigid bond/torsion constraints without recomputing priorities or chemistry in a viewer.
     * @param positions exactly one finite position per existing atom
     * @return immutable repositioned structure
     * @throws IllegalArgumentException if atom counts differ or a specified center is inverted/flattened
     */
    public MoleculeStructure withPositions(List<Vector3f> positions) {
        if (positions.size() != atoms.size()) throw new IllegalArgumentException("A position is required for every atom");
        List<Atom> replacement = new ArrayList<>(atoms.size());
        for (int i = 0; i < atoms.size(); i++)
            replacement.add(new Atom(atoms.get(i).element(), positions.get(i), atoms.get(i).annotation()));
        for (int center : stereochemistry.keySet()) {
            List<Integer> neighbors = new ArrayList<>(4);
            for (Bond bond : bonds) {
                if (bond.from() == center) neighbors.add(bond.to());
                else if (bond.to() == center) neighbors.add(bond.from());
            }
            if (neighbors.size() != 4) throw new IllegalArgumentException("Specified tetrahedral center has incomplete connectivity");
            double before = tetrahedron(atoms, neighbors), after = tetrahedron(replacement, neighbors);
            if (Math.abs(after) < 1e-8 || Math.signum(before) != Math.signum(after))
                throw new IllegalArgumentException("Position change inverts or flattens specified stereocenter " + center);
        }
        return new MoleculeStructure(id, formula, replacement, bonds, stereochemistry);
    }

    /**
     * Computes the oriented determinant of four ligand positions for load-time validation.
     * @param values atom coordinates
     * @param neighbors four ligand indices in the same order for both compared structures
     * @return signed volume multiplied by six
     */
    private static double tetrahedron(List<Atom> values, List<Integer> neighbors) {
        var origin = values.get(neighbors.get(3)).position();
        var a = new org.joml.Vector3d(values.get(neighbors.get(0)).position()).sub(origin.x, origin.y, origin.z);
        var b = new org.joml.Vector3d(values.get(neighbors.get(1)).position()).sub(origin.x, origin.y, origin.z);
        var c = new org.joml.Vector3d(values.get(neighbors.get(2)).position()).sub(origin.x, origin.y, origin.z);
        return a.dot(b.cross(c));
    }

    /**
     * Attaches validated assignments without exposing an unchecked public label setter.
     * @param assignments assignments produced by the stereo loader or verified disk cache
     * @return immutable structure containing the assignments
     */
    MoleculeStructure withStereochemistry(Map<Integer, StereoConfiguration> assignments) {
        for (var entry : assignments.entrySet())
            if (entry.getKey() < 0 || entry.getKey() >= atoms.size() || entry.getValue() == null)
                throw new IllegalArgumentException("Invalid tetrahedral atom index or configuration");
        return new MoleculeStructure(id, formula, atoms, bonds, assignments);
    }

    /**
     * Computes and returns a vector representing the geometric center of the molecule.
     *
     * @return {@link Vector3f} center position
     */
    public Vector3f center() {
        return new Vector3f(centerX, centerY, centerZ);
    }

    /**
     * Returns the X coordinate of the geometric center.
     *
     * @return center X coordinate float
     */
    public float centerX() {
        return centerX;
    }

    /**
     * Returns the Y coordinate of the geometric center.
     *
     * @return center Y coordinate float
     */
    public float centerY() {
        return centerY;
    }

    /**
     * Returns the Z coordinate of the geometric center.
     *
     * @return center Z coordinate float
     */
    public float centerZ() {
        return centerZ;
    }

    /**
     * Returns the bounding radius of the molecule.
     *
     * @return bounding radius float
     */
    public float radius() {
        return radius;
    }

    /** Represents an individual atom within a molecule structure, holding its element type and 3D position. */
    public record Atom(AtomElement element, Vector3f position, AtomAnnotation annotation) {

        /**
         * Creates an ordinary atom, retaining the existing two-argument API.
         * @param element rendering element
         * @param position coordinates copied by the canonical constructor
         */
        public Atom(AtomElement element, Vector3f position) {
            this(element, position, AtomAnnotation.NONE);
        }

        /**
         * Canonical constructor validating element and finite 3D coordinates.
         *
         * @param element atom element definition
         * @param position 3D vector position
         * @throws NullPointerException if element or position is null
         * @throws IllegalArgumentException if any position coordinate is non-finite
         */
        public Atom {
            annotation = Objects.requireNonNull(annotation, "annotation").forElement(element.symbol());
            Objects.requireNonNull(element, "element");
            Objects.requireNonNull(position, "position");
            if (!Float.isFinite(position.x) || !Float.isFinite(position.y) || !Float.isFinite(position.z))
                throw new IllegalArgumentException("Atom coordinates must be finite");
            position = new Vector3f(position);
        }

        /**
         * Returns a cloned position vector for this atom.
         *
         * @return cloned {@link Vector3f} position
         */
        @Override
        public Vector3f position() {
            return new Vector3f(position);
        }

        /**
         * Returns the X coordinate of the atom.
         *
         * @return X coordinate float
         */
        public float x() {
            return position.x;
        }

        /**
         * Returns the Y coordinate of the atom.
         *
         * @return Y coordinate float
         */
        public float y() {
            return position.y;
        }

        /**
         * Returns the Z coordinate of the atom.
         *
         * @return Z coordinate float
         */
        public float z() {
            return position.z;
        }
    }

    /** Represents a chemical bond connecting two atom indices with a specific bond type. */
    public record Bond(int from, int to, BondType type) {
    }

    /**
     * Creates a new builder for constructing a molecule structure.
     *
     * @param id structure resource identifier
     * @param formula chemical formula string
     * @return constructed {@link Builder}
     */
    public static Builder builder(String id, String formula) {
        return new Builder(id, formula);
    }

    /**
     * Capacity hints used by bounded geometry readers; they do not add atoms or bonds.
     *
     * @param id structure resource identifier
     * @param formula chemical formula string
     * @param atomCapacity initial atom capacity hint
     * @param bondCapacity initial bond capacity hint
     * @return constructed {@link Builder}
     */
    public static Builder builder(String id, String formula, int atomCapacity, int bondCapacity) {
        return new Builder(id, formula, atomCapacity, bondCapacity);
    }

    /** Builder utility class for constructing immutable {@link MoleculeStructure} instances. */
    public static final class Builder {

        /** Maximum retained grid atoms limit. */
        private static final int MAX_RETAINED_GRID_ATOMS = 32768;

        /** Thread-local cell grid cache for efficient spatial auto-bonding. */
        private static final ThreadLocal<CellGrid> BOND_GRID = ThreadLocal.withInitial(CellGrid::new);

        /** Structure identifier string. */
        private final String id;

        /** Chemical formula string. */
        private final String formula;

        /** Mutable list of atoms being built. */
        private final List<Atom> atoms;

        /** Mutable list of bonds being built. */
        private final List<Bond> bonds;

        /**
         * Constructs a builder with default capacity hints.
         *
         * @param id structure resource identifier
         * @param formula chemical formula string
         */
        private Builder(String id, String formula) {
            this(id, formula, 10, 10);
        }

        /**
         * Constructs a builder with explicit atom and bond capacity hints.
         *
         * @param id structure resource identifier
         * @param formula chemical formula string
         * @param atomCapacity atom capacity hint
         * @param bondCapacity bond capacity hint
         * @throws IllegalArgumentException if capacities are negative
         */
        private Builder(String id, String formula, int atomCapacity, int bondCapacity) {
            if (atomCapacity < 0 || bondCapacity < 0)
                throw new IllegalArgumentException("Negative geometry capacity");
            this.id = id;
            this.formula = formula;
            atoms = new ArrayList<>(atomCapacity);
            bonds = new ArrayList<>(bondCapacity);
        }

        /**
         * Adds an atom to the structure builder.
         *
         * @param element atom element definition
         * @param x X coordinate
         * @param y Y coordinate
         * @param z Z coordinate
         * @return integer index of the added atom
         */
        public int addAtom(AtomElement element, float x, float y, float z) {
            atoms.add(new Atom(element, new Vector3f(x, y, z)));
            return atoms.size() - 1;
        }

        /**
         * Adds an atom with explicit external-group or radical metadata.
         * @param element element or wildcard rendering identity
         * @param x horizontal coordinate
         * @param y vertical coordinate
         * @param z depth coordinate
         * @param annotation validated atom-local metadata
         * @return zero-based atom index
         */
        public int addAtom(AtomElement element, float x, float y, float z, AtomAnnotation annotation) {
            atoms.add(new Atom(element, new Vector3f(x, y, z), annotation));
            return atoms.size() - 1;
        }

        /**
         * Adds a single chemical bond between two atom indices.
         *
         * @param from source atom index 5 * @param to target atom index
         * @return this {@link Builder}
         */
        public Builder bond(int from, int to) {
            bonds.add(new Bond(from, to, BondType.SINGLE));
            return this;
        }

        /**
         * Adds a chemical bond with a specified bond type between two atom indices.
         *
         * @param from source atom index
         * @param to target atom index
         * @param type bond type instance
         * @return this {@link Builder}
         */
        public Builder bond(int from, int to, BondType type) {
            bonds.add(new Bond(from, to, type));
            return this;
        }

        /**
         * Automatically generates single bonds between close atom pairs based on van der Waals radii.
         *
         * @return this {@link Builder}
         */
        public Builder autoBond() {
            if (atoms.size() >= 128 && autoBondGrid())
                return this;
            for (int i = 0; i < atoms.size(); i++) {
                for (int j = i + 1; j < atoms.size(); j++) {
                    autoBondPair(i, j);
                }
            }
            return this;
        }

        /** Spatial cell grid container for accelerated neighbor searching during auto-bonding. */
        private static final class CellGrid {
            long[] x = new long[0], y = new long[0], z = new long[0];
            int[] heads = new int[0], next = new int[0], candidates = new int[0];
            int mask;
            boolean busy;

            /**
             * Prepares and resizes grid arrays based on atom counts.
             *
             * @param atoms total atom count
             */
            void prepare(int atoms) {
                int capacity = 256;
                while (capacity < atoms * 2)
                    capacity <<= 1;
                if (heads.length < capacity) {
                    x = new long[capacity];
                    y = new long[capacity];
                    z = new long[capacity];
                    heads = new int[capacity];
                }
                if (next.length < atoms)
                    next = new int[capacity / 2];
                if (candidates.length < atoms)
                    candidates = new int[capacity / 2];
                mask = capacity - 1;
                // Only the active table range participates after a larger prior molecule.
                Arrays.fill(heads, 0, capacity, -1);
            }

            /**
             * Computes a hash slot for spatial cell coordinates.
             *
             * @param cellX cell X coordinate
             * @param cellY cell Y coordinate
             * @param cellZ cell Z coordinate
             * @return table slot integer
             */
            int slot(long cellX, long cellY, long cellZ) {
                long hash = cellX * 0x9e3779b97f4a7c15L ^ cellY * 0xc2b2ae3d27d4eb4fL ^ cellZ * 0x165667b19e3779f9L;
                hash ^= hash >>> 33;
                hash *= 0xff51afd7ed558ccdL;
                hash ^= hash >>> 33;
                int slot = (int) hash & mask;
                while (heads[slot] != -1 && (x[slot] != cellX || y[slot] != cellY || z[slot] != cellZ))
                    slot = (slot + 1) & mask;
                return slot;
            }

            /**
             * Adds an atom index into the spatial cell grid.
             *
             * 5 * @param atom atom index
             *
             * @param cellX cell X coordinate
             * @param cellY cell Y coordinate
             * @param cellZ cell Z coordinate
             */
            void add(int atom, long cellX, long cellY, long cellZ) {
                int slot = slot(cellX, cellY, cellZ);
                x[slot] = cellX;
                y[slot] = cellY;
                z[slot] = cellZ;
                next[atom] = heads[slot];
                heads[slot] = atom;
            }

            /**
             * Returns the head atom index for a spatial cell.
             *
             * @param cellX cell X coordinate
             * @param cellY cell Y coordinate
             * @param cellZ cell Z coordinate
             * @return head atom index, or {@code -1}
             */
            int head(long cellX, long cellY, long cellZ) {
                return heads[slot(cellX, cellY, cellZ)];
            }
        }

        /**
         * Acquires a leased cell grid instance from thread-local storage.
         *
         * @param atoms total atom count
         * @return prepared {@link CellGrid}
         */
        private static CellGrid acquireGrid(int atoms) {
            CellGrid cached = BOND_GRID.get();
            // Nested work receives its own lease; oversized tables are never retained.
            CellGrid grid = !cached.busy && atoms <= MAX_RETAINED_GRID_ATOMS ? cached : new CellGrid();
            grid.busy = true;
            try {
                grid.prepare(atoms);
            } catch (RuntimeException | Error failure) {
                grid.busy = false;
                throw failure;
            }
            return grid;
        }

        /**
         * Executes spatial grid-accelerated auto-bonding.
         *
         * @return {@code true} if successful, {@code false} if fallback required
         */
        private boolean autoBondGrid() {
            float radius = 0f;
            for (Atom atom : atoms)
                radius = Math.max(radius, atom.element().radius());
            // Round outward from the exact float threshold used by autoBondPair.
            double width = Math.nextUp((radius + radius) * 1.3f);
            if (!(width >= Math.sqrt(Float.MIN_NORMAL)) || !Double.isFinite(width) || atoms.size() > (1 << 26))
                return false;
            CellGrid cells = acquireGrid(atoms.size());
            try {
                for (int i = 0; i < atoms.size(); i++) {
                    Atom atom = atoms.get(i);
                    double x = Math.floor(atom.x() / width), y = Math.floor(atom.y() / width),
                            z = Math.floor(atom.z() / width);
                    // Very large valid coordinates retain the conservative pairwise fallback.
                    if (Math.abs(x) >= 0x1p62 || Math.abs(y) >= 0x1p62 || Math.abs(z) >= 0x1p62)
                        return false;
                    cells.add(i, (long) x, (long) y, (long) z);
                }
                int[] candidates = cells.candidates;
                for (int i = 0; i < atoms.size(); i++) {
                    Atom atom = atoms.get(i);
                    long x = (long) Math.floor(atom.x() / width), y = (long) Math.floor(atom.y() / width),
                            z = (long) Math.floor(atom.z() / width);
                    int count = 0;
                    for (int dx = -1; dx <= 1; dx++)
                        for (int dy = -1; dy <= 1; dy++)
                            for (int dz = -1; dz <= 1; dz++) {
                                for (int j = cells.head(x + dx, y + dy, z + dz); j != -1; j = cells.next[j])
                                    if (j > i)
                                        candidates[count++] = j;
                            }
                    // Spatial traversal must not change externally visible bond/triangle order.
                    Arrays.sort(candidates, 0, count);
                    for (int next = 0; next < count; next++)
                        autoBondPair(i, candidates[next]);
                }
                return true;
            } finally {
                cells.busy = false;
            }
        }

        /**
         * Evaluates a pair of atoms and adds a single bond if their distance is within bonding thresholds.
         *
         * @param i first atom index
         * @param j second atom index
         */
        private void autoBondPair(int i, int j) {
            Atom first = atoms.get(i), second = atoms.get(j);
            float ri = first.element().radius(), rj = second.element().radius();
            float dx = first.x() - second.x(), dy = first.y() - second.y(), dz = first.z() - second.z();
            float dist = (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
            if (dist < (ri + rj) * 1.3f)
                bonds.add(new Bond(i, j, BondType.SINGLE));
        }

        /**
         * Builds and validates the immutable molecule structure.
         *
         * @return constructed {@link MoleculeStructure}
         * @throws IllegalArgumentException if atom list is empty or bond indices are invalid
         */
        public MoleculeStructure build() {
            if (atoms.isEmpty())
                throw new IllegalArgumentException("A molecule must contain at least one atom");
            for (Bond bond : bonds) {
                if (bond.from < 0 || bond.to < 0 || bond.from >= atoms.size() || bond.to >= atoms.size())
                    throw new IllegalArgumentException("Bond index is outside the atom array");
                if (bond.from == bond.to)
                    throw new IllegalArgumentException("A bond cannot connect an atom to itself");
                Objects.requireNonNull(bond.type, "bond type");
            }
            return new MoleculeStructure(id, formula, atoms, bonds);
        }
    }
}
