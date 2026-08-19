package com.ebim.tms.shared.security;

import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Fixtures for the security tests: two organizations, three companies, and principals whose
 * access differs in exactly the way each test needs to distinguish.
 *
 * <p>The ids are fixed constants rather than random values so a failure message names a
 * recognisable company instead of a UUID nobody can place.
 */
public final class TestPrincipals {

    public static final UUID NORTH_ORG = UUID.fromString("00000000-0000-4000-8000-0000000000a1");
    public static final UUID SOUTH_ORG = UUID.fromString("00000000-0000-4000-8000-0000000000a2");

    public static final UUID NORTH_LIMA = UUID.fromString("00000000-0000-4000-8000-0000000000c1");
    public static final UUID NORTH_TRUJILLO = UUID.fromString("00000000-0000-4000-8000-0000000000c2");
    /** Belongs to the other organization: nothing in NORTH may ever reach it. */
    public static final UUID SOUTH_AREQUIPA = UUID.fromString("00000000-0000-4000-8000-0000000000c3");

    public static final UUID PLANNER_AUTH_USER = UUID.fromString("00000000-0000-4000-8000-0000000000e1");
    public static final UUID VIEWER_AUTH_USER = UUID.fromString("00000000-0000-4000-8000-0000000000e2");
    public static final UUID OUTSIDER_AUTH_USER = UUID.fromString("00000000-0000-4000-8000-0000000000e3");
    public static final UUID UNPROVISIONED_AUTH_USER = UUID.fromString("00000000-0000-4000-8000-0000000000e4");

    private TestPrincipals() {}

    /** Member of NORTH-LIMA with company read access, plus orders and trips. */
    public static TmsPrincipal planner() {
        return new TmsPrincipal(
                UUID.fromString("00000000-0000-4000-8000-0000000000f1"),
                PLANNER_AUTH_USER,
                "planner@example.invalid",
                "Test Planner",
                List.of(scope(NORTH_LIMA, "NORTH-LIMA", "North Lima", NORTH_ORG, Set.of(
                        Permission.IAM_COMPANY_READ,
                        Permission.ORDERS_ORDER_READ,
                        Permission.ORDERS_ORDER_MANAGE,
                        Permission.PLANNING_TRIP_READ,
                        Permission.PLANNING_TRIP_MANAGE))));
    }

    /**
     * Member of NORTH-LIMA <em>and</em> NORTH-TRUJILLO, but with different access in each:
     * company read in Lima, orders read only in Trujillo. Proves that permissions are
     * evaluated per selected company rather than unioned across the principal.
     */
    public static TmsPrincipal viewerOfTwoCompanies() {
        return new TmsPrincipal(
                UUID.fromString("00000000-0000-4000-8000-0000000000f2"),
                VIEWER_AUTH_USER,
                "viewer@example.invalid",
                "Test Viewer",
                List.of(
                        scope(NORTH_LIMA, "NORTH-LIMA", "North Lima", NORTH_ORG,
                                Set.of(Permission.IAM_COMPANY_READ, Permission.ORDERS_ORDER_READ)),
                        scope(NORTH_TRUJILLO, "NORTH-TRUJILLO", "North Trujillo", NORTH_ORG,
                                Set.of(Permission.ORDERS_ORDER_READ))));
    }

    /** Belongs to the other organization entirely. */
    public static TmsPrincipal outsider() {
        return new TmsPrincipal(
                UUID.fromString("00000000-0000-4000-8000-0000000000f3"),
                OUTSIDER_AUTH_USER,
                "outsider@example.invalid",
                "Test Outsider",
                List.of(scope(SOUTH_AREQUIPA, "SOUTH-AREQUIPA", "South Arequipa", SOUTH_ORG,
                        Set.of(Permission.IAM_COMPANY_READ))));
    }

    /** Provisioned, active, but no membership has been granted yet - a legitimate state. */
    public static TmsPrincipal withoutMemberships() {
        return new TmsPrincipal(
                UUID.fromString("00000000-0000-4000-8000-0000000000f4"),
                UUID.fromString("00000000-0000-4000-8000-0000000000e5"),
                "newcomer@example.invalid",
                "Test Newcomer",
                List.of());
    }

    private static CompanyScope scope(UUID companyId, String code, String name, UUID organizationId,
            Set<Permission> permissions) {
        boolean north = NORTH_ORG.equals(organizationId);
        return new CompanyScope(companyId, code, name, "America/Lima", organizationId,
                north ? "NORTH" : "SOUTH", north ? "North Organization" : "South Organization", permissions);
    }
}
