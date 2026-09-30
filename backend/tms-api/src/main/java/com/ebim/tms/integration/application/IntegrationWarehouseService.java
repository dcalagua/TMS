package com.ebim.tms.integration.application;

import com.ebim.tms.shared.web.CorrelationId;
import com.ebim.tms.shared.reference.DispatchConfirmationCommand;
import com.ebim.tms.shared.reference.DispatchConfirmationResult;
import com.ebim.tms.shared.reference.WarehouseExecutionPort;
import com.ebim.tms.shared.reference.WarehouseMilestoneCommand;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

/**
 * The warehouse half of the integration API (WAREHOUSE_EXECUTION_V1 §4): turns contract v1 into the
 * planning module's commands. Owns the wire format; decides nothing about the trip.
 */
@Service
public class IntegrationWarehouseService {

    private static final Set<String> MILESTONE_TYPES = Set.of("LOADING_STARTED", "LOAD_READY", "LOAD_CANCELLED");
    private static final Pattern EVENT_ID = Pattern.compile("^[A-Za-z0-9._:-]{1,128}$");
    private static final int MAX_REFERENCE = 80;

    private final WarehouseExecutionPort port;
    private final ObjectMapper objectMapper;

    public IntegrationWarehouseService(WarehouseExecutionPort port, ObjectMapper objectMapper) {
        this.port = port;
        this.objectMapper = objectMapper;
    }

    public IntegrationOutcome<DispatchConfirmationResponseV1> dispatch(IntegrationPrincipal principal,
            DispatchConfirmationDelivery delivery, String idempotencyKey) {
        DispatchConfirmationV1 document = delivery.document();
        DispatchConfirmationCommand command = new DispatchConfirmationCommand(
                principal.id(), idempotencyKey, CorrelationId.current().orElse(null),
                document.sourceSystem(), document.dispatchReference().trim(), document.revision(),
                // Opaque (WAREHOUSE_EXECUTION_V1 §2.1): stored and matched verbatim, never parsed.
                document.transportReference().trim(), trimmed(document.loadReference()),
                trimmed(document.warehouseCode()), document.actualDispatchAt(),
                document.carrier() == null ? null : trimmed(document.carrier().code()),
                document.carrier() == null ? null : trimmed(document.carrier().name()),
                document.vehicle() == null ? null : trimmed(document.vehicle().licensePlate()),
                document.vehicle() == null ? null : trimmed(document.vehicle().type()),
                document.driver() == null ? null : trimmed(document.driver().name()),
                document.driver() == null ? null : trimmed(document.driver().documentNumber()),
                trimmed(document.sealNumber()), trimmed(document.transportDocumentNumber()),
                document.totals() == null ? null : document.totals().handlingUnits(),
                document.totals() == null ? null : document.totals().weightKg(),
                document.totals() == null ? null : document.totals().volumeM3(),
                document.orders() == null ? List.of() : document.orders().stream().map(IntegrationWarehouseService::toOrder).toList(),
                delivery.rawBody(),
                PayloadHash.of(objectMapper, document));
        DispatchConfirmationResult result = port.receiveDispatch(principal.companyScope(), command);
        DispatchConfirmationResponseV1 body = new DispatchConfirmationResponseV1(result.id(), result.dispatchReference(),
                result.revision(), result.outcome(), result.transportReference(), result.verificationStatus(),
                result.discrepancies().stream()
                        .map(item -> new DispatchConfirmationResponseV1.Discrepancy(item.code(), item.severity(),
                                item.orderReference(), item.lineNumber(), item.planned(), item.dispatched(), item.uom(),
                                item.detail()))
                        .toList());
        return IntegrationOutcome.single(body, result.firstReceipt() ? 201 : 200, result.id(), document.sourceSystem(),
                document.dispatchReference());
    }

    /**
     * Each milestone on its own: one that is malformed is {@code INVALID} and its neighbours still
     * land, the batch contract every other integration batch in TMS keeps.
     */
    public IntegrationOutcome<WarehouseMilestoneBatchResultV1> milestones(IntegrationPrincipal principal,
            WarehouseMilestoneBatchV1 batch) {
        List<WarehouseMilestoneBatchResultV1.Item> results = new ArrayList<>();
        int recorded = 0;
        int refused = 0;
        for (int index = 0; index < batch.milestones().size(); index++) {
            WarehouseMilestoneBatchV1.Milestone milestone = batch.milestones().get(index);
            String problem = problemWith(milestone);
            if (problem != null) {
                results.add(new WarehouseMilestoneBatchResultV1.Item(index, milestone.eventId(), "INVALID", problem));
                refused++;
                continue;
            }
            String result = port.receiveMilestone(principal.companyScope(), new WarehouseMilestoneCommand(
                    principal.id(), batch.sourceSystem(), milestone.eventId().trim(), milestone.type().trim(),
                    milestone.transportReference().trim(), trimmed(milestone.loadReference()),
                    trimmed(milestone.warehouseCode()), milestone.occurredAt()));
            if ("RECORDED".equals(result)) {
                recorded++;
            }
            results.add(new WarehouseMilestoneBatchResultV1.Item(index, milestone.eventId(), result, null));
        }
        return IntegrationOutcome.batch(
                new WarehouseMilestoneBatchResultV1(batch.milestones().size(), recorded, refused, List.copyOf(results)),
                batch.milestones().size(), batch.milestones().size() - refused, refused, batch.sourceSystem());
    }

    private static String problemWith(WarehouseMilestoneBatchV1.Milestone milestone) {
        if (milestone.eventId() == null || !EVENT_ID.matcher(milestone.eventId().trim()).matches()) {
            return "eventId is required: 1-128 letters, digits or '.', '_', ':', '-'.";
        }
        if (milestone.type() == null || !MILESTONE_TYPES.contains(milestone.type().trim())) {
            return "type must be one of " + MILESTONE_TYPES + ".";
        }
        if (milestone.transportReference() == null || milestone.transportReference().isBlank()
                || milestone.transportReference().trim().length() > MAX_REFERENCE) {
            return "transportReference is required, at most 80 characters.";
        }
        if (milestone.occurredAt() == null) {
            return "occurredAt is required.";
        }
        if (milestone.occurredAt().isAfter(OffsetDateTime.now().plusMinutes(5))) {
            return "occurredAt cannot be in the future.";
        }
        if (tooLong(milestone.loadReference()) || tooLong(milestone.warehouseCode())) {
            return "loadReference and warehouseCode are at most 80 characters.";
        }
        return null;
    }

    private static boolean tooLong(String value) {
        return value != null && value.trim().length() > MAX_REFERENCE;
    }

    private static DispatchConfirmationCommand.Order toOrder(DispatchConfirmationV1.Order order) {
        return new DispatchConfirmationCommand.Order(order.externalSource(), order.externalReference().trim(),
                trimmed(order.warehouseOrderNumber()), trimmed(order.status()), order.handlingUnits(), order.weightKg(),
                order.volumeM3(), order.lines() == null ? List.of() : order.lines().stream()
                        .map(line -> new DispatchConfirmationCommand.Line(line.lineNumber(), trimmed(line.materialCode()),
                                trimmed(line.lotCode()), line.quantity(), trimmed(line.uom()),
                                trimmed(line.handlingUnitCode())))
                        .toList());
    }

    private static String trimmed(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
