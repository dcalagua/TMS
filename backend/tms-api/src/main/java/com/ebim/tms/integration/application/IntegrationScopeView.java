package com.ebim.tms.integration.application;

import com.ebim.tms.integration.domain.IntegrationScope;

/**
 * One entry of the scope catalogue the credentials form is drawn from
 * ({@code GET /api/v1/integration-clients/scopes}).
 *
 * @param code the authority string, exactly as {@code integration_client_scope.scope} stores it
 * @param group the kind of partner it is meant for ({@code ERP}, {@code WAREHOUSE}, {@code CARRIER}, {@code FINANCE})
 * @param requiresCarrier whether a credential holding it must name a carrier
 */
public record IntegrationScopeView(String code, String group, boolean requiresCarrier) {

    public static IntegrationScopeView of(IntegrationScope scope) {
        return new IntegrationScopeView(scope.code(), scope.group().name(), scope.requiresCarrier());
    }
}
