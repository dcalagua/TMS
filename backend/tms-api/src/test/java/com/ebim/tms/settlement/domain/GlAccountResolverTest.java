package com.ebim.tms.settlement.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** The most specific active mapping wins: ROUTE, then ORIGIN, then COMPANY (V65, ADR-023). */
class GlAccountResolverTest {

    private static final UUID COMPANY = UUID.randomUUID();
    private static final UUID ROUTE = UUID.randomUUID();
    private static final UUID ORIGIN = UUID.randomUUID();

    private final GlAccountResolver resolver = new GlAccountResolver(List.of(
            mapping(GlScopeType.COMPANY, null, "C"),
            mapping(GlScopeType.ORIGIN, ORIGIN, "O"),
            mapping(GlScopeType.ROUTE, ROUTE, "R")));

    @Test
    @DisplayName("route beats origin beats company")
    void specificity() {
        assertThat(account(ROUTE, ORIGIN)).isEqualTo("R");
        assertThat(account(UUID.randomUUID(), ORIGIN)).isEqualTo("O");
        assertThat(account(null, null)).isEqualTo("C");
        assertThat(account(UUID.randomUUID(), UUID.randomUUID())).isEqualTo("C");
    }

    @Test
    @DisplayName("a retired mapping does not apply, and none at all is empty")
    void retiredAndMissing() {
        GlAccountMapping retired = mapping(GlScopeType.COMPANY, null, "X");
        retired.deactivate(null);
        assertThat(new GlAccountResolver(List.of(retired)).resolve(GlConcept.FREIGHT_EXPENSE, null, null)).isEmpty();
        assertThat(resolver.resolve(GlConcept.CARRIER_PAYABLE, ROUTE, ORIGIN)).isEmpty();
    }

    private String account(UUID routeId, UUID originId) {
        return resolver.resolve(GlConcept.FREIGHT_EXPENSE, routeId, originId).orElseThrow().accountCode();
    }

    private static GlAccountMapping mapping(GlScopeType scope, UUID scopeId, String account) {
        return new GlAccountMapping(COMPANY, GlConcept.FREIGHT_EXPENSE, scope, scopeId, account, null, null);
    }
}
