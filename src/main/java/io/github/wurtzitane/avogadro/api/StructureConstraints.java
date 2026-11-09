package io.github.wurtzitane.avogadro.api;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import io.github.wurtzitane.avogadro.molecule.MoleculeStructure;
import org.joml.Vector3d;
import org.joml.Vector3f;

/**
 * Immutable, ordered geometric constraints applied during loading, never during rendering.
 * Bond lengths translate the component on the second endpoint; dihedrals rotate the
 * component on the third endpoint around the central bond. Ring bonds cannot be cut this
 * way and are rejected. This is a deterministic rigid-fragment operation, not a force field,
 * energy minimizer, ring-closure solver, or alternative SMILES dialect.
 * <p>Distances use the same units as the input coordinates; dihedrals use signed degrees.
 * All requested targets are checked after the final operation, so incompatible constraints
 * fail instead of silently overriding an earlier target.</p>
 */
public final class StructureConstraints {
    /** Bounded recipe operations, frozen on construction. */
    private final List<Constraint> constraints;
    /** One length or torsion target; negative fourth index distinguishes a length target. */
    private record Constraint(int a, int b, int c, int d, double target) {}

    /**
     * Creates an immutable constraint sequence.
     * @param constraints validated ordered targets
     */
    private StructureConstraints(List<Constraint> constraints) { this.constraints = List.copyOf(constraints); }

    /**
     * Starts an empty constraint builder.
     * @return mutable load-time builder
     */
    public static Builder builder() { return new Builder(); }

    /** Collects at most 64 targets; atom indices are validated against geometry when applied. */
    public static final class Builder {
        /** Pending ordered targets. */
        private final List<Constraint> values = new ArrayList<>();
        /** Creates an empty builder. */
        public Builder() {}

        /**
         * Fixes the length of an existing, non-ring bond, keeping its first side stationary.
         * @param from stationary endpoint
         * @param to translated endpoint and connected component
         * @param length positive target length, at most 10000 coordinate units
         * @return this builder
         */
        public Builder bondLength(int from, int to, double length) {
            if (!Double.isFinite(length) || length <= 0 || length > 10000 || from < 0 || to < 0 || from == to)
                throw new IllegalArgumentException("Bond length requires distinct nonnegative endpoints and a finite target in (0, 10000]");
            add(new Constraint(from, to, -1, -1, length));
            return this;
        }

        /**
         * Fixes signed A-B-C-D torsion about B-C; the C/D component rotates as a rigid body.
         * Positive angles follow the right-hand rule around B-to-C, measured from the
         * perpendicular B-to-A projection to the perpendicular C-to-D projection.
         * @param a first terminal atom
         * @param b stationary central atom
         * @param c rotating central atom
         * @param d rotating terminal atom
         * @param degrees finite signed target angle, normalized to [-180, 180)
         * @return this builder
         */
        public Builder dihedral(int a, int b, int c, int d, double degrees) {
            if (a < 0 || b < 0 || c < 0 || d < 0 || Set.of(a, b, c, d).size() != 4 || !Double.isFinite(degrees))
                throw new IllegalArgumentException("Dihedral requires four distinct nonnegative atom indices and a finite angle");
            add(new Constraint(a, b, c, d, normalize(degrees)));
            return this;
        }

        /**
         * Enforces the operation bound before storing a target.
         * @param constraint next target
         */
        private void add(Constraint constraint) {
            if (values.size() == 64) throw new IllegalArgumentException("At most 64 geometry constraints are allowed");
            values.add(constraint);
        }

        /**
         * Freezes the accumulated targets independently of subsequent builder changes.
         * @return immutable constraints
         */
        public StructureConstraints build() { return new StructureConstraints(values); }
    }

