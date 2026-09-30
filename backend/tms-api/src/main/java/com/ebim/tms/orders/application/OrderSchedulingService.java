package com.ebim.tms.orders.application;

import com.ebim.tms.orders.domain.OrderHold;
import com.ebim.tms.orders.domain.OrderStatus;
import com.ebim.tms.orders.domain.SchedulingAssessment;
import com.ebim.tms.orders.domain.TransportOrder;
import com.ebim.tms.orders.infrastructure.OrderHoldRepository;
import com.ebim.tms.orders.infrastructure.OrderSchedulingSpecifications;
import com.ebim.tms.orders.infrastructure.TransportOrderRepository;
import com.ebim.tms.shared.api.InvalidRequestException;
import com.ebim.tms.shared.api.PageQuery;
import com.ebim.tms.shared.api.PageResponse;
import com.ebim.tms.shared.api.ResourceNotFoundException;
import com.ebim.tms.shared.reference.CalendarVerdict;
import com.ebim.tms.shared.reference.DestinationLookupPort;
import com.ebim.tms.shared.reference.MasterReference;
import com.ebim.tms.shared.reference.OriginLookupPort;
import com.ebim.tms.shared.reference.RouteResolution;
import com.ebim.tms.shared.reference.RouteResolutionPort;
import com.ebim.tms.shared.reference.RouteResolutionPort.OriginDestination;
import com.ebim.tms.shared.reference.ServiceCalendarPort;
import com.ebim.tms.shared.security.CompanyScope;
import java.time.Clock;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The Scheduling and Release read model (ADR-014 section 4): for each order, whether it may be
 * released right now and why - a composition over route resolution ({@link RouteResolutionPort}),
 * the destination and route calendars ({@link ServiceCalendarPort}), the release deadline, the
 * required data and the holds. The rule itself is {@link SchedulingAssessment}; this class only
 * loads its facts.
 *
 * <p><b>Nothing here is stored.</b> Eligibility is not an order state: it is recomputed on every
 * read, so a route or a frequency edited in the master data changes the answer at the next read.
 *
 * <p><b>Batched, never per row.</b> Assessing a page costs a fixed number of queries - origins,
 * destinations, holds, one route query for every distinct origin/destination pair, and the calendars
 * once per distinct dispatch date - whatever the page size.
 */
@Service
public class OrderSchedulingService {

    /**
     * How many orders one board read will evaluate when it has to filter on something derived
     * (route, eligibility, frequency). A ceiling, refused rather than silently cut - the
     * {@code AutoPlanningService.MAX_ORDERS_PER_RUN} reasoning: a planner told "12 blocked" about a
     * truncated set has been misled.
     */
    static final int MAX_EVALUATED = 5000;

    private static final Set<OrderStatus> BOARD_STATUSES =
            EnumSet.of(OrderStatus.NOT_READY, OrderStatus.READY_FOR_PLANNING);

    private static final Set<String> SORTABLE_PROPERTIES =
            Set.of("orderNumber", "serviceDate", "priority", "status", "customerName", "createdAt");

    private final TransportOrderRepository transportOrderRepository;
    private final OrderHoldRepository holdRepository;
    private final OriginLookupPort originLookupPort;
    private final DestinationLookupPort destinationLookupPort;
    private final RouteResolutionPort routeResolutionPort;
    private final ServiceCalendarPort serviceCalendarPort;
    private final Clock clock;

    public OrderSchedulingService(TransportOrderRepository transportOrderRepository,
            OrderHoldRepository holdRepository, OriginLookupPort originLookupPort,
            DestinationLookupPort destinationLookupPort, RouteResolutionPort routeResolutionPort,
            ServiceCalendarPort serviceCalendarPort, Clock clock) {
        this.transportOrderRepository = transportOrderRepository;
        this.holdRepository = holdRepository;
        this.originLookupPort = originLookupPort;
        this.destinationLookupPort = destinationLookupPort;
        this.routeResolutionPort = routeResolutionPort;
        this.serviceCalendarPort = serviceCalendarPort;
        this.clock = clock;
    }

