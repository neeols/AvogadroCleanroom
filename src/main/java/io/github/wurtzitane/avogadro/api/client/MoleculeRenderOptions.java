package io.github.wurtzitane.avogadro.api.client;

import io.github.wurtzitane.avogadro.Config;
import io.github.wurtzitane.avogadro.client.ElementPalette;
import java.util.Map;

/**
 * Immutable presentation snapshot.
 * <p>Coordinates use GUI pixels; angles use degrees.
 */
public final class MoleculeRenderOptions {

    /** The yaw rotation angle in degrees. */
    private final float yaw;

    /** The pitch rotation angle in degrees. */
    private final float pitch;

    /** The roll rotation angle in degrees. */
    private final float roll;

    /** The horizontal pan offset in pixels. */
    private final float panX;

    /** The vertical pan offset in pixels. */
    private final float panY;

    /** The zoom scale factor. */
    private final float zoom;

    /** The atom size scale factor. */
    private final float atomScale;

    /** The bond size scale factor. */
    private final float bondScale;

    /** The rotational speed in degrees per second. */
    private final double rotationSpeed;

    /** Nullable ARGB background color; null preserves the caller's existing background. */
    private final Integer background;

    /** Whether atoms are rendered in a cubic style. */
    private final boolean cubic;

    /** The element palette snapshot containing color definitions. */
    private final ElementPalette.Snapshot palette;

    /**
     * Constructs render options from the specified builder, validating all values.
     *
     * @param b the builder instance containing configuration parameters
     * @throws IllegalArgumentException if any numeric value is non-finite or non-positive where required
     */
    private MoleculeRenderOptions(Builder b) {
        yaw = finite(b.yaw, "yaw");
        pitch = finite(b.pitch, "pitch");
        roll = finite(b.roll, "roll");
        panX = finite(b.panX, "panX");
        panY = finite(b.panY, "panY");
        zoom = positive(b.zoom, "zoom");
        atomScale = positive(b.atomScale, "atomScale");
        bondScale = positive(b.bondScale, "bondScale");

        if (!Double.isFinite(b.rotationSpeed)) {
            throw new IllegalArgumentException("Rotation speed must be finite");
        }
        rotationSpeed = b.rotationSpeed;
        background = b.background;
        cubic = b.cubic;
        palette = ElementPalette.withOverrides(b.palette, b.colors);
    }

    /**
     * Creates a new builder for configuring molecule render options.
     *
     * @return a new {@link Builder} instance
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Retrieves the yaw angle.
     *
     * @return the yaw in degrees
     */
    public float yaw() {
        return yaw;
    }

    /**
     * Retrieves the pitch angle.
     *
     * @return the pitch in degrees
     */
    public float pitch() {
        return pitch;
    }

    /**
     * Retrieves the roll angle.
     *
     * @return the roll in degrees
     */
    public float roll() {
        return roll;
    }

    /**
     * Retrieves the horizontal pan offset.
     *
     * @return the pan X coordinate in pixels
     */
    public float panX() {
        return panX;
    }

    /**
     * Retrieves the vertical pan offset.
     *
     * @return the pan Y coordinate in pixels
     */
    public float panY() {
        return panY;
    }

    /**
     * Retrieves the zoom factor.
     *
     * @return the zoom scale
     */
    public float zoom() {
        return zoom;
    }

    /**
     * Retrieves the atom scale factor.
     *
     * @return the atom scale
     */
    public float atomScale() {
        return atomScale;
    }

    /**
     * Retrieves the bond scale factor.
     *
     * @return the bond scale
     */
    public float bondScale() {
        return bondScale;
    }

    /**
     * Retrieves the rotation speed.
     *
     * @return the rotation speed in degrees per second
     */
    public double rotationSpeed() {
        return rotationSpeed;
    }

    /**
     * Retrieves the nullable ARGB background color.
     *
     * @return the ARGB background color integer, or {@code null} to preserve the existing background
     */
    public Integer background() {
        return background;
    }

    /**
     * Checks whether cubic atom rendering is enabled.
     *
     * @return {@code true} if cubic rendering is enabled, {@code false} otherwise
     */
    public boolean cubic() {
        return cubic;
    }

    /**
     * Retrieves the element palette snapshot used for coloring.
     *
     * @return the {@link ElementPalette.Snapshot} instance
     */
    public ElementPalette.Snapshot palette() {
        return palette;
    }

