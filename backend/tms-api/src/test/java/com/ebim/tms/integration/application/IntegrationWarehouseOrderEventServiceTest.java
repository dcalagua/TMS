package com.ebim.tms.integration.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.OffsetDateTime;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;

/** Only the event's own unique key is a duplicate; any other constraint refuses the event (post-review fix). */
class IntegrationWarehouseOrderEventServiceTest {

    private final WarehouseOrderEventRecorder recorder = mock(WarehouseOrderEventRecorder.class);
    private final IntegrationWarehouseOrderEventService service = new IntegrationWarehouseOrderEventService(recorder);

    private static WarehouseOrderEventBatchV1.Event event(String id) {
        return new WarehouseOrderEventBatchV1.Event(id, "ORDER_CANCELLED", "ERP_SAP", "PO-1", null, null,
                OffsetDateTime.now().minusMinutes(1), "Customer cancelled", null);
    }

    @Test
    @DisplayName("uq_warehouse_order_event is DUPLICATE; another constraint is INVALID, never a silent DUPLICATE")
    void onlyTheEventKeyIsADuplicate() {
        WarehouseOrderEventBatchV1.Event raced = event("evt-raced");
        WarehouseOrderEventBatchV1.Event refused = event("evt-refused");
        when(recorder.record(any(), any(), any(), eq(raced))).thenThrow(new DataIntegrityViolationException(
                "duplicate key value violates unique constraint \"uq_warehouse_order_event\""));
        when(recorder.record(any(), any(), any(), eq(refused))).thenThrow(new DataIntegrityViolationException(
                "new row violates check constraint \"ck_order_hold_reason\""));

        IntegrationOutcome<WarehouseOrderEventBatchResultV1> outcome = service.receive(
                mock(IntegrationPrincipal.class), new WarehouseOrderEventBatchV1("EWM_EBIM", List.of(raced, refused)));

        assertThat(outcome.body().results()).extracting(WarehouseOrderEventBatchResultV1.Item::result)
                .containsExactly("DUPLICATE", "INVALID");
        assertThat(outcome.body().refused()).isEqualTo(1);
    }
}