    /** One order's eligibility - what the release gate asks inside its own transaction. */
    @Transactional(readOnly = true)
    public SchedulingAssessment assess(CompanyScope scope, TransportOrder order) {
        return assessAll(scope, List.of(order)).assessments().get(order.id());
    }

    /** One order as the board shows it, for the reasons side panel. */
    @Transactional(readOnly = true)
    public SchedulingRowView row(CompanyScope scope, UUID orderId) {
        TransportOrder order = transportOrderRepository.findByIdAndCompanyId(orderId, scope.companyId())
                .orElseThrow(() -> new ResourceNotFoundException("Order not found."));
        return rows(scope, List.of(order)).get(0);
    }

    /**
     * The board. Filters on stored columns narrow in SQL and page in SQL; a derived filter (route,
     * eligibility, frequency) evaluates the whole matching set - refused above {@link #MAX_EVALUATED}
     * - and pages what survives.
     */
    @Transactional(readOnly = true)
    public PageResponse<SchedulingRowView> search(CompanyScope scope, SchedulingFilter filter, PageQuery pageQuery) {
        Specification<TransportOrder> specification = specificationOf(scope, filter);
        Sort sort = sortOf(pageQuery);

        if (!filter.hasDerivedFilter()) {
            Page<TransportOrder> page = transportOrderRepository.findAll(specification,
                    PageRequest.of(pageQuery.pageNumber(), pageQuery.pageSize(), sort));
            return new PageResponse<>(rows(scope, page.getContent()), pageQuery.pageNumber(), pageQuery.pageSize(),
                    page.getTotalElements());
        }

        List<SchedulingRowView> matching = rows(scope, loadBounded(specification, sort)).stream()
                .filter(row -> matchesDerived(row, filter))
                .toList();
        int from = Math.min(pageQuery.offset(), matching.size());
        int to = Math.min(from + pageQuery.pageSize(), matching.size());
        return new PageResponse<>(matching.subList(from, to), pageQuery.pageNumber(), pageQuery.pageSize(),
                matching.size());
    }

    /** The summary strip: the same filters as {@link #search}, counted by origin, route and dispatch date. */
    @Transactional(readOnly = true)
    public SchedulingSummaryView summary(CompanyScope scope, SchedulingFilter filter) {
        Specification<TransportOrder> specification = specificationOf(scope, filter);
        List<SchedulingRowView> rows = rows(scope,
                loadBounded(specification, Sort.by(Sort.Order.asc("serviceDate"), Sort.Order.asc("orderNumber"))))
                .stream()
                .filter(row -> matchesDerived(row, filter))
                .toList();

        record GroupKey(LocalDate date, UUID originId, String routeCode) {
        }
        Map<GroupKey, List<SchedulingRowView>> grouped = rows.stream().collect(Collectors.groupingBy(
                row -> new GroupKey(row.scheduledDispatchDate(), row.originId(), row.routeCode()),
                LinkedHashMap::new, Collectors.toList()));

        List<SchedulingSummaryView.Group> groups = grouped.entrySet().stream()
                .sorted(Comparator.comparing((Map.Entry<GroupKey, List<SchedulingRowView>> entry) -> entry.getKey().date())
                        .thenComparing(entry -> Objects.toString(entry.getValue().get(0).originCode(), ""))
                        .thenComparing(entry -> Objects.toString(entry.getKey().routeCode(), "~")))
                .map(entry -> {
                    SchedulingRowView first = entry.getValue().get(0);
                    return new SchedulingSummaryView.Group(first.originId(), first.originCode(), first.originName(),
                            first.routeCode(), first.routeName(), first.scheduledDispatchDate(),
                            countsOf(entry.getValue()));
                })
                .toList();
        return new SchedulingSummaryView(countsOf(rows), groups);
    }

    // --- assessment ------------------------------------------------------------------------

    /** Everything a page's assessment needs, loaded once. */
    private record Assessed(Map<UUID, SchedulingAssessment> assessments, Map<UUID, Long> activeHolds,
            Map<UUID, Long> activeBlockingHolds) {
    }

