package com.ebim.tms.orders.application;

import com.ebim.tms.orders.domain.Eligibility;
import com.ebim.tms.orders.domain.OrderPriority;
import com.ebim.tms.orders.domain.OrderStatus;
import com.ebim.tms.orders.domain.SchedulingAssessment;
import com.ebim.tms.orders.domain.SchedulingReason;
import com.ebim.tms.orders.domain.TransportOrder;
import com.ebim.tms.shared.reference.MasterReference;
import com.ebim.tms.shared.reference.RouteResolution;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * One order as the Scheduling and Release board shows it (ADR-014): the order, and its eligibility
 * derived on this read. Nothing in the second half is stored anywhere.
 *
 * @param scheduledDispatchDate {@code service_date} (ADR-014 section 2)
 * @param releaseDeadline       when the release window closes, or null when no calendar applies
 * @param releaseDeadlineEndOfDay true when no cutoff applied and the deadline is the next midnight
 * @param routeResolution       RESOLVED, NOT_FOUND, AMBIGUOUS or NOT_CONFIGURED
 * @param routeCandidates       the compatible routes' codes - one when resolved, several when ambiguous
 * @param activeHolds           active holds, blocking or not
 * @param activeBlockingHolds   the ones that stop release, planning and dispatch
 */
public record SchedulingRowView(
        UUID orderId,
        String orderNumber,
        String externalReference,
        String customerName,
        String customerReference,
        UUID originId,
        String originCode,
        String originName,
        UUID destinationId,
        String destinationCode,
        String destinationName,
        LocalDate scheduledDispatchDate,
        OrderPriority priority,
        OrderStatus status,
        BigDecimal totalWeightKg,
        BigDecimal totalVolumeM3,
        BigDecimal totalPallets,
        long version,
        Eligibility eligibility,
        boolean requiresOverride,
        List<SchedulingReason> reasons,
        OffsetDateTime releaseDeadline,
        boolean releaseDeadlineEndOfDay,
        RouteResolution.Status routeResolution,
        String routeCode,
        String routeName,
        List<String> routeCandidates,
        String locationFrequencyCode,
        String routeFrequencyCode,
        long activeHolds,
        long activeBlockingHolds) {

    static SchedulingRowView of(TransportOrder order, MasterReference origin, MasterReference destination,
            SchedulingAssessment assessment, long activeHolds, long activeBlockingHolds) {
        RouteResolution route = assessment.routeResolution();
        return new SchedulingRowView(order.id(), order.orderNumber(), order.externalReference(),
                order.customerName(), order.customerReference(), order.originId(),
                origin == null ? null : origin.code(), origin == null ? null : origin.name(),
                order.destinationId(), destination == null ? null : destination.code(),
                destination == null ? null : destination.name(), order.serviceDate(), order.priority(),
                order.status(), order.totalWeightKg(), order.totalVolumeM3(), order.totalPallets(), order.version(),
                assessment.eligibility(), assessment.requiresOverride(), assessment.reasons(),
                assessment.releaseDeadline() == null ? null : assessment.releaseDeadline().at(),
                assessment.releaseDeadline() != null && assessment.releaseDeadline().endOfDay(),
                route.status(), route.routeCode(),
                route.route().map(RouteResolution.ResolvedRoute::name).orElse(null),
                route.candidates().stream().map(RouteResolution.ResolvedRoute::code).toList(),
                assessment.locationFrequencyCode(), assessment.routeFrequencyCode(), activeHolds,
                activeBlockingHolds);
    }
}
