package com.ebim.tms.masterdata.infrastructure;

import com.ebim.tms.masterdata.domain.Route;
import com.ebim.tms.masterdata.domain.Zone;
import com.ebim.tms.shared.reference.RouteReference;
import com.ebim.tms.shared.reference.RouteReferenceLookupPort;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * The only implementation of {@link RouteReferenceLookupPort}: two batched, company-scoped reads,
 * the routes and then their zones, whatever the number of ids. The route's stops are never
 * touched, so their lazy collection is never loaded.
 */
@Component
@Transactional(readOnly = true)
class RouteReferenceLookupAdapter implements RouteReferenceLookupPort {

    private final RouteRepository routeRepository;
    private final ZoneRepository zoneRepository;

    RouteReferenceLookupAdapter(RouteRepository routeRepository, ZoneRepository zoneRepository) {
        this.routeRepository = routeRepository;
        this.zoneRepository = zoneRepository;
    }

    @Override
    public Map<UUID, RouteReference> findAllInCompany(Set<UUID> ids, UUID companyId) {
        Map<UUID, RouteReference> byId = new HashMap<>();
        if (ids.isEmpty()) {
            return byId;
        }
        List<Route> routes = routeRepository.findByIdInAndCompanyId(ids, companyId);
        Set<UUID> zoneIds = routes.stream().map(Route::zoneId).filter(Objects::nonNull).collect(Collectors.toSet());
        Map<UUID, Zone> zones = zoneIds.isEmpty()
                ? Map.of()
                : zoneRepository.findByIdInAndCompanyId(zoneIds, companyId).stream()
                        .collect(Collectors.toMap(Zone::id, Function.identity()));
        for (Route route : routes) {
            Zone zone = route.zoneId() == null ? null : zones.get(route.zoneId());
            byId.put(route.id(), new RouteReference(route.id(), route.code(), route.name(),
                    zone == null ? null : zone.id(), zone == null ? null : zone.code(),
                    zone == null ? null : zone.name()));
        }
        return byId;
    }
}
