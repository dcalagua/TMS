package com.ebim.tms.orders.application.imports;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.Locale;

/**
 * Converts the text of one spreadsheet cell into a domain value, or explains in one sentence why
 * it cannot. Every method returns {@code null} for "absent" and throws {@link Rejected} for
 * "present but unusable", so a caller can tell the two apart without a second lookup.
 *
 * <p>The conversions are deliberately narrow. A spreadsheet import that guesses generously is
 * the classic way for 5 kg to become 5,000 - so each rule below either accepts an unambiguous
 * form or refuses with a message naming the form it wants.
 */
final class OrderImportValues {

    /** A cell that is present but cannot be converted. Carries the operator-facing sentence. */
    static final class Rejected extends RuntimeException {

        private static final long serialVersionUID = 1L;

        Rejected(String message) {
            super(message, null, false, false);
        }
    }

    private OrderImportValues() {}

    /**
     * Accepts ISO {@code yyyy-MM-dd}, and {@code dd/MM/yyyy} / {@code dd-MM-yyyy} for a file
     * typed by hand.
     *
     * <p>Day-first, not month-first, and that choice cannot be inferred from the data: {@code
     * 03/04/2026} is a valid date under both readings. V1 operates in Peru, where day-first is
     * what every operator writes and reads, so day-first is the rule and the message says so.
     * An XLSX date cell never reaches this branch - {@code OrderImportParser} renders it as ISO
     * before the validator sees it - so this only concerns text and CSV.
     */
    static LocalDate date(String raw) {
        String value = clean(raw);
        if (value == null) {
            return null;
        }
        try {
            if (value.length() == 10 && value.charAt(4) == '-') {
                return LocalDate.parse(value);
            }
            String[] parts = value.split("[/-]");
            if (parts.length == 3 && parts[2].length() == 4) {
                return LocalDate.of(Integer.parseInt(parts[2]), Integer.parseInt(parts[1]), Integer.parseInt(parts[0]));
            }
        } catch (RuntimeException unparseable) {
            throw new Rejected("'" + raw + "' is not a valid date. Use yyyy-MM-dd, for example 2026-03-01.");
        }
        throw new Rejected("'" + raw + "' is not a valid date. Use yyyy-MM-dd, for example 2026-03-01. "
                + "dd/MM/yyyy is also accepted and is read day first.");
    }

    /** Accepts {@code HH:mm} and {@code HH:mm:ss}; seconds are dropped, as the column stores none. */
    static LocalTime time(String raw) {
        String value = clean(raw);
        if (value == null) {
            return null;
        }
        try {
            String[] parts = value.split(":");
            if (parts.length == 2 || parts.length == 3) {
                return LocalTime.of(Integer.parseInt(parts[0]), Integer.parseInt(parts[1]));
            }
        } catch (RuntimeException unparseable) {
            throw new Rejected("'" + raw + "' is not a valid time. Use HH:mm, for example 08:30.");
        }
        throw new Rejected("'" + raw + "' is not a valid time. Use HH:mm, for example 08:30.");
    }

    /**
     * Accepts a decimal with a dot separator, and a decimal with a single comma separator
     * because that is what a Spanish-locale spreadsheet writes when it exports text.
     *
     * <p>A thousands separator is refused rather than stripped. {@code 1,234} is one thousand
     * two hundred thirty-four to one operator and 1.234 to the next, and no amount of context in
     * a single cell resolves that - so the import declines to guess and says which form it wants.
     * Numeric cells in an XLSX carry no separators at all and are unaffected.
     */
    static BigDecimal decimal(String raw) {
        String value = clean(raw);
        if (value == null) {
            return null;
        }
        // Ordinary, non-breaking (U+00A0) and thin (U+2009) spaces are what a spreadsheet's own
        // thousands grouping leaves behind when a cell is copied as text: whitespace, not data.
        // The last two are written as escapes because they are invisible in a source file.
        value = value.replace(" ", "").replace("\u00A0", "").replace("\u2009", "");
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

    /** Same as {@link #decimal}, refusing zero and negatives - a quantity or a unit measure. */
    static BigDecimal positiveDecimal(String raw) {
        BigDecimal value = decimal(raw);
        if (value != null && value.signum() <= 0) {
            throw new Rejected("'" + raw + "' must be greater than zero.");
        }
        return value;
    }

    /** Same as {@link #decimal}, refusing negatives but allowing an explicit zero. */
    static BigDecimal nonNegativeDecimal(String raw) {
        BigDecimal value = decimal(raw);
        if (value != null && value.signum() < 0) {
            throw new Rejected("'" + raw + "' cannot be negative.");
        }
        return value;
    }

    /** Case-insensitive, so {@code urgent} and {@code Urgent} both resolve. */
    static <E extends Enum<E>> E enumeration(String raw, Class<E> type, String allowed) {
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

    private static String clean(String raw) {
        if (raw == null) {
            return null;
        }
        String trimmed = raw.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
