package com.ebim.tms.orders.application;

import com.ebim.tms.orders.domain.OrderPriority;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.UUID;

/**
 * Create and update share one shape, following every other module's convention - see
 * {@code OriginRequest} for why company/id come from context rather than the body.
 *
 * <p>Two things are specific to Orders:
 *
 * <ul>
 *   <li>{@code lines} may be empty. Unlike a route's {@code destinationIds}, a header-only order
 *       is a legitimate {@code NOT_READY} state (step brief: "orders not ready" is one of the
 *       V1 statuses) - {@code OrderService.markReadyForPlanning} is where "must have at least
 *       one line" is actually enforced, not creation.</li>
 *   <li>{@code version} is required on update (ignored on create, where it is meaningless) and
 *       is compared against the persisted order's version by {@code OrderService} before any
 *       change is applied - the explicit optimistic-locking check the brief asks for. See
 *       {@code docs/domain/ORDER_LIFECYCLE_V1.md}, "Concurrency".</li>
 * </ul>
 *
 * <p>{@code status} is deliberately absent: it is server-controlled exclusively through the
 * {@code mark-ready} and {@code cancel} endpoints, the same way {@code RouteRequest} excludes
 * {@code active} in favour of dedicated activate/deactivate endpoints.
 *
 * <p>So are {@code totalWeightKg}/{@code totalVolumeM3}/{@code totalPallets}. A caller may state
 * what it believes the order weighs - {@code declaredWeightKg} and its two siblings - but the
 * figures planning reads are always produced by {@code OrderTotals} from those declarations and
 * the lines together. A request that could set the effective totals directly would make
 * "totals are never trusted from the browser" a comment rather than a property.
 */
public record OrderRequest(
        @Size(max = 64) String externalSource,
        @Size(max = 128) String externalReference,
        @NotNull UUID originId,
        @NotNull UUID destinationId,
        @Size(max = 200) String customerName,
        @Size(max = 100) String customerReference,
        @NotNull LocalDate serviceDate,
        @NotNull OrderPriority priority,
        LocalTime requestedWindowStart,
        LocalTime requestedWindowEnd,
        @DecimalMin(value = "0", message = "must be zero or greater") BigDecimal declaredWeightKg,
        @DecimalMin(value = "0", message = "must be zero or greater") BigDecimal declaredVolumeM3,
        @DecimalMin(value = "0", message = "must be zero or greater") BigDecimal declaredPallets,
        Long version,
        @NotNull List<@Valid @NotNull OrderLineRequest> lines) {

    /**
     * One requested order line. {@code palletQuantity} allows zero (an explicit "no pallet
     * contribution", distinct from "unknown" - {@code null}), the same {@code != null} rather
     * than {@code > 0} discipline {@code EffectiveCapacityResolver} uses for a vehicle override.
     */
    public record OrderLineRequest(
            @NotBlank @Size(max = 64) String materialCode,
            @NotBlank @Size(max = 200) String materialDescription,
            @NotNull @DecimalMin(value = "0", inclusive = false, message = "must be greater than zero") BigDecimal quantity,
            @NotBlank @Size(max = 16) String uom,
            @DecimalMin(value = "0", inclusive = false, message = "must be greater than zero") BigDecimal unitWeightKg,
            @DecimalMin(value = "0", inclusive = false, message = "must be greater than zero") BigDecimal unitVolumeM3,
            @DecimalMin(value = "0", message = "must be zero or greater") BigDecimal palletQuantity) {
    }
}
