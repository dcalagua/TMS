package com.ebim.tms.shared.security;

import java.util.Arrays;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * The authorization vocabulary of TMS, mirroring {@code tms.permission} one for one.
 *
 * <p>A permission is an atomic capability written {@code resource:action}. The database is the
 * catalogue of record (migrations V3, V5 and V14); this enum is the compile-time view of it, so a
 * typo in a {@code @PreAuthorize} expression is a failing test rather than a silently open
 * endpoint. {@code PermissionCatalogueIntegrationTest} asserts the two are identical.
 *
 * <p>Granularity is per resource, not per module: {@code masterdata.origin:read} rather than
 * {@code MASTER_DATA_VIEW}. Coarse module-level names remain available to the UI through
 * {@link Capability}, which groups these; see {@code docs/security/AUTHORIZATION_MODEL.md}
 * for the mapping between the two.
 */
public enum Permission {

    IAM_ORGANIZATION_READ("iam.organization:read"),
    IAM_ORGANIZATION_MANAGE("iam.organization:manage"),
    IAM_COMPANY_READ("iam.company:read"),
    IAM_COMPANY_MANAGE("iam.company:manage"),
    IAM_USER_READ("iam.user:read"),
    IAM_USER_MANAGE("iam.user:manage"),
    IAM_MEMBERSHIP_READ("iam.membership:read"),
    IAM_MEMBERSHIP_MANAGE("iam.membership:manage"),

    MASTERDATA_LOCATION_READ("masterdata.location:read"),
    MASTERDATA_LOCATION_MANAGE("masterdata.location:manage"),
    MASTERDATA_ORIGIN_READ("masterdata.origin:read"),
    MASTERDATA_ORIGIN_MANAGE("masterdata.origin:manage"),
    MASTERDATA_ZONE_READ("masterdata.zone:read"),
    MASTERDATA_ZONE_MANAGE("masterdata.zone:manage"),
    MASTERDATA_DESTINATION_READ("masterdata.destination:read"),
    MASTERDATA_DESTINATION_MANAGE("masterdata.destination:manage"),
    MASTERDATA_FREQUENCY_READ("masterdata.frequency:read"),
    MASTERDATA_FREQUENCY_MANAGE("masterdata.frequency:manage"),
    MASTERDATA_ROUTE_READ("masterdata.route:read"),
    MASTERDATA_ROUTE_MANAGE("masterdata.route:manage"),

    FLEET_CARRIER_READ("fleet.carrier:read"),
    FLEET_CARRIER_MANAGE("fleet.carrier:manage"),
    FLEET_VEHICLE_TYPE_READ("fleet.vehicle_type:read"),
    FLEET_VEHICLE_TYPE_MANAGE("fleet.vehicle_type:manage"),
    FLEET_VEHICLE_READ("fleet.vehicle:read"),
    FLEET_VEHICLE_MANAGE("fleet.vehicle:manage"),

    ORDERS_ORDER_READ("orders.order:read"),
    ORDERS_ORDER_MANAGE("orders.order:manage"),

    PLANNING_PLAN_READ("planning.plan:read"),
    PLANNING_PLAN_MANAGE("planning.plan:manage"),
    PLANNING_TRIP_READ("planning.trip:read"),
    PLANNING_TRIP_MANAGE("planning.trip:manage"),

    MONITORING_TRANSPORT_READ("monitoring.transport:read"),

    AUDIT_LOG_READ("audit.log:read");

    private static final Map<String, Permission> BY_CODE = Arrays.stream(values())
            .collect(Collectors.toUnmodifiableMap(Permission::code, Function.identity()));

    private final String code;

    Permission(String code) {
        this.code = code;
    }

    /** The {@code resource:action} string, identical to {@code tms.permission.code}. */
    public String code() {
        return code;
    }

    public String resource() {
        return code.substring(0, code.indexOf(':'));
    }

    public String action() {
        return code.substring(code.indexOf(':') + 1);
    }

    /**
     * Resolves a code coming from the database. Empty when the database holds a permission
     * this build does not know about, which happens legitimately during a rolling deploy: the
     * caller ignores it rather than failing the request, and never grants it.
     */
    public static Optional<Permission> fromCode(String code) {
        return Optional.ofNullable(BY_CODE.get(code));
    }
}
