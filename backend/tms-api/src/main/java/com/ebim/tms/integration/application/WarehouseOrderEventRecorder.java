package com.ebim.tms.integration.application;

import com.ebim.tms.integration.domain.WarehouseOrderEvent;
import com.ebim.tms.integration.infrastructure.WarehouseOrderEventRepository;
import com.ebim.tms.shared.reference.OrderHoldPort;
import com.ebim.tms.shared.reference.OrderHoldPort.IntegrationHoldCommand;
import com.ebim.tms.shared.reference.OrderHoldPort.IntegrationHoldResult;
import com.ebim.tms.shared.security.CompanyScope;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

/**
 * One warehouse order event in one transaction (V61, ADR-025 section 4): the hold it asks for and the
 * row that records it commit together or not at all. A concurrent repeat of the same event id loses
 * on {@code uq_warehouse_order_event} and takes its hold down with it; the caller answers
 * {@code DUPLICATE}.
 *
 * <p>Never a cancellation (D-03): {@code ORDER_CANCELLED} places a blocking hold
 * {@code WAREHOUSE_CANCELLED}, {@code ORDER_SHORT_CLOSED} a non-blocking {@code WAREHOUSE_SHORT_CLOSED}
 * that carries the figures. A person lifts the hold or cancels the order.
 */
@Component
public class WarehouseOrderEventRecorder {

    static final String CANCELLED_REASON_CODE = "WAREHOUSE_CANCELLED";
    static final String SHORT_CLOSED_REASON_CODE = "WAREHOUSE_SHORT_CLOSED";
    private static final int MAX_HOLD_REASON = 500;

    /** What one event came to: the stored outcome and the TMS order it resolved to. */
    record Recorded(String result, String orderNumber) {
    }

    private final WarehouseOrderEventRepository repository;
    private final OrderHoldPort holdPort;
    private final ObjectMapper objectMapper;

    public WarehouseOrderEventRecorder(WarehouseOrderEventRepository repository, OrderHoldPort holdPort,
            ObjectMapper objectMapper) {
        this.repository = repository;
        this.holdPort = holdPort;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public Recorded record(CompanyScope scope, IntegrationPrincipal principal, String sourceSystem,
            WarehouseOrderEventBatchV1.Event event) {
        String eventId = event.eventId().trim();
        if (repository.existsByCompanyIdAndSourceSystemAndEventId(scope.companyId(), sourceSystem, eventId)) {
            return new Recorded("DUPLICATE", null);
        }
        WarehouseOrderEvent.Type type = WarehouseOrderEvent.Type.valueOf(event.type().trim());
        boolean cancelled = type == WarehouseOrderEvent.Type.ORDER_CANCELLED;
        IntegrationHoldResult hold = holdPort.placeFromIntegration(scope, new IntegrationHoldCommand(principal.id(),
                event.externalSource().trim(), event.externalReference().trim(),
                cancelled ? CANCELLED_REASON_CODE : SHORT_CLOSED_REASON_CODE, holdReason(type, event), cancelled));

        WarehouseOrderEvent.Outcome outcome = switch (hold.outcome()) {
            case PLACED -> cancelled ? WarehouseOrderEvent.Outcome.HOLD_PLACED : WarehouseOrderEvent.Outcome.NOTED;
            case ALREADY_HELD -> WarehouseOrderEvent.Outcome.ALREADY_HELD;
            case ORDER_CLOSED -> WarehouseOrderEvent.Outcome.ORDER_CLOSED;
            case UNKNOWN_ORDER -> WarehouseOrderEvent.Outcome.UNKNOWN_ORDER;
        };
        boolean placed = hold.outcome() == OrderHoldPort.IntegrationHoldOutcome.PLACED;
        repository.saveAndFlush(new WarehouseOrderEvent(scope.companyId(), principal.id(), sourceSystem, eventId, type,
                event.externalSource().trim(), event.externalReference().trim(), trimmed(event.warehouseOrderNumber()),
                trimmed(event.warehouseCode()), trimmed(event.reason()), event.occurredAt(), hold.orderId(),
                placed ? hold.holdId() : null, outcome, payloadOf(event)));
        return new Recorded(outcome.name(), hold.orderNumber());
    }

    /**
     * What the planner reads on the hold. The figures of a short close travel here because the hold
     * is where a person looks; the full event stays in {@code warehouse_order_event.payload}.
     */
    static String holdReason(WarehouseOrderEvent.Type type, WarehouseOrderEventBatchV1.Event event) {
        StringBuilder text = new StringBuilder(type == WarehouseOrderEvent.Type.ORDER_CANCELLED
                ? "The warehouse cancelled its order" : "The warehouse closed its order short");
        if (trimmed(event.warehouseOrderNumber()) != null) {
            text.append(' ').append(event.warehouseOrderNumber().trim());
        }
        if (trimmed(event.warehouseCode()) != null) {
            text.append(" (").append(event.warehouseCode().trim()).append(')');
        }
        if (trimmed(event.reason()) != null) {
            text.append(": ").append(event.reason().trim());
        }
        text.append('.');
        List<String> figures = new ArrayList<>();
        if (event.lines() != null) {
            for (WarehouseOrderEventBatchV1.Line line : event.lines()) {
                if (line == null) {
                    continue;
                }
                figures.add("line " + (line.lineNumber() == null ? "?" : line.lineNumber())
                        + (trimmed(line.materialCode()) == null ? "" : " " + line.materialCode().trim())
                        + ": requested " + plain(line.requested()) + ", shipped " + plain(line.shipped())
                        + ", cancelled " + plain(line.cancelled())
                        + (trimmed(line.uom()) == null ? "" : " " + line.uom().trim()));
            }
        }
        if (!figures.isEmpty()) {
            text.append(' ').append(String.join("; ", figures)).append('.');
        }
        return text.length() <= MAX_HOLD_REASON ? text.toString() : text.substring(0, MAX_HOLD_REASON - 3) + "...";
    }

    private String payloadOf(WarehouseOrderEventBatchV1.Event event) {
        try {
            return objectMapper.writeValueAsString(event);
        } catch (JacksonException notSerialisable) {
            throw new IllegalStateException("a warehouse order event could not be serialised", notSerialisable);
        }
    }

    private static String plain(BigDecimal value) {
        return value == null ? "-" : value.stripTrailingZeros().toPlainString();
    }

    private static String trimmed(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
