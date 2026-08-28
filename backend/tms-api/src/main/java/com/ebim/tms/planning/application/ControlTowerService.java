package com.ebim.tms.planning.application;

import com.ebim.tms.planning.domain.DepartureDelay;
import com.ebim.tms.planning.domain.StopExecutionStatus;
import com.ebim.tms.planning.domain.StopServiceWindow;
import com.ebim.tms.planning.domain.Trip;
import com.ebim.tms.planning.domain.TripException;
import com.ebim.tms.planning.domain.TripExceptionStatus;
import com.ebim.tms.planning.domain.TripStatus;
import com.ebim.tms.planning.domain.TripStop;
import com.ebim.tms.planning.infrastructure.TripExceptionRepository;
import com.ebim.tms.planning.infrastructure.TripRepository;
import com.ebim.tms.planning.infrastructure.TripStopRepository;
import com.ebim.tms.shared.api.PageQuery;
import com.ebim.tms.shared.api.PageResponse;
import com.ebim.tms.shared.reference.DestinationLookupPort;
import com.ebim.tms.shared.reference.MasterReference;
import com.ebim.tms.shared.reference.OrderPlanningPort;
import com.ebim.tms.shared.reference.PlannableOrderQuery;
import com.ebim.tms.shared.reference.VehicleCapacityReference;
import com.ebim.tms.shared.reference.ResourceAvailabilityPort;
import com.ebim.tms.shared.reference.VehicleLookupPort;
import com.ebim.tms.shared.security.CompanyScope;
import com.ebim.tms.shared.security.Permission;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.Comparator;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The control tower ({@code docs/domain/CONTROL_TOWER_V1.md}): one operating day, read-only,
 * answering the seven questions a transport supervisor asks between 06:00 and 18:00 - what is
 * leaving, what is late, what has an open problem, what is on the road, which deliveries have run
 * past their window, what is still unplanned, and which trucks are carrying the most.
 *
 * <p><b>It owns no state and no rule of its own.</b> Every fact it returns already belongs to
 * something: the lifecycle is {@code TripStatus}, the delay verdicts are {@code DepartureDelay} and
 * {@code StopServiceWindow}, the capacity numbers are {@code TripViewAssembler}'s, the shipment
 * header is {@code TripView}. A control tower that re-derived any of them would be a second,
 * quietly diverging opinion about the same day - which is the failure mode dashboards have. This
 * class is a composition, and the only thing it decides is <em>what is worth showing</em>.
 *
 * <p><b>Why it lives in {@code planning} and not in a module of its own.</b> Everything it reads is
 * planning's - trips, their stops, their exceptions - and a {@code monitoring} package would have
 * to reach all three through new ports to satisfy {@code ModuleBoundaryTest}, buying a boundary
 * with nothing on the other side of it. The one thing it reads from elsewhere, the unplanned-order
 * backlog, goes through {@link OrderPlanningPort} exactly as the rest of planning does.
 * Authorization is another matter and does not follow the package: both endpoints are behind
 * {@code monitoring.transport:read}, which has meant "see where the transport is" since migration
 * V3 - see {@code ControlTowerController}.
 *
 * <h2>What is scoped by what</h2>
 *
 * <p>The KPI strip and the three panels are scoped by <em>company and day</em>. The operational
 * table is additionally scoped by the origin, carrier and status the operator picked. That
 * asymmetry is deliberate and is the screen's central design decision: the headline numbers are
 * the day's whole picture, so narrowing to one carrier can never make an open exception on another
 * carrier disappear from the count of open exceptions. Filters drill; they do not blind.
 *
 * <h2>Query budget</h2>
 *
 * <p>The overview costs a fixed number of statements, none of which returns a row per trip:
 * one grouped status count, two departure counts, two stop counts, one exception count, one
 * assignable-order count, and then the three panels - each a capped fetch plus the batched lookups
 * behind it. The board costs {@code TripService.list}'s fixed set plus one stop query and one
 * grouped exception count for the page. Nothing here is N+1, and nothing downloads a table so the
 * browser can count it.
 */
@Service
public class ControlTowerService {

    /**
     * How many rows each side panel carries. Twenty is about what fits on a monitor without
     * scrolling, and the total always travels beside it in the summary, so a capped list reads as
     * "the worst twenty of forty-seven" and never as "forty-seven".
     */
    private static final int PANEL_SIZE = 20;

