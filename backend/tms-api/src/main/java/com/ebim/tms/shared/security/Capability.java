package com.ebim.tms.shared.security;

import java.util.Arrays;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/**
 * Module-level capability names, used by the UI to decide which sections and actions to show.
 *
 * <p>These are the coarse names the product speaks in ({@code MASTER_DATA_VIEW},
 * {@code TRIPS_MANAGE}, ...). Each one is a group of {@link Permission}s and is
 * <em>derived</em> from them - it is never stored, never granted and never enforced.
 *
 * <p><b>Enforcement uses {@link Permission}, always.</b> A capability answers "should this menu
 * entry be visible", which is a UX question; the endpoint behind the menu entry still checks
 * its own fine-grained permission server-side, so hiding or un-hiding a button changes
 * nothing about what a caller may actually do.
 *
 * <p>A capability is held when the caller holds <em>at least one</em> of its permissions,
 * because it gates entry to a screen that will then enforce its own rules per resource.
 */
public enum Capability {

    MASTER_DATA_VIEW(
            Permission.MASTERDATA_LOCATION_READ,
            Permission.MASTERDATA_ORIGIN_READ,
            Permission.MASTERDATA_ZONE_READ,
            Permission.MASTERDATA_DESTINATION_READ,
            Permission.MASTERDATA_FREQUENCY_READ,
            Permission.MASTERDATA_ROUTE_READ),
    MASTER_DATA_MANAGE(
            Permission.MASTERDATA_LOCATION_MANAGE,
            Permission.MASTERDATA_ORIGIN_MANAGE,
            Permission.MASTERDATA_ZONE_MANAGE,
            Permission.MASTERDATA_DESTINATION_MANAGE,
            Permission.MASTERDATA_FREQUENCY_MANAGE,
            Permission.MASTERDATA_ROUTE_MANAGE),

    FLEET_VIEW(
            Permission.FLEET_CARRIER_READ,
            Permission.FLEET_VEHICLE_TYPE_READ,
            Permission.FLEET_VEHICLE_READ,
            Permission.FLEET_DRIVER_READ),
    FLEET_MANAGE(
            Permission.FLEET_CARRIER_MANAGE,
            Permission.FLEET_VEHICLE_TYPE_MANAGE,
            Permission.FLEET_VEHICLE_MANAGE,
            Permission.FLEET_DRIVER_MANAGE),

    ORDERS_VIEW(Permission.ORDERS_ORDER_READ),
    ORDERS_MANAGE(Permission.ORDERS_ORDER_MANAGE),

    PLANNING_VIEW(Permission.PLANNING_PLAN_READ),
    PLANNING_MANAGE(Permission.PLANNING_PLAN_MANAGE),

    /**
     * Tendering joins the trips group rather than getting a capability of its own (migration V31):
     * it has no screen of its own, it is a card on the trip workspace, and a role that may answer
     * tenders needs that screen. The finer question - may this account see the offered price -
     * stays where it is enforced, on {@code planning.tender:read}.
     */
    TRIPS_VIEW(Permission.PLANNING_TRIP_READ, Permission.PLANNING_TENDER_READ),
    TRIPS_MANAGE(
            Permission.PLANNING_TRIP_MANAGE,
            // Operating a trip (V25) and tendering it (V31) are separate authorities and stay
            // separate where they are enforced. They share this capability because they share a
            // screen: all three open the trip workspace, and a capability answers "should this menu
            // entry be visible", never "may this caller do it".
            Permission.PLANNING_TRIP_EXECUTE,
            Permission.PLANNING_TENDER_MANAGE),

    TRANSPORT_MONITOR_VIEW(Permission.MONITORING_TRANSPORT_READ),

    /**
     * Tariffs and what a shipment cost (migration V30). One capability over both resources: they
     * share a screen group, and the finer question - may this account see the <em>agreement</em>
     * as well as the figure - stays where it is enforced, on the two permissions.
     */
    RATES_VIEW(Permission.RATES_RATE_CARD_READ, Permission.RATES_TRIP_COST_READ),
    RATES_MANAGE(Permission.RATES_RATE_CARD_MANAGE, Permission.RATES_TRIP_COST_MANAGE),

    /**
     * Deliberately separate from {@code IAM_*}: issuing a machine credential is not the same
     * decision as inviting a person, and an installation may well want the two in different hands.
     *
     * <p>Webhook subscriptions (migration V35) join it rather than getting a capability of their
     * own, because they share the screen: the Integration Hub answers "what is connected to us"
     * with inbound credentials on one tab and outbound endpoints on the other. The finer question -
     * may this account configure where our data is <em>sent</em>, as opposed to who may write into
     * us - stays where it is enforced, on the two {@code integration.webhook:*} permissions, and
     * the tab a caller cannot read simply does not appear.
     */
    INTEGRATION_VIEW(Permission.INTEGRATION_CLIENT_READ, Permission.INTEGRATION_WEBHOOK_READ),
    INTEGRATION_MANAGE(Permission.INTEGRATION_CLIENT_MANAGE, Permission.INTEGRATION_WEBHOOK_MANAGE),

    IAM_VIEW(
            Permission.IAM_ORGANIZATION_READ,
            Permission.IAM_COMPANY_READ,
            Permission.IAM_USER_READ,
            Permission.IAM_MEMBERSHIP_READ),
    IAM_MANAGE(
            Permission.IAM_ORGANIZATION_MANAGE,
            Permission.IAM_COMPANY_MANAGE,
            Permission.IAM_USER_MANAGE,
            Permission.IAM_MEMBERSHIP_MANAGE),

    AUDIT_VIEW(Permission.AUDIT_LOG_READ);

    private final Set<Permission> permissions;

    Capability(Permission... permissions) {
        this.permissions = Set.of(permissions);
    }

    public Set<Permission> permissions() {
        return permissions;
    }

    public boolean isHeldWith(Set<Permission> held) {
        return permissions.stream().anyMatch(held::contains);
    }

    /** The capabilities implied by a permission set, in declaration order. */
    public static List<Capability> from(Set<Permission> held) {
        if (held.isEmpty()) {
            return List.of();
        }
        return Arrays.stream(values()).filter(capability -> capability.isHeldWith(held)).toList();
    }

    /** Every permission reachable through a capability; used to prove the mapping is complete. */
    static Set<Permission> allMapped() {
        EnumSet<Permission> mapped = EnumSet.noneOf(Permission.class);
        Arrays.stream(values()).forEach(capability -> mapped.addAll(capability.permissions()));
        return mapped;
    }
}
