package com.ebim.tms.orders.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * ADR-028: the shape of a document's fiscal identity, and nothing more. Ecuador's rules apply only
 * with {@code fiscalCountry = EC}; a document without a country is checked exactly as before V69.
 */
class LogisticsDocumentShapeTest {

    /** Demo clave de acceso: 15/10/2026, 06, RUC 0992999999001, pruebas, 001-001-000000123, mod-11 ok. */
    static final String CLAVE = "1510202606099299999900110010010000001231234567819";

    private static LogisticsDocumentShape.Subject ecGuide(String number, String code, String key, String issuer) {
        return new LogisticsDocumentShape.Subject("REMISSION_GUIDE", number, issuer, "EC", code, key,
                LocalDate.of(2026, 10, 15), LocalDate.of(2026, 10, 16));
    }

    @Test
    @DisplayName("a well-formed SRI guia de remision has no violation")
    void wellFormedEcuadorianGuide() {
        assertThat(CLAVE).hasSize(49);
        assertThat(LogisticsDocumentShape.violations(ecGuide("001-001-000000123", "06", CLAVE, "0992999999001")))
                .isEmpty();
        // Every fiscal field is optional: only what is sent is checked.
        assertThat(LogisticsDocumentShape.violations(ecGuide("001-001-000000123", null, null, null))).isEmpty();
    }

    @Test
    @DisplayName("Ecuador: the clave de acceso is 49 digits, the number is 3-3-9, the issuer RUC 13 digits")
    void ecuadorShape() {
        assertThat(LogisticsDocumentShape.violations(ecGuide("001-001-000000123", "06", CLAVE.substring(1), null)))
                .singleElement().asString().contains("49 digits").contains("48 characters");
        assertThat(LogisticsDocumentShape.violations(ecGuide("001-001-000000123", "06",
                CLAVE.substring(0, 48) + "X", null))).singleElement().asString().contains("49 digits");
        assertThat(LogisticsDocumentShape.violations(ecGuide("GR-123", "06", CLAVE, null)))
                .singleElement().asString().contains("001-001-000000123");
        assertThat(LogisticsDocumentShape.violations(ecGuide("001-001-000000123", "06", CLAVE, "099299999900")))
                .singleElement().asString().contains("13 digits");
    }

    @Test
    @DisplayName("Ecuador: a guide is SRI 06 - another code is refused, never corrected")
    void ecuadorCode() {
        assertThat(LogisticsDocumentShape.violations(ecGuide("001-001-000000123", "01", CLAVE, null)))
                .singleElement().asString().contains("expected 06");
    }

    @Test
    @DisplayName("Ecuador: an internal delivery note without an SRI code keeps its own numbering")
    void ecuadorInternalDocument() {
        assertThat(LogisticsDocumentShape.violations(new LogisticsDocumentShape.Subject("DELIVERY_NOTE", "NE-77",
                null, "EC", null, null, null, null))).isEmpty();
        // An SRI factura (code 01) is a comprobante: the same 3-3-9 number.
        assertThat(LogisticsDocumentShape.violations(new LogisticsDocumentShape.Subject("INVOICE", "F-77",
                null, "EC", "01", null, null, null))).singleElement().asString().contains("3-3-9");
    }

    @Test
    @DisplayName("Peru: the GRE aliases are SUNAT 09 / 31 and cannot be labelled with another country")
    void peruAliases() {
        assertThat(LogisticsDocumentShape.violations(new LogisticsDocumentShape.Subject("GRE_REMITENTE",
                "T001-45", null, null, null, null, null, null))).isEmpty();
        assertThat(LogisticsDocumentShape.violations(new LogisticsDocumentShape.Subject("GRE_TRANSPORTISTA",
                "V001-45", null, "PE", "31", "any-sunat-reference", null, null))).isEmpty();
        assertThat(LogisticsDocumentShape.violations(new LogisticsDocumentShape.Subject("GRE_REMITENTE",
                "T001-45", null, "PE", "31", null, null, null))).singleElement().asString().contains("expected 09");
        assertThat(LogisticsDocumentShape.violations(new LogisticsDocumentShape.Subject("GRE_REMITENTE",
                "001-001-000000123", null, "EC", null, null, null, null)))
                .anySatisfy(message -> assertThat(message).contains("REMISSION_GUIDE"));
        // Peru's numbers are never parsed: no Ecuadorian rule leaks across.
        assertThat(LogisticsDocumentShape.violations(new LogisticsDocumentShape.Subject("REMISSION_GUIDE",
                "T001-00000045", null, "PE", "09", "short", null, null))).isEmpty();
    }

    @Test
    @DisplayName("the transport cannot end before it starts; a malformed country is named")
    void datesAndCountry() {
        assertThat(LogisticsDocumentShape.violations(new LogisticsDocumentShape.Subject("REMISSION_GUIDE",
                "X-1", null, "CO", null, null, LocalDate.of(2026, 10, 16), LocalDate.of(2026, 10, 15))))
                .singleElement().asString().contains("transportEndDate");
        assertThat(LogisticsDocumentShape.violations(new LogisticsDocumentShape.Subject("REMISSION_GUIDE",
                "X-1", null, "ecu", null, null, null, null))).singleElement().asString().contains("ISO 3166-1");
    }
}
