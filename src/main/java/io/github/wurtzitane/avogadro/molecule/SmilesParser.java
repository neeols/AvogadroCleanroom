package io.github.wurtzitane.avogadro.molecule;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Locale;
import java.util.Set;

/** SMILES string parser and force-directed 2D molecular geometry layout engine. */
public final class SmilesParser {

    /** Private constructor to prevent instantiation of utility class. */
    private SmilesParser() {
    }

    /** Maximum layout iteration steps. */
    private static final int ITERATIONS = 500;

    /** Set of supported two-letter chemical element symbols. */
    private static final Set<String> TWO_LETTER = Set.of(
            "He", "Li", "Be", "Ne", "Na", "Mg", "Al", "Si", "Cl", "Ar",
            "Ca", "Sc", "Ti", "Cr", "Mn", "Fe", "Co", "Ni", "Cu", "Zn",
            "Ga", "Ge", "As", "Se", "Br", "Kr", "Rb", "Sr", "Zr", "Nb",
            "Mo", "Tc", "Ru", "Rh", "Pd", "Ag", "Cd", "In", "Sn", "Sb",
            "Te", "Xe", "Cs", "Ba", "La", "Ce", "Pr", "Nd", "Pm", "Sm",
            "Eu", "Gd", "Tb", "Dy", "Ho", "Er", "Tm", "Yb", "Lu", "Hf",
            "Ta", "Re", "Os", "Ir", "Pt", "Au", "Hg", "Tl", "Pb", "Bi",
            "Po", "At", "Rn", "Fr", "Ra", "Ac", "Th", "Pa", "Np", "Pu",
            "Am", "Cm", "Bk", "Cf", "Es", "Fm", "Md", "No", "Lr", "Rf",
            "Db", "Sg", "Bh", "Hs", "Mt", "Ds", "Rg", "Cn", "Nh", "Fl",
            "Mc", "Lv", "Ts", "Og"
    );

    /** Default bond length constant. */
    private static final float BOND = 1.0f;

    /** Spring constant for bond force calculations. */
    private static final float SPRING = 0.12f;

    /** Repulsion constant for non-bonded atom force calculations. */
    private static final float REPULSION = 0.10f;

    /** Bond length for implicit hydrogen atoms. */
    private static final float H_BOND = 0.9f;

    /** Maximum retained atom count and bond entry limits for adjacency workspaces. */
    private static final int MAX_RETAINED_ATOMS = 65536, MAX_RETAINED_BOND_ENTRIES = 262144;

    /** Thread-local adjacency workspace for accelerated graph traversals. */
    private static final ThreadLocal<AdjacencyWorkspace> ADJACENCY = ThreadLocal.withInitial(AdjacencyWorkspace::new);

    /** Thread-local layout workspace for force-directed positioning calculations. */
    private static final ThreadLocal<LayoutWorkspace> LAYOUT = ThreadLocal.withInitial(LayoutWorkspace::new);

    /**
     * Parses a SMILES string into a molecule structure, utilizing the geometry cache.
     *
     * @param id     structure resource identifier string
     * @param smiles SMILES chemical structure string
     * @return constructed {@link MoleculeStructure} instance
     */
    public static MoleculeStructure parse(String id, String smiles) {
        MoleculeTasks.checkCancelled();
        Locale locale = Locale.getDefault();
        return SmilesGeometryCache.get(id, smiles, locale, () -> parseUncached(id, smiles, locale));
    }

    /**
     * Parses a SMILES string without checking or updating the geometry cache.
     *
     * @param id     structure resource identifier string
     * @param smiles SMILES chemical structure string
     * @param locale locale instance
     * @return constructed {@link MoleculeStructure} instance
     */
    static MoleculeStructure parseUncached(String id, String smiles, Locale locale) {
        Graph g = build(id, smiles, locale);
        try {
            layout(g);
            return structure(g);
        } finally { release(g); }
    }

    /** Represents an atom parsed from a SMILES string. */
    private static final class Atom {
        String element;
        boolean aromatic;
        boolean bracket;
        int hydrogens;
        float x, y;
    }

