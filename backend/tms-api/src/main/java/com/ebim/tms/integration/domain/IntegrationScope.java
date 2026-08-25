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
    SHIPMENT_READ("integration.shipment:read"),

    /**
     * Report where a vehicle is (migration V29). Its own scope rather than part of any other,
     * because the party holding it is a different one: a telematics vendor, or a customer's
     * middleware relaying one, and neither has any business creating orders. Write-only in effect -
     * holding it grants no read anywhere, so a provider pushing positions learns nothing about the
     * shipments it pushes against.
     */
    TRACKING_WRITE("integration.tracking:write"),

    /**
     * See and answer the tenders offered to <em>one</em> carrier (migration V31).
     *
     * <p>One scope and not a read/write pair, unlike every {@link com.ebim.tms.shared.security.Permission}:
     * a carrier reading its own offers and answering them is one capability from one party's point
     * of view. There is no role that should see its offers and be unable to answer them, and none
     * that should answer offers it cannot read, so splitting it would produce two scopes that are
     * always granted together.
     *
     * <p><b>Meaningless without a carrier.</b> The credential holding this must have
     * {@code integration_client.carrier_id} set, and the endpoints refuse it outright when it does
     * not - never falling back to the company, which would hand one partner every carrier's
     * tenders. That is the one scope in this enum whose grant is not sufficient on its own, and the
     * asymmetry is deliberate: the alternative was a second, carrier-shaped credential type.
     *
     * <p>Deliberately not {@link #SHIPMENT_READ}, which exposes every confirmed shipment of the
     * company. A carrier learns about the shipments it was offered and about no others.
     */
    TENDER_RESPOND("integration.tender:respond");

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
