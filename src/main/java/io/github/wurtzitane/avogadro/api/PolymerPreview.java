package io.github.wurtzitane.avogadro.api;

import java.util.Map;

import com.google.gson.JsonObject;

import io.github.wurtzitane.avogadro.molecule.MoleculeStructure;

/**
 * One completed polymer-network model and the settings used to build it.
 *
 * <p>
 * Use {@link #fragment()} to inspect or edit its atoms and bonds, {@link #formula()} for its finite formula, and
 * {@link #reproduction()} to save enough information to build the same model again.
 */
public final class PolymerPreview {

    /** The realized structure fragment representing the network preview geometry. */
    private final StructureFragment fragment;

    /** The exact network preview request parameters and seed used to generate this preview. */
    private final NetworkPreviewRequest request;

    /** Diagnostic statistics and metrics collected during network assembly. */
    private final Map<String, Integer> diagnostics;

    /** JSON object containing reproduction and verification metadata for the preview. */
    private final JsonObject reproduction;

    /**
     * Creates a {@link PolymerPreview} with realized geometry, request details, diagnostics, and reproduction data.
     *
     * @param fragment the realized {@link StructureFragment}
     * @param request the {@link NetworkPreviewRequest} used
     * @param diagnostics map of diagnostic metrics
     * @param reproduction the reproduction {@link JsonObject}
     */
    PolymerPreview(StructureFragment fragment, NetworkPreviewRequest request, Map<String, Integer> diagnostics,
            JsonObject reproduction) {
        this.fragment = fragment;
        this.request = request;
        this.diagnostics = Map.copyOf(diagnostics);
        this.reproduction = reproduction.deepCopy();
    }

    /**
     * Returns the realized structure fragment.
     *
     * @return the {@link StructureFragment}
     */
    public StructureFragment fragment() {
        return fragment;
    }

    /**
     * Converts the realized fragment into a compiled molecule structure.
     *
     * @return a new {@link MoleculeStructure} for the network preview
     */
    public MoleculeStructure structure() {
        return fragment.toStructure("avogadro:network_preview");
    }

    /**
     * Returns the exact network preview request that produced this preview.
     *
     * @return the {@link NetworkPreviewRequest}
     */
    public NetworkPreviewRequest request() {
        return request;
    }

    /**
     * Returns diagnostic metrics and statistics collected during assembly.
     *
     * @return an immutable map of diagnostic key-value counts
     */
    public Map<String, Integer> diagnostics() {
        return diagnostics;
    }

    /**
     * Computes the chemical formula string for this preview structure.
     *
     * @return formula string
     */
    public String formula() {
        return io.github.wurtzitane.avogadro.molecule.MoleculeLoader.formula(structure());
    }

    /**
     * Calculates the molecular weight and mass results for this preview structure.
     *
     * @return the calculated {@link MoleculeWeight.Result}
     */
    public MoleculeWeight.Result weight() {
        return MoleculeWeight.calculate(structure());
    }

    /**
     * Returns a deep copy of the reproduction JSON object.
     *
     * @return a new {@link JsonObject} containing reproduction metadata
     */
    public JsonObject reproduction() {
        return reproduction.deepCopy();
    }
}