    /** Represents a chemical bond between two atom indices in a graph. */
    private static final class Bond {
        int a, b, order;
    }

    /** Represents a molecular graph containing atoms, bonds, and optional adjacency workspaces. */
    private static final class Graph {
        String id;
        final List<Atom> atoms = new ArrayList<>();
        final List<Bond> bonds = new ArrayList<>();
        AdjacencyWorkspace adjacency;
    }

    /** Primitive scratch only: no graph, atom, bond or molecule references survive a lease. */
    private static final class AdjacencyWorkspace {
        int[] starts = new int[0], cursor = new int[0], bondIndices = new int[0];
        boolean busy;

        /**
         * Indexes graph connectivity for fast neighbor lookups.
         *
         * @param graph graph instance
         */
        void index(Graph graph) {
            int atoms = graph.atoms.size(), entries = graph.bonds.size() * 2;
            if (starts.length < atoms + 1) starts = new int[capacity(atoms + 1)];
            if (cursor.length < atoms) cursor = new int[capacity(atoms)];
            if (bondIndices.length < entries) bondIndices = new int[capacity(entries)];
            java.util.Arrays.fill(cursor, 0, atoms, 0);
            for (Bond bond : graph.bonds) { cursor[bond.a]++; cursor[bond.b]++; }
            starts[0] = 0;
            for (int i = 0; i < atoms; i++) starts[i + 1] = starts[i] + cursor[i];
            System.arraycopy(starts, 0, cursor, 0, atoms);
            for (int i = 0; i < graph.bonds.size(); i++) {
                Bond bond = graph.bonds.get(i);
                bondIndices[cursor[bond.a]++] = i;
                bondIndices[cursor[bond.b]++] = i;
            }
        }

        /**
         * Computes array capacity.
         *
         * @param needed needed capacity integer
         * @return allocated capacity integer
         */
        private static int capacity(int needed) {
            if (needed > (1 << 29)) return needed;
            int result = 64;
            while (result < needed) result <<= 1;
            return result;
        }
    }

    /**
     * Acquires an adjacency workspace from thread-local storage.
     *
     * @param graph graph instance
     * @return prepared {@link AdjacencyWorkspace} instance
     */
    private static AdjacencyWorkspace acquire(Graph graph) {
        AdjacencyWorkspace cached = ADJACENCY.get();
        AdjacencyWorkspace workspace = !cached.busy && graph.atoms.size() < MAX_RETAINED_ATOMS
                && graph.bonds.size() <= MAX_RETAINED_BOND_ENTRIES / 2 ? cached : new AdjacencyWorkspace();
        workspace.busy = true;
        try { workspace.index(graph); }
        catch (RuntimeException | Error failure) { workspace.busy = false; throw failure; }
        return workspace;
    }

    /**
     * Releases an adjacency workspace back to thread-local storage.
     *
     * @param graph graph instance
     */
    private static void release(Graph graph) {
        AdjacencyWorkspace workspace = graph.adjacency;
        graph.adjacency = null;
        if (workspace != null) workspace.busy = false;
    }

    /**
     * Adds a bond to a molecular graph.
     *
     * @param g     graph instance
     * @param a     first atom index
     * @param b     second atom index
     * @param order bond order integer
     */
    private static void addBond(Graph g, int a, int b, int order) {
        if (a < 0 || b < 0 || a == b) return;
        Bond bond = new Bond();
        bond.a = a;
        bond.b = b;
        bond.order = Math.max(1, Math.min(3, order));
        g.bonds.add(bond);
    }

    /**
     * Builds a molecular graph from a SMILES string using default locale.
     *
     * @param id structure resource identifier string
     * @param s  SMILES string
     * @return constructed {@link Graph} instance
     */
    private static Graph build(String id, String s) { return build(id, s, Locale.getDefault()); }

