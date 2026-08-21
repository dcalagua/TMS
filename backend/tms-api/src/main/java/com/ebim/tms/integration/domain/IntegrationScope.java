package com.ebim.tms.integration.domain;

import java.util.Arrays;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * What an integration credential is allowed to do.
 *
 * <p>Deliberately a different vocabulary from {@link com.ebim.tms.shared.security.Permission}.
 * A partner credential is not a user with a role: it is a narrow, purpose-built key that should
 * hold one or two capabilities and nothing else, and mixing the two namespaces would make it
 * possible to grant a machine {@code iam.user:manage} by copying a role. The codes are prefixed
 * {@code integration.} so that a scope can never collide with a permission in a
 * {@code @PreAuthorize} expression either.
 *
 * <p>The value domain is mirrored by {@code ck_integration_client_scope_value} in V18: adding a
 * capability takes a migration and an enum constant, which is exactly the friction such a change
 * deserves.
 */
public enum IntegrationScope {

    /** Create and update locations/stores. */
    LOCATION_WRITE("integration.location:write"),

    /** Create and update transport orders. */
    ORDER_WRITE("integration.order:write"),

    /**
     * Read confirmed/cancelled shipments (job 08's outbound {@code ShipmentPlan V1}). Read-only:
     * holding it grants no write anywhere, on purpose - see
     * {@code docs/integrations/OUTBOUND_SHIPMENT_V1.md}.
     */
    SHIPMENT_READ("integration.shipment:read");

    private static final Map<String, IntegrationScope> BY_CODE = Arrays.stream(values())
            .collect(Collectors.toUnmodifiableMap(IntegrationScope::code, Function.identity()));

    private final String code;

    IntegrationScope(String code) {
        this.code = code;
    }

    /** The authority string the endpoints check, identical to {@code integration_client_scope.scope}. */
    public String code() {
        return code;
    }

    public static Optional<IntegrationScope> byCode(String code) {
        return Optional.ofNullable(code).map(value -> BY_CODE.get(value.trim()));
    }
}