    /** How many shipments the workload panel shows. Five is a glance, not a report. */
    private static final int WORKLOAD_SIZE = 5;

    /**
     * The most trips the workload ranking will read before ranking them.
     *
     * <p>Utilisation cannot be ordered in SQL - a draft trip's limit lives on the vehicle master,
     * behind {@link VehicleLookupPort} - so the candidates are loaded and ranked in Java. In normal
     * use the set is bounded by the fleet, because a vehicle may hold only one active trip per
     * planning date ({@code uq_trip_vehicle_active_planning_date}, migration V16), which the scale
     * target puts at 100-300. This is the backstop for a day that is not normal: it bounds the read
     * rather than letting one pathological date decide how much memory a dashboard refresh costs.
     */
    private static final int WORKLOAD_SCAN_LIMIT = 400;

    /**
     * The trips whose load is worth ranking: committed and not yet finished. A draft is not loaded
     * onto anything in a binding sense and a completed trip has already been unloaded, so neither
     * answers "which truck is carrying the most right now".
     */
    private static final Set<TripStatus> WORKLOAD_STATES =
            Set.copyOf(EnumSet.of(TripStatus.CONFIRMED, TripStatus.READY_FOR_DISPATCH, TripStatus.IN_TRANSIT));

    /**
     * Derived from {@link StopExecutionStatus#isOutstanding()} rather than listed, so adding an
     * outcome to the enum cannot leave this screen silently counting the wrong stops.
     */
    /**
     * The states in which a blocker still matters: committed, and not yet gone.
     *
     * <p>A draft is not going anywhere today either, but nothing is stopping it - it simply has not
     * been committed - and listing drafts here would bury the shipments somebody has to act on.
     */
    private static final Set<TripStatus> BLOCKABLE_STATES =
            Set.copyOf(EnumSet.of(TripStatus.CONFIRMED, TripStatus.READY_FOR_DISPATCH));

    private static final Set<StopExecutionStatus> OUTSTANDING_STOPS = Stream.of(StopExecutionStatus.values())
            .filter(StopExecutionStatus::isOutstanding)
            .collect(Collectors.toUnmodifiableSet());

    private final TripService tripService;
    private final TripViewAssembler assembler;
    private final TripRepository tripRepository;
    private final TripStopRepository tripStopRepository;
    private final TripExceptionRepository tripExceptionRepository;
    private final DestinationLookupPort destinationLookupPort;
    private final VehicleLookupPort vehicleLookupPort;
    private final OrderPlanningPort orderPlanningPort;
    private final ResourceAvailabilityPort resourceAvailabilityPort;

    public ControlTowerService(TripService tripService, TripViewAssembler assembler, TripRepository tripRepository,
            TripStopRepository tripStopRepository, TripExceptionRepository tripExceptionRepository,
            DestinationLookupPort destinationLookupPort, VehicleLookupPort vehicleLookupPort,
            OrderPlanningPort orderPlanningPort, ResourceAvailabilityPort resourceAvailabilityPort) {
        this.resourceAvailabilityPort = resourceAvailabilityPort;
        this.tripService = tripService;
        this.assembler = assembler;
        this.tripRepository = tripRepository;
        this.tripStopRepository = tripStopRepository;
        this.tripExceptionRepository = tripExceptionRepository;
        this.destinationLookupPort = destinationLookupPort;
        this.vehicleLookupPort = vehicleLookupPort;
        this.orderPlanningPort = orderPlanningPort;
    }

    /**
     * The day's overview: the KPI strip and the three panels.
     *
     * <p>One {@code now} is taken at the top and threaded through everything that depends on the
     * clock, so a trip cannot be counted as overdue by the summary and reported as scheduled by the
     * panel two statements later. It is returned to the caller as
     * {@code ControlTowerView.generatedAt} for the same reason.
     */
    @Transactional(readOnly = true)
    public ControlTowerView overview(CompanyScope scope, ControlTowerFilter filter) {
        LocalDate date = dateOf(scope, filter);
        OffsetDateTime now = OffsetDateTime.now();
        ZoneId zone = scope.zoneId();

        List<ControlTowerExceptionView> exceptions = openExceptions(scope, date);
        List<ControlTowerStopView> stops = outstandingStops(scope, date, zone, now);

        List<ControlTowerBlockerView> blockers = blockers(scope, date);

        return new ControlTowerView(date, now, summary(scope, date, zone, now, blockers.size()),
                workload(scope, date), exceptions, stops, blockers);
    }

