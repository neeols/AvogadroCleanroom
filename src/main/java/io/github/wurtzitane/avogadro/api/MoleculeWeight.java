package io.github.wurtzitane.avogadro.api;

import io.github.wurtzitane.avogadro.molecule.MoleculeDef;
import io.github.wurtzitane.avogadro.molecule.MoleculeStructure;

import java.util.*;

/**
 * Calculates average-composition masses, not isotope-specific masses.
 * <p>Contains no client or world dependencies.
 */
public final class MoleculeWeight {

    /** Exact SI value, <a href="https://www.bipm.org/en/measurement-units/si-defining-constants">BIPM SI Defining Constants</a>. */
    public static final double AVOGADRO_CONSTANT = 6.02214076e23;

    /** Map of element symbols to their abridged standard atomic weights (CIAAW 2024 central values). */
    private static final Map<String, Double> WEIGHTS = weights();

    /** Maximum formula coefficient or atom count limit. */
    private static final long LIMIT = 1_000_000_000L;

    /**
     * Private constructor to prevent instantiation of this utility class.
     */
    private MoleculeWeight() {}

    /**
     * Represents the calculated molar and individual entity mass results.
     *
     * @param molarMass      the molar mass in grams per mole (g/mol)
     * @param gramsPerEntity the absolute mass per individual entity in grams
     */
    public record Result(double molarMass, double gramsPerEntity) {

        /**
         * Formats the molar mass into a readable string with units.
         *
         * @return formatted molar mass string
         */
        public String molarMassText() {
            return String.format(Locale.ROOT, "%.5g g/mol", molarMass);
        }

        /**
         * Formats the individual entity mass into a scientific notation string with units.
         *
         * @return formatted entity mass string
         */
        public String entityMassText() {
            return String.format(Locale.ROOT, "%.4e g", gramsPerEntity);
        }
    }

    /**
     * Calculates the molecular weight result for a given molecule definition based on its classification.
     *
     * @param molecule the {@link MoleculeDef} to calculate weight for
     * @return the calculated {@link Result}
     * @throws IllegalArgumentException if the molecule classification is variable or lacks a unique mass
     */
    public static Result calculate(MoleculeDef molecule) {
        return switch (MoleculeClassification.of(molecule)) {
            case CRYSTAL -> calculate(molecule.formula());
            case MIXTURE -> throw new IllegalArgumentException("Variable mixtures have no unique molecular mass");
            case POLYMER -> throw new IllegalArgumentException("A polymer has no unique mass without a chain length and end groups");
            default -> calculate(molecule.structure());
        };
    }

    /**
     * Calculates the molecular weight result for a given molecule structure.
     *
     * @param structure the {@link MoleculeStructure} to calculate weight for
     * @return the calculated {@link Result}
     */
    public static Result calculate(MoleculeStructure structure) {
        return calculate(composition(structure));
    }

    /**
     * Calculates the molecular weight result for a given chemical formula string.
     *
     * @param formula the chemical formula string
     * @return the calculated {@link Result}
     */
    public static Result calculate(String formula) {
        return calculate(composition(formula));
    }

    /**
     * Internal helper to compute total mass and per-entity mass from an atomic composition map.
     *
     * @param counts map of element symbols to their atom counts
     * @return the calculated {@link Result}
     */
    private static Result calculate(Map<String, Long> counts) {
        double mass = 0;
        for (var entry : counts.entrySet()) {
            Double weight = WEIGHTS.get(entry.getKey());
            if (weight == null) {
                weight = hostAtomicWeight(entry.getKey());
            }
            if (weight == null) {
                throw new IllegalArgumentException("No standard atomic weight for " + entry.getKey());
            }
            mass += weight * entry.getValue();
        }
        return new Result(mass, mass / AVOGADRO_CONSTANT);
    }

