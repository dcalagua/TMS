package com.ebim.tms.planning.application;

import com.ebim.tms.planning.domain.DocumentDelivery;
import com.ebim.tms.planning.domain.DocumentDeliveryEvidence;
import com.ebim.tms.planning.infrastructure.DocumentDeliveryEvidenceRepository;
import com.ebim.tms.planning.infrastructure.DocumentDeliveryQueries;
import com.ebim.tms.planning.infrastructure.DocumentDeliveryQueries.MonitorRow;
import com.ebim.tms.shared.reference.CarrierLookupPort;
import com.ebim.tms.shared.reference.DriverLookupPort;
import com.ebim.tms.shared.reference.DriverReference;
import com.ebim.tms.shared.reference.LogisticsDocumentPort;
import com.ebim.tms.shared.reference.MasterReference;
import com.ebim.tms.shared.reference.OrderPlanningPort;
import com.ebim.tms.shared.reference.PlannableOrder;
import com.ebim.tms.shared.reference.VehicleCapacityReference;
import com.ebim.tms.shared.reference.VehicleLookupPort;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

/**
 * Turns tracking rows into the views of the portal and the monitor (ADR-018), resolving every name
 * that lives in another module through its port, in one batched call per page.
 */
@Component
public class DocumentDeliveryViews {

    private final DocumentDeliveryQueries queries;
    private final DocumentDeliveryEvidenceRepository evidence;
    private final OrderPlanningPort orders;
    private final CarrierLookupPort carriers;
    private final VehicleLookupPort vehicles;
    private final DriverLookupPort drivers;
    private final LogisticsDocumentPort documents;

    public DocumentDeliveryViews(DocumentDeliveryQueries queries, DocumentDeliveryEvidenceRepository evidence,
            OrderPlanningPort orders, CarrierLookupPort carriers, VehicleLookupPort vehicles,
            DriverLookupPort drivers, LogisticsDocumentPort documents) {
        this.queries = queries;
        this.evidence = evidence;
        this.orders = orders;
        this.carriers = carriers;
        this.vehicles = vehicles;
        this.drivers = drivers;
        this.documents = documents;
    }

    /** The orders (in this company) that each tracked document covers. */
    public record OrderLinks(Map<UUID, List<UUID>> orderIdsByDelivery, Map<UUID, PlannableOrder> orders) {

        public List<UUID> orderIdsOf(UUID deliveryId) {
            return orderIdsByDelivery.getOrDefault(deliveryId, List.of());
        }

        public List<String> orderNumbersOf(UUID deliveryId) {
            return orderIdsOf(deliveryId).stream().map(orders::get).filter(Objects::nonNull)
                    .map(PlannableOrder::orderNumber).toList();
        }

        /** The first order's customer: a document is issued to one customer even across orders. */
        public String customerNameOf(UUID deliveryId) {
            return orderIdsOf(deliveryId).stream().map(orders::get).filter(Objects::nonNull)
                    .map(PlannableOrder::customerName).filter(Objects::nonNull).findFirst().orElse(null);
        }
    }

    public OrderLinks orderLinks(UUID companyId, Collection<UUID> deliveryIds) {
        Map<UUID, List<UUID>> byDelivery = queries.orderIdsOf(companyId, deliveryIds);
        Set<UUID> orderIds = byDelivery.values().stream().flatMap(List::stream).collect(Collectors.toSet());
        Map<UUID, PlannableOrder> found = orderIds.isEmpty() ? Map.of() : orders.findAllInCompany(orderIds, companyId);
        return new OrderLinks(byDelivery, found);
    }

    public List<PortalDocumentView> portalDocuments(UUID companyId, List<DocumentDelivery> deliveries) {
        List<UUID> ids = deliveries.stream().map(DocumentDelivery::id).toList();
        OrderLinks links = orderLinks(companyId, ids);
        Map<UUID, List<DocumentDeliveryEvidence>> evidenceByDelivery = ids.isEmpty() ? Map.of()
                : evidence.findByCompanyIdAndDocumentDeliveryIdInOrderByUploadedAtAsc(companyId, ids).stream()
                        .collect(Collectors.groupingBy(DocumentDeliveryEvidence::documentDeliveryId));
        // ADR-028: the clave de acceso and the authority's code, in one batched read for the page.
        Map<UUID, LogisticsDocumentPort.FiscalIdentity> fiscal = documents.fiscalIdentitiesOf(
                deliveries.stream().map(DocumentDelivery::documentId).collect(Collectors.toSet()), companyId);
        return deliveries.stream().map(delivery -> portalDocument(delivery, links,
                evidenceByDelivery.getOrDefault(delivery.id(), List.of()), fiscal.get(delivery.documentId())))
                .toList();
    }

    public static PortalDocumentView portalDocument(DocumentDelivery delivery, OrderLinks links,
            List<DocumentDeliveryEvidence> artefacts, LogisticsDocumentPort.FiscalIdentity fiscal) {
        return new PortalDocumentView(delivery.id(), delivery.documentId(), delivery.documentType(),
                delivery.documentNumber(), links.orderNumbersOf(delivery.id()), links.customerNameOf(delivery.id()),
                delivery.status(), delivery.reasonCode(), delivery.remark(), delivery.reportedAt(),
                delivery.recordedAt(), delivery.returnedAt(), delivery.isOpenForReport(),
                artefacts.stream().map(item -> new DocumentEvidenceRefView(item.id(), item.evidenceType(),
                        item.contentType(), item.uploadedAt())).toList(), fiscal);
    }

