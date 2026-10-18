package com.ebim.tms.integration.application;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

/**
 * {@code POST /integration/v1/warehouse-order-events} (V61, WAREHOUSE_EXECUTION_V1 7.5): the
 * warehouse cancelled its order or closed it short. Owns the wire format and the per-item checks;
 * {@link WarehouseOrderEventRecorder} owns the transaction of each item, and {@code orders} decides
 * what a hold is. Nothing here cancels anything (D-03).
 */
@Service
public class IntegrationWarehouseOrderEventService {

    private static final Set<String> TYPES = Set.of("ORDER_CANCELLED", "ORDER_SHORT_CLOSED");
    private static final Pattern EVENT_ID = Pattern.compile("^[A-Za-z0-9._:-]{1,128}$");
    private static final int MAX_REFERENCE = 80;
    private static final int MAX_REASON = 500;
    private static final int MAX_LINES = 500;

    private final WarehouseOrderEventRecorder recorder;

    public IntegrationWarehouseOrderEventService(WarehouseOrderEventRecorder recorder) {
        this.recorder = recorder;
    }

    /**
     * Each event on its own: one that is malformed is {@code INVALID} and its neighbours still land,
     * the batch contract every other integration batch in TMS keeps.
     */
    public IntegrationOutcome<WarehouseOrderEventBatchResultV1> receive(IntegrationPrincipal principal,
            WarehouseOrderEventBatchV1 batch) {
        List<WarehouseOrderEventBatchResultV1.Item> results = new ArrayList<>();
        int recorded = 0;
        int refused = 0;
        for (int index = 0; index < batch.events().size(); index++) {
            WarehouseOrderEventBatchV1.Event event = batch.events().get(index);
            String problem = problemWith(event);
            if (problem != null) {
                results.add(new WarehouseOrderEventBatchResultV1.Item(index, event.eventId(), "INVALID", null, problem));
                refused++;
                continue;
            }
            WarehouseOrderEventRecorder.Recorded outcome;
            try {
                outcome = recorder.record(principal.companyScope(), principal, batch.sourceSystem(), event);
            } catch (DataIntegrityViolationException raced) {
                // The same event id landed first on another connection; this item's transaction,
                // hold included, was rolled back.
                outcome = new WarehouseOrderEventRecorder.Recorded("DUPLICATE", null);
            }
            if (!"DUPLICATE".equals(outcome.result())) {
                recorded++;
            }
            results.add(new WarehouseOrderEventBatchResultV1.Item(index, event.eventId(), outcome.result(),
                    outcome.orderNumber(), null));
        }
        return IntegrationOutcome.batch(
                new WarehouseOrderEventBatchResultV1(batch.events().size(), recorded, refused, List.copyOf(results)),
                batch.events().size(), batch.events().size() - refused, refused, batch.sourceSystem());
    }

    static String problemWith(WarehouseOrderEventBatchV1.Event event) {
        if (event.eventId() == null || !EVENT_ID.matcher(event.eventId().trim()).matches()) {
            return "eventId is required: 1-128 letters, digits or '.', '_', ':', '-'.";
        }
        if (event.type() == null || !TYPES.contains(event.type().trim())) {
            return "type must be one of " + TYPES + ".";
        }
        if (blankOrLong(event.externalSource()) || blankOrLong(event.externalReference())) {
            return "externalSource and externalReference are required, at most 80 characters each.";
        }
        if (event.occurredAt() == null) {
            return "occurredAt is required.";
        }
        if (event.occurredAt().isAfter(OffsetDateTime.now().plusMinutes(5))) {
            return "occurredAt cannot be in the future.";
        }
        if (tooLong(event.warehouseOrderNumber()) || tooLong(event.warehouseCode())) {
            return "warehouseOrderNumber and warehouseCode are at most 80 characters.";
        }
        if (event.reason() != null && event.reason().trim().length() > MAX_REASON) {
            return "reason is at most 500 characters.";
        }
        if (event.lines() != null) {
            if (event.lines().size() > MAX_LINES) {
                return "lines may contain at most 500 entries.";
            }
            for (WarehouseOrderEventBatchV1.Line line : event.lines()) {
                if (line == null || negative(line.requested()) || negative(line.shipped())
                        || negative(line.cancelled())) {
                    return "every line needs requested, shipped and cancelled zero or greater when present.";
                }
            }
        }
        return null;
    }

    private static boolean negative(BigDecimal value) {
        return value != null && value.signum() < 0;
    }

    private static boolean blankOrLong(String value) {
        return value == null || value.isBlank() || value.trim().length() > MAX_REFERENCE;
    }

    private static boolean tooLong(String value) {
        return value != null && value.trim().length() > MAX_REFERENCE;
    }
}
