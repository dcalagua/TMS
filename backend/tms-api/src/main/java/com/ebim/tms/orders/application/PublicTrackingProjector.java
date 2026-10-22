package com.ebim.tms.orders.application;

import com.ebim.tms.orders.domain.PublicDisclosure;
import com.ebim.tms.orders.domain.TrackingLink;
import com.ebim.tms.orders.domain.TrackingTimeline;
import com.ebim.tms.orders.domain.TransportOrder;
import com.ebim.tms.orders.infrastructure.TrackingLinkRepository;
import com.ebim.tms.orders.infrastructure.TransportOrderRepository;
import com.ebim.tms.shared.reference.CarrierLookupPort;
import com.ebim.tms.shared.reference.DestinationLookupPort;
import com.ebim.tms.shared.reference.MasterReference;
import com.ebim.tms.shared.reference.OrderTrackingPort;
import com.ebim.tms.shared.reference.OrderTrackingPort.OrderTrackingLeg;
import com.ebim.tms.shared.reference.VehicleCapacityReference;
import com.ebim.tms.shared.reference.VehicleLookupPort;
import com.ebim.tms.shared.security.CompanyScope;
import com.ebim.tms.shared.settings.CompanySettingsPort;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The scoped half of a public tracking read (ADR-029 decision 3): everything after the token resolved.
 *
 * <p>A bean of its own so that its transaction - and therefore its connection - begins <em>after</em>
 * {@link PublicTrackingAuthentication} is on the context. The connection is then {@code tms_app} bound to
 * the link's company, and every statement here is filtered by RLS to that tenant in addition to naming
 * the company and the order itself.
 *
 * <p>Answers empty for every reason a link does not work - revoked, expired, order gone - so the caller
 * turns them all into the same 404 (ADR-029 T6).
 */
@Service
public class PublicTrackingProjector {

    private final TrackingLinkRepository links;
    private final TransportOrderRepository orders;
    private final OrderTrackingPort orderTracking;
    private final DestinationLookupPort destinations;
    private final CarrierLookupPort carriers;
    private final VehicleLookupPort vehicles;
    private final CompanySettingsPort settings;
    private final TrackingLinkProperties properties;
    private final Clock clock;

    public PublicTrackingProjector(TrackingLinkRepository links, TransportOrderRepository orders,
            OrderTrackingPort orderTracking, DestinationLookupPort destinations, CarrierLookupPort carriers,
            VehicleLookupPort vehicles, CompanySettingsPort settings, TrackingLinkProperties properties, Clock clock) {
        this.links = links;
        this.orders = orders;
        this.orderTracking = orderTracking;
        this.destinations = destinations;
        this.carriers = carriers;
        this.vehicles = vehicles;
        this.settings = settings;
        this.properties = properties;
        this.clock = clock;
    }

    @Transactional
    public Optional<PublicTrackingView> project(CompanyScope scope, UUID linkId) {
        UUID companyId = scope.companyId();
        Optional<TrackingLink> found = links.findByIdAndCompanyId(linkId, companyId);
        if (found.isEmpty() || found.get().isRevoked()) {
            return Optional.empty();
        }
        TrackingLink link = found.get();
        Optional<TransportOrder> foundOrder = orders.findByIdAndCompanyId(link.orderId(), companyId);
        if (foundOrder.isEmpty()) {
            return Optional.empty();
        }
        TransportOrder order = foundOrder.get();

        TrackingTimeline timeline = TrackingTimeline.of(order.status(), order.createdAt(), order.updatedAt(),
                orderTracking.legsOf(order.id(), order.destinationId(), companyId));
        OffsetDateTime now = OffsetDateTime.now(clock).truncatedTo(ChronoUnit.MICROS);
        OffsetDateTime expiresAt = link.effectiveExpiry(timeline.closedAt(), properties.afterClosure());
        if (!expiresAt.isAfter(now)) {
            return Optional.empty();
        }
        links.recordView(link.id(), companyId, now);

        OrderTrackingLeg leg = timeline.currentLeg();
        PublicDisclosure.ArrivalWindow window = leg == null || timeline.hasOutcome() || leg.stopArrivedAt() != null
                ? null
                : PublicDisclosure.arrivalWindow(leg.stopEtaArrivalAt(), leg.planningDate(),
                        leg.serviceWindowStart(), leg.serviceWindowEnd(), scope.zoneId());

        return Optional.of(new PublicTrackingView(
                scope.companyName(),
                order.orderNumber(),
                firstNonBlank(order.externalReference(), order.customerReference()),
                destinationName(order.destinationId(), companyId),
                timeline.status(),
                timeline.steps().stream()
                        .map(step -> new PublicTrackingView.Step(step.code().name(), step.state().name(), step.at(),
                                step.outcome()))
                        .toList(),
                window == null ? null : new PublicTrackingView.ArrivalWindow(window.from(), window.to(), window.basis()),
                leg == null ? null : carrierName(leg.carrierId(), companyId),
                leg == null ? null : plate(leg.vehicleId(), companyId),
                proofOfDelivery(leg),
                scope.zoneId().getId(),
                expiresAt,
                now));
    }

    private String destinationName(UUID destinationId, UUID companyId) {
        MasterReference destination = destinations.findAllInCompany(Set.of(destinationId), companyId).get(destinationId);
        return destination == null ? null : destination.name();
    }

    private String carrierName(UUID carrierId, UUID companyId) {
        if (carrierId == null) {
            return null;
        }
        MasterReference carrier = carriers.findAllInCompany(Set.of(carrierId), companyId).get(carrierId);
        return carrier == null ? null : carrier.name();
    }

    private String plate(UUID vehicleId, UUID companyId) {
        if (vehicleId == null) {
            return null;
        }
        VehicleCapacityReference vehicle = vehicles.findAllInCompany(Set.of(vehicleId), companyId).get(vehicleId);
        return vehicle == null
                ? null
                : PublicDisclosure.plate(vehicle.licensePlate(), settings.settingsOf(companyId).trackingPlateDisplay());
    }

    /** Only once an outcome with a handover is recorded: a failed attempt has no receiver to name. */
    private static PublicTrackingView.ProofOfDelivery proofOfDelivery(OrderTrackingLeg leg) {
        if (leg == null || leg.deliveryResult() == null || leg.deliveredAt() == null
                || !("DELIVERED".equals(leg.deliveryResult()) || "PARTIAL".equals(leg.deliveryResult()))) {
            return null;
        }
        return new PublicTrackingView.ProofOfDelivery(PublicDisclosure.receiver(leg.receiverName()), leg.deliveredAt());
    }

    private static String firstNonBlank(String first, String second) {
        if (first != null && !first.isBlank()) {
            return first.trim();
        }
        return second == null || second.isBlank() ? null : second.trim();
    }
}
