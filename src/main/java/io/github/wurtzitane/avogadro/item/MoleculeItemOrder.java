package io.github.wurtzitane.avogadro.item;

import java.util.Comparator;
import java.util.List;
import java.util.function.Function;

import io.github.wurtzitane.avogadro.Config;
import io.github.wurtzitane.avogadro.api.ElementCatalog;
import io.github.wurtzitane.avogadro.api.MoleculeClassification;
import io.github.wurtzitane.avogadro.molecule.MoleculeDef;

/** Presentation-only ordering; stored molecule identities are never changed. */
final class MoleculeItemOrder {

    private MoleculeItemOrder() {}

    /**
     * Groups and filters a list of molecule definitions based on split and element parameters.
     *
     * @param definitions list of molecule definitions
     * @param split whether to split tabs
     * @param elements whether to filter for elements
     * @return filtered list of molecule definitions
     */
    static List<MoleculeDef> group(List<MoleculeDef> definitions, boolean split, boolean elements) {
        if (!split)
            return definitions.stream().filter(MoleculeDef::generateItem).toList();
        return group(definitions, true, false, false,
                elements ? MoleculeClassification.ELEMENT : MoleculeClassification.MOLECULE);
    }

    /**
     * Groups and filters molecule definitions according to category classifications and tab target.
     *
     * @param definitions list of molecule definitions
     * @param elements whether elements tab is active
     * @param crystals whether crystals tab is active
     * @param polymers whether polymers tab is active
     * @param tab target molecule classification tab
     * @return filtered list of molecule definitions
     */
    static List<MoleculeDef> group(List<MoleculeDef> definitions, boolean elements, boolean crystals, boolean polymers,
            MoleculeClassification tab) {
        return definitions.stream().filter(MoleculeDef::generateItem)
                .filter(value -> destination(value, elements, crystals, polymers) == tab).toList();
    }

    /**
     * Determines the destination classification tab for a molecule definition.
     *
     * @param definition molecule definition
     * @param elements whether elements tab is active
     * @param crystals whether crystals tab is active
     * @param polymers whether polymers tab is active
     * @return destination molecule classification
     */
    private static MoleculeClassification destination(MoleculeDef definition, boolean elements, boolean crystals,
            boolean polymers) {
        return switch (MoleculeClassification.of(definition)) {
            case ELEMENT -> elements ? MoleculeClassification.ELEMENT : MoleculeClassification.MOLECULE;
            case CRYSTAL -> crystals ? MoleculeClassification.CRYSTAL : MoleculeClassification.MOLECULE;
            case POLYMER -> polymers ? MoleculeClassification.POLYMER : MoleculeClassification.MOLECULE;
            default -> MoleculeClassification.MOLECULE;
        };
    }

    /**
     * Returns the atomic number for an elemental molecule definition, or max integer value if non-elemental.
     *
     * @param definition molecule definition
     * @return atomic number integer value
     */
    static int atomicNumber(MoleculeDef definition) {
        if (io.github.wurtzitane.avogadro.api.MoleculeClassification
                .of(definition) != io.github.wurtzitane.avogadro.api.MoleculeClassification.ELEMENT)
            return Integer.MAX_VALUE;
        for (int i = 0; i < definition.structure().atoms().size(); i++) {
            var configured = definition.inspection().atom(definition.structure(), i);
            if (configured != null)
                return configured.atomicNumber();
        }
        if (definition.structure().atoms().isEmpty())
            return Integer.MAX_VALUE;
        String symbol = definition.structure().atoms().getFirst().element().symbol();
        String number = ElementCatalog.fact(symbol, "number");
        return number.isEmpty() ? Integer.MAX_VALUE : Integer.parseInt(number);
    }

    /**
     * Sorts a list of molecule definitions according to the specified creative sorting mode and name provider.
     *
     * @param definitions list of molecule definitions
     * @param mode creative sort mode configuration
     * @param names name resolution function
     * @return sorted list of molecule definitions
     */
    static List<MoleculeDef> sort(List<MoleculeDef> definitions, Config.CreativeSort mode,
            Function<MoleculeDef, String> names) {
        if (mode == Config.CreativeSort.REGISTRATION)
            return List.copyOf(definitions);
        Comparator<MoleculeDef> comparator = switch (mode) {
            case NAME -> Comparator.comparing(names, String.CASE_INSENSITIVE_ORDER);
            case ATOMIC_NUMBER -> Comparator.comparingInt(MoleculeItemOrder::atomicNumber);
            case FORMULA -> Comparator.comparing(MoleculeDef::formula, String.CASE_INSENSITIVE_ORDER);
            default -> Comparator.comparing(value -> value.id().toString());
        };
        return definitions.stream().sorted(comparator.thenComparing(value -> value.id().toString())).toList();
    }
}
