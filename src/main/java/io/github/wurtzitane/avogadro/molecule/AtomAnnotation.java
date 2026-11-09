package io.github.wurtzitane.avogadro.molecule;

import com.google.gson.JsonObject;

/**
 * Explicit atom-local authoring data. A labeled wildcard represents an unresolved external
 * group, not a new element. Radical electrons are an authored count, not a guess from
 * valence, aromatic spelling, atomic charge, or an examiner electron-shell configuration.
 * @param label external-group label, empty for a real atom; at most eight ASCII identifier characters
 * @param radicalElectrons number of unpaired electrons, from zero through four
 */
public record AtomAnnotation(String label, int radicalElectrons) {
    /** No external group or explicit radical information. */
    public static final AtomAnnotation NONE = new AtomAnnotation("", 0);
    /** Default label of a standard SMILES wildcard. */
    public static final AtomAnnotation R_GROUP = new AtomAnnotation("R", 0);

    /** Validates bounded labels and electron counts without interpreting chemistry. */
    public AtomAnnotation {
        if (label == null || !label.isEmpty() && !label.matches("[A-Za-z][A-Za-z0-9_]{0,7}"))
            throw new IllegalArgumentException("External-group labels must contain 1–8 ASCII identifier characters");
        if (radicalElectrons < 0 || radicalElectrons > 4)
            throw new IllegalArgumentException("radical_electrons must be an integer from 0 to 4");
        if (!label.isEmpty() && radicalElectrons != 0)
            throw new IllegalArgumentException("An unresolved external group cannot have an exact radical count");
    }

    /**
     * Reads optional atom fields, defaulting wildcard labels to R.
     * @param atom atom JSON object
     * @param symbol real element or the wildcard symbol {@code *}
     * @return validated annotation
     */
    public static AtomAnnotation fromJson(JsonObject atom, String symbol) {
        String label = "*".equals(symbol) ? "R" : "";
        if (atom.has("label")) {
            if (!atom.get("label").isJsonPrimitive() || !atom.getAsJsonPrimitive("label").isString())
                throw new IllegalArgumentException("label must be a string on a wildcard atom");
            label = atom.get("label").getAsString();
        }
        int radical = 0;
        if (atom.has("radical_electrons")) {
            try {
                if (!atom.get("radical_electrons").isJsonPrimitive()
                        || !atom.getAsJsonPrimitive("radical_electrons").isNumber()) throw new ArithmeticException();
                radical = atom.get("radical_electrons").getAsBigDecimal().intValueExact();
            } catch (RuntimeException invalid) {
                throw new IllegalArgumentException("radical_electrons must be an integer from 0 to 4", invalid);
            }
        }
        return new AtomAnnotation(label, radical).forElement(symbol);
    }

    /**
     * Checks that labels belong only to wildcards and that wildcards always have a label.
     * @param symbol element symbol or {@code *}
     * @return this annotation, or the default R annotation for an unannotated wildcard
     */
    public AtomAnnotation forElement(String symbol) {
        if ("*".equals(symbol)) {
            if (radicalElectrons != 0) throw new IllegalArgumentException("An unresolved group has no exact radical count");
            return label.isEmpty() ? R_GROUP : this;
        }
        if (!label.isEmpty()) throw new IllegalArgumentException("Only wildcard atoms (e: *) may have external-group labels");
        return this;
    }

    /**
     * Writes only explicitly present annotation fields into an existing atom object.
     * @param target atom JSON object
     */
    public void write(JsonObject target) {
        if (!label.isEmpty()) target.addProperty("label", label);
        if (radicalElectrons != 0) target.addProperty("radical_electrons", radicalElectrons);
    }
}