    private Assessed assessAll(CompanyScope scope, List<TransportOrder> orders) {
        UUID companyId = scope.companyId();
        if (orders.isEmpty()) {
            return new Assessed(Map.of(), Map.of(), Map.of());
        }
        Set<UUID> orderIds = orders.stream().map(TransportOrder::id).collect(Collectors.toSet());
        Set<UUID> originIds = orders.stream().map(TransportOrder::originId).collect(Collectors.toSet());
        Set<UUID> destinationIds = orders.stream().map(TransportOrder::destinationId).collect(Collectors.toSet());

        Set<UUID> usableOrigins = originLookupPort.findActiveIdsInCompany(originIds, companyId);
        Set<UUID> usableDestinations = destinationLookupPort.findActiveIdsInCompany(destinationIds, companyId);

        Map<UUID, Long> activeHolds = new HashMap<>();
        Map<UUID, Long> activeBlocking = new HashMap<>();
        for (OrderHold hold : holdRepository.findActiveForOrders(orderIds, companyId)) {
            activeHolds.merge(hold.orderId(), 1L, Long::sum);
            if (hold.blocking()) {
                activeBlocking.merge(hold.orderId(), 1L, Long::sum);
            }
        }

        Map<OriginDestination, RouteResolution> routes = routeResolutionPort.resolveAll(companyId,
                orders.stream().map(order -> new OriginDestination(order.originId(), order.destinationId()))
                        .collect(Collectors.toSet()));

        // Calendars are per date, so they are asked once per distinct dispatch date on the page.
        Map<LocalDate, List<TransportOrder>> byDate = orders.stream()
                .collect(Collectors.groupingBy(TransportOrder::serviceDate));
        Map<LocalDate, Map<UUID, CalendarVerdict>> destinationCalendars = new HashMap<>();
        Map<LocalDate, Map<UUID, CalendarVerdict>> routeCalendars = new HashMap<>();
        byDate.forEach((date, ofDate) -> {
            destinationCalendars.put(date, serviceCalendarPort.locationCalendarsOn(
                    ofDate.stream().map(TransportOrder::destinationId).collect(Collectors.toSet()), date, companyId));
            Set<UUID> routeFrequencies = ofDate.stream()
                    .map(order -> routes.get(new OriginDestination(order.originId(), order.destinationId())))
                    .map(resolution -> resolution == null ? null
                            : resolution.route().map(RouteResolution.ResolvedRoute::frequencyId).orElse(null))
                    .filter(Objects::nonNull)
                    .collect(Collectors.toSet());
            routeCalendars.put(date, routeFrequencies.isEmpty()
                    ? Map.of() : serviceCalendarPort.frequencyCalendarsOn(routeFrequencies, date, companyId));
        });

        OffsetDateTime now = OffsetDateTime.now(clock);
        Map<UUID, SchedulingAssessment> assessments = new LinkedHashMap<>();
        for (TransportOrder order : orders) {
            RouteResolution route = routes.getOrDefault(
                    new OriginDestination(order.originId(), order.destinationId()), RouteResolution.NOT_CONFIGURED);
            UUID routeFrequency = route.route().map(RouteResolution.ResolvedRoute::frequencyId).orElse(null);
            CalendarVerdict routeCalendar = routeFrequency == null
                    ? CalendarVerdict.NOT_CONFIGURED
                    : routeCalendars.get(order.serviceDate()).getOrDefault(routeFrequency, CalendarVerdict.NOT_CONFIGURED);
            SchedulingAssessment.Facts facts = new SchedulingAssessment.Facts(order.serviceDate(),
                    usableOrigins.contains(order.originId()), usableDestinations.contains(order.destinationId()),
                    hasCapacity(order), activeBlocking.getOrDefault(order.id(), 0L), route,
                    destinationCalendars.get(order.serviceDate())
                            .getOrDefault(order.destinationId(), CalendarVerdict.NOT_CONFIGURED),
                    routeCalendar, scope.zoneId());
            assessments.put(order.id(), SchedulingAssessment.assess(facts, now));
        }
        return new Assessed(assessments, activeHolds, activeBlocking);
    }

    /** Today's mark-ready rule, unchanged: blocked only when weight, volume and pallets are all zero. */
    static boolean hasCapacity(TransportOrder order) {
        return order.totalWeightKg().signum() != 0 || order.totalVolumeM3().signum() != 0
                || order.totalPallets().signum() != 0;
    }

