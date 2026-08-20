package com.ebim.tms.integration.application;

import com.ebim.tms.shared.api.InvalidRequestException;
import com.ebim.tms.shared.api.PageQuery;
import com.ebim.tms.shared.api.PageResponse;
import com.ebim.tms.shared.api.ResourceNotFoundException;
import com.ebim.tms.shared.reference.PublishedShipment;
import com.ebim.tms.shared.reference.PublishedShipmentDetail;
import com.ebim.tms.shared.reference.PublishedShipmentEvent;
import com.ebim.tms.shared.reference.PublishedShipmentOrder;
import com.ebim.tms.shared.reference.PublishedShipmentStop;
import com.ebim.tms.shared.reference.ShipmentEventQuery;
import com.ebim.tms.shared.reference.ShipmentPublicationPort;
import com.ebim.tms.shared.reference.ShipmentPublicationQuery;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The read-only half of the outbound integration surface (job 08): maps
 * {@link ShipmentPublicationPort}'s port vocabulary into the frozen {@code ShipmentPlan V1} wire
 * contract. See {@code docs/integrations/OUTBOUND_SHIPMENT_V1.md}.
 *
 * <p>Unlike {@link IntegrationLocationService}/{@link IntegrationOrderService}, nothing here goes
 * through {@link IntegrationRequestExecutor}: that machinery exists for audited, idempotent
 * <em>writes</em>, and a {@code GET} has no payload to fingerprint and nothing to replay. It bypasses
 * the inbox exactly as {@code IntegrationIdentityController#ping} already does.
 */
@Service
public class IntegrationShipmentService {

    /** Mirrors {@code ck_shipment_outbox_event_type} minus the value nothing ever publishes as a filter. */
    private static final Set<String> PUBLISHABLE_STATUSES = Set.of("CONFIRMED", "CANCELLED");

    private final ShipmentPublicationPort port;

    public IntegrationShipmentService(ShipmentPublicationPort port) {
        this.port = port;
    }

    @Transactional(readOnly = true)
    public PageResponse<ShipmentPlanHeaderV1> search(
            IntegrationPrincipal principal, Set<String> statuses, OffsetDateTime updatedSince, PageQuery pageQuery) {
        Set<String> effectiveStatuses = requireValidStatuses(statuses);
        ShipmentPublicationQuery query =
                new ShipmentPublicationQuery(principal.companyId(), effectiveStatuses, updatedSince);
        PageResponse<PublishedShipment> page = port.search(query, pageQuery);
        List<ShipmentPlanHeaderV1> content =
                page.content().stream().map(shipment -> toHeader(principal, shipment)).toList();
        return new PageResponse<>(content, page.page(), page.size(), page.totalElements());
    }

    @Transactional(readOnly = true)
    public ShipmentPlanV1 find(IntegrationPrincipal principal, String shipmentNumber) {
        PublishedShipmentDetail detail = port.findDetail(principal.companyId(), shipmentNumber)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "No confirmed or cancelled shipment '" + shipmentNumber + "' exists in this company."));

        List<ShipmentPlanStopV1> stops = detail.stops().stream().map(IntegrationShipmentService::toStop).toList();
        List<ShipmentPlanOrderV1> orders = detail.orders().stream().map(IntegrationShipmentService::toOrder).toList();
        return new ShipmentPlanV1(toHeader(principal, detail.shipment()), stops, orders);
    }

    @Transactional(readOnly = true)
    public PageResponse<ShipmentEventV1> searchEvents(
            IntegrationPrincipal principal, OffsetDateTime since, PageQuery pageQuery) {
        ShipmentEventQuery query = new ShipmentEventQuery(principal.companyId(), since);
        PageResponse<PublishedShipmentEvent> page = port.searchEvents(query, pageQuery);
        List<ShipmentEventV1> content = page.content().stream()
                .map(event -> new ShipmentEventV1(event.id(), event.eventType(), event.shipmentNumber(), event.occurredAt()))
                .toList();
        return new PageResponse<>(content, page.page(), page.size(), page.totalElements());
    }

    /** Defaults to {@code CONFIRMED} only - a partner that never asked for cancellations should not start seeing them. */
    private static Set<String> requireValidStatuses(Set<String> requested) {
        if (requested == null || requested.isEmpty()) {
            return Set.of("CONFIRMED");
        }
        for (String status : requested) {
            if (!PUBLISHABLE_STATUSES.contains(status)) {
                throw new InvalidRequestException(
                        "status must be one of " + PUBLISHABLE_STATUSES + "; was '" + status + "'.");
            }
        }
        return requested;
    }

    private static ShipmentPlanHeaderV1 toHeader(IntegrationPrincipal principal, PublishedShipment shipment) {
        return new ShipmentPlanHeaderV1(
                shipment.id(), principal.companyScope().companyCode(), principal.companyScope().companyName(),
                shipment.shipmentNumber(), shipment.planNumber(), shipment.planningDate(), shipment.status(),
                shipment.originCode(), shipment.originName(), shipment.originLatitude(), shipment.originLongitude(),
                shipment.plannedDepartureAt(), shipment.carrierCode(), shipment.carrierName(), shipment.vehicleCode(),
                shipment.vehicleLicensePlate(), shipment.vehicleTypeCode(), shipment.capacitySource(),
                shipment.maxWeightKg(), shipment.maxVolumeM3(), shipment.maxPallets(), shipment.usedWeightKg(),
                shipment.usedVolumeM3(), shipment.usedPallets(), shipment.weightUtilizationPct(),
                shipment.volumeUtilizationPct(), shipment.palletsUtilizationPct(), shipment.stopCount(),
                shipment.orderCount(), shipment.version(), shipment.createdAt(), shipment.updatedAt());
    }

    private static ShipmentPlanStopV1 toStop(PublishedShipmentStop stop) {
        return new ShipmentPlanStopV1(stop.destinationId(), stop.sequence(), stop.locationCode(), stop.locationName(),
                stop.latitude(), stop.longitude(), stop.serviceWindowStart(), stop.serviceWindowEnd());
    }

    private static ShipmentPlanOrderV1 toOrder(PublishedShipmentOrder order) {
        return new ShipmentPlanOrderV1(order.orderId(), order.orderNumber(), order.externalSource(),
                order.externalReference(), order.destinationCode(), order.weightKg(), order.volumeM3(), order.pallets());
    }
}
