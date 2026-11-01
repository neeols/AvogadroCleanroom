package io.github.wurtzitane.avogadro.molecule;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import io.github.wurtzitane.avogadro.api.StructureBuilder;
import io.github.wurtzitane.avogadro.api.StructureFragment;
import io.github.wurtzitane.avogadro.api.StructureAssembly;
import io.github.wurtzitane.avogadro.api.StructureEdits;
import io.github.wurtzitane.avogadro.api.AtomicConfiguration;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/** Expands bounded, generic authoring operations into the existing explicit geometry format. */
public final class StructureRecipe {

    /** Maximum allowed structural operation nesting limit. */
    private static final int MAX_OPERATIONS = 64;

    /** Maximum allowed recipe nesting depth. */
    private static final int MAX_NESTING = 16;

    // Public re-entry can occur through assembly fragment nodes and serialized append edits.
    /** Thread-local re-entry depth counter tracking recursive structural parsing. */
    private static final ThreadLocal<Integer> REENTRY = ThreadLocal.withInitial(() -> 0);

    /** Private constructor to prevent instantiation of utility class. */
    private StructureRecipe() {}

    /**
     * Parses a structure recipe JSON object into a structure fragment instance.
     *
     * @param recipe JSON object representing the structure recipe
     * @return constructed {@link StructureFragment} instance
     * @throws IllegalArgumentException if nesting limits or syntax rules are violated
     */
    public static StructureFragment parse(JsonObject recipe) {
        int entered = REENTRY.get();
        if (entered >= MAX_NESTING) throw invalid("Nested structure/assembly parsing exceeds its limit.");
        REENTRY.set(entered + 1);
        try {
            if (entered == 0) bounded(recipe, 0, new int[1]);
            return parse(recipe, 0);
        } finally {
            if (entered == 0) REENTRY.remove(); else REENTRY.set(entered);
        }
    }

    /**
     * Internal parsing helper for structure recipes at a specified depth.
     *
     * @param recipe JSON object recipe
     * @param depth  recursion depth integer
     * @return constructed {@link StructureFragment} instance
     */
    private static StructureFragment parse(JsonObject recipe, int depth) {
        if (recipe == null) throw invalid("structure must be an object.");
        if (depth > MAX_NESTING) throw invalid("Nested structures exceed the maximum depth of " + MAX_NESTING + ".");
        fields(recipe, "structure", "atoms", "bonds", "cell", "operations", "attachments", "random_link_capacities", "assembly");
        StructureFragment result;
        if (recipe.has("assembly")) {
            for (String key : List.of("atoms", "bonds", "cell", "attachments", "random_link_capacities"))
                if (recipe.has(key)) throw invalid("assembly cannot be combined with " + key);
            result = StructureAssembly.fromJson(object(recipe.get("assembly"), "structure.assembly")).build();
        } else {
            StructureBuilder builder = new StructureBuilder();
            JsonArray atoms = array(required(recipe, "atoms"), "structure.atoms", 1, StructureFragment.MAX_ATOMS);
            for (int index = 0; index < atoms.size(); index++) {
                String path = "structure.atoms[" + index + "]";
                JsonObject atom = object(atoms.get(index), path);
                fields(atom, path, "e", "x", "y", "z", "nuclear");
                builder.atom(string(required(atom, "e"), path + ".e"), number(required(atom, "x"), path + ".x"),
                        number(required(atom, "y"), path + ".y"), number(required(atom, "z"), path + ".z"));
                if (atom.has("nuclear")) {
                    JsonObject nuclear = object(atom.get("nuclear"), path + ".nuclear");
                    fields(nuclear, path + ".nuclear", "atomic_number", "neutrons", "charge", "electron_shells");
                    builder.atomConfiguration(index, AtomicConfiguration.fromJson(nuclear));
                }
            }
            if (recipe.has("attachments")) {
                object(recipe.get("attachments"), "structure.attachments").entrySet().forEach(entry ->
                        builder.attachment(entry.getKey(), integer(entry.getValue(), "structure.attachments." + entry.getKey(), 0, atoms.size() - 1)));
            }
            if (recipe.has("random_link_capacities")) {
                object(recipe.get("random_link_capacities"), "structure.random_link_capacities").entrySet().forEach(entry -> {
                    int index;
                    try { index = Integer.parseInt(entry.getKey()); }
                    catch (NumberFormatException invalid) { throw invalid("Random link capacity keys must be atom indices."); }
                    if (index < 0 || index >= atoms.size()) throw invalid("Random link capacity atom index outside fragment.");
                    builder.randomLinkCapacity(index, integer(entry.getValue(), "random_link_capacities", 0, StructureFragment.MAX_BONDS));
                });
            }
            if (recipe.has("bonds")) {
                JsonArray bonds = array(recipe.get("bonds"), "structure.bonds", 0, StructureFragment.MAX_BONDS);
                for (int index = 0; index < bonds.size(); index++) {
                    String path = "structure.bonds[" + index + "]";
                    JsonArray bond = array(bonds.get(index), path, 2, 3);
                    BondType type = bond.size() == 3 ? bondType(bond.get(2), path + ".type") : BondType.SINGLE;
                    if (bond.get(0).isJsonPrimitive() && bond.get(0).getAsJsonPrimitive().isString())
                        builder.bond(string(bond.get(0), path + ".from"), string(bond.get(1), path + ".to"), type);
                    else builder.bond(integer(bond.get(0), path + ".from", 0, StructureFragment.MAX_ATOMS - 1),
                            integer(bond.get(1), path + ".to", 0, StructureFragment.MAX_ATOMS - 1), type);
                }
            }
            if (recipe.has("cell")) readCell(builder, object(recipe.get("cell"), "structure.cell"));
            result = builder.build();
        }
        if (recipe.has("operations")) {
            JsonArray operations = array(recipe.get("operations"), "structure.operations", 0, MAX_OPERATIONS);
            for (int index = 0; index < operations.size(); index++) {
                try { result = operation(result, object(operations.get(index), "structure.operations[" + index + "]"), depth); }
                catch (IllegalArgumentException failure) {
                    throw new IllegalArgumentException("structure.operations[" + index + "]: " + failure.getMessage(), failure);
                }
            }
        }
        if (result.isUnitCell()) throw invalid("A structure with cell vectors must include a repeat operation; relative links cannot be omitted.");
        return result;
    }

