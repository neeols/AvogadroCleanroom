package io.github.wurtzitane.avogadro.api;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * Built-in element and isotope data, including names, atomic numbers, and electron shell counts.
 */
public final class ElementCatalog {

    /** Mapping of element symbols to their bundled fact attribute maps. */
    private static final Map<String, Map<String, String>> FACTS;

    /** Immutable list of bundled reference elements ordered by atomic number. */
    private static final List<Element> ELEMENTS;

    /** Mapping of element symbols to their corresponding bundled reference elements. */
    private static final Map<String, Element> BY_SYMBOL;

    /** Volatile container tracking host-registered extension elements, facts, and revision states. */
    private static volatile Extensions extensions = new Extensions(Map.of(), Map.of(), Map.of(), List.of(), 0);

    /**
     * Internal record holding state and lookup maps for host-registered extension elements.
     *
     * @param symbols map of symbol to extension element
     * @param numbers map of atomic number to extension element
     * @param facts map of symbol to extension fact mappings
     * @param elements list of all extension elements sorted by atomic number
     * @param revision tracking revision counter incremented upon changes
     */
    private record Extensions(Map<String, Element> symbols, Map<Integer, Element> numbers,
            Map<String, Map<String, String>> facts, List<Element> elements, long revision) {
    }

    static {
        JsonObject facts = read("/assets/avogadro/elements.json");
        JsonObject isotopeData = read("/data/avogadro/reference/isotopes.json").getAsJsonObject("elements");
        var factsBySymbol = new LinkedHashMap<String, Map<String, String>>();
        var elements = new ArrayList<Element>();

        for (var entry : facts.entrySet()) {
            String symbol = entry.getKey();
            JsonObject value = entry.getValue().getAsJsonObject();
            var fields = new LinkedHashMap<String, String>();
            value.entrySet().forEach(field -> fields.put(field.getKey(), field.getValue().getAsString()));
            factsBySymbol.put(symbol, Map.copyOf(fields));

            JsonObject nuclear = isotopeData.getAsJsonObject(symbol);
            var shells = new ArrayList<Integer>();
            nuclear.getAsJsonArray("electron_shells").forEach(shell -> shells.add(shell.getAsInt()));

            var isotopes = new ArrayList<Isotope>();
            for (var isotope : nuclear.getAsJsonArray("isotopes")) {
                JsonObject item = isotope.getAsJsonObject();
                isotopes.add(new Isotope(item.get("mass_number").getAsInt(), item.get("mass_estimated").getAsBoolean(),
                        item.get("half_life_estimated").getAsBoolean(), item.get("half_life").getAsString(),
                        item.get("half_life_unit").getAsString()));
            }

            elements.add(new Element(value.get("number").getAsInt(), symbol, fields.get("name"), fields.get("mass"),
                    fields.get("configuration"), fields.get("category"),
                    0xFF000000 | Integer.parseInt(fields.get("color").substring(1), 16),
                    nuclear.get("reference_mass_number").getAsInt(), shells, isotopes));
        }

        elements.sort(java.util.Comparator.comparingInt(Element::atomicNumber));

        if (elements.size() != 118 || elements.stream().mapToInt(element -> element.isotopes().size()).sum() != 3339) {
            throw new IllegalStateException("Incomplete element reference catalog");
        }

        var symbols = new LinkedHashMap<String, Element>();
        for (int i = 0; i < elements.size(); i++) {
            Element element = elements.get(i);
            if (element.atomicNumber() != i + 1 || symbols.put(element.symbol(), element) != null) {
                throw new IllegalStateException("Invalid element ordering or duplicate symbol");
            }
            new AtomicConfiguration(element.atomicNumber(), element.referenceMassNumber() - element.atomicNumber(), 0,
                    element.electronShells());
        }

        FACTS = Map.copyOf(factsBySymbol);
        ELEMENTS = List.copyOf(elements);
        BY_SYMBOL = Map.copyOf(symbols);
    }

    private ElementCatalog() {}

    /**
     * Returns all bundled reference elements in atomic-number order, from hydrogen through oganesson.
     *
     * @return an immutable list of reference elements
     */
    public static List<Element> elements() {
        return ELEMENTS;
    }

    /**
     * Returns all host-defined extension elements registered at runtime.
     *
     * @return an immutable list of additional elements
     */
    public static List<Element> additionalElements() {
        return extensions.elements();
    }

