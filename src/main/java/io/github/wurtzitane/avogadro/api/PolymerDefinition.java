package io.github.wurtzitane.avogadro.api;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.github.wurtzitane.avogadro.molecule.BondType;
import io.github.wurtzitane.avogadro.molecule.MoleculeDef;
import io.github.wurtzitane.avogadro.molecule.StructureRecipe;

import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;

/**
 * An authored linear repeat unit and explicit terminal groups, not a molecular-weight distribution.
 *
 * @param repeatUnit   the structure fragment representing the repeating unit
 * @param head         the zero-based index of the head atom in the repeat unit
 * @param tail         the zero-based index of the tail atom in the repeat unit
 * @param translation  the Cartesian displacement vector between successive units
 * @param linkType     the bond type used to link repeat units together
 * @param headCap      the terminal cap structure at the head of the chain
 * @param tailCap      the terminal cap structure at the tail of the chain
 * @param previewUnits the number of units rendered in previews
 */
public record PolymerDefinition(StructureFragment repeatUnit, int head, int tail, Vector translation,
                                BondType linkType, Cap headCap, Cap tailCap, int previewUnits) {

    /** Metadata key used to store or retrieve polymer chain definitions. */
    public static final String METADATA_KEY = "polymer_chain";

    /**
     * Cartesian displacement between successive units, in the same angstrom frame as their atoms.
     *
     * @param x the x-axis displacement component
     * @param y the y-axis displacement component
     * @param z the z-axis displacement component
     */
    public record Vector(double x, double y, double z) {

        /**
         * Compact constructor for {@link Vector} that validates coordinate finiteness,
         * coordinate bounds, and ensures the translation vector is non-zero.
         *
         * @throws IllegalArgumentException if coordinates are non-finite, out of bounds, or all zero
         */
        public Vector {
            for (double value : new double[] { x, y, z }) {
                if (!Double.isFinite(value) || Math.abs(value) > 1_000_000) {
                    throw new IllegalArgumentException("Polymer translation must be finite and bounded");
                }
            }
            if (x == 0 && y == 0 && z == 0) {
                throw new IllegalArgumentException("Polymer translation must not be zero");
            }
        }
    }

    /**
     * Coordinates are relative to the first or last repeat unit; attachment identifies the cap atom.
     *
     * @param structure  the structure fragment of the cap
     * @param attachment the zero-based index of the attachment atom within the cap structure
     * @param bondType   the bond type connecting the cap to the chain
     */
    public record Cap(StructureFragment structure, int attachment, BondType bondType) {

        /**
         * Compact constructor for {@link Cap} that validates structure expansion and attachment index bounds.
         *
         * @throws IllegalArgumentException if the structure is a unit cell or the attachment index is invalid
         */
        public Cap {
            Objects.requireNonNull(structure, "cap structure");
            Objects.requireNonNull(bondType, "cap bond type");
            if (structure.isUnitCell() || attachment < 0 || attachment >= structure.atoms().size()) {
                throw new IllegalArgumentException("A cap requires expanded geometry and a valid attachment atom");
            }
        }

        /**
         * Resolves an authored attachment name without changing the cap's coordinates.
         *
         * @param structure  the structure fragment of the cap
         * @param attachment the attachment name string
         * @param bondType   the bond type connecting the cap
         * @return a new {@link Cap} instance
         */
        public static Cap usingAttachment(StructureFragment structure, String attachment, BondType bondType) {
            Objects.requireNonNull(structure, "cap structure");
            return new Cap(structure, structure.attachment(attachment), bondType);
        }
    }

    /**
     * Compact constructor for {@link PolymerDefinition} that validates null safety, repeat geometry expansion,
     * head/tail index bounds, and preview unit limits.
     *
     * @throws IllegalArgumentException if any parameter is invalid or out of bounds
     */
    public PolymerDefinition {
        Objects.requireNonNull(repeatUnit, "repeat unit");
        Objects.requireNonNull(translation, "translation");
        Objects.requireNonNull(linkType, "link type");
        Objects.requireNonNull(headCap, "head cap");
        Objects.requireNonNull(tailCap, "tail cap");
        if (repeatUnit.isUnitCell() || head < 0 || head >= repeatUnit.atoms().size() || tail < 0 ||
                tail >= repeatUnit.atoms().size()) {
            throw new IllegalArgumentException("A polymer requires expanded repeat geometry and valid head/tail atoms");
        }
        PolymerGeometry.validateSize(repeatUnit, headCap, tailCap, previewUnits);
    }

    /**
     * Resolves repeat-unit attachment names; the existing index-based record remains unchanged.
     *
     * @param repeatUnit   the repeat unit structure fragment
     * @param head         the head attachment name string
     * @param tail         the tail attachment name string
     * @param translation  the displacement vector
     * @param linkType     the link bond type
     * @param headCap      the head cap
     * @param tailCap      the tail cap
     * @param previewUnits the preview unit count
     * @return a new {@link PolymerDefinition} instance
     */
    public static PolymerDefinition usingAttachments(StructureFragment repeatUnit, String head, String tail,
                                                     Vector translation, BondType linkType, Cap headCap,
                                                     Cap tailCap, int previewUnits) {
        Objects.requireNonNull(repeatUnit, "repeat unit");
        return new PolymerDefinition(repeatUnit, repeatUnit.attachment(head), repeatUnit.attachment(tail),
                translation, linkType, headCap, tailCap, previewUnits);
    }

    /**
     * Computes the chemical formula string of the single repeat unit.
     *
     * @return repeat unit formula string
     */
    public String repeatUnitFormula() {
        return formula(composition(repeatUnit));
    }

    /**
     * Formats the generic polymer formula representation: {@code (repeat_unit)n}.
     *
     * @return polymer formula string
     */
    public String formula() {
        return "(" + repeatUnitFormula() + ")n";
    }

    /**
     * Computes the specific chemical formula for a polymer chain of a given unit length,
     * including terminal caps.
     *
     * @param units the number of units in the chain
     * @return chain formula string
     */
    public String chainFormula(int units) {
        PolymerGeometry.validateSize(repeatUnit, headCap, tailCap, units);
        Map<String, Long> counts = new TreeMap<>(composition(repeatUnit));
        counts.replaceAll((element, count) -> count * units);
        composition(headCap.structure()).forEach((element, count) -> counts.merge(element, count, Long::sum));
        composition(tailCap.structure()).forEach((element, count) -> counts.merge(element, count, Long::sum));
        return formula(counts);
    }

    /**
     * Serializes this polymer definition into a JSON object.
     *
     * @return a {@link JsonObject} representation of the polymer definition
     */
    public JsonObject toJson() {
        JsonObject result = new JsonObject();
        result.add("repeat_unit", repeatUnit.toJson());
        result.addProperty("head", head);
        result.addProperty("tail", tail);
        JsonArray vector = new JsonArray();
        vector.add(translation.x());
        vector.add(translation.y());
        vector.add(translation.z());
        result.add("translation", vector);
        result.addProperty("link_type", linkType.name());
        result.add("head_cap", capJson(headCap));
        result.add("tail_cap", capJson(tailCap));
        result.addProperty("preview_units", previewUnits);
        return result;
    }

    /**
     * Parses a polymer definition from a JSON object.
     *
     * @param json the {@link JsonObject} to parse
     * @return a new parsed {@link PolymerDefinition} instance
     * @throws IllegalArgumentException if fields are missing, invalid, or malformed
     */
    public static PolymerDefinition fromJson(JsonObject json) {
        fields(json, "repeat_unit", "head", "tail", "translation", "link_type", "head_cap", "tail_cap",
                "preview_units");
        JsonElement vectorValue = required(json, "translation");
        if (!vectorValue.isJsonArray() || vectorValue.getAsJsonArray().size() != 3) {
            throw new IllegalArgumentException("Polymer translation requires exactly three coordinates");
        }
        JsonArray vector = vectorValue.getAsJsonArray();
        return new PolymerDefinition(
                StructureRecipe.parse(required(json, "repeat_unit").getAsJsonObject()),
                integer(json, "head"),
                integer(json, "tail"),
                new Vector(number(vector.get(0)), number(vector.get(1)), number(vector.get(2))),
                bondType(json, "link_type"),
                cap(required(json, "head_cap").getAsJsonObject()),
                cap(required(json, "tail_cap").getAsJsonObject()),
                integer(json, "preview_units")
        );
    }

    /**
     * Retrieves the polymer definition from a molecule definition's metadata if present.
     *
     * @param definition the {@link MoleculeDef} to inspect
     * @return the {@link PolymerDefinition}, or {@code null} if not present
     */
    public static PolymerDefinition of(MoleculeDef definition) {
        String json = definition.inspection().metadata().get(METADATA_KEY);
        return json == null ? null : fromJson(JsonParser.parseString(json).getAsJsonObject());
    }

    /**
     * Parses a terminal cap from a JSON object.
     *
     * @param json the cap {@link JsonObject}
     * @return the parsed {@link Cap} instance
     */
    private static Cap cap(JsonObject json) {
        fields(json, "structure", "attachment", "bond_type");
        return new Cap(
                StructureRecipe.parse(required(json, "structure").getAsJsonObject()),
                integer(json, "attachment"),
                bondType(json, "bond_type")
        );
    }

    /**
     * Serializes a terminal cap into a JSON object.
     *
     * @param cap the {@link Cap} to serialize
     * @return the cap {@link JsonObject}
     */
    private static JsonObject capJson(Cap cap) {
        JsonObject json = new JsonObject();
        json.add("structure", cap.structure().toJson());
        json.addProperty("attachment", cap.attachment());
        json.addProperty("bond_type", cap.bondType().name());
        return json;
    }

    /**
     * Computes the composition map of atom element counts for a structure fragment.
     *
     * @param fragment the {@link StructureFragment}
     * @return map of element symbols to atom counts
     */
    private static Map<String, Long> composition(StructureFragment fragment) {
        Map<String, Long> result = new TreeMap<>();
        for (var atom : fragment.atoms()) {
            result.merge(atom.element(), 1L, Long::sum);
        }
        return result;
    }

    /**
     * Formats an atomic composition map into a standard chemical formula string,
     * placing Carbon (C) and Hydrogen (H) first.
     *
     * @param composition the composition map
     * @return formatted chemical formula string
     */
    private static String formula(Map<String, Long> composition) {
        Map<String, Long> remaining = new TreeMap<>(composition);
        StringBuilder result = new StringBuilder();
        if (remaining.containsKey("C")) {
            append(result, "C", remaining.remove("C"));
            if (remaining.containsKey("H")) {
                append(result, "H", remaining.remove("H"));
            }
        }
        remaining.forEach((element, count) -> append(result, element, count));
        return result.toString();
    }

    /**
     * Appends an element symbol and count to a string builder, omitting the count if it equals 1.
     *
     * @param target  the {@link StringBuilder} target
     * @param element the element symbol string
     * @param count   the atom count
     */
    private static void append(StringBuilder target, String element, long count) {
        target.append(element);
        if (count != 1) {
            target.append(count);
        }
    }

    /**
     * Validates that all fields present in the JSON object are within the allowed set.
     *
     * @param json    the {@link JsonObject} to check
     * @param allowed array of allowed field name strings
     * @throws IllegalArgumentException if an unsupported field is encountered
     */
    private static void fields(JsonObject json, String... allowed) {
        Set<String> names = Set.of(allowed);
        for (String field : json.keySet()) {
            if (!names.contains(field)) {
                throw new IllegalArgumentException("Unsupported polymer field '" + field + "'");
            }
        }
    }

    /**
     * Retrieves a required JSON element from an object, throwing an exception if missing or null.
     *
     * @param json  the parent {@link JsonObject}
     * @param field the field name
     * @return the retrieved {@link JsonElement}
     * @throws IllegalArgumentException if the field is missing or null
     */
    private static JsonElement required(JsonObject json, String field) {
        if (!json.has(field) || json.get(field).isJsonNull()) {
            throw new IllegalArgumentException("Missing polymer field '" + field + "'");
        }
        return json.get(field);
    }

    /**
     * Extracts and validates a finite number value from a JSON element.
     *
     * @param value the {@link JsonElement}
     * @return the double value
     * @throws IllegalArgumentException if the value is not a finite number
     */
    private static double number(JsonElement value) {
        if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber() ||
                !Double.isFinite(value.getAsDouble())) {
            throw new IllegalArgumentException("A polymer coordinate or index must be a finite number");
        }
        return value.getAsDouble();
    }

    /**
     * Extracts a non-negative integer value from a JSON object field.
     *
     * @param json  the parent {@link JsonObject}
     * @param field the field name
     * @return the integer value
     * @throws IllegalArgumentException if the value is not a non-negative integer
     */
    private static int integer(JsonObject json, String field) {
        double value = number(required(json, field));
        if (value != Math.rint(value) || value < 0 || value > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("Polymer field '" + field + "' requires a nonnegative integer");
        }
        return (int) value;
    }

    /**
     * Parses a bond type enum value from a JSON object field.
     *
     * @param json  the parent {@link JsonObject}
     * @param field the field name
     * @return the resolved {@link BondType}
     * @throws IllegalArgumentException if the field is not a valid bond type name
     */
    private static BondType bondType(JsonObject json, String field) {
        JsonElement value = required(json, field);
        if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()) {
            throw new IllegalArgumentException("Polymer field '" + field + "' requires a bond type name");
        }
        return BondType.valueOf(value.getAsString());
    }
}