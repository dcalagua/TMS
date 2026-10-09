package com.ebim.tms.iam.entitlements.domain;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * RFC 8785 canonical JSON (JCS) and the checksum of {@code ebim.entitlements/v1}.
 *
 * <h2>Why an implementation of our own</h2>
 *
 * <p>Decision P-04 of the CCP plan: one small class, held to EVERY vector of the contract
 * ({@code jcs-vectors.json}), instead of a new dependency for two hundred lines. MasterAdmin computes
 * the checksum in TypeScript and in SQL; if this one differed by a single byte every PUT would be a
 * {@code CHECKSUM_MISMATCH} and nothing could ever be synchronised.
 *
 * <h2>The three rules that matter</h2>
 *
 * <ul>
 *   <li><b>Keys</b> sorted by UTF-16 code units - exactly {@link String#compareTo}.</li>
 *   <li><b>Strings</b> escaped as {@code JSON.stringify} does: {@code \"}, {@code \\},
 *       {@code \b \f \n \r \t}, other controls and lone surrogates as {@code \\u} + four lower-case
 *       hex digits; everything else literal.</li>
 *   <li><b>Numbers</b> as {@code Number.prototype.toString}: the value is an IEEE-754 double, as in
 *       JavaScript; {@code -0} is {@code 0}; the exponent appears from 1e21 and below 1e-6.</li>
 * </ul>
 */
public final class Jcs {

    private Jcs() {}

    public static String canonicalize(JsonNode node) {
        StringBuilder out = new StringBuilder();
        write(node, out);
        return out.toString();
    }

    /** {@code "sha256:" + hex} of the canonical snapshot <b>without</b> its {@code checksum} field. */
    public static String entitlementChecksum(ObjectNode snapshot) {
        ObjectNode withoutChecksum = snapshot.deepCopy();
        withoutChecksum.remove("checksum");
        return "sha256:" + sha256Hex(canonicalize(withoutChecksum));
    }

    public static String sha256Hex(String text) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is not available", e);
        }
    }

    private static void write(JsonNode node, StringBuilder out) {
        if (node == null || node.isNull() || node.isMissingNode()) {
            out.append("null");
        } else if (node.isBoolean()) {
            out.append(node.booleanValue());
        } else if (node.isNumber()) {
            out.append(number(node.doubleValue()));
        } else if (node.isString()) {
            string(node.stringValue(), out);
        } else if (node.isArray()) {
            out.append('[');
            for (int i = 0; i < node.size(); i++) {
                if (i > 0) {
                    out.append(',');
                }
                write(node.get(i), out);
            }
            out.append(']');
        } else if (node.isObject()) {
            List<String> keys = new ArrayList<>(node.propertyNames());
            keys.sort(String::compareTo);
            out.append('{');
            for (int i = 0; i < keys.size(); i++) {
                if (i > 0) {
                    out.append(',');
                }
                string(keys.get(i), out);
                out.append(':');
                write(node.get(keys.get(i)), out);
            }
            out.append('}');
        } else {
            throw new IllegalArgumentException("JSON node type not supported by JCS: " + node.getNodeType());
        }
    }

    private static void string(String s, StringBuilder out) {
        out.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\b' -> out.append("\\b");
                case '\f' -> out.append("\\f");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> {
                    if (c < 0x20 || loneSurrogate(s, i)) {
                        out.append(String.format("\\u%04x", (int) c));
                    } else {
                        out.append(c);
                    }
                }
            }
        }
        out.append('"');
    }

    /** Well-formed {@code JSON.stringify} (ES2019): an unpaired surrogate is escaped. */
    private static boolean loneSurrogate(String s, int i) {
        char c = s.charAt(i);
        if (Character.isHighSurrogate(c)) {
            return i + 1 >= s.length() || !Character.isLowSurrogate(s.charAt(i + 1));
        }
        if (Character.isLowSurrogate(c)) {
            return i == 0 || !Character.isHighSurrogate(s.charAt(i - 1));
        }
        return false;
    }

    /**
     * {@code Number.prototype.toString(10)} (ECMA-262 section 6.1.6.1.20).
     *
     * <p>Since JDK 19 {@link Double#toString} yields the SHORTEST decimal that round-trips to the
     * same double, which is the digit string ECMAScript requires; only the layout is rearranged here.
     */
    static String number(double d) {
        if (Double.isNaN(d) || Double.isInfinite(d)) {
            throw new IllegalArgumentException("JCS does not admit NaN or infinities");
        }
        if (d == 0) {
            return "0";
        }
        String sign = d < 0 ? "-" : "";
        BigDecimal decimal = new BigDecimal(Double.toString(Math.abs(d))).stripTrailingZeros();
        String digits = decimal.unscaledValue().toString();
        int k = digits.length();
        int n = k - decimal.scale();

        String body;
        if (k <= n && n <= 21) {
            body = digits + "0".repeat(n - k);
        } else if (0 < n && n <= 21) {
            body = digits.substring(0, n) + "." + digits.substring(n);
        } else if (-6 < n && n <= 0) {
            body = "0." + "0".repeat(-n) + digits;
        } else {
            int e = n - 1;
            String exponent = "e" + (e < 0 ? "-" : "+") + Math.abs(e);
            body = k == 1 ? digits + exponent : digits.charAt(0) + "." + digits.substring(1) + exponent;
        }
        return sign + body;
    }
}
