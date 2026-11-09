package io.github.wurtzitane.avogadro.api;

import java.util.Objects;
import java.util.Set;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import io.github.wurtzitane.avogadro.molecule.BondType;
import io.github.wurtzitane.avogadro.molecule.StructureRecipe;

/**
 * A saved list of editing steps that can be applied to a fragment or written as JSON.
 *
 * <p>
 * Steps run in order. Use {@link StructureEditor} when you want to edit directly; use this class when you want to save
 * or reuse the same edits.
 */
public final class StructureEdits {

    /** Maximum allowed operations in a single edit sequence. */
    public static final int MAX_OPERATIONS = 64;

    /** The serialized JSON array containing the sequential edit operations. */
    private final JsonArray operations;

    /**
     * Creates a {@link StructureEdits} from a JSON array of operations, validating their structure and boundaries.
     *
     * @param operations the {@link JsonArray} of edit operation objects
     * @throws IllegalArgumentException if operation counts exceed limits, nesting depth is exceeded, or validation
     *     fails
     */
    private StructureEdits(JsonArray operations) {
        if (operations.size() > MAX_OPERATIONS) {
            throw invalid("At most " + MAX_OPERATIONS + " edits are allowed.");
        }
        bounded(operations, 0);
        this.operations = operations.deepCopy();
        for (int index = 0; index < this.operations.size(); index++) {
            try {
                validate(object(this.operations.get(index)));
            } catch (RuntimeException failure) {
                throw invalid("edits[" + index + "]: " + failure.getMessage());
            }
        }
    }

    /**
     * Creates a new edit sequence builder.
     *
     * @return a new {@link Builder}
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Creates structure edits from a JSON array of operations.
     *
     * @param operations the {@link JsonArray} of operations
     * @return a new {@link StructureEdits}
     * @throws NullPointerException if operations is null
     */
    public static StructureEdits fromJson(JsonArray operations) {
        return new StructureEdits(Objects.requireNonNull(operations));
    }

    /**
     * Serializes these edits into a deep-copied JSON array.
     *
     * @return a new {@link JsonArray} of operations
     */
    public JsonArray toJson() {
        return operations.deepCopy();
    }

    /**
     * Checks whether this edit sequence is empty.
     *
     * @return {@code true} if no operations are present, {@code false} otherwise
     */
    public boolean isEmpty() {
        return operations.isEmpty();
    }

    /**
     * Determines whether any operation in this sequence modifies structure geometry (atoms, bonds, or connections).
     *
     * @return {@code true} if geometry is changed, {@code false} otherwise
     */
    public boolean changesGeometry() {
        for (var value : operations) {
            if (Set.of("replaceAtom", "moveAtom", "atom", "setBond", "removeBond", "removeAtoms", "append", "connect")
                    .contains(value.getAsJsonObject().get("op").getAsString())) {
                return true;
            }
        }
        return false;
    }

    /**
     * Determines whether any operation in this sequence modifies molecular composition (adding/removing atoms).
     *
     * @return {@code true} if composition is changed, {@code false} otherwise
     */
    public boolean changesComposition() {
        for (var value : operations) {
            if (Set.of("replaceAtom", "atom", "removeAtoms", "append")
                    .contains(value.getAsJsonObject().get("op").getAsString())) {
                return true;
            }
        }
        return false;
    }

    /**
     * Applies this sequence of edits to a source structure fragment.
     *
     * @param source the source {@link StructureFragment}
     * @return a {@link StructureEditResult} containing the edited fragment and index mappings
     * @throws java.util.concurrent.CancellationException if the thread is interrupted during execution
     */
    public StructureEditResult apply(StructureFragment source) {
        var editor = source.edit();
        for (int index = 0; index < operations.size(); index++) {
            if (Thread.currentThread().isInterrupted()) {
                throw new java.util.concurrent.CancellationException();
            }
            try {
                apply(editor, operations.get(index).getAsJsonObject());
            } catch (IllegalArgumentException failure) {
                throw new IllegalArgumentException("edits[" + index + "]: " + failure.getMessage(), failure);
            }
        }
        return editor.build();
    }

    /**
     * Applies these edits to a molecule definition, producing a new definition.
     *
     * @param newId the resource identifier string for the new definition
     * @param source the source {@link io.github.wurtzitane.avogadro.molecule.MoleculeDef}
     * @param materialize whether to materialize the molecule
     * @return the resulting edited {@link io.github.wurtzitane.avogadro.molecule.MoleculeDef}
     */
    public io.github.wurtzitane.avogadro.molecule.MoleculeDef apply(String newId,
            io.github.wurtzitane.avogadro.molecule.MoleculeDef source, boolean materialize) {
        return MoleculeEdits.apply(newId, source, this, materialize);
    }