    /**
     * Resolves atomic weight for host-defined or extended elements via the element catalog.
     *
     * @param symbol the element symbol
     * @return the atomic weight double, or {@code null} if not found or invalid
     */
    private static Double hostAtomicWeight(String symbol) {
        ElementCatalog.Element element;
        try {
            element = ElementCatalog.bySymbol(symbol);
        } catch (IllegalArgumentException unknownElement) {
            return null;
        }
        if (element.atomicNumber() >= 1 && element.atomicNumber() <= 118) {
            return null;
        }
        try {
            double mass = Double.parseDouble(element.mass());
            return Double.isFinite(mass) && mass > 0 ? mass : null;
        } catch (NumberFormatException unknownMass) {
            return null;
        }
    }

    /**
     * Computes the atomic composition map from a molecule structure.
     *
     * @param structure the {@link MoleculeStructure}
     * @return an unmodifiable map of element symbols to atom counts
     */
    public static Map<String, Long> composition(MoleculeStructure structure) {
        Map<String, Long> result = new TreeMap<>();
        for (var atom : structure.atoms()) {
            result.merge(atom.element().symbol(), 1L, Long::sum);
        }
        return Collections.unmodifiableMap(result);
    }

    /**
     * Parses a chemical formula string into an atomic composition map.
     * <p>Supports ASCII or subscript counts, nested ()/[] groups, and dot-separated hydrates.
     *
     * @param formula the chemical formula string
     * @return an unmodifiable map of element symbols to atom counts
     * @throws IllegalArgumentException if the formula is null, blank, too long, or syntactically invalid
     */
    public static Map<String, Long> composition(String formula) {
        if (formula == null || formula.isBlank() || formula.length() > 4096) {
            throw new IllegalArgumentException("A formula of 1 to 4096 characters is required");
        }
        StringBuilder normalized = new StringBuilder();
        for (char c : formula.toCharArray()) {
            if (c >= '₀' && c <= '₉') {
                c = (char) ('0' + c - '₀');
            }
            normalized.append(c);
        }
        Parser parser = new Parser(normalized.toString());
        Map<String, Long> result = new TreeMap<>();
        merge(result, parser.group('\0', 0), 1);
        while (parser.index < parser.text.length()) {
            char c = parser.text.charAt(parser.index++);
            if (c != '.' && c != '·') {
                throw parser.error();
            }
            long multiplier = parser.count();
            merge(result, parser.group('\0', 0), multiplier);
        }
        return Collections.unmodifiableMap(result);
    }

    /**
     * Merges source composition counts into a target map with a specified multiplier.
     *
     * @param target     the target composition map
     * @param source     the source composition map to merge
     * @param multiplier the multiplier factor for the counts
     * @throws IllegalArgumentException if counts overflow limits
     */
    private static void merge(Map<String, Long> target, Map<String, Long> source, long multiplier) {
        for (var entry : source.entrySet()) {
            long count;
            try {
                count = Math.addExact(target.getOrDefault(entry.getKey(), 0L),
                        Math.multiplyExact(entry.getValue(), multiplier));
            } catch (ArithmeticException failure) {
                throw new IllegalArgumentException("Formula count exceeds limit");
            }
            if (count > LIMIT) {
                throw new IllegalArgumentException("Formula count exceeds " + LIMIT);
            }
            target.put(entry.getKey(), count);
        }
    }

    /**
     * Internal recursive parser for chemical formula strings.
     */
    private static final class Parser {

        /** The formula text being parsed. */
        final String text;

        /** The current character index position. */
        int index;

        /**
         * Constructs a new formula parser for the given text.
         *
         * @param text the text to parse
         */
        Parser(String text) {
            this.text = text;
        }

        /**
         * Creates a parsing exception with the current position and text.
         *
         * @return an {@link IllegalArgumentException} error
         */
        IllegalArgumentException error() {
            return new IllegalArgumentException("Invalid or unsupported formula at position " + index + ": " + text);
        }

