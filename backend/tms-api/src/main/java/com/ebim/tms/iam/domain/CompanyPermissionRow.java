package com.ebim.tms.iam.domain;

import java.util.UUID;

/**
 * One (company, permission) pair a user holds, as the access query returns it.
 *
 * <p>Flat on purpose: a user with an organization-wide membership and a company-scoped one in
 * the same organization contributes rows for both, and the service unions them per company.
 * Doing that grouping in Java rather than in SQL keeps the query a single index-friendly join
 * instead of a nested aggregate.
 */
public record CompanyPermissionRow(
        UUID companyId,
        String companyCode,
        String companyName,
        String timeZone,
        UUID organizationId,
        String organizationCode,
        String organizationName,
        String permissionCode) {}