    /**
     * Internal dispatcher executing an individual edit operation upon a structure editor session.
     *
     * @param editor the {@link StructureEditor}
     * @param op the operation {@link JsonObject}
     */
    private static void apply(StructureEditor editor, JsonObject op) {
        switch (text(op, "op")) {
            case "replaceAtom" -> editor.replaceAtom(index(op, "index"), text(op, "e"));
            case "moveAtom" -> editor.moveAtom(index(op, "index"), number(op, "x"), number(op, "y"), number(op, "z"));
            case "atom" -> editor.atom(text(op, "e"), number(op, "x"), number(op, "y"), number(op, "z"));
            case "setBond" -> editor.setBond(index(op, "from"), index(op, "to"), type(op));
            case "removeBond" -> editor.removeBond(index(op, "from"), index(op, "to"));
            case "removeAtoms" -> editor.removeAtoms(indices(op));
            case "append" ->
                editor.append(text(op, "instance"), StructureRecipe.parse(object(required(op, "structure"))));
            case "connect" -> editor.connect(text(op, "from"), text(op, "to"), type(op));
            case "attachment" -> editor.attachment(text(op, "name"), index(op, "index"));
            case "removeAttachment" -> editor.removeAttachment(text(op, "name"));
            case "atomConfiguration" -> editor.atomConfiguration(index(op, "index"), configuration(op));
            case "clearAtomConfiguration" -> editor.clearAtomConfiguration(index(op, "index"));
            case "randomLinkCapacity" -> editor.randomLinkCapacity(index(op, "index"),
                    integer(required(op, "capacity"), StructureFragment.MAX_BONDS));
            default -> throw invalid("Unknown edit operation.");
        }
    }

    /**
     * Validates syntax, required fields, and parameter types for an individual edit operation object.
     *
     * @param op the operation {@link JsonObject} to validate
     * @throws IllegalArgumentException if the operation is unsupported or fields are malformed
     */
    private static void validate(JsonObject op) {
        switch (text(op, "op")) {
            case "replaceAtom" -> {
                fields(op, "op", "index", "e");
                index(op, "index");
                new StructureFragment.Atom(text(op, "e"), 0, 0, 0);
            }
            case "moveAtom", "atom" -> {
                boolean added = text(op, "op").equals("atom");
                fields(op, "op", added ? "e" : "index", "x", "y", "z");
                if (!added) {
                    index(op, "index");
                }
                new StructureFragment.Atom(added ? text(op, "e") : "C", number(op, "x"), number(op, "y"),
                        number(op, "z"));
            }
            case "setBond", "removeBond" -> {
                boolean set = text(op, "op").equals("setBond");
                if (set) {
                    fields(op, "op", "from", "to", "type");
                } else {
                    fields(op, "op", "from", "to");
                }
                new StructureFragment.Bond(index(op, "from"), index(op, "to"), set ? type(op) : BondType.SINGLE);
            }
            case "removeAtoms" -> {
                fields(op, "op", "indices");
                indices(op);
            }
            case "append" -> {
                fields(op, "op", "instance", "structure");
                StructureFragment.attachmentName(text(op, "instance"));
                StructureRecipe.parse(object(required(op, "structure")));
            }
            case "connect" -> {
                fields(op, "op", "from", "to", "type");
                StructureFragment.attachmentName(text(op, "from"));
                StructureFragment.attachmentName(text(op, "to"));
                type(op);
            }
            case "attachment" -> {
                fields(op, "op", "name", "index");
                StructureFragment.attachmentName(text(op, "name"));
                index(op, "index");
            }
            case "removeAttachment" -> {
                fields(op, "op", "name");
                StructureFragment.attachmentName(text(op, "name"));
            }
            case "atomConfiguration" -> {
                fields(op, "op", "index", "nuclear");
                index(op, "index");
                configuration(op);
            }
            case "clearAtomConfiguration" -> {
                fields(op, "op", "index");
                index(op, "index");
            }
            case "randomLinkCapacity" -> {
                fields(op, "op", "index", "capacity");
                index(op, "index");
                integer(required(op, "capacity"), StructureFragment.MAX_BONDS);
            }
            default -> throw invalid("Unsupported edit operation '" + text(op, "op") + "'.");
        }
    }

    /**
     * Adds editing steps to a reusable edit list.
     */
    public static final class Builder {
        private final JsonArray operations = new JsonArray();

        /**
         * Private constructor.
         */
        Builder() {}

        /**
         * Internal helper to add and validate an operation JSON object.
         *
         * @param op the operation {@link JsonObject}
         * @return this builder
         */
        private Builder add(JsonObject op) {
            if (operations.size() >= MAX_OPERATIONS) {
                throw invalid("Too many edits.");
            }
            validate(op);
            operations.add(op);
            return this;
        }

