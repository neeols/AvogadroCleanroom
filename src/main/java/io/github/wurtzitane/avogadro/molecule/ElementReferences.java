package io.github.wurtzitane.avogadro.molecule;

import io.github.wurtzitane.avogadro.api.ElementCatalog;
import io.github.wurtzitane.avogadro.api.MoleculeBuilder;
import io.github.wurtzitane.avogadro.api.MoleculeClassification;
import net.minecraft.util.ResourceLocation;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/** Optional generated samples form the lowest-priority common definition layer. */
final class ElementReferences {

    /** Caches generated molecule resource data based on element and isotope configuration flags. */
    private static final Map<Integer, MoleculeResources.Data> CACHE = new LinkedHashMap<>();

    /** Private constructor to prevent instantiation of utility class. */
    private ElementReferences() {}

    /**
     * Retrieves or generates cached molecule resource data for chemical elements and isotopes.
     *
     * @param elements include base elements flag
     * @param isotopes include isotopes flag
     * @return generated {@link MoleculeResources.Data} instance
     */
    static synchronized MoleculeResources.Data data(boolean elements, boolean isotopes) {
        if (!elements && !isotopes) return MoleculeResources.Data.empty();
        int key = (elements ? 1 : 0) | (isotopes ? 2 : 0);
        return CACHE.computeIfAbsent(key, ignored -> generate(elements, isotopes));
    }

    /**
     * Generates molecule resource data containing JSON definitions for elements and isotopes.
     *
     * @param elements include base elements flag
     * @param isotopes include isotopes flag
     * @return constructed {@link MoleculeResources.Data} instance
     */
    private static MoleculeResources.Data generate(boolean elements, boolean isotopes) {
        Map<ResourceLocation, String> definitions = new LinkedHashMap<>();
        for (var element : ElementCatalog.elements()) {
            if (elements) add(definitions, element, element.referenceMassNumber(), false);
            if (isotopes) for (var isotope : element.isotopes()) add(definitions, element, isotope.massNumber(), true);
        }
        return new MoleculeResources.Data(definitions, Map.of(), Map.of());
    }

    /**
     * Builds and adds an individual element or isotope molecule definition to the resource map.
     *
     * @param definitions target resource location to JSON string definitions map
     * @param element     catalog element instance
     * @param massNumber  mass number integer
     * @param isotope     {@code true} if an isotope variant, {@code false} otherwise
     */
    private static void add(Map<ResourceLocation, String> definitions, ElementCatalog.Element element,
                            int massNumber, boolean isotope) {
        var atom = ElementCatalog.configuration(element.symbol(), massNumber);
        String path = "elements/" + element.symbol().toLowerCase(Locale.ROOT) + (isotope ? "_" + massNumber : "");
        var sample = new MoleculeBuilder("avogadro:" + path).atom(element.symbol(), 0, 0, 0).noBonds()
                .name(element.name() + (isotope ? "-" + massNumber : ""))
                .generateItem(true).classification(MoleculeClassification.ELEMENT)
                .element(element.symbol(), atom.atomicNumber(), atom.neutrons(),
                        atom.electronShells().stream().mapToInt(Integer::intValue).toArray())
                .inspectionMetadata("reference_isotope", element.symbol() + "-" + massNumber)
                .inspectionMetadata("source", "NUBASE2020").build();
        definitions.put(sample.id(), sample.json());
    }
}