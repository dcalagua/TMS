package com.ebim.tms.planning.application;

import com.ebim.tms.planning.domain.PlanningRun;
import com.ebim.tms.planning.infrastructure.DocumentDeliveryQueries;
import com.ebim.tms.planning.infrastructure.PlanningRunRepository;
import com.ebim.tms.planning.infrastructure.ShipmentTrackingQueries;
import com.ebim.tms.planning.infrastructure.ShipmentTrackingQueries.Row;
import com.ebim.tms.planning.infrastructure.ShipmentTrackingQueries.Stop;
import com.ebim.tms.planning.infrastructure.ShipmentTrackingQueries.TrackedDocument;
import com.ebim.tms.planning.infrastructure.ShipmentTrackingQueries.Totals;
import com.ebim.tms.shared.api.InvalidRequestException;
import com.ebim.tms.shared.api.PageQuery;
import com.ebim.tms.shared.api.PageResponse;
import com.ebim.tms.shared.reference.DestinationLookupPort;
import com.ebim.tms.shared.reference.DriverLookupPort;
import com.ebim.tms.shared.reference.DriverReference;
import com.ebim.tms.shared.reference.MasterReference;
import com.ebim.tms.shared.reference.OrderPlanningPort;
import com.ebim.tms.shared.reference.OriginLookupPort;
import com.ebim.tms.shared.reference.PlannableOrder;
import com.ebim.tms.shared.reference.VehicleCapacityReference;
import com.ebim.tms.shared.reference.VehicleLookupPort;
import com.ebim.tms.shared.security.CompanyScope;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The dispatched-shipment tracking report (ADR-019 section 2). Read-only: it reports what the
 * warehouse dispatched and what the carriers reported, and moves nothing.
 */
@Service
public class ShipmentTrackingService {

    static final long MAX_RANGE_DAYS = 31;

    private final ShipmentTrackingQueries queries;
    private final DocumentDeliveryQueries documentQueries;
    private final DocumentDeliveryViews views;
    private final PlanningRunRepository planningRuns;
    private final OriginLookupPort origins;
    private final DestinationLookupPort destinations;
    private final OrderPlanningPort orders;
    private final VehicleLookupPort vehicles;
    private final DriverLookupPort drivers;

    @SuppressWarnings("java:S107")
    public ShipmentTrackingService(ShipmentTrackingQueries queries, DocumentDeliveryQueries documentQueries,
            DocumentDeliveryViews views, PlanningRunRepository planningRuns, OriginLookupPort origins,
            DestinationLookupPort destinations, OrderPlanningPort orders, VehicleLookupPort vehicles,
            DriverLookupPort drivers) {
        this.queries = queries;
        this.documentQueries = documentQueries;
        this.views = views;
        this.planningRuns = planningRuns;
        this.origins = origins;
        this.destinations = destinations;
        this.orders = orders;
        this.vehicles = vehicles;
        this.drivers = drivers;
    }

    @Transactional(readOnly = true)
    public PageResponse<ShipmentTrackingRowView> list(CompanyScope scope, ShipmentTrackingFilter filter,
            PageQuery pageQuery) {
        ShipmentTrackingQueries.Filter resolved = resolve(scope, filter);
        List<Row> rows = queries.page(resolved, pageQuery.pageSize(), pageQuery.offset());
        return PageResponse.of(assemble(scope, rows), pageQuery, queries.count(resolved));
    }

    @Transactional(readOnly = true)
    public ShipmentTrackingSummaryView summary(CompanyScope scope, ShipmentTrackingFilter filter) {
        ShipmentTrackingFilter totalsFilter = filter == null ? null : new ShipmentTrackingFilter(filter.dateFrom(),
                filter.dateTo(), filter.carrierId(), null, null, filter.q());
        Totals totals = queries.totals(resolve(scope, totalsFilter));
        return new ShipmentTrackingSummaryView(totals.shipments(), totals.inTransit(), totals.completed(),
                totals.withPendingDocuments(), totals.documentsTotal(), totals.documentsPending(),
                totals.documentsDelivered(), totals.documentsRejected(), totals.documentsNotDelivered(),
                totals.awaitingReturn(), totals.dispatchMismatches());
    }

