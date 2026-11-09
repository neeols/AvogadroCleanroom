package io.github.wurtzitane.avogadro.api.client;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Objects;

import com.google.gson.JsonObject;

import io.github.wurtzitane.avogadro.client.export.*;
import io.github.wurtzitane.avogadro.molecule.*;

import net.minecraft.util.ResourceLocation;

/**
 * Immutable export selection for media generation.
 * <p>
 * Arbitrary finite geometry need not be registered.
 */
public final class MediaExportRequest {

    /** The list of molecule definitions to be exported. */
    private final List<MoleculeDef> definitions;

    /** General export options controlling layout and dimensions. */
    private final ExportOptions options;

    /** Optional configuration specific to item exports. */
    private final ItemExportOptions item;

    /** Optional configuration specific to atomic inspection exports. */
    private final AtomExportOptions atom;

    /** Optional custom rendering options for the molecules. */
    private final MoleculeRenderOptions rendering;

    /** Optional reproduction metadata associated with the export. */
    private final JsonObject reproduction;

    /**
     * Creates a media export request from the given builder, validating all constraints.
     *
     * @param b the builder containing configuration values
     * @throws IllegalArgumentException if validation checks fail (e.g., mismatched counts, missing background, invalid
     *     options, or reproduction metadata exceeding 1 MiB)
     */
    private MediaExportRequest(Builder b) {
        definitions = List.copyOf(b.definitions);
        options = Objects.requireNonNull(b.options);

        if (definitions.isEmpty() || options.moleculeCount() != definitions.size()) {
            throw new IllegalArgumentException("Export selection and molecule count must agree");
        }

        item = b.item;
        atom = b.atom;
        rendering = b.rendering;

        if (item != null && !options.equals(item.media())) {
            throw new IllegalArgumentException("Item settings must match its layout");
        }
        if (atom != null && atom.details() && options.background() == null) {
            throw new IllegalArgumentException("Atom details require a solid background");
        }
        if (atom != null && rendering != null) {
            throw new IllegalArgumentException(
                    "Molecule rendering options are not supported by atomic inspection exports");
        }

        reproduction = b.reproduction == null ? null : b.reproduction.deepCopy();
        if (reproduction != null && reproduction.toString().getBytes(StandardCharsets.UTF_8).length > 1024 * 1024) {
            throw new IllegalArgumentException("Reproduction metadata exceeds one MiB");
        }
    }

    /**
     * Creates a builder for exporting a list of molecule definitions.
     *
     * @param definitions the list of molecule definitions
     * @return a new {@link Builder}
     */
    public static Builder molecules(List<MoleculeDef> definitions) {
        return new Builder(definitions);
    }

    /**
     * Creates a builder for exporting a single molecule definition.
     *
     * @param definition the molecule definition
     * @return a new {@link Builder}
     */
    public static Builder molecule(MoleculeDef definition) {
        return molecules(List.of(definition));
    }

    /**
     * Exports an exact successful realization and saves its reproduction metadata beside the media.
     *
     * @param preview the polymer preview containing structure, formula, and reproduction data
     * @param name the name of the export
     * @return a configured {@link Builder}
     */
    public static Builder polymer(io.github.wurtzitane.avogadro.api.PolymerPreview preview, String name) {
        Objects.requireNonNull(preview, "preview");
        return structure(preview.structure(), name, preview.formula(), preview.reproduction());
    }

    /**
     * Creates a builder for a specific molecule structure with custom formatting metadata.
     *
     * @param structure the molecule structure to render
     * @param name the export name
     * @param formula the molecular formula
     * @param reproduction optional reproduction metadata object
     * @return a configured {@link Builder}
     */
    public static Builder structure(MoleculeStructure structure, String name, String formula, JsonObject reproduction) {
        MoleculeDef definition = new MoleculeDef(new ResourceLocation("avogadro", "export_preview"),
                Objects.requireNonNull(structure), Objects.requireNonNull(formula), List.of(), List.of(), List.of(),
                List.of(), List.of(), false, name, false);
        Builder builder = molecule(definition);
        builder.reproduction = reproduction == null ? null : reproduction.deepCopy();
        return builder;
    }

    /**
     * Creates a builder configured for an item export.
     *
     * @param item the item export options
     * @return a configured {@link Builder}
     */
    public static Builder item(ItemExportOptions item) {
        Builder b = molecule(item.selection().definition());
        b.item = item;
        b.options = item.media();
        return b;
    }

    /**
     * Creates a builder configured for an atomic inspection export.
     *
     * @param atom the atom export options
     * @return a configured {@link Builder}
     */
    public static Builder atom(AtomExportOptions atom) {
        Builder b = molecule(atom.definition());
        b.atom = atom;
        return b;
    }

    /**
     * Returns the immutable list of molecule definitions included in this request.
     *
     * @return the list of {@link MoleculeDef} instances
     */
    public List<MoleculeDef> definitions() {
        return definitions;
    }

    /**
     * Returns the general export options.
     *
     * @return the {@link ExportOptions}
     */
    public ExportOptions options() {
        return options;
    }

    /**
     * Returns the item export options, if applicable.
     *
     * @return the {@link ItemExportOptions}, or {@code null} if not an item export
     */
    public ItemExportOptions item() {
        return item;
    }

    /**
     * Returns the atom export options, if applicable.
     *
     * @return the {@link AtomExportOptions}, or {@code null} if not an atom export
     */
    public AtomExportOptions atom() {
        return atom;
    }

    /**
     * Returns custom molecule rendering options, if specified.
     *
     * @return the {@link MoleculeRenderOptions}, or {@code null} if using defaults
     */
    public MoleculeRenderOptions renderOptions() {
        return rendering;
    }

    /**
     * Returns a deep copy of the reproduction metadata associated with this request.
     *
     * @return a cloned {@link JsonObject} of the metadata, or {@code null} if none is set
     */
    public JsonObject reproduction() {
        return reproduction == null ? null : reproduction.deepCopy();
    }

    /**
     * Builds immutable {@link MediaExportRequest} instances.
     */
    public static final class Builder {

        /** The list of molecule definitions targeted by this builder. */
        private final List<MoleculeDef> definitions;

        /** Configured export options. */
        private ExportOptions options;

        /** Optional item export options. */
        private ItemExportOptions item;

        /** Optional atom export options. */
        private AtomExportOptions atom;

        /** Optional molecule rendering options. */
        private MoleculeRenderOptions rendering;

        /** Optional reproduction metadata. */
        private JsonObject reproduction;

        /**
         * Creates a builder with the specified molecule definitions.
         *
         * @param definitions the molecule definitions to include
         */
        private Builder(List<MoleculeDef> definitions) {
            this.definitions = List.copyOf(definitions);
            this.options = ExportOptions.builder(definitions.size()).build();
        }

        /**
         * Sets the export options for this request.
         *
         * @param value the {@link ExportOptions} to apply
         * @return this builder
         */
        public Builder options(ExportOptions value) {
            options = Objects.requireNonNull(value);
            return this;
        }

        /**
         * Sets custom molecule rendering options for this request.
         *
         * @param value the {@link MoleculeRenderOptions} to apply
         * @return this builder
         */
        public Builder renderOptions(MoleculeRenderOptions value) {
            rendering = Objects.requireNonNull(value);
            return this;
        }

        /**
         * Builds, validates, and returns an immutable {@link MediaExportRequest}.
         *
         * @return a new {@link MediaExportRequest}
         */
        public MediaExportRequest build() {
            return new MediaExportRequest(this);
        }
    }
}
