package com.ebim.tms.orders.application;

import com.ebim.tms.orders.domain.OrderPriority;
import com.ebim.tms.orders.domain.OrderStatus;
import com.ebim.tms.orders.domain.TransportOrder;
import com.ebim.tms.orders.infrastructure.TransportOrderRepository;
import com.ebim.tms.shared.api.ConflictException;
import com.ebim.tms.shared.api.InvalidRequestException;
import com.ebim.tms.shared.reference.DestinationLookupPort;
import com.ebim.tms.shared.reference.IntakeOutcome;
import com.ebim.tms.shared.reference.MasterReference;
import com.ebim.tms.shared.reference.OrderIntakeCommand;
import com.ebim.tms.shared.reference.OrderIntakePort;
import com.ebim.tms.shared.reference.OrderIntakeResult;
import com.ebim.tms.shared.reference.OriginLookupPort;
import com.ebim.tms.shared.security.CompanyScope;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import jakarta.validation.Validator;
import java.math.BigDecimal;
import java.util.Arrays;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The inbound integration's door into Orders: {@code orders}' implementation of
 * {@link OrderIntakePort}, and the exact counterpart of {@code LocationIntakeService}.
 *
 * <p>It resolves codes to masters, decides create-or-update, and delegates every write to
 * {@link OrderService}. The lifecycle rules therefore hold identically for a partner and for the
 * UI: an order that is already {@code PLANNED} or {@code CANCELLED} is refused with a conflict
 * rather than rewritten behind a planner's back, and the totals a planner reads are still
 * produced by {@code OrderTotals} from the declarations and the lines, never taken from the
 * payload.
 */
@Service
public class OrderIntakeService implements OrderIntakePort {

    private final OrderService orderService;
    private final TransportOrderRepository transportOrderRepository;
    private final OriginLookupPort originLookupPort;
    private final DestinationLookupPort destinationLookupPort;
    private final Validator validator;

    public OrderIntakeService(OrderService orderService, TransportOrderRepository transportOrderRepository,
            OriginLookupPort originLookupPort, DestinationLookupPort destinationLookupPort, Validator validator) {
        this.orderService = orderService;
        this.transportOrderRepository = transportOrderRepository;
        this.originLookupPort = originLookupPort;
        this.destinationLookupPort = destinationLookupPort;
        this.validator = validator;
    }

    @Override
    @Transactional
    public OrderIntakeResult upsert(CompanyScope scope, OrderIntakeCommand command) {
        String externalSource = require(command.externalSource(), "externalSource");
        String externalReference = require(command.externalReference(), "externalReference");
        UUID originId = resolveOrigin(scope, command.originCode());
        UUID destinationId = resolveDestination(scope, command.destinationCode());

        Optional<TransportOrder> existing = transportOrderRepository
                .findByCompanyIdAndExternalSourceAndExternalReference(scope.companyId(), externalSource, externalReference);

        if (existing.isEmpty()) {
            OrderRequest request = toRequest(command, externalSource, externalReference, originId, destinationId, null);
            validate(request);
            OrderDetailView created = orderService.create(scope, request);
            return finish(scope, created, IntakeOutcome.CREATED, command.markReadyForPlanning());
        }

        TransportOrder order = existing.get();
        requireRewritable(order);

        OrderDetailView current = orderService.get(scope, order.id());
        OrderRequest request = toRequest(command, externalSource, externalReference, originId, destinationId,
                current.version());
        validate(request);

        if (matches(current, request)) {
            // A pure redelivery. Returning UNCHANGED rather than rewriting keeps the version and
            // updated_at stable, which matters when a partner replays a week of traffic after an
            // outage: an "update" that changes nothing should not look like one to anybody
            // watching the order.
            return finish(scope, current, IntakeOutcome.UNCHANGED, command.markReadyForPlanning());
        }

        OrderDetailView updated = orderService.update(scope, order.id(), request);
        return finish(scope, updated, IntakeOutcome.UPDATED, command.markReadyForPlanning());
    }

    /**
     * Applies the optional promotion out of {@code NOT_READY} and builds the result from whatever
     * status the order actually ended in - never from what the payload asked for.
     */
    private OrderIntakeResult finish(CompanyScope scope, OrderDetailView order, IntakeOutcome outcome,
            boolean markReadyForPlanning) {
        OrderDetailView finalState = order;
        if (markReadyForPlanning && finalState.status() == OrderStatus.NOT_READY) {
            finalState = orderService.markReadyForPlanning(scope, finalState.id());
        }
        return new OrderIntakeResult(finalState.id(), finalState.orderNumber(), finalState.status().name(), outcome);
    }

    /**
     * An order a planner has already put on a trip, or one that was cancelled, is not something a
     * sending system may overwrite. {@code OrderService.update} would refuse it anyway; refusing
     * here lets the message name the external reference the partner actually knows.
     */
    private static void requireRewritable(TransportOrder order) {
        if (order.status() != OrderStatus.NOT_READY && order.status() != OrderStatus.READY_FOR_PLANNING) {
            throw new ConflictException("Order '" + order.externalReference() + "' is " + order.status()
                    + " and can no longer be changed by an integration. Cancel or unassign it in TMS first.");
        }
    }

    private OrderRequest toRequest(OrderIntakeCommand command, String externalSource, String externalReference,
            UUID originId, UUID destinationId, Long version) {
        return new OrderRequest(
                externalSource,
                externalReference,
                originId,
                destinationId,
                trimmed(command.customerName()),
                trimmed(command.customerReference()),
                command.serviceDate(),
                parsePriority(command.priority()),
                command.requestedWindowStart(),
                command.requestedWindowEnd(),
                command.declaredWeightKg(),
                command.declaredVolumeM3(),
                command.declaredPallets(),
                version,
                command.lines().stream().map(OrderIntakeService::toLineRequest).toList());
    }

