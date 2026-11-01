package io.github.wurtzitane.avogadro.api;

/**
 * Native-font chemical typography formatting.
 * <p>This changes presentation only, never parsed chemistry or saved data.
 */
public final class ChemicalFormula {

    /** Subscript character lookup table for numbers 0–9. */
    private static final String SUB = "₀₁₂₃₄₅₆₇₈₉";

    /** Superscript character lookup table for numbers 0–9. */
    private static final String SUPER = "⁰¹²³⁴⁵⁶⁷⁸⁹";

    /**
     * Private constructor to prevent instantiation of this utility class.
     */
    private ChemicalFormula() {}

    /**
     * Converts a raw formula string into beautifully formatted chemical typography
     * using Unicode subscripts and superscripts.
     *
     * @param formula the raw chemical formula string to format
     * @return the formatted formula string with typography applied, or the original if null/empty
     */
    public static String display(String formula) {
        if (formula == null || formula.isEmpty()) {
            return formula;
        }

        StringBuilder out = new StringBuilder(formula.length());

        for (int i = 0; i < formula.length();) {
            char c = formula.charAt(i);

            // Handle color/formatting codes (e.g. §r, §l)
            if (c == '§' && i + 1 < formula.length()) {
                out.append(c).append(formula.charAt(i + 1));
                i += 2;
                continue;
            }

            // Handle explicit superscript notation starting with '^'
            if (c == '^') {
                i++;
                boolean braces = i < formula.length() && formula.charAt(i) == '{';
                if (braces) {
                    i++;
                }
                while (i < formula.length()) {
                    char value = formula.charAt(i);
                    if (value >= '0' && value <= '9') {
                        out.append(SUPER.charAt(value - '0'));
                    } else if (value == '+') {
                        out.append('⁺');
                    } else if (value == '-') {
                        out.append('⁻');
                    } else {
                        break;
                    }
                    i++;
                }
                if (braces && i < formula.length() && formula.charAt(i) == '}') {
                    i++;
                }
                continue;
            }

            // Handle digit sequences (subscripts, isotopes, or charges)
            if (c >= '0' && c <= '9') {
                int end = i + 1;
                while (end < formula.length() && formula.charAt(end) >= '0' && formula.charAt(end) <= '9') {
                    end++;
                }

                char previous = i == 0 ? 0 : formula.charAt(i - 1);
                boolean isotope = previous == '[' && end < formula.length() && Character.isUpperCase(formula.charAt(end));
                boolean charge = end + 1 == formula.length()
                        && (formula.charAt(end) == '+' || formula.charAt(end) == '-')
                        && (previous == ']' || previous == '}' || formula.substring(0, i).matches("[A-Z][a-z]?"));
                boolean count = Character.isLetter(previous) || previous == ')' || previous == ']' || previous == '}';

                for (int digit = i; digit < end; digit++) {
                    char value = formula.charAt(digit);
                    out.append(isotope || charge ? SUPER.charAt(value - '0') : count ? SUB.charAt(value - '0') : value);
                }
                i = end;
                continue;
            }

            // Handle trailing charges at the end of the string
            if ((c == '+' || c == '-') && i == formula.length() - 1) {
                out.append(c == '+' ? '⁺' : '⁻');
            } else {
                out.append(c);
            }
            i++;
        }

        return out.toString();
    }
}