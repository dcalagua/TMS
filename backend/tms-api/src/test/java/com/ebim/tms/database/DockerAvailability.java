package com.ebim.tms.database;

import org.testcontainers.DockerClientFactory;

/**
 * Gate for the database integration tests.
 *
 * <p>Database tests need a disposable PostgreSQL, which needs a Docker daemon. When no
 * daemon is reachable the tests are <em>skipped and reported as skipped</em> rather than
 * failed or silently dropped: an honest "not verified here" is worth more than a green
 * build that proved nothing. Never replace this with a fallback to a shared database.
 *
 * <p>A machine without Docker may instead opt in to a <em>local, disposable</em> PostgreSQL by
 * setting {@code TMS_TEST_DB_URL} (with {@code TMS_TEST_DB_USER} / {@code TMS_TEST_DB_PASSWORD}).
 * The tests are then enabled and {@link PostgresTestDatabase} uses that server instead of the
 * container (see {@link ExternalTestServer}). Docker is not probed in that case, and a configured
 * server that is unreachable or unlike the container fails the tests loudly instead of skipping
 * them. Without the variable this gate is exactly the Docker probe it always was.
 *
 * <p>Used through {@code @EnabledIf("com.ebim.tms.database.DockerAvailability#isAvailable")}.
 */
public final class DockerAvailability {

    public static final String CONDITION = "com.ebim.tms.database.DockerAvailability#isAvailable";

    public static final String DISABLED_REASON =
            "Docker daemon is not available and TMS_TEST_DB_URL is not set: disposable PostgreSQL could not be started. "
                    + "This test verifies migrations and is not a substitute for one - start Docker, or point "
                    + "TMS_TEST_DB_URL / TMS_TEST_DB_USER / TMS_TEST_DB_PASSWORD at a local disposable "
                    + "PostgreSQL 17 + PostGIS, and rerun.";

    private DockerAvailability() {}

    public static boolean isAvailable() {
        if (ExternalTestServer.isConfigured()) {
            return true;
        }
        try {
            return DockerClientFactory.instance().isDockerAvailable();
        } catch (RuntimeException | LinkageError probeFailed) {
            return false;
        }
    }
}
