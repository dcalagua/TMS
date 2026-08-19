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
            Permission.MASTERDATA_ORIGIN_READ,
            Permission.MASTERDATA_ZONE_READ,
            Permission.MASTERDATA_DESTINATION_READ,
            Permission.MASTERDATA_FREQUENCY_READ,
            Permission.MASTERDATA_ROUTE_READ),
    MASTER_DATA_MANAGE(
            Permission.MASTERDATA_ORIGIN_MANAGE,
            Permission.MASTERDATA_ZONE_MANAGE,
            Permission.MASTERDATA_DESTINATION_MANAGE,
            Permission.MASTERDATA_FREQUENCY_MANAGE,
            Permission.MASTERDATA_ROUTE_MANAGE),

    FLEET_VIEW(
            Permission.FLEET_CARRIER_READ,
            Permission.FLEET_VEHICLE_TYPE_READ,
            Permission.FLEET_VEHICLE_READ),
    FLEET_MANAGE(
            Permission.FLEET_CARRIER_MANAGE,
            Permission.FLEET_VEHICLE_TYPE_MANAGE,
            Permission.FLEET_VEHICLE_MANAGE),

    ORDERS_VIEW(Permission.ORDERS_ORDER_READ),
    ORDERS_MANAGE(Permission.ORDERS_ORDER_MANAGE),

    PLANNING_VIEW(Permission.PLANNING_PLAN_READ),
    PLANNING_MANAGE(Permission.PLANNING_PLAN_MANAGE),

    TRIPS_VIEW(Permission.PLANNING_TRIP_READ),
    TRIPS_MANAGE(Permission.PLANNING_TRIP_MANAGE),

    TRANSPORT_MONITOR_VIEW(Permission.MONITORING_TRANSPORT_READ),

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
