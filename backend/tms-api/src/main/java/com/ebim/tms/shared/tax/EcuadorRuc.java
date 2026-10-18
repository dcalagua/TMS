package com.ebim.tms.shared.tax;

/**
 * The SRI check of an Ecuadorian RUC (ADR-027). Thirteen digits:
 *
 * <ul>
 *   <li>digits 1-2: the province, {@code 01}-{@code 24}, or {@code 30} (registered abroad);
 *   <li>digit 3: the taxpayer type - {@code 0}-{@code 5} a natural person, {@code 6} a public entity,
 *       {@code 9} a private company or a foreign one; {@code 7} and {@code 8} do not exist;
 *   <li>a check digit, computed by type:
 *       <ul>
 *         <li>natural person: the cédula's modulo 10 over digits 1-9 with coefficients
 *             {@code 2,1,2,1,2,1,2,1,2} (a product above 9 loses 9); the check is digit 10;
 *         <li>public entity: modulo 11 over digits 1-8 with weights {@code 3,2,7,6,5,4,3,2}; the check
 *             ({@code 11 - sum mod 11}, 11 reads 0, 10 is impossible) is digit 9;
 *         <li>private company: modulo 11 over digits 1-9 with weights {@code 4,3,2,7,6,5,4,3,2}; the
 *             check, read the same way, is digit 10;
 *       </ul>
 *   <li>the establishment: the last three digits, never {@code 000} (the main one is {@code 001}; a
 *       public entity's is printed {@code 0001}).
 * </ul>
 *
 * <p>A pure function. What it refuses is a typing mistake; whether the RUC is active is the SRI's
 * question, which TMS does not ask.
 */
public final class EcuadorRuc {

    private static final int[] NATURAL_COEFFICIENTS = {2, 1, 2, 1, 2, 1, 2, 1, 2};
    private static final int[] PUBLIC_WEIGHTS = {3, 2, 7, 6, 5, 4, 3, 2};
    private static final int[] PRIVATE_WEIGHTS = {4, 3, 2, 7, 6, 5, 4, 3, 2};

    private EcuadorRuc() {
    }

    public static boolean isValid(String ruc) {
        if (ruc == null || !ruc.matches("^[0-9]{13}$")) {
            return false;
        }
        int province = Integer.parseInt(ruc.substring(0, 2));
        if (!((province >= 1 && province <= 24) || province == 30)) {
            return false;
        }
        if (ruc.endsWith("000")) {
            return false;
        }
        int type = ruc.charAt(2) - '0';
        if (type <= 5) {
            return naturalCheckDigit(ruc) == digit(ruc, 9);
        }
        if (type == 6) {
            Integer check = modulo11(ruc, PUBLIC_WEIGHTS);
            return check != null && check == digit(ruc, 8) && digit(ruc, 9) == 0;
        }
        if (type == 9) {
            Integer check = modulo11(ruc, PRIVATE_WEIGHTS);
            return check != null && check == digit(ruc, 9);
        }
        return false;
    }

    /** The cédula's modulo-10 check digit over digits 1-9. */
    static int naturalCheckDigit(String digits) {
        int sum = 0;
        for (int i = 0; i < NATURAL_COEFFICIENTS.length; i++) {
            int product = digit(digits, i) * NATURAL_COEFFICIENTS[i];
            sum += product > 9 ? product - 9 : product;
        }
        return (10 - sum % 10) % 10;
    }

    /** {@code 11 - (sum mod 11)}, where 11 reads 0; null when it is 10, which no valid RUC has. */
    static Integer modulo11(String digits, int[] weights) {
        int sum = 0;
        for (int i = 0; i < weights.length; i++) {
            sum += digit(digits, i) * weights[i];
        }
        int check = 11 - (sum % 11);
        if (check == 11) {
            return 0;
        }
        return check == 10 ? null : check;
    }

    private static int digit(String digits, int index) {
        return digits.charAt(index) - '0';
    }
}