    private List<SchedulingRowView> rows(CompanyScope scope, List<TransportOrder> orders) {
        if (orders.isEmpty()) {
            return List.of();
        }
        Assessed assessed = assessAll(scope, orders);
        Map<UUID, MasterReference> origins = originLookupPort.findAllInCompany(
                orders.stream().map(TransportOrder::originId).collect(Collectors.toSet()), scope.companyId());
        Map<UUID, MasterReference> destinations = destinationLookupPort.findAllInCompany(
                orders.stream().map(TransportOrder::destinationId).collect(Collectors.toSet()), scope.companyId());
        return orders.stream()
                .map(order -> SchedulingRowView.of(order, origins.get(order.originId()),
                        destinations.get(order.destinationId()), assessed.assessments().get(order.id()),
                        assessed.activeHolds().getOrDefault(order.id(), 0L),
                        assessed.activeBlockingHolds().getOrDefault(order.id(), 0L)))
                .toList();
    }

    // --- filters --------------------------------------------------------------------------

    private static Specification<TransportOrder> specificationOf(CompanyScope scope, SchedulingFilter filter) {
        Set<OrderStatus> statuses = filter.status() == null ? BOARD_STATUSES : EnumSet.of(filter.status());
        return OrderSchedulingSpecifications.board(scope.companyId(), filter.orderNumber(), filter.originId(),
                filter.serviceDateFrom(), filter.serviceDateTo(), statuses, filter.priority(), filter.customer(),
                filter.hasHold());
    }

    private List<TransportOrder> loadBounded(Specification<TransportOrder> specification, Sort sort) {
        long matching = transportOrderRepository.count(specification);
        if (matching > MAX_EVALUATED) {
            throw new InvalidRequestException("These filters match " + matching + " orders; at most " + MAX_EVALUATED
                    + " can be evaluated at once. Narrow the dispatch dates or pick an origin.");
        }
        return transportOrderRepository.findAll(specification, sort);
    }

    static boolean matchesDerived(SchedulingRowView row, SchedulingFilter filter) {
        if (filter.eligibility() != null && row.eligibility() != filter.eligibility()) {
            return false;
        }
        if (SchedulingFilter.notBlank(filter.routeCode())) {
            String wanted = filter.routeCode().trim();
            boolean match = SchedulingFilter.NO_ROUTE.equalsIgnoreCase(wanted)
                    ? row.routeCode() == null
                    : wanted.equalsIgnoreCase(Objects.toString(row.routeCode(), ""));
            if (!match) {
                return false;
            }
        }
        if (SchedulingFilter.notBlank(filter.frequency())) {
            String wanted = filter.frequency().trim().toUpperCase(Locale.ROOT);
            return wanted.equalsIgnoreCase(Objects.toString(row.locationFrequencyCode(), ""))
                    || wanted.equalsIgnoreCase(Objects.toString(row.routeFrequencyCode(), ""));
        }
        return true;
    }

    private static SchedulingSummaryView.Counts countsOf(List<SchedulingRowView> rows) {
        long eligible = 0;
        long warning = 0;
        long blocked = 0;
        long withHolds = 0;
        long released = 0;
        for (SchedulingRowView row : rows) {
            switch (row.eligibility()) {
                case ELIGIBLE -> eligible++;
                case WARNING -> warning++;
                case BLOCKED -> blocked++;
            }
            if (row.activeHolds() > 0) {
                withHolds++;
            }
            if (row.status() == OrderStatus.READY_FOR_PLANNING) {
                released++;
            }
        }
        return new SchedulingSummaryView.Counts(rows.size(), eligible, warning, blocked, withHolds, released);
    }

    private static Sort sortOf(PageQuery pageQuery) {
        List<PageQuery.SortTerm> terms = pageQuery.sortTerms(SORTABLE_PROPERTIES);
        if (terms.isEmpty()) {
            return Sort.by(Sort.Order.asc("serviceDate"), Sort.Order.asc("orderNumber"));
        }
        return Sort.by(terms.stream()
                .map(term -> new Sort.Order(term.descending() ? Sort.Direction.DESC : Sort.Direction.ASC,
                        term.property()))
                .toList());
    }
}