    /**
     * Returns the current revision count of host-registered elements.
     *
     * @return the revision tracking number
     */
    public static long revision() {
        return extensions.revision();
    }

    /**
     * Registers host-defined elements without replacing the bundled 118-element reference.
     *
     * @param element the element definition to register
     * @param facts a map of supplementary fact strings for the element
     * @throws IllegalArgumentException if the atomic number or symbol is invalid or already registered
     */
    public static synchronized void register(Element element, Map<String, String> facts) {
        java.util.Objects.requireNonNull(element);
        java.util.Objects.requireNonNull(facts);

        if (element.atomicNumber() < 0 || (element.atomicNumber() >= 1 && element.atomicNumber() <= 118)
                || !element.symbol().matches("[A-Z][a-z]?")) {
            throw new IllegalArgumentException(
                    "Additional elements require atomic number zero or above 118 and a symbol");
        }

        new AtomicConfiguration(element.atomicNumber(), element.referenceMassNumber() - element.atomicNumber(), 0,
                element.electronShells());

        Extensions previous = extensions;
        Element numbered = previous.numbers().get(element.atomicNumber());
        Element named = previous.symbols().get(element.symbol());

        if (BY_SYMBOL.containsKey(element.symbol()) || (numbered != null && !numbered.symbol().equals(element.symbol()))
                || (named != null && named.atomicNumber() != element.atomicNumber())) {
            throw new IllegalArgumentException("Element symbol or atomic number is already registered");
        }

        Map<String, String> details = new LinkedHashMap<>(facts);
        details.put("number", Integer.toString(element.atomicNumber()));
        details.put("name", element.name());
        details.put("mass", element.mass());
        details.put("configuration", element.configuration());
        details.put("category", element.category());
        details.put("color", String.format(java.util.Locale.ROOT, "#%06X", element.color() & 0xFFFFFF));
        details = Map.copyOf(details);

        if (element.equals(named) && details.equals(previous.facts().get(element.symbol()))) {
            return;
        }

        var symbols = new LinkedHashMap<>(previous.symbols());
        var numbers = new LinkedHashMap<>(previous.numbers());
        var fields = new LinkedHashMap<>(previous.facts());

        symbols.put(element.symbol(), element);
        numbers.put(element.atomicNumber(), element);
        fields.put(element.symbol(), details);

        List<Element> extra = numbers.values().stream().sorted(java.util.Comparator.comparingInt(Element::atomicNumber))
                .toList();

        extensions = new Extensions(Map.copyOf(symbols), Map.copyOf(numbers), Map.copyOf(fields), extra,
                previous.revision() + 1);
    }

    /**
     * Returns an element by its symbol. Symbols are case-sensitive, as in molecule definitions.
     *
     * @param symbol the element symbol (e.g., "H", "Fe")
     * @return the corresponding {@link Element}
     * @throws IllegalArgumentException if the symbol is unknown
     */
    public static Element bySymbol(String symbol) {
        Element element = symbol == null ? null : BY_SYMBOL.get(symbol);
        if (element == null && symbol != null) {
            element = extensions.symbols().get(symbol);
        }
        if (element == null) {
            throw new IllegalArgumentException("Unknown element symbol: " + symbol);
        }
        return element;
    }

    /**
     * Returns an element by its atomic number.
     *
     * @param atomicNumber the atomic number
     * @return the corresponding {@link Element}
     * @throws IllegalArgumentException if the atomic number is unknown
     */
    public static Element byAtomicNumber(int atomicNumber) {
        if (atomicNumber >= 1 && atomicNumber <= ELEMENTS.size()) {
            return ELEMENTS.get(atomicNumber - 1);
        }
        Element element = extensions.numbers().get(atomicNumber);
        if (element == null) {
            throw new IllegalArgumentException("Unknown atomic number: " + atomicNumber);
        }
        return element;
    }

    /**
     * Returns a specific fact string for an element symbol.
     * <p>
     * Missing facts remain empty so cards can also show host-authored synthetic elements.
     *
     * @param symbol the element symbol
     * @param key the fact key
     * @return the fact string, or an empty string if not found
     */
    public static String fact(String symbol, String key) {
        if (symbol == null || key == null) {
            return "";
        }
        Map<String, String> fields = FACTS.get(symbol);
        if (fields == null) {
            fields = extensions.facts().get(symbol);
        }
        return fields == null ? "" : fields.getOrDefault(key, "");
    }

