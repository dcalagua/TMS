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
import com.ebim.tms.shared.reference.DriverLookupPort;
import com.ebim.tms.shared.reference.DriverReference;
import com.ebim.tms.shared.reference.OrderLineSnapshot;
import com.ebim.tms.shared.reference.OrderPlanningPort;
import com.ebim.tms.shared.reference.OrderReferencePort;
import com.ebim.tms.shared.reference.OriginLookupPort;
import com.ebim.tms.shared.reference.PlannableOrder;
import com.ebim.tms.shared.reference.PublishedShipment;
import com.ebim.tms.shared.reference.PublishedShipmentDelivery;
import com.ebim.tms.shared.reference.PublishedShipmentDetail;
import com.ebim.tms.shared.reference.PublishedShipmentEvent;
import com.ebim.tms.shared.reference.PublishedShipmentOrder;
import com.ebim.tms.shared.reference.PublishedShipmentStop;
import com.ebim.tms.shared.reference.ShipmentEventQuery;
import com.ebim.tms.shared.reference.ShipmentPublicationPort;
import com.ebim.tms.shared.reference.ShipmentPublicationQuery;
import java.util.Comparator;
import java.util.Collection;
import java.util.EnumSet;
import java.util.HashMap;
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
    private final OriginLookupPort originLookupPort;
    private final DriverLookupPort driverLookupPort;
    private final OrderDeliveryLineQueries deliveryLines;
    private final OrderReferencePort orderReferencePort;

    @SuppressWarnings("java:S107")
    public ShipmentPublicationAdapter(TripRepository tripRepository, ShipmentOutboxEventRepository outboxRepository,
            TripViewAssembler assembler, CarrierLookupPort carrierLookupPort, OrderPlanningPort orderPlanningPort,
            OriginLookupPort originLookupPort, DriverLookupPort driverLookupPort,
            OrderDeliveryLineQueries deliveryLines, OrderReferencePort orderReferencePort) {
        this.deliveryLines = deliveryLines;
        this.orderReferencePort = orderReferencePort;
        this.originLookupPort = originLookupPort;
        this.driverLookupPort = driverLookupPort;
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
        // Two repository methods, never one with a nullable bound: see TripRepository.findPublishable.
        Pageable pageable = toPageable(pageQuery);
        Page<Trip> page = query.updatedSince() == null
                ? tripRepository.findPublishable(query.companyId(), statuses, pageable)
                : tripRepository.findPublishable(query.companyId(), statuses, query.updatedSince(), pageable);
        List<Trip> trips = page.getContent();
        List<TripView> views = assembler.toViews(trips, query.companyId());

        Map<UUID, MasterReference> carriers = carrierLookupPort.findAllInCompany(
                trips.stream().map(Trip::carrierId).filter(Objects::nonNull).collect(Collectors.toSet()),
                query.companyId());
        WarehouseFields warehouse = warehouseFieldsOf(views, query.companyId());
        List<PublishedShipment> content =
                views.stream().map(view -> toPublished(view, carrierOf(view, carriers), warehouse)).toList();
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
        Pageable pageable = toPageable(pageQuery);
        Page<ShipmentOutboxEvent> page = query.since() == null
                ? outboxRepository.findPublishable(query.companyId(), pageable)
                : outboxRepository.findPublishable(query.companyId(), query.since(), pageable);
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
        PublishedShipment header = toPublished(detail.trip(), carrier,
                warehouseFieldsOf(List.of(detail.trip()), companyId));

        Set<UUID> orderIds = detail.assignments().stream().map(TripAssignmentView::orderId).collect(Collectors.toSet());
        Map<UUID, PlannableOrder> orders = orderPlanningPort.findAllInCompany(orderIds, companyId);

        // Indexed by order rather than joined per row: a shipment has one delivery per order, and
        // the detail view has already read them all in one query. An order with no entry has simply
        // not been recorded yet, which the payload reports as null and never as "not delivered".
        Map<UUID, OrderDeliveryView> deliveries = detail.deliveries().stream()
                .collect(Collectors.toMap(OrderDeliveryView::orderId, delivery -> delivery, (first, second) -> first));

        Map<UUID, List<PublishedShipmentDelivery.Line>> linesByDelivery = deliveryLinesOf(deliveries.values(), companyId);

        Map<UUID, Integer> sequenceByDestination = detail.stops().stream()
                .collect(Collectors.toMap(TripStopView::destinationId, TripStopView::sequence, (first, second) -> first));
        List<PublishedShipmentOrder> publishedOrders = detail.assignments().stream()
                .map(assignment -> {
                    OrderDeliveryView delivery = deliveries.get(assignment.orderId());
                    return toPublishedOrder(assignment, orders.get(assignment.orderId()), delivery,
                            sequenceByDestination.get(assignment.destinationId()),
                            delivery == null ? List.of() : linesByDelivery.getOrDefault(delivery.id(), List.of()));
                })
                .toList();
        List<PublishedShipmentStop> stops = detail.stops().stream().map(ShipmentPublicationAdapter::toPublishedStop).toList();

        return new PublishedShipmentDetail(header, stops, publishedOrders);
    }

    /**
     * The per-line results of a shipment's deliveries (V45), in two batched reads whatever the
     * number of orders: the delivery lines, then their line numbers and materials from the orders
     * module. A line the orders module no longer knows keeps its quantities with no number.
     */
    private Map<UUID, List<PublishedShipmentDelivery.Line>> deliveryLinesOf(Collection<OrderDeliveryView> deliveries,
            UUID companyId) {
        Set<UUID> deliveryIds = deliveries.stream().map(OrderDeliveryView::id).collect(Collectors.toSet());
        Map<UUID, List<OrderDeliveryLineQueries.DeliveryLineRow>> rows = deliveryLines.linesOf(companyId, deliveryIds);
        if (rows.isEmpty()) {
            return Map.of();
        }
        Set<UUID> lineIds = rows.values().stream().flatMap(List::stream)
                .map(OrderDeliveryLineQueries.DeliveryLineRow::orderLineId).collect(Collectors.toSet());
        Map<UUID, OrderLineSnapshot> orderLines = orderReferencePort.linesByIds(lineIds, companyId);
        Map<UUID, List<PublishedShipmentDelivery.Line>> byDelivery = new HashMap<>();
        rows.forEach((deliveryId, lines) -> byDelivery.put(deliveryId, lines.stream()
                .map(row -> {
                    OrderLineSnapshot line = orderLines.get(row.orderLineId());
                    return new PublishedShipmentDelivery.Line(line == null ? null : line.lineNumber(),
                            line == null ? null : line.materialCode(), row.uom(), row.quantityAttempted(),
                            row.quantityDelivered(), row.quantityRefused());
                })
                .sorted(Comparator.comparing(PublishedShipmentDelivery.Line::lineNumber,
                        Comparator.nullsLast(Comparator.naturalOrder())))
                .toList()));
        return byDelivery;
    }

    private static MasterReference carrierOf(TripView view, Map<UUID, MasterReference> carriers) {
        return view.carrierId() == null ? null : carriers.get(view.carrierId());
    }

    /** The warehouse-facing fields of a page of trips, resolved in two batched lookups. */
    private record WarehouseFields(Map<UUID, String> originExternalReferences, Map<UUID, DriverReference> drivers) {
    }

    private WarehouseFields warehouseFieldsOf(List<TripView> views, UUID companyId) {
        Set<UUID> origins = views.stream().map(TripView::originId).filter(Objects::nonNull).collect(Collectors.toSet());
        Set<UUID> driverIds = views.stream().map(TripView::driverId).filter(Objects::nonNull).collect(Collectors.toSet());
        return new WarehouseFields(originLookupPort.externalReferencesInCompany(origins, companyId),
                driverIds.isEmpty() ? Map.of() : driverLookupPort.findAllInCompany(driverIds, companyId));
    }

    private static PublishedShipment toPublished(TripView view, MasterReference carrier, WarehouseFields warehouse) {
        DriverReference driver = view.driverId() == null ? null : warehouse.drivers().get(view.driverId());
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
                view.createdAt(), view.updatedAt(),
                view.originId() == null ? null : warehouse.originExternalReferences().get(view.originId()),
                view.routeCode(), view.driverCode(), view.driverName(),
                driver == null ? null : driver.documentNumber());
    }

    /**
     * @param delivery what was handed over, or null when nobody has recorded it yet. The receiver's
     *     identity document is deliberately not published - see {@link PublishedShipmentOrder}
     * @param lines the delivery's per-line results, empty when recorded per order only
     */
    private static PublishedShipmentOrder toPublishedOrder(TripAssignmentView assignment, PlannableOrder order,
            OrderDeliveryView delivery, Integer stopSequence, List<PublishedShipmentDelivery.Line> lines) {
        return new PublishedShipmentOrder(assignment.orderId(), assignment.orderNumber(),
                order == null ? null : order.externalSource(), order == null ? null : order.externalReference(),
                assignment.destinationCode(), assignment.assignedWeightKg(), assignment.assignedVolumeM3(),
                assignment.assignedPallets(),
                delivery == null ? null : delivery.result().name(),
                delivery == null ? null : delivery.deliveredAt(),
                delivery == null ? null : delivery.receiverName(),
                delivery == null ? null : delivery.notes(),
                delivery == null ? 0 : delivery.evidence().size(),
                stopSequence,
                delivery == null ? null : toPublishedDelivery(delivery, lines));
    }

    /** Plan v1.1's delivery block (WAREHOUSE_EXECUTION_V1 §7.2): amounts null when not recorded, never zero. */
    private static PublishedShipmentDelivery toPublishedDelivery(OrderDeliveryView delivery,
            List<PublishedShipmentDelivery.Line> lines) {
        OrderDeliveryView.DeliveryQuantitiesView quantities = delivery.quantities();
        return new PublishedShipmentDelivery(delivery.result().name(), delivery.deliveredAt(), delivery.receiverName(),
                delivery.evidence().size(),
                quantities == null ? null : PublishedShipmentDelivery.Amounts.ofNullable(quantities.attemptedWeightKg(),
                        quantities.attemptedVolumeM3(), quantities.attemptedPallets()),
                quantities == null ? null : PublishedShipmentDelivery.Amounts.ofNullable(quantities.deliveredWeightKg(),
                        quantities.deliveredVolumeM3(), quantities.deliveredPallets()),
                quantities == null ? null : PublishedShipmentDelivery.Amounts.ofNullable(quantities.refusedWeightKg(),
                        quantities.refusedVolumeM3(), quantities.refusedPallets()),
                lines);
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