    /**
     * Computes the yaw angle at a given elapsed time, incorporating rotation speed.
     *
     * @param elapsedSeconds the elapsed time in seconds
     * @return the calculated yaw angle normalized within 0 to 360 degrees
     * @throws IllegalArgumentException if elapsed time is non-finite or resulting angle is out of range
     */
    public float yawAt(double elapsedSeconds) {
        if (!Double.isFinite(elapsedSeconds)) {
            throw new IllegalArgumentException("Elapsed time must be finite");
        }
        double angle = yaw + rotationSpeed * elapsedSeconds;

        if (!Double.isFinite(angle)) {
            throw new IllegalArgumentException("Rotation exceeds finite range");
        }
        return (float) (angle % 360.0);
    }

    /**
     * Validates that a float value is finite.
     *
     * @param value the float value to check
     * @param field the name of the field for error reporting
     * @return the validated value
     * @throws IllegalArgumentException if the value is not finite
     */
    private static float finite(float value, String field) {
        if (!Float.isFinite(value)) {
            throw new IllegalArgumentException(field + " must be finite");
        }
        return value;
    }

    /**
     * Validates that a float value is finite and strictly positive.
     *
     * @param value the float value to check
     * @param field the name of the field for error reporting
     * @return the validated value
     * @throws IllegalArgumentException if the value is not finite or not positive
     */
    private static float positive(float value, String field) {
        if (finite(value, field) <= 0) {
            throw new IllegalArgumentException(field + " must be positive");
        }
        return value;
    }

    /**
     * Builder class for constructing immutable {@link MoleculeRenderOptions} instances.
     */
    public static final class Builder {

        private float yaw;
        private float pitch = 15;
        private float roll;
        private float panX;
        private float panY;
        private float zoom = 1;
        private float atomScale = 1;
        private float bondScale = 1;
        private double rotationSpeed;
        private Integer background;
        private boolean cubic = Config.CUBIC_ATOMS.get();
        private final ElementPalette.Snapshot palette = ElementPalette.snapshot();
        private Map<String, Integer> colors = Map.of();

        /**
         * Constructs a new builder with default rendering values.
         */
        private Builder() {}

        /**
         * Sets the camera orientation angles.
         *
         * @param yaw   the yaw rotation in degrees
         * @param pitch the pitch rotation in degrees
         * @param roll  the roll rotation in degrees
         * @return this builder instance for chaining
         */
        public Builder orientation(float yaw, float pitch, float roll) {
            this.yaw = yaw;
            this.pitch = pitch;
            this.roll = roll;
            return this;
        }

        /**
         * Sets the pan offsets.
         *
         * @param x the horizontal pan in pixels
         * @param y the vertical pan in pixels
         * @return this builder instance for chaining
         */
        public Builder pan(float x, float y) {
            panX = x;
            panY = y;
            return this;
        }

        /**
         * Sets the zoom level.
         *
         * @param value the zoom scale factor
         * @return this builder instance for chaining
         */
        public Builder zoom(float value) {
            zoom = value;
            return this;
        }

        /**
         * Sets the atom size scale.
         *
         * @param value the atom scale factor
         * @return this builder instance for chaining
         */
        public Builder atomScale(float value) {
            atomScale = value;
            return this;
        }

        /**
         * Sets the bond size scale.
         *
         * @param value the bond scale factor
         * @return this builder instance for chaining
         */
        public Builder bondScale(float value) {
            bondScale = value;
            return this;
        }

        /**
         * Configures whether atoms are rendered in a cubic style.
         *
         * @param value {@code true} for cubic atoms, {@code false} otherwise
         * @return this builder instance for chaining
         */
        public Builder cubic(boolean value) {
            cubic = value;
            return this;
        }

        /**
         * Sets a custom ARGB background color.
         *
         * @param argb the ARGB background color integer, or {@code null}
         * @return this builder instance for chaining
         */
        public Builder background(Integer argb) {
            background = argb;
            return this;
        }

        /**
         * Sets signed degrees per second. The default zero leaves the camera stationary.
         *
         * @param value the rotation speed in degrees per second
         * @return this builder instance for chaining
         */
        public Builder rotationSpeed(double value) {
            rotationSpeed = value;
            return this;
        }

        /**
         * Overrides specific RGB colors in this builder's captured palette without changing global preferences.
         *
         * @,param value a map of color overrides
         * @return this builder instance for chaining
         */
        public Builder palette(Map<String, Integer> value) {
            colors = Map.copyOf(value);
            return this;
        }

        /**
         * Builds and validates the immutable {@link MoleculeRenderOptions} snapshot.
         *
         * @return a new {@link MoleculeRenderOptions} instance
         */
        public MoleculeRenderOptions build() {
            return new MoleculeRenderOptions(this);
        }
    }
}