package io.github.wurtzitane.avogadro.api;

import java.util.Map;

/**
 * Explicit inputs to a reproducible finite polymer realization.
 *
 * @param counts        map of component or monomer counts
 * @param probabilities map of probability values for linking or branching
 * @param seed          the random seed ensuring reproducibility
 */
public record NetworkPreviewRequest(Map<String, Integer> counts, Map<String, Double> probabilities, long seed) {

    /**
     * Compact constructor for {@link NetworkPreviewRequest} that creates immutable defensive copies
     * of all internal maps and validates that all probability values are finite and lie between 0.0 and 1.0 inclusive.
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