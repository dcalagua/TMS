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
    /**
     * The driver master (migration V26). A resource of its own and not part of
     * {@link #FLEET_VEHICLE_MANAGE}: a driver record holds personal data - name, identity
     * document, phone - that a vehicle record does not.
     */
    FLEET_DRIVER_READ("fleet.driver:read"),
    FLEET_DRIVER_MANAGE("fleet.driver:manage"),

    ORDERS_ORDER_READ("orders.order:read"),
    ORDERS_ORDER_MANAGE("orders.order:manage"),

    PLANNING_PLAN_READ("planning.plan:read"),
    PLANNING_PLAN_MANAGE("planning.plan:manage"),
    PLANNING_TRIP_READ("planning.trip:read"),
    PLANNING_TRIP_MANAGE("planning.trip:manage"),
    /**
     * Operating a trip through its day - ready, dispatch, complete (migration V25). Deliberately
     * not implied by {@link #PLANNING_TRIP_MANAGE}: building a plan and running it are different
     * jobs, and a role may hold either without the other.
     */
    PLANNING_TRIP_EXECUTE("planning.trip:execute"),
    /**
     * Placing a shipment with its carrier (migration V31). A resource of its own for the reason
     * {@link #PLANNING_TRIP_EXECUTE} is one: offering a load at a price is a commercial act,
     * building the plan is not, and an installation may well want the two in different hands.
     */
    PLANNING_TENDER_READ("planning.tender:read"),
    PLANNING_TENDER_MANAGE("planning.tender:manage"),
    /** Dock scheduling (migration V41). VIEWER holds the read: a booking carries no price. */
    APPOINTMENTS_APPOINTMENT_READ("appointments.appointment:read"),
    APPOINTMENTS_APPOINTMENT_MANAGE("appointments.appointment:manage"),
    /** Configuring doors is an administrator's job: adding one changes what the site can promise. */
    APPOINTMENTS_RESOURCE_MANAGE("appointments.resource:manage"),

    /**
     * The commercial agreement a carrier is paid under (migration V30). Separate from
     * {@link #RATES_TRIP_COST_READ} because they are different disclosures: a dispatcher may
     * legitimately need to see what one shipment cost without being shown the tariff behind it.
     */
    RATES_RATE_CARD_READ("rates.rate_card:read"),
    RATES_RATE_CARD_MANAGE("rates.rate_card:manage"),
    RATES_TRIP_COST_READ("rates.trip_cost:read"),
    RATES_TRIP_COST_MANAGE("rates.trip_cost:manage"),

    /**
     * Freight audit (migration V46). Six, and the split is the point.
     *
     * <p>Recording an invoice, deciding it is payable and handing it to accounting are three
     * different authorities, and an installation will want them in different hands: a clerk keys
     * what arrives, a controller approves the expenditure, and only somebody trusted with the
     * accounting boundary exports it. Collapsing them into one {@code settlement:manage} would let
     * whoever types an invoice approve their own.
     */
    SETTLEMENT_INVOICE_READ("settlement.invoice:read"),
    SETTLEMENT_INVOICE_MANAGE("settlement.invoice:manage"),
    SETTLEMENT_INVOICE_MATCH("settlement.invoice:match"),
    /** Authorising an expenditure. The one that must never be held by a machine - see debt D4. */
    SETTLEMENT_INVOICE_APPROVE("settlement.invoice:approve"),
    SETTLEMENT_INVOICE_EXPORT("settlement.invoice:export"),
    SETTLEMENT_TOLERANCE_MANAGE("settlement.tolerance:manage"),

    INTEGRATION_CLIENT_READ("integration.client:read"),
    INTEGRATION_CLIENT_MANAGE("integration.client:manage"),

    /**
     * Where this company's published events are pushed (migration V35). Its own resource rather
     * than part of {@link #INTEGRATION_CLIENT_MANAGE} because the two fail in opposite directions:
     * a credential is a way <em>in</em> - mismanaging one lets somebody write orders into this
     * company - and a subscription is a way <em>out</em> - mismanaging one sends this company's
     * shipment numbers to an address of the administrator's choosing. Both go to the same two
     * roles today; a deployment that later wants an integrations operator who may configure
     * endpoints and may not mint credentials can say so without a migration.
     */
    INTEGRATION_WEBHOOK_READ("integration.webhook:read"),
    INTEGRATION_WEBHOOK_MANAGE("integration.webhook:manage"),

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
