package com.ebim.tms.integration.api;

/**
 * The inbound integration API's URL space.
 *
 * <p>{@code /integration/v1} is deliberately <em>not</em> under {@code tms.api.base-path}. The two
 * surfaces version independently: the browser application ships with the backend and can follow
 * {@code /api/v1} wherever it goes, while a partner's ERP is deployed on someone else's schedule
 * and a URL they were given three years ago has to keep meaning the same thing. Sharing a base
 * path would tie those two release cadences together for no benefit.
 *
 * <p>It is also what lets {@code IntegrationSecurityConfig} own a whole prefix with a single
 * security matcher, so no user-facing endpoint can ever be reached with a partner credential.
 */
public final class IntegrationApiPaths {

    public static final String V1 = "/integration/v1";

    private IntegrationApiPaths() {}
}
