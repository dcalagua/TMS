/**
 * Identity and access management: mapping of the Supabase {@code auth.users} identity to
 * the business {@code app_user}, memberships, organizations, companies, roles and the
 * resolution of the effective company scope for every request.
 *
 * <p>Owner of tenancy resolution. See {@code docs/architecture/ADR-003-multitenancy-company-scope.md}.
 */
package com.ebim.tms.iam;