    /**
     * Applies constraints while preserving atom annotations and verified stereo labels.
     * @param source complete molecule coordinates
     * @return new molecule, or the source when no targets exist
     * @throws IllegalArgumentException for cycles, degenerate torsions, conflicting targets or inverted centers
     */
    public MoleculeStructure apply(MoleculeStructure source) {
        if (constraints.isEmpty()) return source;
        List<Vector3d> positions = source.atoms().stream().map(a -> new Vector3d(a.x(), a.y(), a.z())).toList();
        List<List<Integer>> adjacent = new ArrayList<>();
        for (int i = 0; i < positions.size(); i++) adjacent.add(new ArrayList<>());
        source.bonds().forEach(b -> { adjacent.get(b.from()).add(b.to()); adjacent.get(b.to()).add(b.from()); });
        for (Constraint value : constraints) {
            if (value.d < 0) {
                Set<Integer> side = movable(adjacent, value.a, value.b);
                Vector3d delta = new Vector3d(positions.get(value.b)).sub(positions.get(value.a));
                double current = delta.length();
                if (current < 1e-8) throw new IllegalArgumentException("Cannot set the length of a zero-length bond");
                delta.mul(value.target / current - 1);
                side.forEach(atom -> positions.get(atom).add(delta));
            } else {
                requireBond(adjacent, value.a, value.b); requireBond(adjacent, value.c, value.d);
                Set<Integer> side = movable(adjacent, value.b, value.c);
                if (side.contains(value.a) || !side.contains(value.d))
                    throw new IllegalArgumentException("Dihedral endpoints must be on opposite sides of its central bond");
                Vector3d origin = positions.get(value.b);
                Vector3d axis = new Vector3d(positions.get(value.c)).sub(origin).normalize();
                double rotation = Math.toRadians(normalize(value.target - dihedral(positions, value)));
                for (int atom : side) positions.get(atom).sub(origin).rotateAxis(rotation, axis.x, axis.y, axis.z).add(origin);
            }
        }
        MoleculeStructure result = source.withPositions(positions.stream().map(v -> new Vector3f((float) v.x, (float) v.y, (float) v.z)).toList());
        List<Vector3d> rounded = result.atoms().stream().map(a -> new Vector3d(a.x(), a.y(), a.z())).toList();
        for (Constraint value : constraints) {
            double error = value.d < 0 ? Math.abs(rounded.get(value.a).distance(rounded.get(value.b)) - value.target)
                    : Math.abs(normalize(dihedral(rounded, value) - value.target));
            if (error > (value.d < 0 ? Math.max(1e-4, value.target * 1e-5) : 0.01))
                throw new IllegalArgumentException("Conflicting or numerically unstable geometry constraints");
        }
        return result;
    }

    /**
     * Applies constraints to an expanded fragment without losing attachments or nuclear metadata.
     * @param source expanded fragment
     * @return constrained copy
     */
    public StructureFragment apply(StructureFragment source) {
        var result = apply(source.toStructure("avogadro:constrained"));
        var edit = source.edit();
        for (int i = 0; i < result.atoms().size(); i++) {
            var atom = result.atoms().get(i); edit.moveAtom(i, atom.x(), atom.y(), atom.z());
        }
        return edit.fragment();
    }

    /**
     * Serializes portable targets used by Java, GroovyScript and JSON alike.
     * @return fresh JSON array
     */
    public JsonArray toJson() {
        var result = new JsonArray();
        for (Constraint value : constraints) {
            var json = new JsonObject(); var atoms = new JsonArray();
            atoms.add(value.a); atoms.add(value.b);
            if (value.d >= 0) { atoms.add(value.c); atoms.add(value.d); }
            json.addProperty("type", value.d < 0 ? "bond_length" : "dihedral");
            json.add("atoms", atoms); json.addProperty(value.d < 0 ? "length" : "degrees", value.target); result.add(json);
        }
        return result;
    }

