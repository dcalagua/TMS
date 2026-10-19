package com.ebim.tms.settlement.domain;

import com.ebim.tms.settlement.domain.tax.WithholdingKind;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Picks the mapping that books a concept for one trip (V65, ADR-023): ROUTE, then ORIGIN, then
 * COMPANY. Empty when none applies - and then the export says so rather than inventing an account.
 *
 * <p>A withholding (ADR-027) is booked by the mapping of its own kind when there is one, else by the
 * {@link GlConcept#WITHHOLDING_PAYABLE} mapping for every kind - the kind is the more specific
 * qualifier, so it wins at each scope before the scope widens.
 */
public final class GlAccountResolver {

    private final List<GlAccountMapping> active;

    public GlAccountResolver(List<GlAccountMapping> mappings) {
        this.active = mappings.stream().filter(GlAccountMapping::active).toList();
    }

    public Optional<GlAccountMapping> resolve(GlConcept concept, UUID routeId, UUID originId) {
        return resolve(concept, null, routeId, originId);
    }

    /** {@code kind} is read only for {@link GlConcept#WITHHOLDING_PAYABLE}. */
    public Optional<GlAccountMapping> resolve(GlConcept concept, WithholdingKind kind, UUID routeId, UUID originId) {
        WithholdingKind qualifier = concept == GlConcept.WITHHOLDING_PAYABLE ? kind : null;
        return find(concept, qualifier, GlScopeType.ROUTE, routeId)
                .or(() -> find(concept, qualifier, GlScopeType.ORIGIN, originId))
                .or(() -> find(concept, qualifier, GlScopeType.COMPANY, null));
    }

    private Optional<GlAccountMapping> find(GlConcept concept, WithholdingKind kind, GlScopeType scopeType,
            UUID scopeId) {
        if (scopeType != GlScopeType.COMPANY && scopeId == null) {
            return Optional.empty();
        }
        Optional<GlAccountMapping> ofKind = kind == null ? Optional.empty() : first(concept, kind, scopeType, scopeId);
        return ofKind.or(() -> first(concept, null, scopeType, scopeId));
    }

    private Optional<GlAccountMapping> first(GlConcept concept, WithholdingKind kind, GlScopeType scopeType,
            UUID scopeId) {
        return active.stream()
                .filter(mapping -> mapping.concept() == concept && mapping.scopeType() == scopeType)
                .filter(mapping -> mapping.withholdingKind() == kind)
                .filter(mapping -> scopeType == GlScopeType.COMPANY || scopeId.equals(mapping.scopeId()))
                .findFirst();
    }
}