    /**
     * Builds a molecular graph from a SMILES string and locale.
     *
     * @param id     structure resource identifier string
     * @param s      SMILES string
     * @param locale locale instance
     * @return constructed {@link Graph} instance
     */
    private static Graph build(String id, String s, Locale locale) {
        Graph g = new Graph();
        g.id = id;
        int prev = -1;
        int pending = 0;
        int i = 0;
        int n = s.length();
        ArrayDeque<Integer> branch = new ArrayDeque<>();
        Map<Integer, int[]> rings = new HashMap<>();
        int parseSteps = 0;

        while (i < n) {
            if ((parseSteps++ & 255) == 0) MoleculeTasks.checkCancelled();
            char c = s.charAt(i);
            if (c == ' ' || c == '\t' || c == '\n' || c == '\r') {
                i++;
                continue;
            }
            if (c == '(') {
                branch.push(prev);
                i++;
                continue;
            }
            if (c == ')') {
                prev = branch.isEmpty() ? -1 : branch.pop();
                i++;
                continue;
            }
            if (c == '.') {
                prev = -1;
                i++;
                continue;
            }
            if (c == '@' || c == '>') {
                i++;
                continue;
            }
            if (c == '-' || c == '=' || c == '#' || c == ':' || c == '/' || c == '\\') {
                pending = switch (c) {
                    case '=' -> 2;
                    case '#' -> 3;
                    default -> 1;
                };
                i++;
                continue;
            }
            if (c == '%') {
                int ring = Integer.parseInt(s.substring(i + 1, i + 3));
                i += 3;
                prev = closeRing(g, rings, ring, prev, pending);
                pending = 0;
                continue;
            }
            if (c >= '0' && c <= '9') {
                int ring = c - '0';
                i++;
                prev = closeRing(g, rings, ring, prev, pending);
                pending = 0;
                continue;
            }

            Atom atom = new Atom();
            if (c == '[') {
                int end = s.indexOf(']', i);
                if (end < 0) end = n - 1;
                parseBracket(s.substring(i + 1, end), atom, locale);
                i = end + 1;
            } else {
                if (i + 1 < n && Character.isUpperCase(c) && Character.isLowerCase(s.charAt(i + 1))
                        && TWO_LETTER.contains(s.substring(i, i + 2))) {
                    atom.element = s.substring(i, i + 2);
                    i += 2;
                } else {
                    atom.element = String.valueOf(c);
                    i++;
                }
                atom.aromatic = Character.isLowerCase(c);
                if (atom.aromatic) {
                    atom.element = atom.element.toUpperCase(locale).substring(0, 1);
                }
            }

            int idx = g.atoms.size();
            g.atoms.add(atom);
            if (prev >= 0) {
                boolean aromatic = g.atoms.get(prev).aromatic && atom.aromatic;
                addBond(g, prev, idx, pending != 0 ? pending : (aromatic ? 1 : 1));
            }
            prev = idx;
            pending = 0;
        }

        // Tiny molecules are cheaper to scan directly than to prepare an index.
        if (g.atoms.size() >= 64) g.adjacency = acquire(g);
        try {
            if (g.adjacency == null) {
                for (int a = 0; a < g.atoms.size(); a++) fillImplicitHydrogens(g, a);
            } else {
                for (int a = 0; a < g.atoms.size(); a++) fillIndexedHydrogens(g, a);
            }
            return g;
        } catch (RuntimeException | Error failure) { release(g); throw failure; }
    }

    /**
     * Closes a ring closure bond in the molecular graph.
     *
     * @param g       graph instance
     * @param rings   active ring map
     * @param ring    ring identifier integer
     * @param prev    previous atom index
     * @param pending pending bond order
     * @return previous atom index
     */
    private static int closeRing(Graph g, Map<Integer, int[]> rings, int ring, int prev, int pending) {
        if (prev < 0) return prev;
        int[] open = rings.remove(ring);
        if (open == null) {
            rings.put(ring, new int[]{prev, pending});
            return prev;
        }
        addBond(g, open[0], prev, pending != 0 ? pending : (open[1] != 0 ? open[1] : 1));
        return prev;
    }

