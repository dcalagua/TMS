package com.ebim.tms.orders.domain;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * The shape rules of a logistics document's fiscal identity (ADR-028), the ones Bean Validation
 * cannot express on a single field. Pure: no database, no clock.
 *
 * <p>Shape only, on purpose. TMS receives documents the customer's ERP or e-invoicing provider
 * issued and authorised; it never verifies one with the SRI or SUNAT, never interprets
 * {@code external_status} and never derives a missing field. What it refuses is a document whose
 * identifiers cannot be what they claim to be - a clave de acceso that is not 49 digits, an SRI
 * number that is not establecimiento-puntoEmision-secuencial - because showing such a value as "the
 * clave de acceso" would mislead the person who copies it. Every rule here is mirrored by a CHECK
 * of V69, so the database refuses what this class would.
 */
public final class LogisticsDocumentShape {

    private static final Pattern EC_NUMBER = Pattern.compile("^\\d{3}-\\d{3}-\\d{9}$");
    private static final Pattern EC_ACCESS_KEY = Pattern.compile("^\\d{49}$");
    private static final Pattern EC_RUC = Pattern.compile("^\\d{13}$");
    /** ISO 3166-1 alpha-2, the shape; whether the country exists is not TMS's question. */
    private static final Pattern COUNTRY = Pattern.compile("^[A-Z]{2}$");

    private LogisticsDocumentShape() {
    }

    /** What the shape rules see of a document. */
    public record Subject(String documentType, String documentNumber, String issuerTaxId, String fiscalCountry,
            String fiscalDocumentCode, String authorizationNumber, LocalDate transportStartDate,
            LocalDate transportEndDate) {
    }

    /**
     * The known (country, type) -> authority code pairs, used only to refuse a contradiction (an
     * Ecuadorian guide labelled '01'). Never used to fill a missing code.
     */
    public static Set<String> expectedCodes(String fiscalCountry, String documentType) {
        if ("EC".equals(fiscalCountry) && "REMISSION_GUIDE".equals(documentType)) {
            return Set.of("06");
        }
        if ("PE".equals(fiscalCountry) || fiscalCountry == null) {
            return switch (documentType) {
                case "GRE_REMITENTE" -> Set.of("09");
                case "GRE_TRANSPORTISTA" -> Set.of("31");
                case "REMISSION_GUIDE" -> "PE".equals(fiscalCountry) ? Set.of("09", "31") : Set.of();
                default -> Set.of();
            };
        }
        return Set.of();
    }

    /** Every violation, each one a sentence naming the field; empty when the document is well formed. */
    public static List<String> violations(Subject document) {
        List<String> found = new ArrayList<>();
        String country = document.fiscalCountry();
        String type = document.documentType();
        if (country != null && !COUNTRY.matcher(country).matches()) {
            found.add("fiscalCountry must be an ISO 3166-1 alpha-2 code in upper case (EC, PE, ...).");
            return found;
        }
        if (country != null && !"PE".equals(country)
                && ("GRE_REMITENTE".equals(type) || "GRE_TRANSPORTISTA".equals(type))) {
            found.add(type + " is SUNAT's (Peru); a remission guide of " + country
                    + " is documentType REMISSION_GUIDE.");
        }
        Set<String> expected = expectedCodes(country, type);
        if (document.fiscalDocumentCode() != null && !expected.isEmpty()
                && !expected.contains(document.fiscalDocumentCode())) {
            found.add("fiscalDocumentCode " + document.fiscalDocumentCode() + " is not a " + type
                    + (country == null ? "" : " of " + country) + "; expected " + String.join(" or ",
                            expected.stream().sorted().toList()) + ".");
        }
        if (document.transportStartDate() != null && document.transportEndDate() != null
                && document.transportEndDate().isBefore(document.transportStartDate())) {
            found.add("transportEndDate cannot be before transportStartDate.");
        }
        if ("EC".equals(country)) {
            ecuador(document, found);
        }
        return found;
    }

    private static void ecuador(Subject document, List<String> found) {
        boolean sriVoucher = "REMISSION_GUIDE".equals(document.documentType()) || document.fiscalDocumentCode() != null;
        if (sriVoucher && !EC_NUMBER.matcher(document.documentNumber()).matches()) {
            found.add("documentNumber of an SRI comprobante is establecimiento-puntoEmision-secuencial, "
                    + "e.g. 001-001-000000123 (3-3-9 digits).");
        }
        String key = document.authorizationNumber();
        if (key != null && !EC_ACCESS_KEY.matcher(key).matches()) {
            found.add("authorizationNumber (SRI clave de acceso) must be exactly 49 digits; got "
                    + key.length() + " characters.");
        }
        String issuer = document.issuerTaxId();
        if (issuer != null && !EC_RUC.matcher(issuer).matches()) {
            found.add("issuerTaxId of an SRI comprobante is the issuer's RUC: 13 digits.");
        }
    }
}
