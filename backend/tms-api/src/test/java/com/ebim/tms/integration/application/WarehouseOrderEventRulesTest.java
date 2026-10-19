package com.ebim.tms.integration.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.ebim.tms.integration.domain.WarehouseOrderEvent;
import com.ebim.tms.orders.domain.OrderStatus;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** The pure rules behind V61's warehouse order events and order feed (WAREHOUSE_EXECUTION_V1 7.4-7.5). */
class WarehouseOrderEventRulesTest {

    private static final OffsetDateTime PAST = OffsetDateTime.parse("2026-10-17T08:55:00-05:00");

    @Test
    @DisplayName("the order feed's status filter is OrderStatus, value for value")
    void statusesMirrorOrderStatus() {
        // integration may not import orders.domain; a test may, and this one keeps the copy honest.
        assertThat(IntegrationOrderReadService.STATUSES)
                .containsExactlyInAnyOrderElementsOf(Arrays.stream(OrderStatus.values()).map(Enum::name).toList());
    }

    @Test
    @DisplayName("a well-formed event has no problem; each malformed field is named")
    void problems() {
        assertThat(IntegrationWarehouseOrderEventService.problemWith(event("e-1", "ORDER_CANCELLED", "PED-1", PAST, null)))
                .isNull();
        assertThat(IntegrationWarehouseOrderEventService.problemWith(event("bad id!", "ORDER_CANCELLED", "PED-1", PAST, null)))
                .contains("eventId");
        assertThat(IntegrationWarehouseOrderEventService.problemWith(event("e-1", "ORDER_DELETED", "PED-1", PAST, null)))
                .contains("type");
        assertThat(IntegrationWarehouseOrderEventService.problemWith(event("e-1", "ORDER_CANCELLED", " ", PAST, null)))
                .contains("externalReference");
        assertThat(IntegrationWarehouseOrderEventService.problemWith(
                event("e-1", "ORDER_CANCELLED", "PED-1", OffsetDateTime.now().plusHours(1), null)))
                .contains("future");
        assertThat(IntegrationWarehouseOrderEventService.problemWith(event("e-1", "ORDER_SHORT_CLOSED", "PED-1", PAST,
                List.of(new WarehouseOrderEventBatchV1.Line(1, "SKU", "UN", BigDecimal.TEN, BigDecimal.ONE,
                        new BigDecimal("-1"))))))
                .contains("zero or greater");
    }

    @Test
    @DisplayName("the hold reason names the warehouse order, the reason and the figures, and fits the 500-character column")
    void holdReason() {
        WarehouseOrderEventBatchV1.Event shortClosed = event("e-2", "ORDER_SHORT_CLOSED", "PED-1", PAST,
                List.of(new WarehouseOrderEventBatchV1.Line(1, "SKU-100", "UN", new BigDecimal("100.000"),
                        new BigDecimal("97"), new BigDecimal("3"))));
        assertThat(WarehouseOrderEventRecorder.holdReason(WarehouseOrderEvent.Type.ORDER_SHORT_CLOSED, shortClosed))
                .isEqualTo("The warehouse closed its order short ORR-7 (CD01): Saldo anulado. "
                        + "line 1 SKU-100: requested 100, shipped 97, cancelled 3 UN.");

        List<WarehouseOrderEventBatchV1.Line> many = java.util.stream.IntStream.rangeClosed(1, 60)
                .mapToObj(n -> new WarehouseOrderEventBatchV1.Line(n, "SKU-" + n, "UN", BigDecimal.TEN, BigDecimal.ONE,
                        new BigDecimal("9")))
                .toList();
        String reason = WarehouseOrderEventRecorder.holdReason(WarehouseOrderEvent.Type.ORDER_SHORT_CLOSED,
                event("e-3", "ORDER_SHORT_CLOSED", "PED-1", PAST, many));
        assertThat(reason).hasSize(500).endsWith("...");
    }

    private static WarehouseOrderEventBatchV1.Event event(String eventId, String type, String reference,
            OffsetDateTime occurredAt, List<WarehouseOrderEventBatchV1.Line> lines) {
        return new WarehouseOrderEventBatchV1.Event(eventId, type, "SAPB1_PE", reference, "ORR-7", "CD01", occurredAt,
                "Saldo anulado", lines);
    }
}
