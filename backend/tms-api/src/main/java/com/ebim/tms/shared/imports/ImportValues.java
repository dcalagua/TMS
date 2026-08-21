package com.ebim.tms.shared.imports;

import java.math.BigDecimal;
import java.util.Locale;

/**
 * Converts the text of one spreadsheet cell into a domain value, or explains in one sentence why
 * it cannot. Every method returns {@code null} for "absent" and throws {@link Rejected} for
 * "present but unusable", so a caller can tell the two apart without a second lookup.
 *
 * <p>Generalizes {@code OrderImportValues} (which predates this package and is left as it is) so
 * every entity import - Locations, Carriers, Vehicle Types, Vehicles - shares one set of narrow,
 * unambiguous conversions instead of four copies of them. The conversions stay narrow on purpose:
 * a spreadsheet import that guesses generously is the classic way for 5 kg to become 5,000.
 */
public final class ImportValues {

    /** A cell that is present but cannot be converted. Carries the operator-facing sentence. */
    public static final class Rejected extends RuntimeException {

        private static final long serialVersionUID = 1L;

        public Rejected(String message) {
            super(message, null, false, false);
        }
    }

    private ImportValues() {}

    /**
     * Accepts a decimal with a dot separator, and a decimal with a single comma separator because
     * that is what a Spanish-locale spreadsheet writes when it exports text.
     *
     * <p>A thousands separator is refused rather than stripped: {@code 1,234} is one thousand two
     * hundred thirty-four to one operator and 1.234 to the next, and no amount of context in a
     * single cell resolves that. Numeric cells in an XLSX carry no separators at all.
     */
    public static BigDecimal decimal(String raw) {
        String value = clean(raw);
        if (value == null) {
            return null;
        }
        // Ordinary, non-breaking (U+00A0) and thin (U+2009) spaces are what a spreadsheet's own
        // thousands grouping leaves behind when a cell is copied as text: whitespace, not data.
        // Written as code points rather than the literal characters, which are indistinguishable
        // from an ordinary space in a source file.
        value = value.replace(" ", "")
                .replace(String.valueOf((char) 0x00A0), "")
                .replace(String.valueOf((char) 0x2009), "");
        boolean hasDot = value.indexOf('.') >= 0;
        long commas = value.chars().filter(character -> character == ',').count();
        if (hasDot && commas > 0 || commas > 1) {
            throw new Rejected("'" + raw + "' has a thousands separator. Write the number plainly, "
                    + "for example 1234.5 instead of 1,234.5.");
        }
        if (commas == 1) {
            value = value.replace(',', '.');
        }
        try {
            return new BigDecimal(value);
        } catch (NumberFormatException unparseable) {
            throw new Rejected("'" + raw + "' is not a number.");
        }
    }

    /** Same as {@link #decimal}, refusing zero and negatives - a capacity or a quantity. */
    public static BigDecimal positiveDecimal(String raw) {
        BigDecimal value = decimal(raw);
        if (value != null && value.signum() <= 0) {
            throw new Rejected("'" + raw + "' must be greater than zero.");
        }
        return value;
    }

    /** Same as {@link #decimal}, refusing negatives but allowing an explicit zero. */
    public static BigDecimal nonNegativeDecimal(String raw) {
        BigDecimal value = decimal(raw);
        if (value != null && value.signum() < 0) {
            throw new Rejected("'" + raw + "' cannot be negative.");
        }
        return value;
    }

    /** A whole, non-negative number - a count or a duration in minutes. */
    public static Integer nonNegativeInteger(String raw) {
        String value = clean(raw);
        if (value == null) {
            return null;
        }
        try {
            int parsed = Integer.parseInt(value);
            if (parsed < 0) {
                throw new Rejected("'" + raw + "' cannot be negative.");
            }
            return parsed;
        } catch (NumberFormatException unparseable) {
            throw new Rejected("'" + raw + "' is not a whole number.");
        }
    }

    /** Case-insensitive, so {@code urgent} and {@code Urgent} both resolve. */
    public static <E extends Enum<E>> E enumeration(String raw, Class<E> type, String allowed) {
        String value = clean(raw);
        if (value == null) {
            return null;
        }
        try {
            return Enum.valueOf(type, value.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException unknown) {
            throw new Rejected("'" + raw + "' is not recognised. Use one of: " + allowed + ".");
        }
    }

    /** True/false, accepting yes/no and 1/0 as a spreadsheet author's shorthand. */
    public static Boolean bool(String raw) {
        String value = clean(raw);
        if (value == null) {
            return null;
        }
        String normalized = value.toLowerCase(Locale.ROOT);
        if (normalized.equals("true") || normalized.equals("yes") || normalized.equals("1")) {
            return Boolean.TRUE;
        }
        if (normalized.equals("false") || normalized.equals("no") || normalized.equals("0")) {
            return Boolean.FALSE;
        }
        throw new Rejected("'" + raw + "' is not recognised. Use TRUE or FALSE.");
    }

    public static String clean(String raw) {
        if (raw == null) {
            return null;
        }
        String trimmed = raw.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
