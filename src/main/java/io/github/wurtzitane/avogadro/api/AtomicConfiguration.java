package io.github.wurtzitane.avogadro.api;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.util.List;

/**
 * Represents explicit isotope and shell populations.
 * <p>Orbits are schematic.
 *
 * @param atomicNumber    the atomic number (number of protons)
 * @param neutrons        the number of neutrons
 * @param charge          the net electrical charge
 * @param electronShells  an immutable list of electron counts per shell
 */
public record AtomicConfiguration(int atomicNumber, int neutrons, int charge, List<Integer> electronShells) {

    /**
     * Compact constructor for {@link AtomicConfiguration} that validates physical and structural constraints.
     *
     * @throws IllegalArgumentException if atomic numbers, neutron counts, charges, shell capacities,
     *         or total electron populations are invalid or out of bounds
     */
    public AtomicConfiguration {
        electronShells = List.copyOf(electronShells);
        // The host may describe hypothetical nuclei. These finite resource limits are not a periodic-table catalog.
        if (atomicNumber < 0 || atomicNumber > 1000 || neutrons < 0 || neutrons > 10000 ||
                atomicNumber == 0 && (neutrons == 0 || charge != 0)) {
            throw new IllegalArgumentException("Invalid atomic number or neutron count");
        }
        if (electronShells.size() > 32 || electronShells.stream().anyMatch(n -> n < 0)) {
            throw new IllegalArgumentException("Invalid electron shell populations");
        }
        if (charge > atomicNumber || charge < -1000) {
            throw new IllegalArgumentException("Unsupported charge");
        }
        for (int shell = 0; shell < electronShells.size(); shell++) {
            if (electronShells.get(shell) > 2 * (shell + 1) * (shell + 1)) {
                throw new IllegalArgumentException("Electron shell exceeds its capacity");
            }
        }
        long electrons = electronShells.stream().mapToLong(Integer::longValue).sum();
        if (electrons != (long) atomicNumber - charge) {
            throw new IllegalArgumentException("Electron populations must equal atomic number minus charge");
        }
    }

    /**
     * Calculates the total number of electrons based on atomic number and charge.
     *
     * @return the total electron count
     */
    public int electrons() {
        return atomicNumber - charge;
    }

    /**
     * Calculates the mass number of the isotope.
     *
     * @return the sum of protons and neutrons
     */
    public int massNumber() {
        return atomicNumber + neutrons;
    }

    /**
     * Serializes this atomic configuration into a JSON object.
     *
     * @return a {@link JsonObject} representing the configuration
     */
    public JsonObject toJson() {
        JsonObject value = new JsonObject();
        value.addProperty("atomic_number", atomicNumber);
        value.addProperty("neutrons", neutrons);
        value.addProperty("charge", charge);
        JsonArray shells = new JsonArray();
        electronShells.forEach(shells::add);
        value.add("electron_shells", shells);
        return value;
    }

    /**
     * Deserializes an atomic configuration from a JSON object.
     *
     * @param value the {@link JsonObject} to parse
     * @return a new {@link AtomicConfiguration} instance
     * @throws IllegalArgumentException if required fields are missing, invalid, or malformed
     */
    public static AtomicConfiguration fromJson(JsonObject value) {
        for (String field : List.of("atomic_number", "neutrons", "electron_shells")) {
            if (!value.has(field) || value.get(field).isJsonNull()) {
                throw new IllegalArgumentException("Missing required atomic configuration field '" + field + "'");
            }
        }
        if (!value.get("electron_shells").isJsonArray()) {
            throw new IllegalArgumentException("Atomic configuration 'electron_shells' must be an array");
        }
        java.util.ArrayList<Integer> shells = new java.util.ArrayList<>();
        for (var entry : value.getAsJsonArray("electron_shells")) {
            shells.add(integer(entry));
        }
        return new AtomicConfiguration(
                integer(value.get("atomic_number")),
                integer(value.get("neutrons")),
                value.has("charge") ? integer(value.get("charge")) : 0,
                shells
        );
    }

    /**
     * Validates and extracts an integer value from a JSON element.
     *
     * @param value the JSON element to check
     * @return the parsed integer value
     * @throws IllegalArgumentException if the element is not a valid finite integer number
     */
    private static int integer(com.google.gson.JsonElement value) {
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()) {
            throw new IllegalArgumentException("Atomic populations must be integers");
        }
        double n = value.getAsDouble();
        if (!Double.isFinite(n) || n != Math.rint(n) || n < Integer.MIN_VALUE || n > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("Atomic populations must be integers");
        }
        return (int) n;
    }
}