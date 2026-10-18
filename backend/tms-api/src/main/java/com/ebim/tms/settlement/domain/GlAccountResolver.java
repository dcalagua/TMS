package com.ebim.tms.settlement.domain;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Picks the mapping that books a concept for one trip (V65, ADR-023): ROUTE, then ORIGIN, then
 * COMPANY. Empty when none applies - and then the export says so rather than inventing an account.
 */
public final class GlAccountResolver {

    private final List<GlAccountMapping> active;

    public GlAccountResolver(List<GlAccountMapping> mappings) {
        this.active = mappings.stream().filter(GlAccountMapping::active).toList();
    }

    public Optional<GlAccountMapping> resolve(GlConcept concept, UUID routeId, UUID originId) {
        return find(concept, GlScopeType.ROUTE, routeId)
                .or(() -> find(concept, GlScopeType.ORIGIN, originId))
                .or(() -> find(concept, GlScopeType.COMPANY, null));
    }

    private Optional<GlAccountMapping> find(GlConcept concept, GlScopeType scopeType, UUID scopeId) {
        if (scopeType != GlScopeType.COMPANY && scopeId == null) {
            return Optional.empty();
        }
        return active.stream()
                .filter(mapping -> mapping.concept() == concept && mapping.scopeType() == scopeType)
                .filter(mapping -> scopeType == GlScopeType.COMPANY || scopeId.equals(mapping.scopeId()))
                .findFirst();
    }
}
