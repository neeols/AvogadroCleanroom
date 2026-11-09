package io.github.wurtzitane.avogadro.api;

import java.util.Map;

/**
 * Settings for building one polymer-network model.
 *
 * <p>
 * Use the parameter names declared by {@link PolymerNetworkDefinition}. Missing values use that definition's defaults.
 * The seed fixes the outcome of random choices.
 *
 * @param counts repeat counts by parameter name
 * @param probabilities branch or crosslink chances, from 0 (never) to 1 (always)
 * @param seed a number used to repeat the same random choices
 */
public record NetworkPreviewRequest(Map<String, Integer> counts, Map<String, Double> probabilities, long seed) {

    /**
     * Compact constructor for {@link NetworkPreviewRequest} that creates immutable defensive copies of all internal
     * maps and validates that all probability values are finite and lie between 0.0 and 1.0 inclusive.
     *
     * @throws IllegalArgumentException if any probability value is non-finite or outside the range [0, 1]
     */
    public NetworkPreviewRequest {
        counts = Map.copyOf(counts);
        probabilities = Map.copyOf(probabilities);
        probabilities.values().forEach(value -> {
            if (!Double.isFinite(value) || value < 0 || value > 1) {
                throw new IllegalArgumentException("Probabilities must be finite and between zero and one");
            }
        });
    }
}