    /**
     * Parses the strict constraint schema; unknown fields, fractional indices and excessive counts fail.
     * @param array constraint array
     * @return validated immutable targets
     */
    public static StructureConstraints fromJson(JsonArray array) {
        var builder = builder();
        for (var entry : array) {
            if (!entry.isJsonObject()) throw new IllegalArgumentException("Each constraint must be an object");
            var value = entry.getAsJsonObject();
            if (!value.has("type") || !value.get("type").isJsonPrimitive() || !value.getAsJsonPrimitive("type").isString())
                throw new IllegalArgumentException("Constraint type must be a string");
            String type = value.get("type").getAsString();
            boolean length = type.equals("bond_length");
            if (!length && !type.equals("dihedral")) throw new IllegalArgumentException("Unsupported constraint type: " + type);
            String field = length ? "length" : "degrees";
            if (!value.keySet().equals(Set.of("type", "atoms", field))) throw new IllegalArgumentException("Constraint requires exactly type, atoms and " + field);
            if (!value.get("atoms").isJsonArray() || value.getAsJsonArray("atoms").size() != (length ? 2 : 4))
                throw new IllegalArgumentException("Constraint has the wrong number of atom indices");
            int[] indices = new int[length ? 2 : 4];
            for (int i = 0; i < indices.length; i++) {
                var index = value.getAsJsonArray("atoms").get(i);
                if (!index.isJsonPrimitive() || !index.getAsJsonPrimitive().isNumber()) throw new IllegalArgumentException("Constraint indices must be integers");
                try { indices[i] = index.getAsBigDecimal().intValueExact(); }
                catch (ArithmeticException invalid) { throw new IllegalArgumentException("Constraint indices must be integers", invalid); }
            }
            if (!value.get(field).isJsonPrimitive() || !value.getAsJsonPrimitive(field).isNumber()) throw new IllegalArgumentException(field + " must be a number");
            double target = value.get(field).getAsDouble();
            if (length) builder.bondLength(indices[0], indices[1], target);
            else builder.dihedral(indices[0], indices[1], indices[2], indices[3], target);
        }
        return builder.build();
    }

    /** Validates a bonded pair and its indices. */
    private static void requireBond(List<List<Integer>> graph, int a, int b) {
        if (a < 0 || b < 0 || a >= graph.size() || b >= graph.size() || !graph.get(a).contains(b))
            throw new IllegalArgumentException("Constraint references a missing bond or atom index: " + a + "–" + b);
    }

    /** Finds the second endpoint's component after cutting a bridge; cycles are explicitly rejected. */
    private static Set<Integer> movable(List<List<Integer>> graph, int from, int to) {
        requireBond(graph, from, to);
        Set<Integer> result = new HashSet<>(); var queue = new ArrayDeque<Integer>(); queue.add(to); result.add(to);
        while (!queue.isEmpty()) {
            int atom = queue.remove();
            for (int next : graph.get(atom)) {
                if (atom == to && next == from || atom == from && next == to) continue;
                if (next == from) throw new IllegalArgumentException("Ring bonds cannot be constrained by rigid component motion");
                if (result.add(next)) queue.add(next);
            }
        }
        return result;
    }

    /** Measures signed torsion; collinear terminal projections have no defined dihedral. */
    private static double dihedral(List<Vector3d> points, Constraint value) {
        var axis = new Vector3d(points.get(value.c)).sub(points.get(value.b));
        if (axis.lengthSquared() < 1e-16) throw new IllegalArgumentException("Dihedral central bond has zero length");
        axis.normalize();
        var first = new Vector3d(points.get(value.a)).sub(points.get(value.b));
        var last = new Vector3d(points.get(value.d)).sub(points.get(value.c));
        first.sub(new Vector3d(axis).mul(first.dot(axis))); last.sub(new Vector3d(axis).mul(last.dot(axis)));
        if (first.lengthSquared() < 1e-16 || last.lengthSquared() < 1e-16) throw new IllegalArgumentException("Collinear atoms have no defined dihedral");
        return Math.toDegrees(Math.atan2(new Vector3d(first).cross(last).dot(axis), first.dot(last)));
    }

    /** Normalizes a finite angle to the half-open signed range. */
    private static double normalize(double degrees) { return ((degrees % 360 + 540) % 360) - 180; }
}