        /**
         * Adds a replace atom operation.
         *
         * @param index the atom index
         * @param element the new element symbol
         * @return this builder
         */
        public Builder replaceAtom(int index, String element) {
            var op = at("replaceAtom", index);
            op.addProperty("e", element);
            return add(op);
        }

        /**
         * Adds a move atom operation.
         *
         * @param index the atom index
         * @param x x coordinate
         * @param y y coordinate
         * @param z z coordinate
         * @return this builder
         */
        public Builder moveAtom(int index, double x, double y, double z) {
            return add(position(at("moveAtom", index), x, y, z));
        }

        /**
         * Adds an add-atom operation.
         *
         * @param element element symbol
         * @param x x coordinate
         * @param y y coordinate
         * @param z z coordinate
         * @return this builder
         */
        public Builder atom(String element, double x, double y, double z) {
            var op = named("atom");
            op.addProperty("e", element);
            return add(position(op, x, y, z));
        }

        /**
         * Adds a set bond operation with specified bond type.
         *
         * @param from source atom index
         * @param to target atom index
         * @param type the {@link BondType}
         * @return this builder
         */
        public Builder setBond(int from, int to, BondType type) {
            var op = ends("setBond", from, to);
            op.addProperty("type", Objects.requireNonNull(type).name());
            return add(op);
        }

        /**
         * Adds a set single bond operation.
         *
         * @param from source atom index
         * @param to target atom index
         * @return this builder
         */
        public Builder setBond(int from, int to) {
            return setBond(from, to, BondType.SINGLE);
        }

        /**
         * Adds a remove bond operation.
         *
         * @param from source atom index
         * @param to target atom index
         * @return this builder
         */
        public Builder removeBond(int from, int to) {
            return add(ends("removeBond", from, to));
        }

        /**
         * Adds a remove atoms operation.
         *
         * @param indices array of atom indices to remove
         * @return this builder
         */
        public Builder removeAtoms(int... indices) {
            var op = named("removeAtoms");
            var values = new JsonArray();
            for (int index : indices) {
                values.add(index);
            }
            op.add("indices", values);
            return add(op);
        }

        /**
         * Adds an append fragment operation.
         *
         * @param instance instance qualifier prefix string
         * @param fragment the {@link StructureFragment} to append
         * @return this builder
         */
        public Builder append(String instance, StructureFragment fragment) {
            var op = named("append");
            op.addProperty("instance", instance);
            op.add("structure", fragment.toJson());
            return add(op);
        }

        /**
         * Adds a connect attachment operation with specified bond type.
         *
         * @param from source attachment name
         * @param to target attachment name
         * @param type the {@link BondType}
         * @return this builder
         */
        public Builder connect(String from, String to, BondType type) {
            var op = named("connect");
            op.addProperty("from", from);
            op.addProperty("to", to);
            op.addProperty("type", Objects.requireNonNull(type).name());
            return add(op);
        }

        /**
         * Adds a connect attachment operation with single bond type.
         *
         * @param from source attachment name
         * @param to target attachment name
         * @return this builder
         */
        public Builder connect(String from, String to) {
            return connect(from, to, BondType.SINGLE);
        }

        /**
         * Adds an attachment naming operation.
         *
         * @param name attachment name string
         * @param index atom index
         * @return this builder
         */
        public Builder attachment(String name, int index) {
            var op = at("attachment", index);
            op.addProperty("name", name);
            return add(op);
        }

        /**
         * Adds a remove attachment operation.
         *
         * @param name attachment name string to remove
         * @return this builder
         */
        public Builder removeAttachment(String name) {
            var op = named("removeAttachment");
            op.addProperty("name", name);
            return add(op);
        }

        /**
         * Adds an atom configuration assignment operation.
         *
         * @param index atom index
         * @param value the {@link AtomicConfiguration} value
         * @return this builder
         */
        public Builder atomConfiguration(int index, AtomicConfiguration value) {
            var op = at("atomConfiguration", index);
            op.add("nuclear", value.toJson());
            return add(op);
        }

        /**
         * Adds a clear atom configuration operation.
         *
         * @param index atom index
         * @return this builder
         */
        public Builder clearAtomConfiguration(int index) {
            return add(at("clearAtomConfiguration", index));
        }

        /**
         * Adds a random link capacity setting operation.
         *
         * @param index atom index
         * @param capacity maximum random link capacity
         * @return this builder
         */
        public Builder randomLinkCapacity(int index, int capacity) {
            var op = at("randomLinkCapacity", index);
            op.addProperty("capacity", capacity);
            return add(op);
        }