        /**
         * Parses a formula group enclosed by specified delimiters.
         *
         * @param closing the closing delimiter character, or '\0' for root level
         * @param depth   the current recursion depth
         * @return a map of element symbols to atom counts for this group
         */
        Map<String, Long> group(char closing, int depth) {
            if (depth > 32) {
                throw error();
            }
            Map<String, Long> result = new TreeMap<>();
            while (index < text.length()) {
                char c = text.charAt(index);
                if (closing != '\0' && c == closing) {
                    index++;
                    if (result.isEmpty()) {
                        throw error();
                    }
                    return result;
                }
                if (closing == '\0' && (c == '.' || c == '·')) {
                    break;
                }
                if (c == '(' || c == '[') {
                    index++;
                    Map<String, Long> nested = group(c == '(' ? ')' : ']', depth + 1);
                    merge(result, nested, count());
                } else if (c >= 'A' && c <= 'Z') {
                    int start = index++;
                    if (index < text.length() && text.charAt(index) >= 'a' && text.charAt(index) <= 'z') {
                        index++;
                    }
                    String symbol = text.substring(start, index);
                    merge(result, Map.of(symbol, count()), 1);
                } else {
                    throw error();
                }
            }
            if (closing != '\0' || result.isEmpty()) {
                throw error();
            }
            return result;
        }

        /**
         * Parses a numerical multiplier count following an element or group.
         *
         * @return the parsed count, defaulting to 1 if no number is present
         */
        long count() {
            int start = index;
            long result = 0;
            while (index < text.length() && text.charAt(index) >= '0' && text.charAt(index) <= '9') {
                result = result * 10 + text.charAt(index++) - '0';
                if (result > LIMIT) {
                    throw error();
                }
            }
            if (index == start) {
                return 1;
            }
            if (result == 0) {
                throw error();
            }
            return result;
        }
    }

    /**
     * Initializes the standard element weight lookup table.
     *
     * @return an immutable map of element symbols to standard atomic weights
     */
    private static Map<String, Double> weights() {
        String data = "H 1.0080 He 4.0026 Li 6.94 Be 9.0122 B 10.81 C 12.011 N 14.007 O 15.999 F 18.998 Ne 20.180 " +
                "Na 22.990 Mg 24.305 Al 26.982 Si 28.085 P 30.974 S 32.06 Cl 35.45 Ar 39.95 K 39.098 Ca 40.078 " +
                "Sc 44.956 Ti 47.867 V 50.942 Cr 51.996 Mn 54.938 Fe 55.845 Co 58.933 Ni 58.693 Cu 63.546 Zn 65.38 " +
                "Ga 69.723 Ge 72.630 As 74.922 Se 78.971 Br 79.904 Kr 83.798 Rb 85.468 Sr 87.62 Y 88.906 Zr 91.222 " +
                "Nb 92.906 Mo 95.95 Tc 98 Ru 101.07 Rh 102.91 Pd 106.42 Ag 107.87 Cd 112.41 In 114.82 Sn 118.71 " +
                "Sb 121.76 Te 127.60 I 126.90 Xe 131.29 Cs 132.91 Ba 137.33 La 138.91 Ce 140.12 Pr 140.91 Nd 144.24 " +
                "Pm 145 Sm 150.36 Eu 151.96 Gd 157.25 Tb 158.93 Dy 162.50 Ho 164.93 Er 167.26 Tm 168.93 Yb 173.05 " +
                "Lu 174.97 Hf 178.49 Ta 180.95 W 183.84 Re 186.21 Os 190.23 Ir 192.22 Pt 195.08 Au 196.97 Hg 200.59 " +
                "Tl 204.38 Pb 207.2 Bi 208.98 Po 209 At 210 Rn 222 Fr 223 Ra 226.03 Ac 227 Th 232.04 " +
                "Pa 231.04 U 238.03 Np 237.05 Pu 244.06 Am 243.06 Cm 247.07 Bk 247.07 Cf 251.08 Es 252.08 Fm 257.10 " +
                "Md 258.10 No 259.10 Lr 266 Rf 267 Db 268 Sg 269 Bh 270 Hs 277 Mt 278 Ds 281 " +
                "Rg 282 Cn 285 Nh 286 Fl 289 Mc 290 Lv 293 Ts 294 Og 294";
        String[] parts = data.split(" ");
        Map<String, Double> result = new HashMap<>();
        for (int i = 0; i < parts.length; i += 2) {
            result.put(parts[i], Double.parseDouble(parts[i + 1]));
        }
        return Map.copyOf(result);
    }
}