    private static OrderRequest.OrderLineRequest toLineRequest(OrderIntakeCommand.Line line) {
        return new OrderRequest.OrderLineRequest(
                trimmed(line.materialCode()),
                trimmed(line.materialDescription()),
                line.quantity(),
                trimmed(line.uom()),
                line.unitWeightKg(),
                line.unitVolumeM3(),
                line.palletQuantity());
    }

    /**
     * Whether the persisted order already says exactly what the payload says. Compares the header
     * and the lines in order, because line order is the order the sender gave them in and is what
     * {@code lineNumber} records.
     */
    private static boolean matches(OrderDetailView current, OrderRequest request) {
        boolean headerMatches = Objects.equals(current.originId(), request.originId())
                && Objects.equals(current.destinationId(), request.destinationId())
                && Objects.equals(blankToNull(current.customerName()), blankToNull(request.customerName()))
                && Objects.equals(blankToNull(current.customerReference()), blankToNull(request.customerReference()))
                && Objects.equals(current.serviceDate(), request.serviceDate())
                && current.priority() == request.priority()
                && Objects.equals(current.requestedWindowStart(), request.requestedWindowStart())
                && Objects.equals(current.requestedWindowEnd(), request.requestedWindowEnd())
                && numbersEqual(current.declaredWeightKg(), request.declaredWeightKg())
                && numbersEqual(current.declaredVolumeM3(), request.declaredVolumeM3())
                && numbersEqual(current.declaredPallets(), request.declaredPallets());
        if (!headerMatches || current.lines().size() != request.lines().size()) {
            return false;
        }

        for (int index = 0; index < request.lines().size(); index++) {
            OrderDetailView.OrderLineView persisted = current.lines().get(index);
            OrderRequest.OrderLineRequest requested = request.lines().get(index);
            boolean lineMatches = Objects.equals(persisted.materialCode(), requested.materialCode())
                    && Objects.equals(persisted.materialDescription(), requested.materialDescription())
                    && numbersEqual(persisted.quantity(), requested.quantity())
                    && Objects.equals(persisted.uom(), requested.uom())
                    && numbersEqual(persisted.unitWeightKg(), requested.unitWeightKg())
                    && numbersEqual(persisted.unitVolumeM3(), requested.unitVolumeM3())
                    && numbersEqual(persisted.palletQuantity(), requested.palletQuantity());
            if (!lineMatches) {
                return false;
            }
        }
        return true;
    }

    /** {@code compareTo}, not {@code equals}: 1.50 kg and 1.5 kg are the same weight. */
    private static boolean numbersEqual(BigDecimal persisted, BigDecimal requested) {
        if (persisted == null || requested == null) {
            // A declared total the payload omits is stored as zero, so "null" and "0" are the
            // same statement and must not read as a change.
            BigDecimal left = persisted == null ? BigDecimal.ZERO : persisted;
            BigDecimal right = requested == null ? BigDecimal.ZERO : requested;
            return left.compareTo(right) == 0;
        }
        return persisted.compareTo(requested) == 0;
    }

    private UUID resolveOrigin(CompanyScope scope, String code) {
        String candidate = require(code, "originCode");
        return lookup(originLookupPort.findActiveByCodesInCompany(Set.of(candidate), scope.companyId()), candidate,
                "originCode", "an active origin");
    }

    private UUID resolveDestination(CompanyScope scope, String code) {
        String candidate = require(code, "destinationCode");
        return lookup(destinationLookupPort.findActiveByCodesInCompany(Set.of(candidate), scope.companyId()), candidate,
                "destinationCode", "an active destination");
    }

    /**
     * The lookup ports match case-insensitively and key the result by the code as stored, so the
     * single entry is taken rather than looked up by the caller's spelling.
     *
     * <p>A code of another company simply is not in the map - the same answer a nonexistent code
     * gets. That is the tenancy rule stated as behaviour: an integration must not be able to
     * distinguish "not yours" from "does not exist", because the difference is itself information
     * about another tenant.
     */
    private static UUID lookup(java.util.Map<String, MasterReference> resolved, String code, String field,
            String what) {
        return resolved.values().stream().findFirst()
                .map(MasterReference::id)
                .orElseThrow(() -> new InvalidRequestException(
                        field + " '" + code + "' does not name " + what + " in this company."));
    }

    private void validate(OrderRequest request) {
        Set<ConstraintViolation<OrderRequest>> violations = validator.validate(request);
        if (!violations.isEmpty()) {
            throw new ConstraintViolationException(violations);
        }
    }

    private static OrderPriority parsePriority(String priority) {
        String candidate = trimmed(priority);
        if (candidate == null) {
            // Left to OrderRequest's @NotNull so the caller gets a field-level error.
            return null;
        }
        try {
            return OrderPriority.valueOf(candidate.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException unknown) {
            throw new InvalidRequestException("priority must be one of "
                    + Arrays.stream(OrderPriority.values()).map(Enum::name).collect(Collectors.joining(", "))
                    + " (received: " + priority + ").");
        }
    }

    private static String require(String value, String field) {
        String trimmed = trimmed(value);
        if (trimmed == null) {
            throw new InvalidRequestException(field + " is required.");
        }
        return trimmed;
    }

    private static String blankToNull(String value) {
        return trimmed(value);
    }

    private static String trimmed(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