    /**
     * Returns the theme color associated with an element symbol, using a default fallback color.
     *
     * @param symbol the element symbol
     * @return the ARGB color integer
     */
    public static int color(String symbol) {
        return color(symbol, 0xFFE3E3DE);
    }

    /**
     * Returns the theme color associated with an element symbol, specifying a custom fallback color.
     *
     * @param symbol the element symbol
     * @param fallback the fallback ARGB color if the element is unknown
     * @return the ARGB color integer
     */
    public static int color(String symbol, int fallback) {
        Element element = symbol == null ? null : BY_SYMBOL.get(symbol);
        if (element == null && symbol != null) {
            element = extensions.symbols().get(symbol);
        }
        return element == null ? fallback : element.color();
    }

    /**
     * Creates an atomic configuration for a neutral atom of a listed isotope.
     * <p>
     * Mass number is not inferred from atomic weight.
     *
     * @param symbol the element symbol
     * @param massNumber the specific isotope mass number
     * @return the corresponding {@link AtomicConfiguration}
     * @throws IllegalArgumentException if the isotope is not present in the catalog
     */
    public static AtomicConfiguration configuration(String symbol, int massNumber) {
        Element element = bySymbol(symbol);
        if (element.isotopes().stream().noneMatch(isotope -> isotope.massNumber() == massNumber)) {
            throw new IllegalArgumentException(
                    "Isotope is not in the NUBASE2020 catalog: " + symbol + "-" + massNumber);
        }
        return new AtomicConfiguration(element.atomicNumber(), massNumber - element.atomicNumber(), 0,
                element.electronShells());
    }

    /**
     * Reads a JSON resource file from the classpath.
     *
     * @param path the resource path string
     * @return the parsed {@link JsonObject}
     */
    private static JsonObject read(String path) {
        try (var stream = ElementCatalog.class.getResourceAsStream(path)) {
            if (stream == null) {
                throw new IllegalStateException("Missing element reference resource: " + path);
            }
            return JsonParser.parseReader(new InputStreamReader(stream, StandardCharsets.UTF_8)).getAsJsonObject();
        } catch (Exception failure) {
            throw new IllegalStateException("Cannot read element reference resource: " + path, failure);
        }
    }

    /**
     * Represents a chemical element, containing its basic properties, electron shells, and known isotopes.
     *
     * @param atomicNumber the atomic number (number of protons)
     * @param symbol the chemical symbol (e.g., "H")
     * @param name the full element name
     * @param mass the standard atomic weight representation
     * @param configuration the electron configuration string
     * @param category the periodic table category
     * @param color the ARGB display color
     * @param referenceMassNumber the reference isotope mass number
     * @param electronShells the electron population per shell
     * @param isotopes the list of known isotopes
     */
    public record Element(int atomicNumber, String symbol, String name, String mass, String configuration,
            String category, int color, int referenceMassNumber, List<Integer> electronShells, List<Isotope> isotopes) {

        /**
         * Compact constructor for {@link Element} that validates shell/isotope lists and reference presence.
         *
         * @throws IllegalArgumentException if isotopes are not uniquely sorted or the reference isotope is absent
         */
        public Element {
            electronShells = List.copyOf(electronShells);
            isotopes = List.copyOf(isotopes);
            int previous = 0;
            boolean referencePresent = false;
            for (Isotope isotope : isotopes) {
                if (isotope.massNumber() <= previous) {
                    throw new IllegalArgumentException("Isotopes must be unique and sorted by mass number");
                }
                previous = isotope.massNumber();
                referencePresent |= previous == referenceMassNumber;
            }
            if (!referencePresent) {
                throw new IllegalArgumentException("Reference isotope is absent from catalog");
            }
        }
    }

    /**
     * Represents a specific isotope entry.
     * <p>
     * Raw NUBASE half-life text retains limits and '#' estimate markers; 'stbl' means stable.
     *
     * @param massNumber the isotope mass number
     * @param massEstimated whether the mass is estimated
     * @param halfLifeEstimated whether the half-life is estimated
     * @param halfLife the half-life duration text
     * @param halfLifeUnit the unit of the half-life
     */
    public record Isotope(int massNumber, boolean massEstimated, boolean halfLifeEstimated, String halfLife,
            String halfLifeUnit) {
    }
}