    /**
     * Reads and configures unit cell vectors and link rules into a structure builder.
     *
     * @param builder structure builder instance
     * @param cell    cell JSON object
     */
    private static void readCell(StructureBuilder builder, JsonObject cell) {
        fields(cell, "structure.cell", "vectors", "links");
        JsonArray vectors = array(required(cell, "vectors"), "structure.cell.vectors", 1, 3);
        double[][] basis = new double[vectors.size()][];
        for (int index = 0; index < vectors.size(); index++) basis[index] = vector(vectors.get(index), "structure.cell.vectors[" + index + "]");
        builder.cell(basis);
        if (!cell.has("links")) return;
        JsonArray links = array(cell.get("links"), "structure.cell.links", 0, StructureFragment.MAX_BONDS);
        for (int index = 0; index < links.size(); index++) {
            String path = "structure.cell.links[" + index + "]";
            JsonArray link = array(links.get(index), path, 3, 4);
            JsonArray offset = array(link.get(2), path + ".offset", basis.length, basis.length);
            int[] shift = new int[offset.size()];
            for (int axis = 0; axis < shift.length; axis++) shift[axis] = integer(offset.get(axis), path + ".offset", Integer.MIN_VALUE, Integer.MAX_VALUE);
            builder.link(integer(link.get(0), path + ".from", 0, StructureFragment.MAX_ATOMS - 1),
                    integer(link.get(1), path + ".to", 0, StructureFragment.MAX_ATOMS - 1), shift,
                    link.size() == 4 ? bondType(link.get(3), path + ".type") : BondType.SINGLE);
        }
    }

