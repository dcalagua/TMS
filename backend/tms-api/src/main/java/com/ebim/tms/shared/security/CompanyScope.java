package com.ebim.tms.shared.security;

import java.time.DateTimeException;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Set;
import java.util.UUID;

/**
 * One company the caller may act in, together with the permissions they hold there.
 *
 * <p>Two roles, one type:
 *
 * <ol>
 *   <li>inside {@link TmsPrincipal} it is an element of "the companies this caller may select";</li>
 *   <li>as a controller method parameter it is <em>the</em> company the current request operates
 *       in, already validated against the caller's active memberships.</li>
 * </ol>
 *
 * <p>Because the only way to obtain one is server-side resolution
 * ({@link CompanyScopeArgumentResolver}), a service that takes a {@code CompanyScope} cannot
 * be called with a company the caller has no membership in. That is the point: the tenant is
 * a resolved fact, not a method argument the caller chose.
 *
 * @param permissions the union of the permissions granted by every active membership that
 *     applies to this company - the company-scoped membership and any organization-wide one
 * @param carrierId the carrier this caller acts for in this company (ADR-018), or null for everyone
 *     who is not a carrier user. Set, the caller sees only that carrier's trips - in the services
 *     and, through {@code tms.current_carrier_id()}, in Row Level Security
 */
public record CompanyScope(
        UUID companyId,
        String companyCode,
        String companyName,
        String timeZone,
        UUID organizationId,
        String organizationCode,
        String organizationName,
        Set<Permission> permissions,
        UUID carrierId) {

    public CompanyScope {
        permissions = Set.copyOf(permissions);
    }

    /** A scope that acts for no carrier: every caller except a carrier-portal user. */
    public CompanyScope(UUID companyId, String companyCode, String companyName, String timeZone,
            UUID organizationId, String organizationCode, String organizationName, Set<Permission> permissions) {
        this(companyId, companyCode, companyName, timeZone, organizationId, organizationCode, organizationName,
                permissions, null);
    }

    /** True for a carrier-portal user (ADR-018): their world is one carrier's trips. */
    public boolean isCarrierScoped() {
        return carrierId != null;
    }

    public boolean has(Permission permission) {
        return permissions.contains(permission);
    }

    /**
     * The company's own zone, from {@code tms.company.time_zone} - what "today" and "which
     * calendar day is this instant" mean for this tenant. A depot in {@code America/Lima} closing
     * its day at 21:00 is still on the same date the plan calls it; judged in UTC it is already
     * tomorrow.
     *
     * <p>An unrecognised or missing zone falls back to the system default rather than failing the
     * request: a bad {@code time_zone} is a master-data defect, and making it un-plannable would
     * turn a wrong label into an outage.
     */
    public ZoneId zoneId() {
        return zoneOf(timeZone);
    }

    /** {@link #zoneId()} for callers that hold the raw column rather than a scope. */
    public static ZoneId zoneOf(String timeZone) {
        if (timeZone == null || timeZone.isBlank()) {
            return ZoneId.systemDefault();
        }
        try {
            return ZoneId.of(timeZone);
        } catch (DateTimeException unknown) {
            return ZoneId.systemDefault();
        }
    }

    /** The current calendar day in this company's zone - never the server's. */
    public LocalDate today() {
        return LocalDate.now(zoneId());
    }
}