    /**
     * The operational table: today's shipments, narrowed by the operator's filters, with the four
     * monitoring facts a control tower adds to a trip row.
     *
     * <p>Delegates the page itself to {@link TripService#list} rather than composing its own
     * specification: the filter, the sort allow-list and the shipment header are already decided
     * there, and a second copy would be the place they start to disagree. What this adds is one
     * stop query and one grouped exception count <em>for the page</em> - never per row.
     */
    @Transactional(readOnly = true)
    public PageResponse<ControlTowerTripView> board(
            CompanyScope scope, ControlTowerFilter filter, PageQuery pageQuery) {
        LocalDate date = dateOf(scope, filter);
        OffsetDateTime now = OffsetDateTime.now();
        ZoneId zone = scope.zoneId();

        PageResponse<TripView> page = tripService.list(scope,
                new TripFilter(null, filter.status(), filter.originId(), filter.carrierId(), null, null, date, date),
                pageQuery);
        if (page.content().isEmpty()) {
            return new PageResponse<>(List.of(), page.page(), page.size(), page.totalElements());
        }

        List<UUID> tripIds = page.content().stream().map(TripView::id).toList();
        Map<UUID, List<TripStop>> stopsByTrip = tripStopRepository.findByTripIds(tripIds).stream()
                .collect(Collectors.groupingBy(TripStop::tripId));
        Map<UUID, Long> openExceptionCounts = tripExceptionRepository
                .countByStatusAndTripIds(scope.companyId(), TripExceptionStatus.OPEN, tripIds).stream()
                .collect(Collectors.toMap(TripExceptionRepository.TripExceptionCount::getTripId,
                        TripExceptionRepository.TripExceptionCount::getExceptionCount));

        List<ControlTowerTripView> rows = page.content().stream()
                .map(trip -> row(trip, stopsByTrip.getOrDefault(trip.id(), List.of()),
                        openExceptionCounts.getOrDefault(trip.id(), 0L), zone, now))
                .toList();
        return new PageResponse<>(rows, page.page(), page.size(), page.totalElements());
    }

    // --- the KPI strip -------------------------------------------------------------------

    /**
     * What will stop a truck today, before it stops it (JOB 12).
     *
     * <p>Every other panel reports what has already happened. This reports the states that make
     * {@code TripExecutionService.dispatch} refuse - so a dispatcher learns at 06:00 rather than at
     * the gate. Nothing here is a new rule: each reason is a refusal that already exists in the
     * service, the aggregate and the database.
     *
     * <p>Only shipments that have not left. One already in transit either resolved its blocker or
     * never had one, and a cancelled shipment is not going anywhere regardless.
     */
    private List<ControlTowerBlockerView> blockers(CompanyScope scope, LocalDate date) {
        List<ControlTowerBlockerView> blockers = new ArrayList<>();

        for (Trip trip : tripRepository.findAwaitingCarrierVehicleForDay(
                scope.companyId(), date, BLOCKABLE_STATES)) {
            blockers.add(new ControlTowerBlockerView(trip.id(), trip.tripNumber(), trip.shipmentNumber(),
                    ControlTowerBlockerView.BlockerReason.AWAITING_CARRIER_VEHICLE,
                    "Accepted by a carrier that does not own the vehicle assigned to it."));
        }

        // Availability lives in the fleet module, so it is asked through the port rather than
        // joined in SQL - the boundary ModuleBoundaryTest enforces. Asked at the shipment's own
        // planned departure and not at now(): a truck free this minute and in the workshop at 14:00
        // still cannot run a 14:00 shipment, and that is the one a dispatcher needs to hear about.
        for (Trip trip : tripRepository.findAwaitingDepartureWithResourcesForDay(
                scope.companyId(), date, BLOCKABLE_STATES)) {
            if (trip.plannedDepartureAt() == null) {
                continue;
            }
            resourceAvailabilityPort
                    .findBlock(scope.companyId(), trip.vehicleId(), trip.driverId(), trip.plannedDepartureAt())
                    .ifPresent(block -> blockers.add(new ControlTowerBlockerView(
                            trip.id(), trip.tripNumber(), trip.shipmentNumber(),
                            "vehicle".equals(block.resource())
                                    ? ControlTowerBlockerView.BlockerReason.VEHICLE_UNAVAILABLE
                                    : ControlTowerBlockerView.BlockerReason.DRIVER_UNAVAILABLE,
                            "Unavailable (" + block.reason() + ") until " + block.endsAt() + ".")));
        }

        // Capped like every other panel, with the true total travelling in the summary - so a
        // shortened list reads as "the first twenty of forty" and never as "forty".
        return blockers.size() <= PANEL_SIZE ? List.copyOf(blockers) : List.copyOf(blockers.subList(0, PANEL_SIZE));
    }