    /**
     * Parses bracketed atom expressions in SMILES strings.
     *
     * @param body   bracket body string
     * @param atom   target atom instance
     * @param locale locale instance
     */
    private static void parseBracket(String body, Atom atom, Locale locale) {
        atom.bracket = true;
        int i = 0;
        if (i < body.length() && Character.isLowerCase(body.charAt(i))) {
            atom.aromatic = true;
            atom.element = body.substring(i, i + 1).toUpperCase(locale);
            i++;
        } else if (i < body.length()) {
            int j = i + 1;
            if (j < body.length() && Character.isLowerCase(body.charAt(j))) j++;
            atom.element = body.substring(i, j);
            i = j;
        }
        while (i < body.length()) {
            char c = body.charAt(i);
            if (c == 'H') {
                i++;
                int count = 0;
                while (i < body.length() && Character.isDigit(body.charAt(i))) {
                    count = count * 10 + (body.charAt(i) - '0');
                    i++;
                }
                atom.hydrogens = Math.max(1, count);
            } else {
                i++;
            }
        }
    }

    /**
     * Fills implicit hydrogens for an atom using sequential graph scans.
     *
     * @param g   graph instance
     * @param idx atom index
     */
    private static void fillImplicitHydrogens(Graph g, int idx) {
        Atom a = g.atoms.get(idx);
        if (a.bracket) return;
        int valence = valence(a.element, a.aromatic);
        if (valence <= 0) return;
        int used = 0;
        for (int bondIndex = 0; bondIndex < g.bonds.size(); bondIndex++) {
            Bond b = g.bonds.get(bondIndex);
            if (b.a == idx || b.b == idx) used += b.order;
        }
        a.hydrogens = Math.max(0, valence - used);
    }

    /**
     * Fills implicit hydrogens for an atom using accelerated adjacency indexes.
     *
     * @param g   graph instance
     * @param idx atom index
     */
    private static void fillIndexedHydrogens(Graph g, int idx) {
        Atom a = g.atoms.get(idx);
        if (a.bracket) return;
        int valence = valence(a.element, a.aromatic);
        if (valence <= 0) return;
        int used = 0;
        for (int next = g.adjacency.starts[idx]; next < g.adjacency.starts[idx + 1]; next++)
            used += g.bonds.get(g.adjacency.bondIndices[next]).order;
        a.hydrogens = Math.max(0, valence - used);
    }

    /**
     * Computes standard chemical valence for an element symbol and aromatic status.
     *
     * @param element  element symbol string
     * @param aromatic {@code true} if aromatic, {@code false} otherwise
     * @return valence integer
     */
    private static int valence(String element, boolean aromatic) {
        if (aromatic) {
            return switch (element) {
                case "C", "N", "P", "B" -> 3;
                case "O", "S" -> 2;
                default -> 0;
            };
        }
        return switch (element) {
            case "B" -> 3;
            case "C", "Si" -> 4;
            case "N", "P" -> 3;
            case "O", "S" -> 2;
            case "F", "Cl", "Br", "I" -> 1;
            default -> 0;
        };
    }

    /**
     * Performs force-directed 2D layout calculation on a molecular graph.
     *
     * @param g graph instance
     */
    private static void layout(Graph g) {
        LayoutWorkspace workspace = LAYOUT.get();
        if (workspace.busy || g.atoms.size() > MAX_RETAINED_ATOMS || g.bonds.size() > MAX_RETAINED_BOND_ENTRIES / 2)
            workspace = new LayoutWorkspace();
        workspace.busy = true;
        try { layout(g, workspace); }
        finally { workspace.busy = false; }
    }

    /** Workspace container holding layout coordinate and force arrays. */
    private static final class LayoutWorkspace {
        float[] x = new float[0], y = new float[0], fx = new float[0], fy = new float[0];
        int[] bondA = new int[0], bondB = new int[0];
        boolean busy;

        /**
         * Prepares workspace arrays based on atom and bond counts.
         *
         5 * @param atoms atom count integer
         * @param bonds bond count integer
         */
        void prepare(int atoms, int bonds) {
            if (x.length < atoms) { x = new float[atoms]; y = new float[atoms]; fx = new float[atoms]; fy = new float[atoms]; }
            if (bondA.length < bonds) { bondA = new int[bonds]; bondB = new int[bonds]; }
        }
    }