    /**
     * Applies a structural modification operation on a fragment.
     *
     * @param fragment  structure fragment instance
     * @param operation operation JSON object
     * @param depth     recursion depth integer
     * @return modified {@link StructureFragment} instance
     */
    private static StructureFragment operation(StructureFragment fragment, JsonObject operation, int depth) {
        String name = string(required(operation, "op"), "operation.op");
        return switch (name) {
            case "translate" -> {
                fields(operation, name, "op", "by");
                double[] by = vector(required(operation, "by"), name + ".by");
                yield fragment.translate(by[0], by[1], by[2]);
            }
            case "scale" -> {
                fields(operation, name, "op", "by");
                JsonElement value = required(operation, "by");
                if (value.isJsonArray()) {
                    double[] by = vector(value, name + ".by");
                    yield fragment.scale(by[0], by[1], by[2]);
                }
                yield fragment.scale(number(value, name + ".by"));
            }
            case "rotate" -> {
                fields(operation, name, "op", "axis", "degrees");
                double[] axis = vector(required(operation, "axis"), name + ".axis");
                yield fragment.rotate(axis[0], axis[1], axis[2], number(required(operation, "degrees"), name + ".degrees"));
            }
            case "affine" -> {
                fields(operation, name, "op", "matrix");
                yield fragment.affine(matrix(required(operation, "matrix"), name + ".matrix"));
            }
            case "repeat" -> {
                fields(operation, name, "op", "counts", "wrap");
                JsonArray counts = array(required(operation, "counts"), name + ".counts", 1, 3);
                int[] amounts = new int[counts.size()];
                for (int axis = 0; axis < amounts.length; axis++) amounts[axis] = integer(counts.get(axis), name + ".counts", 1, StructureFragment.MAX_ATOMS);
                boolean[] wrap = new boolean[amounts.length];
                if (operation.has("wrap")) {
                    JsonArray flags = array(operation.get("wrap"), name + ".wrap", amounts.length, amounts.length);
                    for (int axis = 0; axis < wrap.length; axis++) {
                        JsonElement flag = flags.get(axis);
                        if (!flag.isJsonPrimitive() || !flag.getAsJsonPrimitive().isBoolean()) throw invalid(name + ".wrap must contain booleans.");
                        wrap[axis] = flag.getAsBoolean();
                    }
                }
                yield fragment.repeat(amounts, wrap);
            }
            case "instances" -> {
                fields(operation, name, "op", "transforms", "prefix");
                JsonArray values = array(required(operation, "transforms"), name + ".transforms", 1,
                        StructureFragment.MAX_ATOMS / fragment.atoms().size());
                List<double[][]> transforms = new ArrayList<>(values.size());
                for (int index = 0; index < values.size(); index++) transforms.add(matrix(values.get(index), name + ".transforms[" + index + "]"));
                yield operation.has("prefix") ? fragment.instances(string(operation.get("prefix"), "instances.prefix"), transforms) : fragment.instances(transforms);
            }
            case "append" -> {
                fields(operation, name, "op", "structure", "instance");
                StructureFragment appended = parse(object(required(operation, "structure"), name + ".structure"), depth + 1);
                yield operation.has("instance") ? fragment.append(string(operation.get("instance"), "append.instance"), appended) : fragment.append(appended);
            }
            case "edit" -> {
                fields(operation, name, "op", "edits");
                yield StructureEdits.fromJson(array(required(operation, "edits"), "edit.edits", 0, StructureEdits.MAX_OPERATIONS)).apply(fragment).fragment();
            }
            case "wrapCylinder" -> {
                fields(operation, name, "op", "origin", "circumference", "axis", "length");
                double[] origin = operation.has("origin") ? vector(operation.get("origin"), name + ".origin") : new double[3];
                yield fragment.wrapCylinder(origin, vector(required(operation, "circumference"), name + ".circumference"),
                        vector(required(operation, "axis"), name + ".axis"), number(required(operation, "length"), name + ".length"));
            }
            default -> throw invalid("Unsupported structure operation '" + name + "'.");
        };
    }

    /**
     * Parses a transformation matrix from a JSON element.
     *
     * @param value JSON element
     * @param path  path string for error reporting
     * @return 3x4 double coefficient matrix
     */
    private static double[][] matrix(JsonElement value, String path) {
        JsonArray rows = array(value, path, 3, 3);
        double[][] result = new double[3][4];
        for (int row = 0; row < 3; row++) {
            JsonArray coefficients = array(rows.get(row), path + "[" + row + "]", 4, 4);
            for (int column = 0; column < 4; column++) result[row][column] = number(coefficients.get(column), path);
        }
        return result;
    }

    /**
     * Parses a 3D vector coordinate from a JSON element.
     *
     * @param value JSON element
     * @param path  path string for error reporting
     * @return double coordinate array of length 3
     */
    private static double[] vector(JsonElement value, String path) {
        JsonArray coordinates = array(value, path, 3, 3);
        return new double[]{number(coordinates.get(0), path), number(coordinates.get(1), path), number(coordinates.get(2), path)};
    }

    /**
     * Parses a bond type from a JSON element.
     *
     * @param value JSON element
     * @param path  path string for error reporting
     * @return resolved {@link BondType} instance
     */
    private static BondType bondType(JsonElement value, String path) {
        if (!value.isJsonPrimitive()) throw invalid(path + " must be a bond type name or an integer from 1 to 4.");
        if (value.getAsJsonPrimitive().isNumber()) {
            return switch (integer(value, path, 1, 4)) {
                case 1 -> BondType.SINGLE;
                case 2 -> BondType.DOUBLE;
                case 3 -> BondType.TRIPLE;
                default -> BondType.QUADRUPLE;
            };
        }
        String type = string(value, path).trim().toUpperCase(java.util.Locale.ROOT);
        return switch (type) {
            case "SINGLE", "-", "1" -> BondType.SINGLE;
            case "DOUBLE", "=", "2" -> BondType.DOUBLE;
            case "TRIPLE", "#", "3" -> BondType.TRIPLE;
            case "QUADRUPLE", "4" -> BondType.QUADRUPLE;
            default -> throw invalid("Unsupported bond type '" + type + "'.");
        };
    }

