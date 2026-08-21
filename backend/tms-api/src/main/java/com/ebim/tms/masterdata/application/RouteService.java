package com.ebim.tms.masterdata.application;

import com.ebim.tms.masterdata.domain.Frequency;
import com.ebim.tms.masterdata.domain.Location;
import com.ebim.tms.masterdata.domain.LocationRole;
import com.ebim.tms.masterdata.domain.Route;
import com.ebim.tms.masterdata.domain.RouteStopInput;
import com.ebim.tms.masterdata.domain.Zone;
import com.ebim.tms.masterdata.infrastructure.FrequencyRepository;
import com.ebim.tms.masterdata.infrastructure.LocationRepository;
import com.ebim.tms.masterdata.infrastructure.RouteRepository;
import com.ebim.tms.masterdata.infrastructure.RouteSpecifications;
import com.ebim.tms.masterdata.infrastructure.RouteStopRepository;
import com.ebim.tms.masterdata.infrastructure.ZoneRepository;
import com.ebim.tms.shared.api.ConflictException;
import com.ebim.tms.shared.api.InvalidRequestException;
import com.ebim.tms.shared.api.PageQuery;
import com.ebim.tms.shared.api.PageResponse;
import com.ebim.tms.shared.api.ResourceNotFoundException;
import com.ebim.tms.shared.audit.AuditActorProvider;
import com.ebim.tms.shared.security.CompanyScope;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Route use cases. Takes a {@link CompanyScope}, never a company id, so the tenant comes from the
 * caller's membership and never from the request body.
 *
 * <p>Since V23 a route's origin and every one of its stops are canonical {@code tms.location}
 * rows, and the eligibility rule is stated here rather than implied by which table an id came
 * from: the origin must hold {@link LocationRole#ORIGIN} and every stop must hold
 * {@link LocationRole#DESTINATION}, both active and both in the caller's company. Rendering an
 * already-saved route is deliberately laxer - a stop whose location was later deactivated still
 * shows the place the route actually serves.
 *
 * <p>List and detail deliberately return different view shapes ({@link RouteView} vs
 * {@link RouteDetailView}) and resolve their related records with batched lookups rather than
 * per-row queries - the step brief's explicit N+1 constraint. See each view's class comment.
 */
@Service
public class RouteService {

    private static final Set<String> SORTABLE_PROPERTIES = Set.of("code", "name", "active", "createdAt", "updatedAt");

    private final RouteRepository routeRepository;
    private final RouteStopRepository routeStopRepository;
    private final LocationRepository locationRepository;
    private final ZoneRepository zoneRepository;
    private final FrequencyRepository frequencyRepository;
    private final AuditActorProvider auditActorProvider;

    public RouteService(RouteRepository routeRepository, RouteStopRepository routeStopRepository,
            LocationRepository locationRepository, ZoneRepository zoneRepository,
            FrequencyRepository frequencyRepository, AuditActorProvider auditActorProvider) {
        this.routeRepository = routeRepository;
        this.routeStopRepository = routeStopRepository;
        this.locationRepository = locationRepository;
        this.zoneRepository = zoneRepository;
        this.frequencyRepository = frequencyRepository;
        this.auditActorProvider = auditActorProvider;
    }

    @Transactional(readOnly = true)
    public PageResponse<RouteView> list(CompanyScope scope, RouteFilter filter, PageQuery pageQuery) {
        var specification = RouteSpecifications.matching(
                scope.companyId(), filter.code(), filter.name(), filter.originId(), filter.zoneId(), filter.active());
        Page<Route> page = routeRepository.findAll(specification, toPageable(pageQuery));
        List<Route> routes = page.getContent();

        Map<UUID, Location> originsById = loadByIds(routes.stream().map(Route::originId), scope,
                locationRepository::findAllById, Location::id, Location::companyId);
        Map<UUID, Zone> zonesById = loadByIds(routes.stream().map(Route::zoneId), scope, zoneRepository::findAllById,
                Zone::id, Zone::companyId);
        Map<UUID, Frequency> frequenciesById = loadByIds(routes.stream().map(Route::frequencyId), scope,
                frequencyRepository::findAllById, Frequency::id, Frequency::companyId);
        Map<UUID, Long> stopCounts = loadStopCounts(routes);

        List<RouteView> content = routes.stream()
                .map(route -> RouteView.from(route, originsById.get(route.originId()), zonesById.get(route.zoneId()),
                        frequenciesById.get(route.frequencyId()), stopCounts.getOrDefault(route.id(), 0L)))
                .toList();
        return new PageResponse<>(content, pageQuery.pageNumber(), pageQuery.pageSize(), page.getTotalElements());
    }

    @Transactional(readOnly = true)
    public RouteDetailView get(CompanyScope scope, UUID id) {
        Route route = find(scope, id);
        return toDetailView(scope, route);
    }

    @Transactional
    public RouteDetailView create(CompanyScope scope, RouteRequest request) {
        String code = normalizeCode(request.code());
        Location origin = requireOriginLocation(scope, request.originId());
        Zone zone = requireZoneInScope(scope, request.zoneId());
        Frequency frequency = requireFrequencyInScope(scope, request.frequencyId());
        List<RouteStopInput> stops = distinctOrThrow(request.stops());
        Map<UUID, Location> destinationsById = requireDestinationLocations(scope, destinationIdsOf(stops));
        if (routeRepository.existsByCompanyIdAndCode(scope.companyId(), code)) {
            throw duplicateCode(code);
        }

        UUID actorId = auditActorProvider.requireAppUserId();
        Route route = new Route(scope.companyId(), code, request.name().trim(), request.originId(), request.zoneId(),
                request.frequencyId(), request.referenceDistanceKm(), request.referenceDurationMinutes(), actorId);
        route.replaceStops(stops, actorId);
        Route saved = saveOrConflict(route, code);
        return RouteDetailView.from(saved, origin, zone, frequency, destinationsById);
    }

    @Transactional
    public RouteDetailView update(CompanyScope scope, UUID id, RouteRequest request) {
        Route route = find(scope, id);
        String code = normalizeCode(request.code());
        Location origin = requireOriginLocation(scope, request.originId());
        Zone zone = requireZoneInScope(scope, request.zoneId());
        Frequency frequency = requireFrequencyInScope(scope, request.frequencyId());
        List<RouteStopInput> stops = distinctOrThrow(request.stops());
        Map<UUID, Location> destinationsById = requireDestinationLocations(scope, destinationIdsOf(stops));
        if (routeRepository.existsByCompanyIdAndCodeAndIdNot(scope.companyId(), code, id)) {
            throw duplicateCode(code);
        }

        UUID actorId = auditActorProvider.requireAppUserId();
        route.applyChanges(code, request.name().trim(), request.originId(), request.zoneId(), request.frequencyId(),
                request.referenceDistanceKm(), request.referenceDurationMinutes(), actorId);
        route.replaceStops(stops, actorId);
        Route saved = saveOrConflict(route, code);
        return RouteDetailView.from(saved, origin, zone, frequency, destinationsById);
    }

    @Transactional
    public RouteDetailView activate(CompanyScope scope, UUID id) {
        Route route = find(scope, id);
        route.activate(auditActorProvider.requireAppUserId());
        return toDetailView(scope, routeRepository.save(route));
    }

    @Transactional
    public RouteDetailView deactivate(CompanyScope scope, UUID id) {
        Route route = find(scope, id);
        route.deactivate(auditActorProvider.requireAppUserId());
        return toDetailView(scope, routeRepository.save(route));
    }

    private RouteDetailView toDetailView(CompanyScope scope, Route route) {
        Location origin = locationRepository.findByIdAndCompanyId(route.originId(), scope.companyId()).orElse(null);
        Zone zone = resolveInScope(scope, route.zoneId(), zoneRepository::findByIdAndCompanyId);
        Frequency frequency = resolveInScope(scope, route.frequencyId(), frequencyRepository::findByIdAndCompanyId);
        List<UUID> destinationIds = route.stops().stream().map(stop -> stop.destinationId()).toList();
        Map<UUID, Location> destinationsById = loadStopLocations(scope, destinationIds);
        return RouteDetailView.from(route, origin, zone, frequency, destinationsById);
    }

    private Route find(CompanyScope scope, UUID id) {
        return routeRepository.findByIdAndCompanyId(id, scope.companyId())
                .orElseThrow(() -> new ResourceNotFoundException("Route not found."));
    }

    /**
     * The route's origin: an active location of this company that is enabled as an origin. All
     * three conditions are checked on every assignment, update included - the same rule
     * {@code OrderService} applies - so a route can never be re-saved pointing at a place the
     * operator has withdrawn from service or from origin duty.
     */
    private Location requireOriginLocation(CompanyScope scope, UUID originId) {
        return locationRepository.findUsableAs(originId, scope.companyId(), LocationRole.ORIGIN)
                .orElseThrow(() -> new InvalidRequestException(
                        "originId does not reference an active location in this company enabled as an origin."));
    }

    /** {@code null} zoneId is allowed (the field is optional); a non-null one must resolve within this company. */
    private Zone requireZoneInScope(CompanyScope scope, UUID zoneId) {
        if (zoneId == null) {
            return null;
        }
        return zoneRepository.findByIdAndCompanyId(zoneId, scope.companyId())
                .orElseThrow(() -> new InvalidRequestException("zoneId does not reference a zone in this company."));
    }

    /** {@code null} frequencyId is allowed (the field is optional); a non-null one must resolve within this company. */
    private Frequency requireFrequencyInScope(CompanyScope scope, UUID frequencyId) {
        if (frequencyId == null) {
            return null;
        }
        return frequencyRepository.findByIdAndCompanyId(frequencyId, scope.companyId())
                .orElseThrow(() -> new InvalidRequestException("frequencyId does not reference a frequency in this company."));
    }

    /**
     * Every stop must resolve to an active location of this company that is enabled as a
     * destination - batched, not one lookup per id. The failure names the offending id rather
     * than saying "one of them": a route can carry dozens of stops, and "fix the wrong one" is
     * not an instruction anybody can follow.
     */
    private Map<UUID, Location> requireDestinationLocations(CompanyScope scope, List<UUID> destinationIds) {
        Map<UUID, Location> byId = new HashMap<>();
        if (destinationIds.isEmpty()) {
            return byId;
        }
        for (Location location : locationRepository.findUsableAsByIds(
                Set.copyOf(destinationIds), scope.companyId(), LocationRole.DESTINATION)) {
            byId.put(location.id(), location);
        }
        for (UUID destinationId : destinationIds) {
            if (!byId.containsKey(destinationId)) {
                throw new InvalidRequestException("stops contains a destination that is not an active location "
                        + "in this company enabled as a destination: " + destinationId);
            }
        }
        return byId;
    }

    /**
     * One batched lookup for a single route's stops, never one query per stop. Deliberately
     * role-blind and state-blind, unlike {@link #requireDestinationLocations}: a saved route has
     * to keep showing where it goes even after one of its stops has been deactivated.
     */
    private Map<UUID, Location> loadStopLocations(CompanyScope scope, List<UUID> destinationIds) {
        Map<UUID, Location> byId = new HashMap<>();
        if (destinationIds.isEmpty()) {
            return byId;
        }
        for (Location location : locationRepository.findByIdInAndCompanyId(
                Set.copyOf(destinationIds), scope.companyId())) {
            byId.put(location.id(), location);
        }
        return byId;
    }

    /** One batched {@code GROUP BY} query for the whole page, never one COUNT per route row. */
    private Map<UUID, Long> loadStopCounts(List<Route> routes) {
        Map<UUID, Long> counts = new HashMap<>();
        if (routes.isEmpty()) {
            return counts;
        }
        Set<UUID> routeIds = new HashSet<>();
        for (Route route : routes) {
            routeIds.add(route.id());
        }
        for (RouteStopRepository.RouteStopCount count : routeStopRepository.countByRouteIds(routeIds)) {
            counts.put(count.getRouteId(), count.getStopCount());
        }
        return counts;
    }

    private interface ScopedFinder<T> {
        java.util.Optional<T> find(UUID id, UUID companyId);
    }

    private <T> T resolveInScope(CompanyScope scope, UUID id, ScopedFinder<T> finder) {
        return id == null ? null : finder.find(id, scope.companyId()).orElse(null);
    }

    private interface BatchFinder<T> {
        Iterable<T> findAllById(Iterable<UUID> ids);
    }

    /** One batched lookup per page instead of one query per row - the same discipline for every related master. */
    private <T> Map<UUID, T> loadByIds(java.util.stream.Stream<UUID> ids, CompanyScope scope, BatchFinder<T> finder,
            java.util.function.Function<T, UUID> idOf, java.util.function.Function<T, UUID> companyIdOf) {
        Set<UUID> distinctIds = ids.filter(Objects::nonNull).collect(java.util.stream.Collectors.toSet());
        // A plain HashMap, not Map.of(): a route with no zone/frequency looks up a null key
        // below, and Map.of()'s immutable map throws on get(null) instead of returning null.
        Map<UUID, T> byId = new HashMap<>();
        if (distinctIds.isEmpty()) {
            return byId;
        }
        for (T item : finder.findAllById(distinctIds)) {
            if (companyIdOf.apply(item).equals(scope.companyId())) {
                byId.put(idOf.apply(item), item);
            }
        }
        return byId;
    }

    /**
     * Rejects a duplicate destination id before it ever reaches {@code Route.replaceStops}, which
     * would silently drop one of the two - and, worse now that a stop carries a service-time
     * override, would keep whichever of the conflicting values happened to be last.
     */
    private static List<RouteStopInput> distinctOrThrow(List<RouteRequest.RouteStopRequest> stops) {
        Set<UUID> seen = new LinkedHashSet<>();
        List<RouteStopInput> inputs = new ArrayList<>(stops.size());
        for (RouteRequest.RouteStopRequest stop : stops) {
            if (!seen.add(stop.destinationId())) {
                throw new InvalidRequestException(
                        "stops contains a repeated destination: " + stop.destinationId());
            }
            inputs.add(new RouteStopInput(stop.destinationId(), stop.serviceTimeOverrideMinutes()));
        }
        return List.copyOf(inputs);
    }

    private static List<UUID> destinationIdsOf(List<RouteStopInput> stops) {
        return stops.stream().map(RouteStopInput::destinationId).toList();
    }

    /** Saves and translates a race with a concurrent duplicate write into the same conflict a pre-check would give. */
    private Route saveOrConflict(Route route, String code) {
        try {
            return routeRepository.saveAndFlush(route);
        } catch (DataIntegrityViolationException raced) {
            throw duplicateCode(code);
        }
    }

    private static ConflictException duplicateCode(String code) {
        return new ConflictException("A route with code '" + code + "' already exists in this company.");
    }

    private static String normalizeCode(String code) {
        return code.trim().toUpperCase(Locale.ROOT);
    }

    private static Pageable toPageable(PageQuery pageQuery) {
        List<PageQuery.SortTerm> terms = pageQuery.sortTerms(SORTABLE_PROPERTIES);
        Sort sort = terms.isEmpty()
                ? Sort.by(Sort.Direction.ASC, "code")
                : Sort.by(terms.stream()
                        .map(term -> new Sort.Order(
                                term.descending() ? Sort.Direction.DESC : Sort.Direction.ASC, term.property()))
                        .toList());
        return PageRequest.of(pageQuery.pageNumber(), pageQuery.pageSize(), sort);
    }
}