    private ControlTowerSummaryView summary(CompanyScope scope, LocalDate date, ZoneId zone, OffsetDateTime now,
            int blockedShipments) {
        UUID companyId = scope.companyId();
        Map<TripStatus, Long> byStatus = new EnumMap<>(TripStatus.class);
        tripRepository.countByStatusForDay(companyId, date)
                .forEach(count -> byStatus.put(count.getStatus(), count.getTripCount()));

        long outstandingStops = tripStopRepository.countOutstandingForDay(
                companyId, date, TripStatus.IN_TRANSIT, OUTSTANDING_STOPS);
        // Skipped entirely for a day that has not started: no window on a future date has closed,
        // and asking the database to confirm that is a query whose answer is known.
        long stopsPastWindow = windowCutoff(date, zone, now)
                .map(cutoff -> tripStopRepository.countOutstandingPastWindowForDay(
                        companyId, date, TripStatus.IN_TRANSIT, OUTSTANDING_STOPS, cutoff))
                .orElse(0L);

        return new ControlTowerSummaryView(
                byStatus.getOrDefault(TripStatus.DRAFT, 0L),
                byStatus.getOrDefault(TripStatus.CONFIRMED, 0L)
                        + byStatus.getOrDefault(TripStatus.READY_FOR_DISPATCH, 0L),
                byStatus.getOrDefault(TripStatus.IN_TRANSIT, 0L),
                byStatus.getOrDefault(TripStatus.COMPLETED, 0L),
                byStatus.getOrDefault(TripStatus.CANCELLED, 0L),
                tripRepository.countDepartedLateForDay(companyId, date),
                tripRepository.countOverdueDepartureForDay(companyId, date, now, DepartureDelay.AWAITING_DEPARTURE),
                tripExceptionRepository.countByStatusForDay(companyId, TripExceptionStatus.OPEN, date),
                outstandingStops,
                stopsPastWindow,
                unplannedOrders(scope, date),
                blockedShipments);
    }

    /**
     * How much of the day nobody has planned yet, or null when the caller may not be told.
     *
     * <p>The endpoint is behind {@code monitoring.transport:read}, which is about transport and not
     * about orders. A dispatcher who holds it without {@code orders.order:read} still gets the
     * screen; they simply do not get this figure, and they get null rather than zero, because zero
     * would be this response asserting an empty backlog it was not allowed to look at.
     *
     * <p>Asked for with a page size of one: the rows are thrown away and only the server's total is
     * kept, which is the cheapest count the port exposes.
     */
    private Long unplannedOrders(CompanyScope scope, LocalDate date) {
        if (!scope.has(Permission.ORDERS_ORDER_READ)) {
            return null;
        }
        return orderPlanningPort.searchAssignable(
                new PlannableOrderQuery(scope.companyId(), null, null, date, null),
                new PageQuery(0, 1, null)).totalElements();
    }

    /**
     * What local time to treat as "now" when asking which service windows have closed on
     * {@code date}, or empty when none of them can have.
     *
     * <p>This is what lets the count be a plain indexed comparison on a {@link LocalTime} column
     * instead of a per-row time-zone conversion in SQL: every stop in the count shares one planning
     * date, so within it local order is absolute order. A date already past is asked with
     * end-of-day, a date still ahead is not asked at all, and today is asked with the company's own
     * wall clock - never the server's, for the reason {@code ShipmentTimeRules} gives.
     */
    private static Optional<LocalTime> windowCutoff(LocalDate date, ZoneId zone, OffsetDateTime now) {
        LocalDate today = now.atZoneSameInstant(zone).toLocalDate();
        if (date.isAfter(today)) {
            return Optional.empty();
        }
        // LocalTime.MAX rather than a separate "everything" branch: window ends are stored to the
        // minute, so nothing real sits in the nanosecond this leaves out.
        return Optional.of(date.isBefore(today) ? LocalTime.MAX : now.atZoneSameInstant(zone).toLocalTime());
    }

