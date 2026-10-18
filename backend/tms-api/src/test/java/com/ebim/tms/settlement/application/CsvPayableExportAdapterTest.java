package com.ebim.tms.settlement.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.ebim.tms.settlement.domain.GlConcept;
import com.ebim.tms.settlement.domain.PayableExport;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The CSV payable export against a golden file (V64, ADR-024 part 1, item 6).
 *
 * <p>The golden file is the contract an ERP import is written against: a byte that moves here
 * breaks somebody's mapping, so the comparison is exact - CRLF, no BOM, quoting and all.
 */
class CsvPayableExportAdapterTest {

    /**
     * A factura with the V66 fiscal detail (ADR-024 part 2, item 10): breakdown, detraction with its
     * constancy, a penalty line and three of the five GL accounts mapped - the two missing ones are
     * empty cells, never invented.
     */
    static final PayableExportPort.PayableInvoice INVOICE = new PayableExportPort.PayableInvoice(
            UUID.fromString("11111111-1111-4111-8111-111111111111"), "F001-123", LocalDate.of(2026, 10, 15),
            UUID.fromString("22222222-2222-4222-8222-222222222222"), "Transportes \"Rápido\", S.A.C.", "PEN",
            new BigDecimal("1298.00"), "ESTIMATED",
            List.of(
                    new PayableExportPort.PayableLine(1, UUID.fromString("33333333-3333-4333-8333-333333333333"),
                            "Flete Lima - Arequipa", new BigDecimal("1100.00"), "FREIGHT"),
                    new PayableExportPort.PayableLine(2, UUID.fromString("33333333-3333-4333-8333-333333333333"),
                            "Peaje", new BigDecimal("50.50"), "TOLL"),
                    new PayableExportPort.PayableLine(3, null, "Recargo\nmensual", new BigDecimal("2E+1"),
                            "OTHER_ACCESSORIAL"),
                    new PayableExportPort.PayableLine(4, UUID.fromString("33333333-3333-4333-8333-333333333333"),
                            "Penalidad por demora", new BigDecimal("-70.50"), "PENALTY", "Llegó 3 h tarde, cita 10:00")),
            new PayableExportPort.FiscalDetail("01", 1, "F001", "123", "20100070970",
                    new BigDecimal("1100.00"), new BigDecimal("18.00"), new BigDecimal("198.00"),
                    "027", new BigDecimal("4.00"), new BigDecimal("52.00"), "0012345678", LocalDate.of(2026, 10, 20),
                    null, null, null, null, new BigDecimal("1298.00"),
                    Map.of(GlConcept.FREIGHT_EXPENSE, "631101", GlConcept.IGV_CREDIT, "401111",
                            GlConcept.CARRIER_PAYABLE, "421201")));

    private final CsvPayableExportAdapter adapter = new CsvPayableExportAdapter();

    @Test
    @DisplayName("matches the golden file byte for byte")
    void goldenFile() throws IOException {
        PayableExportPort.PayableDocument document = adapter.render(INVOICE);

        assertThat(document.format()).isEqualTo(PayableExport.Format.CSV);
        assertThat(document.reference()).isEqualTo("TMS-11111111-1111-4111-8111-111111111111");
        assertThat(document.payload()).isEqualTo(golden());
    }

    @Test
    @DisplayName("RFC 4180: CRLF records, no byte-order mark, one row per line plus the header")
    void shape() {
        String payload = adapter.render(INVOICE).payload();

        assertThat(payload).doesNotStartWith("﻿");
        assertThat(payload.split("\r\n", -1)).hasSize(INVOICE.lines().size() + 2); // header, rows, trailing ""
        assertThat(payload).doesNotContain("E+");
    }

    @Test
    @DisplayName("the same reference as the JSON export, so a retry in either format is one obligation")
    void sameReferenceAsJson() {
        assertThat(adapter.render(INVOICE).reference()).isEqualTo(JsonPayableExportAdapter.referenceFor(INVOICE));
    }

    private static String golden() throws IOException {
        try (InputStream in = CsvPayableExportAdapterTest.class.getResourceAsStream(
                "/settlement/payable-export-golden.csv")) {
            assertThat(in).as("golden file on the test classpath").isNotNull();
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