        /**
         * Builds and returns the immutable {@link StructureEdits}.
         *
         * @return a new {@link StructureEdits}
         */
        public StructureEdits build() {
            return new StructureEdits(operations);
        }
    }

    /** Creates a named operation JSON object. */
    private static JsonObject named(String name) {
        var op = new JsonObject();
        op.addProperty("op", name);
        return op;
    }

    /** Creates a named operation JSON object targeting an index. */
    private static JsonObject at(String name, int index) {
        var op = named(name);
        op.addProperty("index", index);
        return op;
    }

    /** Creates a named operation JSON object with source and target endpoints. */
    private static JsonObject ends(String name, int from, int to) {
        var op = named(name);
        op.addProperty("from", from);
        op.addProperty("to", to);
        return op;
    }

    /** Appends 3D coordinates to an operation JSON object. */
    private static JsonObject position(JsonObject op, double x, double y, double z) {
        op.addProperty("x", x);
        op.addProperty("y", y);
        op.addProperty("z", z);
        return op;
    }

    /** Validates that an operation object only contains allowed fields. */
    private static void fields(JsonObject op, String... fields) {
        var allowed = Set.of(fields);
        for (String field : op.keySet()) {
            if (!allowed.contains(field)) {
                throw invalid("Unsupported edit field '" + field + "'.");
            }
        }
    }

    /** Returns a required non-null field from an operation object. */
    private static JsonElement required(JsonObject op, String name) {
        var value = op.get(name);
        if (value == null || value.isJsonNull()) {
            throw invalid("Missing edit field '" + name + "'.");
        }
        return value;
    }

    /** Ensures a JSON element is a JSON object. */
    private static JsonObject object(JsonElement value) {
        if (!value.isJsonObject()) {
            throw invalid("An edit or structure must be an object.");
        }
        return value.getAsJsonObject();
    }

    /** Extracts a required text string property from an operation object. */
    private static String text(JsonObject op, String name) {
        var value = required(op, name);
        if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()) {
            throw invalid(name + " must be a string.");
        }
        return value.getAsString();
    }

    /** Extracts a required finite number property from an operation object. */
    private static double number(JsonObject op, String name) {
        var value = required(op, name);
        if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()
                || !Double.isFinite(value.getAsDouble())) {
            throw invalid(name + " must be a finite number.");
        }
        return value.getAsDouble();
    }

    /** Extracts and validates an atom index property. */
    private static int index(JsonObject op, String name) {
        return integer(required(op, name), StructureFragment.MAX_ATOMS - 1);
    }

    /** Extracts and validates an integer property within bounds. */
    private static int integer(JsonElement value, int max) {
        if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()) {
            throw invalid("An edit index must be an integer.");
        }
        double n = value.getAsDouble();
        if (!Double.isFinite(n) || n != Math.rint(n) || n < 0 || n > max) {
            throw invalid("An edit index is outside its allowed range.");
        }
        return (int) n;
    }

    /** Extracts and validates an array of atom indices. */
    private static int[] indices(JsonObject op) {
        var value = required(op, "indices");
        if (!value.isJsonArray() || value.getAsJsonArray().size() > StructureFragment.MAX_ATOMS) {
            throw invalid("indices must be a bounded integer array.");
        }
        return java.util.stream.StreamSupport.stream(value.getAsJsonArray().spliterator(), false)
                .mapToInt(item -> integer(item, StructureFragment.MAX_ATOMS - 1)).toArray();
    }

    /** Parses a bond type from an operation object, defaulting to SINGLE. */
    private static BondType type(JsonObject op) {
        if (!op.has("type")) {
            return BondType.SINGLE;
        }
        return BondType.valueOf(text(op, "type"));
    }

    /** Parses an atomic configuration from an operation object. */
    private static AtomicConfiguration configuration(JsonObject op) {
        var value = object(required(op, "nuclear"));
        fields(value, "atomic_number", "neutrons", "charge", "electron_shells");
        return AtomicConfiguration.fromJson(value);
    }

    /** Recursively bounds check JSON element nesting depth. */
    private static void bounded(JsonElement value, int depth) {
        if (depth > 64) {
            throw invalid("Edit JSON exceeds its nesting limit.");
        }
        if (value.isJsonArray()) {
            for (var child : value.getAsJsonArray()) {
                bounded(child, depth + 1);
            }
        } else if (value.isJsonObject()) {
            for (var child : value.getAsJsonObject().entrySet()) {
                bounded(child.getValue(), depth + 1);
            }
        }
    }

    /** Helper to create an invalid argument exception for edit errors. */
    private static IllegalArgumentException invalid(String message) {
        return new IllegalArgumentException(message);
    }
}
