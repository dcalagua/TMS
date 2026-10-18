package com.ebim.tms.integration.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * ADR-028: the fiscal fields are additive. {@link PayloadHash} fingerprints the re-serialised
 * record, so a body sent before V69 must serialise with exactly the V55 keys - or an
 * {@code Idempotency-Key} replay across the upgrade would be refused as "different content".
 */
class LogisticsDocumentV1HashTest {

    private static final ObjectMapper MAPPER = JsonMapper.builder().build();

    private static LogisticsDocumentV1 v55Body(String country) {
        return new LogisticsDocumentV1("SAPB1_PE", "GRE_REMITENTE", "T001-0000045", LocalDate.of(2026, 9, 27),
                null, null, null, new BigDecimal("1500.50"), "PEN", "ISSUED",
                List.of(new LogisticsDocumentV1.OrderKey("SAPB1_PE", "PED-1")), country, null, null, null, null, null,
                null, null, null, null, null);
    }

    @Test
    @DisplayName("a body without the new fields serialises with the V55 keys only, so its hash is unchanged")
    void v55BodyKeepsItsShape() {
        JsonNode tree = MAPPER.valueToTree(v55Body(null));
        assertThat(tree.propertyNames()).containsExactly("sourceSystem", "documentType", "documentNumber",
                "issueDate", "issuerTaxId", "recipientTaxId", "recipientName", "amount", "currency", "status",
                "orders");
    }

    @Test
    @DisplayName("a fiscal field that is present changes the fingerprint: it is content")
    void fiscalFieldIsContent() {
        assertThat(PayloadHash.of(MAPPER, v55Body("PE"))).isNotEqualTo(PayloadHash.of(MAPPER, v55Body(null)));
    }
}
