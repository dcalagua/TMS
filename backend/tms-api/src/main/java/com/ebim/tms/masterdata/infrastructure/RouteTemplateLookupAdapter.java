package com.ebim.tms.masterdata.infrastructure;

import com.ebim.tms.masterdata.domain.Route;
import com.ebim.tms.masterdata.domain.RouteStop;
import com.ebim.tms.shared.reference.RouteTemplate;
import com.ebim.tms.shared.reference.RouteTemplateLookupPort;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * The only implementation of {@link RouteTemplateLookupPort}; see {@link OriginLookupAdapter} for
 * the translation rule every lookup adapter here follows.
 *
 * <p>{@code @Transactional(readOnly = true)} because {@link Route#stops()} is lazy and this
 * adapter is called from another module's transaction boundary in the ordinary case but from a
 * read path with none in the pathological one - without it, resolving a route's stop list outside
 * a session would fail rather than simply cost a second query.
 */
@Component
@Transactional(readOnly = true)
class RouteTemplateLookupAdapter implements RouteTemplateLookupPort {

    private final RouteRepository routeRepository;

    RouteTemplateLookupAdapter(RouteRepository routeRepository) {
        this.routeRepository = routeRepository;
    }

    @Override
    public Optional<RouteTemplate> findActiveInCompany(UUID routeId, UUID companyId) {
        return routeRepository.findByIdAndCompanyId(routeId, companyId)
                .filter(Route::active)
                .map(RouteTemplateLookupAdapter::toTemplate);
    }

    @Override
    public Map<UUID, RouteTemplate> findAllInCompany(Set<UUID> ids, UUID companyId) {
        Map<UUID, RouteTemplate> byId = new HashMap<>();
        if (ids.isEmpty()) {
            return byId;
        }
        for (Route route : routeRepository.findByIdInAndCompanyId(ids, companyId)) {
            byId.put(route.id(), toTemplate(route));
        }
        return byId;
    }

    /**
     * {@link Route#stops()} already returns the master's stops sorted by sequence, so the order
     * of {@code destinationIds} <em>is</em> the corridor's order - the whole point of the record.
     */
    private static RouteTemplate toTemplate(Route route) {
        List<UUID> destinationIds = route.stops().stream().map(RouteStop::destinationId).toList();
        return new RouteTemplate(route.id(), route.code(), route.name(), route.originId(), destinationIds,
                route.active());
    }
}
