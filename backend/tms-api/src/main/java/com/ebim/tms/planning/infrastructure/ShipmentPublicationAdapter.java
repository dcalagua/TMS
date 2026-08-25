package com.ebim.tms.planning.infrastructure;

import com.ebim.tms.planning.application.OrderDeliveryView;
import com.ebim.tms.planning.application.TripAssignmentView;
import com.ebim.tms.planning.application.TripCapacityView;
import com.ebim.tms.planning.application.TripDetailView;
import com.ebim.tms.planning.application.TripStopView;
import com.ebim.tms.planning.application.TripView;
import com.ebim.tms.planning.application.TripViewAssembler;
import com.ebim.tms.planning.domain.ShipmentOutboxEvent;
import com.ebim.tms.planning.domain.Trip;
import com.ebim.tms.planning.domain.TripStatus;
import com.ebim.tms.shared.api.PageQuery;
import com.ebim.tms.shared.api.PageResponse;
import com.ebim.tms.shared.reference.CarrierLookupPort;
import com.ebim.tms.shared.reference.MasterReference;
import com.ebim.tms.shared.reference.OrderPlanningPort;
import com.ebim.tms.shared.reference.PlannableOrder;
import com.ebim.tms.shared.reference.PublishedShipment;
import com.ebim.tms.shared.reference.PublishedShipmentDetail;
import com.ebim.tms.shared.reference.PublishedShipmentEvent;
import com.ebim.tms.shared.reference.PublishedShipmentOrder;
import com.ebim.tms.shared.reference.PublishedShipmentStop;
import com.ebim.tms.shared.reference.ShipmentEventQuery;
import com.ebim.tms.shared.reference.ShipmentPublicationPort;
import com.ebim.tms.shared.reference.ShipmentPublicationQuery;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * The only implementation of {@link ShipmentPublicationPort}: reads a {@link Trip} the same way
 * the internal planning board does - through {@link TripViewAssembler}, batched, never one query
 * per row - and maps it into the port's own vocabulary rather than handing out
 * {@code planning.application.TripView} itself.
 *
 * <p>Every state except {@link TripStatus#DRAFT} is publishable; see the port's class comment for
 * why a draft trip is refused rather than merely filtered by the caller. A shipment a partner was
 * told about must stay readable for the rest of its life, so the execution states migration V25
 * added are in the set for the same reason {@code CANCELLED} always was: they are outcomes of
 * something already published, not new things to publish.
 */
@Component
public class ShipmentPublicationAdapter implements ShipmentPublicationPort {

    private static final Set<TripStatus> PUBLISHABLE = EnumSet.complementOf(EnumSet.of(TripStatus.DRAFT));

    private final TripRepository tripRepository;
    private final ShipmentOutboxEventRepository outboxRepository;
    private final TripViewAssembler assembler;
    private final CarrierLookupPort carrierLookupPort;
    private final OrderPlanningPort orderPlanningPort;

    public ShipmentPublicationAdapter(TripRepository tripRepository, ShipmentOutboxEventRepository outboxRepository,
            TripViewAssembler assembler, CarrierLookupPort carrierLookupPort, OrderPlanningPort orderPlanningPort) {
        this.tripRepository = tripRepository;
        this.outboxRepository = outboxRepository;
        this.assembler = assembler;
        this.carrierLookupPort = carrierLookupPort;
        this.orderPlanningPort = orderPlanningPort;
    }

    @Override
    @Transactional(readOnly = true)
    public PageResponse<PublishedShipment> search(ShipmentPublicationQuery query, PageQuery pageQuery) {
        Set<TripStatus> statuses = toStatuses(query.statuses());
        Page<Trip> page = tripRepository.findPublishable(
                query.companyId(), statuses, query.updatedSince(), toPageable(pageQuery));
        List<Trip> trips = page.getContent();
        List<TripView> views = assembler.toViews(trips, query.companyId());

        Map<UUID, MasterReference> carriers = carrierLookupPort.findAllInCompany(
                trips.stream().map(Trip::carrierId).filter(Objects::nonNull).collect(Collectors.toSet()),
                query.companyId());
        List<PublishedShipment> content =
                views.stream().map(view -> toPublished(view, carrierOf(view, carriers))).toList();
        return new PageResponse<>(content, pageQuery.pageNumber(), pageQuery.pageSize(), page.getTotalElements());
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<PublishedShipmentDetail> findDetail(UUID companyId, String shipmentNumber) {
        return tripRepository.findByShipmentNumberAndCompanyId(shipmentNumber, companyId)
                .filter(trip -> PUBLISHABLE.contains(trip.status()))
                .map(trip -> toDetail(trip, companyId));
    }

    @Override
    @Transactional(readOnly = true)
    public PageResponse<PublishedShipmentEvent> searchEvents(ShipmentEventQuery query, PageQuery pageQuery) {
        Page<ShipmentOutboxEvent> page =
                outboxRepository.findPublishable(query.companyId(), query.since(), toPageable(pageQuery));
        List<PublishedShipmentEvent> content = page.getContent().stream()
                .map(event -> new PublishedShipmentEvent(
                        event.id(), event.eventType().name(), event.shipmentNumber(), event.occurredAt()))
                .toList();
        return new PageResponse<>(content, pageQuery.pageNumber(), pageQuery.pageSize(), page.getTotalElements());
    }

    private PublishedShipmentDetail toDetail(Trip trip, UUID companyId) {
        TripDetailView detail = assembler.toDetail(trip, companyId);
        MasterReference carrier = carrierOf(detail.trip(), carrierLookupPort.findAllInCompany(
                trip.carrierId() == null ? Set.of() : Set.of(trip.carrierId()), companyId));
        PublishedShipment header = toPublished(detail.trip(), carrier);

        Set<UUID> orderIds = detail.assignments().stream().map(TripAssignmentView::orderId).collect(Collectors.toSet());
        Map<UUID, PlannableOrder> orders = orderPlanningPort.findAllInCompany(orderIds, companyId);

        // Indexed by order rather than joined per row: a shipment has one delivery per order, and
        // the detail view has already read them all in one query. An order with no entry has simply
        // not been recorded yet, which the payload reports as null and never as "not delivered".
        Map<UUID, OrderDeliveryView> deliveries = detail.deliveries().stream()
                .collect(Collectors.toMap(OrderDeliveryView::orderId, delivery -> delivery, (first, second) -> first));

        List<PublishedShipmentOrder> publishedOrders = detail.assignments().stream()
                .map(assignment -> toPublishedOrder(assignment, orders.get(assignment.orderId()),
                        deliveries.get(assignment.orderId())))
                .toList();
        List<PublishedShipmentStop> stops = detail.stops().stream().map(ShipmentPublicationAdapter::toPublishedStop).toList();

        return new PublishedShipmentDetail(header, stops, publishedOrders);
    }

    private static MasterReference carrierOf(TripView view, Map<UUID, MasterReference> carriers) {
        return view.carrierId() == null ? null : carriers.get(view.carrierId());
    }

    private static PublishedShipment toPublished(TripView view, MasterReference carrier) {
        TripCapacityView capacity = view.capacity();
        return new PublishedShipment(
                view.id(), view.companyId(), view.shipmentNumber(), view.planNumber(), view.planningDate(),
                view.status().name(), view.originCode(), view.originName(), view.originLatitude(),
                view.originLongitude(), view.plannedDepartureAt(), view.readyAt(), view.actualDepartureAt(),
                view.actualCompletionAt(), carrier == null ? null : carrier.code(),
                view.carrierName(), view.vehicleCode(), view.vehicleLicensePlate(), view.vehicleTypeCode(),
                capacity.source().name(), capacity.weight().limit(), capacity.volume().limit(),
                capacity.pallets().limit(), capacity.weight().used(), capacity.volume().used(),
                capacity.pallets().used(), capacity.weight().percentUsed(), capacity.volume().percentUsed(),
                capacity.pallets().percentUsed(), view.stopCount(), view.orderCount(), view.version(),
                view.createdAt(), view.updatedAt());
    }

    /**
     * @param delivery what was handed over, or null when nobody has recorded it yet. The receiver's
     *     identity document is deliberately not published - see {@link PublishedShipmentOrder}
     */
    private static PublishedShipmentOrder toPublishedOrder(TripAssignmentView assignment, PlannableOrder order,
            OrderDeliveryView delivery) {
        return new PublishedShipmentOrder(assignment.orderId(), assignment.orderNumber(),
                order == null ? null : order.externalSource(), order == null ? null : order.externalReference(),
                assignment.destinationCode(), assignment.assignedWeightKg(), assignment.assignedVolumeM3(),
                assignment.assignedPallets(),
                delivery == null ? null : delivery.result().name(),
                delivery == null ? null : delivery.deliveredAt(),
                delivery == null ? null : delivery.receiverName(),
                delivery == null ? null : delivery.notes(),
                delivery == null ? 0 : delivery.evidence().size());
    }

    private static PublishedShipmentStop toPublishedStop(TripStopView stop) {
        return new PublishedShipmentStop(stop.destinationId(), stop.sequence(), stop.destinationCode(),
                stop.destinationName(), stop.latitude(), stop.longitude(), stop.serviceWindowStart(),
                stop.serviceWindowEnd());
    }

    private static Set<TripStatus> toStatuses(Set<String> codes) {
        Set<TripStatus> statuses = EnumSet.noneOf(TripStatus.class);
        codes.forEach(code -> statuses.add(TripStatus.valueOf(code)));
        return statuses;
    }

    private static Pageable toPageable(PageQuery pageQuery) {
        return PageRequest.of(pageQuery.pageNumber(), pageQuery.pageSize());
    }
}