    private List<ShipmentTrackingRowView> assemble(CompanyScope scope, List<Row> rows) {
        if (rows.isEmpty()) {
            return List.of();
        }
        UUID companyId = scope.companyId();
        Set<UUID> tripIds = rows.stream().map(Row::tripId).collect(Collectors.toSet());

        Map<UUID, String> carrierNames = views.carrierNames(companyId,
                rows.stream().map(Row::carrierId).filter(Objects::nonNull).collect(Collectors.toSet()));
        Map<UUID, VehicleCapacityReference> plannedVehicles = lookup(rows.stream()
                .filter(row -> row.dispatchPlate() == null).map(Row::vehicleId), ids -> vehicles.findAllInCompany(ids,
                companyId));
        Map<UUID, DriverReference> plannedDrivers = lookup(rows.stream()
                .filter(row -> row.dispatchDriverName() == null).map(Row::driverId), ids -> drivers.findAllInCompany(ids,
                companyId));

        Map<UUID, UUID> originByRun = planningRuns.findAllById(rows.stream().map(Row::planningRunId)
                        .collect(Collectors.toSet())).stream()
                .filter(run -> run.companyId().equals(companyId) && run.originId() != null)
                .collect(Collectors.toMap(PlanningRun::id, PlanningRun::originId, (left, right) -> left));
        Map<UUID, MasterReference> originRefs = lookup(originByRun.values().stream(),
                ids -> origins.findAllInCompany(ids, companyId));

        List<Stop> stops = queries.stopsOf(companyId, tripIds);
        Map<UUID, MasterReference> destinationRefs = lookup(stops.stream().map(Stop::destinationId),
                ids -> destinations.findAllInCompany(ids, companyId));

        // Documents per stop: a document is delivered where its orders go (as the portal places it).
        List<TrackedDocument> documents = queries.documentsOf(companyId, tripIds);
        Map<UUID, List<UUID>> ordersByDocument = documentQueries.orderIdsOf(companyId,
                documents.stream().map(TrackedDocument::id).toList());
        Map<UUID, PlannableOrder> orderRefs = lookup(ordersByDocument.values().stream().flatMap(List::stream),
                ids -> orders.findAllInCompany(ids, companyId));
        Map<UUID, int[]> countsByStop = new HashMap<>();
        Map<UUID, List<Stop>> stopsByTrip = stops.stream().collect(Collectors.groupingBy(Stop::tripId));
        for (TrackedDocument document : documents) {
            List<Stop> tripStops = stopsByTrip.getOrDefault(document.tripId(), List.of());
            ordersByDocument.getOrDefault(document.id(), List.of()).stream()
                    .map(orderRefs::get).filter(Objects::nonNull).map(PlannableOrder::destinationId)
                    .filter(Objects::nonNull)
                    .flatMap(destination -> tripStops.stream().filter(stop -> destination.equals(stop.destinationId())))
                    // The earliest stop, as the portal places it: one document, one place, on both screens.
                    .min(Comparator.comparingInt(Stop::sequence))
                    .ifPresent(stop -> {
                        int[] counts = countsByStop.computeIfAbsent(stop.stopId(), key -> new int[5]);
                        counts[0]++;
                        switch (document.status()) {
                            case "PENDING" -> counts[1]++;
                            case "DELIVERED" -> counts[2]++;
                            case "REJECTED" -> counts[3]++;
                            case "NOT_DELIVERED" -> counts[4]++;
                            default -> { }
                        }
                    });
        }

        List<ShipmentTrackingRowView> result = new ArrayList<>(rows.size());
        for (Row row : rows) {
            MasterReference origin = row.planningRunId() == null ? null
                    : get(originRefs, originByRun.get(row.planningRunId()));
            VehicleCapacityReference vehicle = get(plannedVehicles, row.vehicleId());
            DriverReference driver = get(plannedDrivers, row.driverId());
            List<ShipmentTrackingStopView> stopViews = stopsByTrip.getOrDefault(row.tripId(), List.of()).stream()
                    .map(stop -> {
                        MasterReference destination = get(destinationRefs, stop.destinationId());
                        int[] counts = countsByStop.getOrDefault(stop.stopId(), new int[5]);
                        return new ShipmentTrackingStopView(stop.stopId(), stop.sequence(),
                                destination == null ? null : destination.name(),
                                destination == null ? null : destination.address(),
                                destination == null ? null : destination.latitude(),
                                destination == null ? null : destination.longitude(),
                                stop.executionStatus(), counts[0], counts[1], counts[2], counts[3], counts[4]);
                    }).toList();
            result.add(new ShipmentTrackingRowView(row.tripId(), row.shipmentNumber(), row.tripStatus(),
                    row.planningDate(), row.carrierId(), row.carrierId() == null ? null : carrierNames.get(row.carrierId()),
                    row.dispatchReference(), row.dispatchedAt(), row.dispatchVerification(),
                    row.dispatchPlate() != null ? row.dispatchPlate() : vehicle == null ? null : vehicle.licensePlate(),
                    row.dispatchDriverName() != null ? row.dispatchDriverName()
                            : driver == null ? null : driver.fullName(),
                    origin == null ? null : origin.name(), origin == null ? null : origin.latitude(),
                    origin == null ? null : origin.longitude(), row.stopsTotal(), row.stopsResolved(),
                    row.documentCount(), row.pendingCount(), row.deliveredCount(), row.rejectedCount(),
                    row.notDeliveredCount(), row.returnedCount(), row.inconsistentCount(), row.lastReportAt(),
                    stopViews));
        }
        return result;
    }

    private static ShipmentTrackingQueries.Filter resolve(CompanyScope scope, ShipmentTrackingFilter filter) {
        ZoneId zone = scope.zoneId();
        LocalDate from = filter == null ? null : filter.dateFrom();
        LocalDate to = filter == null ? null : filter.dateTo();
        if (from == null && to == null) {
            from = scope.today();
            to = from;
        } else if (from == null) {
            from = to;
        } else if (to == null) {
            to = from;
        }
        if (to.isBefore(from)) {
            throw new InvalidRequestException("dateTo must not be before dateFrom.");
        }
        if (ChronoUnit.DAYS.between(from, to) > MAX_RANGE_DAYS) {
            throw new InvalidRequestException("The date range may span at most " + MAX_RANGE_DAYS + " days.");
        }
        return new ShipmentTrackingQueries.Filter(scope.companyId(), from.atStartOfDay(zone).toOffsetDateTime(),
                to.plusDays(1).atStartOfDay(zone).toOffsetDateTime(), filter == null ? null : filter.carrierId(),
                filter == null ? null : filter.status(), filter == null ? null : filter.documents(),
                filter == null ? null : DocumentDeliveryMonitorService.searchPattern(filter.q()));
    }

    private static <T> Map<UUID, T> lookup(Stream<UUID> ids, Function<Set<UUID>, Map<UUID, T>> loader) {
        Set<UUID> wanted = ids.filter(Objects::nonNull).collect(Collectors.toSet());
        return wanted.isEmpty() ? Map.of() : loader.apply(wanted);
    }

    /** {@code Map.of()} refuses a null key. */
    private static <T> T get(Map<UUID, T> map, UUID id) {
        return id == null ? null : map.get(id);
    }
}