    /**
     * Executes force-directed iterations to compute 2D positions for atoms.
     *
     * @param g         graph instance
     * @param workspace layout workspace instance
     */
    private static void layout(Graph g, LayoutWorkspace workspace) {
        int n = g.atoms.size();
        if (n == 0) return;
        int bondCount = g.bonds.size();
        workspace.prepare(n, bondCount);
        float[] x = workspace.x, y = workspace.y, fx = workspace.fx, fy = workspace.fy;
        int[] bondA = workspace.bondA, bondB = workspace.bondB;
        for (int i = 0; i < bondCount; i++) { bondA[i] = g.bonds.get(i).a; bondB[i] = g.bonds.get(i).b; }
        for (int i = 0; i < n; i++) {
            double angle = 2.0 * Math.PI * i / Math.max(1, n);
            double radius = 0.6 * Math.sqrt(n);
            x[i] = (float) (Math.cos(angle) * radius);
            y[i] = (float) (Math.sin(angle) * radius);
        }

        for (int iter = 0; iter < ITERATIONS; iter++) {
            if (Thread.currentThread().isInterrupted())
                throw new java.util.concurrent.CancellationException("Molecule layout cancelled");
            java.util.Arrays.fill(fx, 0, n, 0f);
            java.util.Arrays.fill(fy, 0, n, 0f);

            for (int bond = 0; bond < bondCount; bond++) {
                int a = bondA[bond], b = bondB[bond];
                float dx = x[a] - x[b], dy = y[a] - y[b];
                float d = (float) Math.sqrt(dx * dx + dy * dy) + 1e-4f;
                float f = (d - BOND) * SPRING;
                float ux = dx / d, uy = dy / d;
                fx[a] -= ux * f;
                fy[a] -= uy * f;
                fx[b] += ux * f;
                fy[b] += uy * f;
            }

            int pairs = 0;
            for (int i = 0; i < n; i++) {
                for (int j = i + 1; j < n; j++) {
                    if ((pairs++ & 4095) == 0) MoleculeTasks.checkCancelled();
                    float dx = x[i] - x[j], dy = y[i] - y[j];
                    float d2 = dx * dx + dy * dy + 1e-3f;
                    float d = (float) Math.sqrt(d2);
                    float f = REPULSION * BOND * BOND / d2;
                    float ux = dx / d, uy = dy / d;
                    fx[i] += ux * f;
                    fy[i] += uy * f;
                    fx[j] -= ux * f;
                    fy[j] -= uy * f;
                }
            }

            float cooling = (1f - (iter / (float) ITERATIONS)) * 0.7f;
            for (int i = 0; i < n; i++) {
                x[i] += clamp(fx[i], 0.2f) * cooling;
                y[i] += clamp(fy[i], 0.2f) * cooling;
            }
        }

        float cx = 0f, cy = 0f;
        for (int i = 0; i < n; i++) {
            cx += x[i];
            cy += y[i];
        }
        cx /= n;
        cy /= n;
        for (int i = 0; i < n; i++) {
            g.atoms.get(i).x = x[i] - cx;
            g.atoms.get(i).y = y[i] - cy;
        }
    }

    /**
     * Clamps a value within specified limits.
     *
     * @param v   value float
     * @param lim limit float magnitude
     * @return clamped float value
     */
    private static float clamp(float v, float lim) {
        return Math.max(-lim, Math.min(lim, v));
    }

