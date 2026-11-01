package io.github.wurtzitane.avogadro.api;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.github.wurtzitane.avogadro.molecule.MoleculeDef;
import org.joml.Vector3f;

/**
 * Represents conventional crystal cell parameters: lengths in angstroms, angles in degrees,
 * and the Cartesian origin in the atom frame.
 *
 * @param a       the length of lattice vector a in angstroms
 * @param b       the length of lattice vector b in angstroms
 * @param c       the length of lattice vector c in angstroms
 * @param alpha   the angle alpha in degrees
 * @param beta    the angle beta in degrees
 * @param gamma   the angle gamma in degrees
 * @param originX the Cartesian x-coordinate of the origin
 * @param originY the Cartesian y-coordinate of the origin
 * @param originZ the Cartesian z-coordinate of the origin
 */
public record CrystalCell(double a, double b, double c, double alpha, double beta, double gamma,
                          double originX, double originY, double originZ) {

    /** Metadata key used to store crystal cell information within molecule inspections. */
    public static final String METADATA_KEY = "crystal_cell";

    /**
     * Constructs a new {@link CrystalCell} with the specified lengths and angles,
     * defaulting the Cartesian origin to (0, 0, 0).
     *
     * @param a     the length of lattice vector a
     * @param b     the length of lattice vector b
     * @param c     the length of lattice vector c
     * @param alpha the angle alpha in degrees
     * @param beta  the angle beta in degrees
     * @param gamma the angle gamma in degrees
     */
    public CrystalCell(double a, double b, double c, double alpha, double beta, double gamma) {
        this(a, b, c, alpha, beta, gamma, 0, 0, 0);
    }

    /**
     * Compact constructor for {@link CrystalCell} that validates parameter finiteness,
     * bounds, angle ranges, and positive unit cell volume.
     *
     * @throws IllegalArgumentException if any parameter is non-finite, out of bounds,
     *         has invalid lengths/angles, or results in a non-positive cell volume
     */
    public CrystalCell {
        for (double value : new double[] { a, b, c, alpha, beta, gamma, originX, originY, originZ }) {
            if (!Double.isFinite(value) || Math.abs(value) > 1_000_000) {
                throw new IllegalArgumentException("Cell parameters must be finite and bounded");
            }
        }
        if (a <= 0 || b <= 0 || c <= 0 || alpha <= 0 || alpha >= 180 || beta <= 0 || beta >= 180 || gamma <= 0 ||
                gamma >= 180) {
            throw new IllegalArgumentException("Invalid cell lengths or angles");
        }

        double x = Math.cos(Math.toRadians(alpha));
        double y = Math.cos(Math.toRadians(beta));
        double z = Math.cos(Math.toRadians(gamma));

        if (1 + 2 * x * y * z - x * x - y * y - z * z <= 1e-10) {
            throw new IllegalArgumentException("Cell must have positive volume");
        }
    }

    /**
     * Computes the Cartesian position vector for a given unit cell grid coordinate (i, j, k).
     *
     * @param i the lattice coordinate index along a
     * @param j the lattice coordinate index along b
     * @param k the lattice coordinate index along c
     * @return the calculated {@link Vector3f} position
     */
    public Vector3f position(int i, int j, int k) {
        double ca = Math.cos(Math.toRadians(alpha));
        double cb = Math.cos(Math.toRadians(beta));
        double cg = Math.cos(Math.toRadians(gamma));
        double sg = Math.sin(Math.toRadians(gamma));

        double cy = c * (ca - cb * cg) / sg;
        double cz = Math.sqrt(c * c - (c * cb) * (c * cb) - cy * cy);

        return new Vector3f(
                (float) (originX + i * a + j * b * cg + k * c * cb),
                (float) (originY + j * b * sg + k * cy),
                (float) (originZ + k * cz)
        );
    }

    /**
     * Serializes this crystal cell into a JSON object.
     *
     * @return a {@link JsonObject} containing all crystal cell parameters
     */
    public JsonObject toJson() {
        JsonObject json = new JsonObject();
        String[] keys = { "a", "b", "c", "alpha", "beta", "gamma", "origin_x", "origin_y", "origin_z" };
        double[] values = { a, b, c, alpha, beta, gamma, originX, originY, originZ };
        for (int idx = 0; idx < keys.length; idx++) {
            json.addProperty(keys[idx], values[idx]);
        }
        return json;
    }

    /**
     * Deserializes a crystal cell from a JSON object.
     *
     * @param json the {@link JsonObject} to parse
     * @return a new {@link CrystalCell} instance
     * @throws IllegalArgumentException if required fields are missing or invalid
     */
    public static CrystalCell fromJson(JsonObject json) {
        for (String field : java.util.List.of("a", "b", "c", "alpha", "beta", "gamma")) {
            if (!json.has(field) || json.get(field).isJsonNull()) {
                throw new IllegalArgumentException("Missing required crystal field '" + field + "'");
            }
            if (!json.get(field).isJsonPrimitive() || !json.getAsJsonPrimitive(field).isNumber()) {
                throw new IllegalArgumentException("Crystal field '" + field + "' must be a number");
            }
        }
        return new CrystalCell(
                json.get("a").getAsDouble(),
                json.get("b").getAsDouble(),
                json.get("c").getAsDouble(),
                json.get("alpha").getAsDouble(),
                json.get("beta").getAsDouble(),
                json.get("gamma").getAsDouble(),
                json.has("origin_x") ? json.get("origin_x").getAsDouble() : 0,
                json.has("origin_y") ? json.get("origin_y").getAsDouble() : 0,
                json.has("origin_z") ? json.get("origin_z").getAsDouble() : 0
        );
    }

    /**
     * Extracts a crystal cell from a molecule definition's inspection metadata, if present.
     *
     * @param definition the molecule definition to inspect
     * @return the parsed {@link CrystalCell}, or {@code null} if not found
     */
    public static CrystalCell of(MoleculeDef definition) {
        String json = definition.inspection().metadata().get(METADATA_KEY);
        return json == null ? null : fromJson(JsonParser.parseString(json).getAsJsonObject());
    }
}