    // --- panels --------------------------------------------------------------------------

    /**
     * The fullest shipments of the day, worst first.
     *
     * <p>Two passes on purpose: the cheap one ranks every candidate on capacity alone
     * ({@link TripViewAssembler#capacitiesOf}, two queries), and the expensive one resolves the
     * full shipment header for the five that won. Resolving carriers, vehicles, drivers and routes
     * for four hundred trips to show five would be four hundred trips' worth of lookups thrown
     * away.
     *
     * <p>Trips whose utilisation is unknown - no vehicle yet, or a vehicle that declares no limit -
     * are left out rather than ranked as 0%: "we do not know how full this is" is not a position in
     * a list of the fullest.
     */
    private List<ControlTowerWorkloadView> workload(CompanyScope scope, LocalDate date) {
        List<Trip> candidates = tripRepository.findByCompanyIdAndPlanningDateAndStatusIn(
                scope.companyId(), date, WORKLOAD_STATES, PageRequest.of(0, WORKLOAD_SCAN_LIMIT));
        if (candidates.isEmpty()) {
            return List.of();
        }
        Map<UUID, TripCapacityView> capacities = assembler.capacitiesOf(candidates, scope.companyId());

        record Ranked(Trip trip, BigDecimal percentUsed) {}
        List<Ranked> ranked = candidates.stream()
                .map(trip -> new Ranked(trip, worstPercentUsed(capacities.get(trip.id()))))
                .filter(candidate -> candidate.percentUsed() != null)
                // Explicitly typed lambda rather than Ranked::percentUsed: a method reference
                // whose receiver is then .reversed() has nothing to infer its type from.
                .sorted(Comparator.comparing((Ranked candidate) -> candidate.percentUsed()).reversed())
                .limit(WORKLOAD_SIZE)
                .toList();
        if (ranked.isEmpty()) {
            return List.of();
        }

        List<TripView> views = assembler.toViews(ranked.stream().map(Ranked::trip).toList(), scope.companyId());
        return IntStream.range(0, views.size())
                .mapToObj(index -> new ControlTowerWorkloadView(views.get(index), ranked.get(index).percentUsed()))
                .toList();
    }

    /**
     * The heaviest of the three dimensions - the one that decides whether a truck is full. Null
     * when none of them has a percentage, which {@code CapacityDimension} documents as unlimited or
     * a zero limit, and which is a different statement from 0%.
     */
    private static BigDecimal worstPercentUsed(TripCapacityView capacity) {
        if (capacity == null) {
            return null;
        }
        return Stream.of(capacity.weight(), capacity.volume(), capacity.pallets())
                .map(CapacityDimension::percentUsed)
                .filter(Objects::nonNull)
                .max(Comparator.naturalOrder())
                .orElse(null);
    }

    private List<ControlTowerExceptionView> openExceptions(CompanyScope scope, LocalDate date) {
        List<TripException> exceptions = tripExceptionRepository.findByStatusForDay(
                scope.companyId(), TripExceptionStatus.OPEN, date, PageRequest.of(0, PANEL_SIZE));
        if (exceptions.isEmpty()) {
            return List.of();
        }
        Map<UUID, Trip> trips = tripsById(scope,
                exceptions.stream().map(TripException::tripId).collect(Collectors.toSet()));
        return exceptions.stream().map(exception -> {
            Trip trip = trips.get(exception.tripId());
            return new ControlTowerExceptionView(exception.id(), exception.tripId(),
                    trip == null ? null : trip.shipmentNumber(), trip == null ? null : trip.status(),
                    exception.tripStopId(), exception.exceptionType(), exception.reportedAt(), exception.notes());
        }).toList();
    }