    /**
     * Converts a layout graph into a compiled {@link MoleculeStructure}.
     *
     * @param g graph instance
     * @return constructed {@link MoleculeStructure} instance
     */
    private static MoleculeStructure structure(Graph g) {
        try {
            MoleculeStructure.Builder b = MoleculeStructure.builder(g.id, "");
            int[] heavyIndex = new int[g.atoms.size()];
            for (int i = 0; i < g.atoms.size(); i++) {
                if ((i & 1023) == 0) MoleculeTasks.checkCancelled();
                Atom a = g.atoms.get(i);
                heavyIndex[i] = b.addAtom(AtomElement.bySymbol(a.element), a.x, a.y, 0f);
            }
            for (int bondIndex = 0; bondIndex < g.bonds.size(); bondIndex++) {
                if ((bondIndex & 1023) == 0) MoleculeTasks.checkCancelled();
                Bond bond = g.bonds.get(bondIndex);
                b.bond(heavyIndex[bond.a], heavyIndex[bond.b], bondType(bond.order));
            }
            for (int i = 0; i < g.atoms.size(); i++) {
                if ((i & 1023) == 0) MoleculeTasks.checkCancelled();
                Atom a = g.atoms.get(i);
                if (a.hydrogens <= 0) continue;
                float base = g.adjacency == null ? neighborAngle(g, i) : indexedNeighborAngle(g, i);
                for (int k = 0; k < a.hydrogens; k++) {
                    if ((k & 1023) == 0) MoleculeTasks.checkCancelled();
                    float spread = (float) Math.toRadians(60.0 * (k - (a.hydrogens - 1) / 2.0));
                    float angle = base + spread;
                    float hx = a.x + (float) Math.cos(angle) * H_BOND;
                    float hy = a.y + (float) Math.sin(angle) * H_BOND;
                    int hIndex = b.addAtom(AtomElement.HYDROGEN, hx, hy, 0f);
                    b.bond(heavyIndex[i], hIndex, BondType.SINGLE);
                }
            }
            return b.build();
        } finally { release(g); }
    }

    /**
     * Computes the outward angle for placing implicit hydrogens based on neighboring atoms.
     *
     5 * @param g   graph instance
     * @param idx atom index integer
     * @return angle in radians
     */
    private static float neighborAngle(Graph g, int idx) {
        Atom a = g.atoms.get(idx);
        float sx = 0f, sy = 0f;
        boolean any = false;
        for (int bondIndex = 0; bondIndex < g.bonds.size(); bondIndex++) {
            Bond bond = g.bonds.get(bondIndex);
            int other = bond.a == idx ? bond.b : bond.b == idx ? bond.a : -1;
            if (other < 0) continue;
            Atom o = g.atoms.get(other);
            float dx = o.x - a.x, dy = o.y - a.y;
            float len = (float) Math.sqrt(dx * dx + dy * dy) + 1e-4f;
            sx += dx / len;
            sy += dy / len;
            any = true;
        }
        if (!any || (Math.abs(sx) < 1e-3f && Math.abs(sy) < 1e-3f)) {
            return (float) (Math.PI / 2.0);
        }
        return (float) Math.atan2(-sy, -sx);
    }

    /**
     * Computes the outward angle for placing implicit hydrogens using accelerated adjacency indexes.
     *
     * @param g   graph instance
     * @param idx atom index integer
     * @return angle in radians
     */
    private static float indexedNeighborAngle(Graph g, int idx) {
        Atom a = g.atoms.get(idx);
        float sx = 0f, sy = 0f;
        boolean any = false;
        for (int next = g.adjacency.starts[idx]; next < g.adjacency.starts[idx + 1]; next++) {
            Bond bond = g.bonds.get(g.adjacency.bondIndices[next]);
            int other = bond.a == idx ? bond.b : bond.a;
            Atom o = g.atoms.get(other);
            float dx = o.x - a.x, dy = o.y - a.y;
            float len = (float) Math.sqrt(dx * dx + dy * dy) + 1e-4f;
            sx += dx / len;
            sy += dy / len;
            any = true;
        }
        if (!any || (Math.abs(sx) < 1e-3f && Math.abs(sy) < 1e-3f)) {
            return (float) (Math.PI / 2.0);
        }
        return (float) Math.atan2(-sy, -sx);
    }

    /**
     * Resolves bond order integers into corresponding {@link BondType} enums.
     *
     * @param order bond order integer
     * @return resolved {@link BondType} instance
     */
    private static BondType bondType(int order) {
        return switch (order) {
            case 2 -> BondType.DOUBLE;
            case 3 -> BondType.TRIPLE;
            default -> BondType.SINGLE;
        };
    }
}