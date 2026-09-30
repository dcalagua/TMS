package com.ebim.tms.masterdata.infrastructure;

import com.ebim.tms.masterdata.domain.Route;
import com.ebim.tms.masterdata.domain.RouteStop;
import com.ebim.tms.shared.reference.RouteResolution;
import com.ebim.tms.shared.reference.RouteResolutionPort;
import com.ebim.tms.shared.reference.RouteTemplate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * The only implementation of {@link RouteResolutionPort}. Loads the active routes of every origin
 * asked about in one query and applies {@link RouteResolution#resolve}, which owns the rule.
 */
@Component
@Transactional(readOnly = true)
class RouteResolutionAdapter implements RouteResolutionPort {

    private final RouteRepository routeRepository;

    RouteResolutionAdapter(RouteRepository routeRepository) {
        this.routeRepository = routeRepository;
    }

    @Override
    public RouteResolution resolve(UUID companyId, UUID originId, UUID destinationId) {
        OriginDestination pair = new OriginDestination(originId, destinationId);
        return resolveAll(companyId, List.of(pair)).get(pair);
    }

    @Override
    public Map<OriginDestination, RouteResolution> resolveAll(UUID companyId, Collection<OriginDestination> pairs) {
        Map<OriginDestination, RouteResolution> resolved = new LinkedHashMap<>();
        if (pairs.isEmpty()) {
            return resolved;
        }
        Set<UUID> originIds = pairs.stream().map(OriginDestination::originId).collect(Collectors.toSet());

        Map<UUID, List<RouteTemplate>> routesByOrigin = new HashMap<>();
        Map<UUID, UUID> frequencyByRoute = new HashMap<>();
        for (Route route : routeRepository.findActiveWithStopsByOrigins(companyId, originIds)) {
            routesByOrigin.computeIfAbsent(route.originId(), key -> new ArrayList<>()).add(toTemplate(route));
            if (route.frequencyId() != null) {
                frequencyByRoute.put(route.id(), route.frequencyId());
            }
        }

        for (OriginDestination pair : pairs) {
            resolved.put(pair, RouteResolution.resolve(
                    routesByOrigin.getOrDefault(pair.originId(), List.of()), frequencyByRoute, pair.destinationId()));
        }
        return resolved;
    }

    private static RouteTemplate toTemplate(Route route) {
        List<UUID> destinationIds = route.stops().stream().map(RouteStop::destinationId).toList();
        return new RouteTemplate(route.id(), route.code(), route.name(), route.originId(), destinationIds,
                route.active());
    }
}