    /**
     * The stops still to be worked on shipments that are out, most overdue first.
     *
     * <p>The repository orders them by planned window end, which within one planning date is the
     * same order as the instants they resolve to - so the cap keeps the twenty most urgent rather
     * than twenty arbitrary ones. Lateness itself is judged here, by {@code StopServiceWindow},
     * because it needs the company's zone and the one shared {@code now}.
     */
    private List<ControlTowerStopView> outstandingStops(
            CompanyScope scope, LocalDate date, ZoneId zone, OffsetDateTime now) {
        List<TripStop> stops = tripStopRepository.findOutstandingForDay(
                scope.companyId(), date, TripStatus.IN_TRANSIT, OUTSTANDING_STOPS, PageRequest.of(0, PANEL_SIZE));
        if (stops.isEmpty()) {
            return List.of();
        }
        Map<UUID, Trip> trips = tripsById(scope, stops.stream().map(TripStop::tripId).collect(Collectors.toSet()));
        Map<UUID, MasterReference> destinations = destinationLookupPort.findAllInCompany(
                stops.stream().map(TripStop::destinationId).collect(Collectors.toSet()), scope.companyId());
        Set<UUID> vehicleIds = trips.values().stream()
                .map(Trip::vehicleId).filter(Objects::nonNull).collect(Collectors.toSet());
        Map<UUID, VehicleCapacityReference> vehicles = vehicleIds.isEmpty()
                ? Map.of()
                : vehicleLookupPort.findAllInCompany(vehicleIds, scope.companyId());

        return stops.stream().map(stop -> {
            Trip trip = trips.get(stop.tripId());
            MasterReference destination = destinations.get(stop.destinationId());
            VehicleCapacityReference vehicle =
                    trip == null || trip.vehicleId() == null ? null : vehicles.get(trip.vehicleId());
            // A stop whose trip somehow did not resolve keeps its window unjudged rather than being
            // measured against today: the date the window belongs to is the trip's, and guessing it
            // would be exactly the invented lateness this module refuses to produce.
            StopServiceWindow window = trip == null
                    ? StopServiceWindow.NONE
                    : StopServiceWindow.of(trip.planningDate(), stop.serviceWindowEnd(), zone,
                            stop.executionStatus(), stop.actualArrivalAt(), now);
            return new ControlTowerStopView(stop.id(), stop.tripId(),
                    trip == null ? null : trip.shipmentNumber(), stop.sequence(), stop.destinationId(),
                    destination == null ? null : destination.code(),
                    destination == null ? null : destination.name(),
                    stop.executionStatus(), stop.serviceWindowStart(), stop.serviceWindowEnd(),
                    window.endsAt(), window.minutesPastWindow(), stop.actualArrivalAt(),
                    vehicle == null ? null : vehicle.licensePlate());
        }).toList();
    }

    // --- board rows ----------------------------------------------------------------------

    private static ControlTowerTripView row(TripView trip, List<TripStop> stops, long openExceptions,
            ZoneId zone, OffsetDateTime now) {
        DepartureDelay delay = DepartureDelay.of(
                trip.status(), trip.plannedDepartureAt(), trip.actualDepartureAt(), now);

        int resolved = 0;
        int pastWindow = 0;
        TripStop next = null;
        for (TripStop stop : stops) {
            if (stop.executionStatus().isResolved()) {
                resolved++;
            } else if (next == null || stop.sequence() < next.sequence()) {
                next = stop;
            }
            if (windowOf(trip, stop, zone, now).isPastWindow()) {
                pastWindow++;
            }
        }

        StopServiceWindow nextWindow = next == null ? StopServiceWindow.NONE : windowOf(trip, next, zone, now);
        return new ControlTowerTripView(trip, delay.timeliness(), delay.minutes(), stops.size(), resolved,
                pastWindow, next == null ? null : next.sequence(), nextWindow.endsAt(), openExceptions);
    }

    private static StopServiceWindow windowOf(TripView trip, TripStop stop, ZoneId zone, OffsetDateTime now) {
        return StopServiceWindow.of(trip.planningDate(), stop.serviceWindowEnd(), zone,
                stop.executionStatus(), stop.actualArrivalAt(), now);
    }

    // --- shared helpers ------------------------------------------------------------------

    /**
     * The company's own today when the caller named no date - never the server's, and never one the
     * browser computed. See {@code ControlTowerFilter}.
     */
    private static LocalDate dateOf(CompanyScope scope, ControlTowerFilter filter) {
        return filter.date() == null ? scope.today() : filter.date();
    }

    private Map<UUID, Trip> tripsById(CompanyScope scope, Set<UUID> ids) {
        if (ids.isEmpty()) {
            return Map.of();
        }
        return tripRepository.findByIdInAndCompanyId(ids, scope.companyId()).stream()
                .collect(Collectors.toMap(Trip::id, Function.identity()));
    }
}
