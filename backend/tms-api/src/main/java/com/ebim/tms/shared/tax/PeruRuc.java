package com.ebim.tms.shared.tax;

/**
 * The SUNAT check digit of a Peruvian RUC (ADR-024 part 2, item 3; ADR-027).
 *
 * <p>Eleven digits, prefixed {@code 10} (person), {@code 15}/{@code 17} (special) or {@code 20}
 * (company). The last digit is {@code 11 - (sum mod 11)} over the first ten with weights
 * {@code 5,4,3,2,7,6,5,4,3,2}, where 10 reads 0 and 11 reads 1. A pure function: what it refuses is a
 * typing mistake, never whether the taxpayer exists or is active.
 */
public final class PeruRuc {

    private static final int[] WEIGHTS = {5, 4, 3, 2, 7, 6, 5, 4, 3, 2};

    private PeruRuc() {
    }

    public static boolean isValid(String ruc) {
        if (ruc == null || !ruc.matches("^(10|15|17|20)[0-9]{9}$")) {
            return false;
        }
        return checkDigitOf(ruc.substring(0, 10)) == ruc.charAt(10) - '0';
    }

    /** The check digit the first ten digits call for. */
    static int checkDigitOf(String firstTen) {
        int sum = 0;
        for (int i = 0; i < WEIGHTS.length; i++) {
            sum += (firstTen.charAt(i) - '0') * WEIGHTS[i];
        }
        int digit = 11 - (sum % 11);
        if (digit == 10) {
            return 0;
        }
        return digit == 11 ? 1 : digit;
    }
}
