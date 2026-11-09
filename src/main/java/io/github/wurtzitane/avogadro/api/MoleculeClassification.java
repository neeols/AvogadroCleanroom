package io.github.wurtzitane.avogadro.api;

import java.util.Locale;

import io.github.wurtzitane.avogadro.molecule.MoleculeDef;

/**
 * Presentation grouping for molecules.
 * <p>
 * Elemental structures may contain arbitrarily many atoms of that element.
 */
public enum MoleculeClassification {

    /** Automatically determined classification based on structure or element composition. */
    AUTO,

    /** Elemental classification consisting of atoms of a single element symbol. */
    ELEMENT,

    /** Standard molecular classification consisting of multiple different elements. */
    MOLECULE,

    /** Crystal lattice classification. */
    CRYSTAL,

    /** Polymer or polymer network classification. */
    POLYMER,

    /** Mixture classification. */
    MIXTURE;

    /** Metadata key used to store or retrieve molecule classification data. */
    public static final String METADATA_KEY = "classification";

    /**
     * Determines the classification of a given molecule definition based on its metadata, structure, and composition.
     *
     * @param definition the {@link MoleculeDef} to inspect
     * @return the resolved {@link MoleculeClassification}
     */
    public static MoleculeClassification of(MoleculeDef definition) {
        if (definition.inspection().metadata().containsKey(PolymerNetworkDefinition.METADATA_KEY)) {
            return POLYMER;
        }
        if (definition.inspection().metadata().containsKey(PolymerDefinition.METADATA_KEY)) {
            return POLYMER;
        }
        if (definition.inspection().metadata().containsKey(CrystalCell.METADATA_KEY)) {
            return CRYSTAL;
        }

        String authored = definition.inspection().metadata().get(METADATA_KEY);
        if (authored != null) {
            try {
                var value = valueOf(authored.toUpperCase(Locale.ROOT));
                if (value != AUTO) {
                    return value;
                }
            } catch (IllegalArgumentException ignored) {
            }
        }

        String first = null;
        for (var atom : definition.structure().atoms()) {
            String symbol = atom.element().symbol();
            if (first == null) {
                first = symbol;
            } else if (!first.equals(symbol)) {
                return MOLECULE;
            }
        }

        return first == null ? MOLECULE : ELEMENT;
    }
}