    public List<DocumentDeliveryRowView> rows(UUID companyId, List<MonitorRow> page) {
        if (page.isEmpty()) {
            return List.of();
        }
        OrderLinks links = orderLinks(companyId, page.stream().map(MonitorRow::id).toList());
        Map<UUID, String> carrierNames = carrierNames(companyId,
                page.stream().map(MonitorRow::carrierId).filter(Objects::nonNull).collect(Collectors.toSet()));
        // The plan's vehicle and driver only where the dispatch did not name them.
        Set<UUID> vehicleIds = page.stream().filter(row -> row.dispatchPlate() == null).map(MonitorRow::vehicleId)
                .filter(Objects::nonNull).collect(Collectors.toSet());
        Set<UUID> driverIds = page.stream().filter(row -> row.dispatchDriverName() == null).map(MonitorRow::driverId)
                .filter(Objects::nonNull).collect(Collectors.toSet());
        Map<UUID, VehicleCapacityReference> plannedVehicles = vehicleIds.isEmpty() ? Map.of()
                : vehicles.findAllInCompany(vehicleIds, companyId);
        Map<UUID, DriverReference> plannedDrivers = driverIds.isEmpty() ? Map.of()
                : drivers.findAllInCompany(driverIds, companyId);

        return page.stream().map(row -> new DocumentDeliveryRowView(row.id(), row.tripId(), row.shipmentNumber(),
                row.tripStatus(), row.dispatchedAt(), row.carrierId(),
                row.carrierId() == null ? null : carrierNames.get(row.carrierId()),
                row.dispatchPlate() != null ? row.dispatchPlate()
                        : Optional.ofNullable(lookup(plannedVehicles, row.vehicleId()))
                                .map(VehicleCapacityReference::licensePlate).orElse(null),
                row.dispatchDriverName() != null ? row.dispatchDriverName()
                        : Optional.ofNullable(lookup(plannedDrivers, row.driverId()))
                                .map(DriverReference::fullName).orElse(null),
                row.documentId(), row.documentType(), row.documentNumber(), links.orderNumbersOf(row.id()),
                links.customerNameOf(row.id()), row.status(), row.reasonCode(), row.remark(), row.reportedAt(),
                row.recordedAt(), row.recordedByLabel(), row.returnedAt(), row.returnedByLabel(),
                row.evidenceCount(), row.inconsistency())).toList();
    }

    public Map<UUID, String> carrierNames(UUID companyId, Set<UUID> carrierIds) {
        if (carrierIds.isEmpty()) {
            return Map.of();
        }
        return carriers.findAllInCompany(new HashSet<>(carrierIds), companyId).values().stream()
                .collect(Collectors.toMap(MasterReference::id, MasterReference::name, (left, right) -> left));
    }

    /** Plate and driver as the portal shows them: the dispatch's, else the plan's. */
    public record Crew(String plate, String driverName) {
    }

    public Map<UUID, Crew> crews(UUID companyId, List<DocumentDeliveryQueries.PortalTripRow> trips) {
        Set<UUID> vehicleIds = trips.stream().filter(trip -> trip.dispatchPlate() == null)
                .map(DocumentDeliveryQueries.PortalTripRow::vehicleId).filter(Objects::nonNull)
                .collect(Collectors.toSet());
        Set<UUID> driverIds = trips.stream().filter(trip -> trip.dispatchDriverName() == null)
                .map(DocumentDeliveryQueries.PortalTripRow::driverId).filter(Objects::nonNull)
                .collect(Collectors.toSet());
        Map<UUID, VehicleCapacityReference> plannedVehicles = vehicleIds.isEmpty() ? Map.of()
                : vehicles.findAllInCompany(vehicleIds, companyId);
        Map<UUID, DriverReference> plannedDrivers = driverIds.isEmpty() ? Map.of()
                : drivers.findAllInCompany(driverIds, companyId);
        return trips.stream().collect(Collectors.toMap(DocumentDeliveryQueries.PortalTripRow::tripId,
                trip -> new Crew(
                        trip.dispatchPlate() != null ? trip.dispatchPlate()
                                : Optional.ofNullable(lookup(plannedVehicles, trip.vehicleId()))
                                        .map(VehicleCapacityReference::licensePlate).orElse(null),
                        trip.dispatchDriverName() != null ? trip.dispatchDriverName()
                                : Optional.ofNullable(lookup(plannedDrivers, trip.driverId()))
                                        .map(DriverReference::fullName).orElse(null)),
                (left, right) -> left));
    }

    /** {@code Map.of()} refuses a null key, and a trip without a driver has one. */
    private static <T> T lookup(Map<UUID, T> map, UUID id) {
        return id == null ? null : map.get(id);
    }

    static <T> Map<UUID, T> byId(Collection<T> items, Function<T, UUID> id) {
        return items.stream().collect(Collectors.toMap(id, Function.identity(), (left, right) -> left));
    }
}