    /**
     * Asserts that a JSON object contains only allowed fields.
     *
     * @param object  JSON object to validate
     * @param path    path string for error reporting
     * @param allowed allowed field names
     * @throws IllegalArgumentException if unsupported fields are present
     */
    private static void fields(JsonObject object, String path, String... allowed) {
        Set<String> fields = Set.of(allowed);
        for (String key : object.keySet()) if (!fields.contains(key)) throw invalid("Unsupported field " + path + "." + key + ".");
    }

    /**
     * Retrieves a required non-null JSON element from an object.
     *
     * @param object JSON object
     * @param field  field name string
     * @return resolved {@link JsonElement}
     * @throws IllegalArgumentException if the field is missing or null
     */
    private static JsonElement required(JsonObject object, String field) {
        if (!object.has(field) || object.get(field).isJsonNull()) throw invalid("Missing required field '" + field + "'.");
        return object.get(field);
    }

    /**
     * Casts a JSON element to a JSON object.
     *
     * @param value JSON element
     * @param path  path string for error reporting
     * @return resolved {@link JsonObject}
     * @throws IllegalArgumentException if not an object
     */
    private static JsonObject object(JsonElement value, String path) {
        if (value == null || !value.isJsonObject()) throw invalid(path + " must be an object.");
        return value.getAsJsonObject();
    }

    /**
     * Validates and casts a JSON element to a JSON array with size bounds.
     *
     * @param value   JSON element
     * @param path    path string for error reporting
     * @param minimum minimum required size
     * @param maximum maximum allowed size
     * @return resolved {@link JsonArray}
     * @throws IllegalArgumentException if size or type is invalid
     */
    private static JsonArray array(JsonElement value, String path, int minimum, int maximum) {
        if (value == null || !value.isJsonArray()) throw invalid(path + " must be an array.");
        JsonArray array = value.getAsJsonArray();
        if (array.size() < minimum || array.size() > maximum) throw invalid(path + " requires "
                + (minimum == maximum ? Integer.toString(minimum) : minimum + ".." + maximum) + " entries.");
        return array;
    }

    /**
     * Extracts a primitive string value from a JSON element.
     *
     * @param value JSON element
     * @param path  path string for error reporting
     * @return string value
     * @throws IllegalArgumentException if not a string primitive
     */
    private static String string(JsonElement value, String path) {
        if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()) throw invalid(path + " must be a string.");
        return value.getAsString();
    }

    /**
     * Extracts a finite numeric double value from a JSON element.
     *
     * @param value JSON element
     * @param path  path string for error reporting
     * @return numeric double value
     * @throws IllegalArgumentException if not a finite number
     */
    private static double number(JsonElement value, String path) {
        if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()) throw invalid(path + " must be a finite number.");
        double number = value.getAsDouble();
        if (!Double.isFinite(number)) throw invalid(path + " must be a finite number.");
        return number;
    }

    /**
     * Extracts and validates an integer value within specified bounds from a JSON element.
     *
     * @param value   JSON element
     * @param path    path string for error reporting
     * @param minimum minimum allowed integer value
     * @param maximum maximum allowed integer value
     * @return integer value
     * @throws IllegalArgumentException if out of bounds or non-integer
     */
    private static int integer(JsonElement value, String path, int minimum, int maximum) {
        double number = number(value, path);
        if (number != Math.rint(number) || number < minimum || number > maximum) throw invalid(path + " must be an integer from " + minimum + " to " + maximum + ".");
        return (int) number;
    }

    /**
     * Recursively verifies depth and entry count bounds for JSON structure elements.
     *
     * @param value   JSON element
     * @param depth   current recursion depth
     * @param entries mutable entry count tracker array
     * @throws IllegalArgumentException if limits are exceeded
     */
    private static void bounded(JsonElement value, int depth, int[] entries) {
        if (value == null) throw invalid("structure must be an object.");
        if (depth > 96 || ++entries[0] > 131072) throw invalid("Structure JSON exceeds its depth or entry limit.");
        if (value.isJsonObject()) for (var child : value.getAsJsonObject().entrySet()) bounded(child.getValue(), depth + 1, entries);
        else if (value.isJsonArray()) for (var child : value.getAsJsonArray()) bounded(child, depth + 1, entries);
    }

    /**
     * Creates an illegal argument exception for invalid syntax or structural rules.
     *
     * @param message error message string
     * @return constructed {@link IllegalArgumentException}
     */
    private static IllegalArgumentException invalid(String message) { return new IllegalArgumentException(message); }
}