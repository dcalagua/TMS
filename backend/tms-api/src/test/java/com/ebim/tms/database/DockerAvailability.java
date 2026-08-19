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
 * <p>Used through {@code @EnabledIf("com.ebim.tms.database.DockerAvailability#isAvailable")}.
 */
final class DockerAvailability {

    static final String CONDITION = "com.ebim.tms.database.DockerAvailability#isAvailable";

    static final String DISABLED_REASON =
            "Docker daemon is not available: disposable PostgreSQL could not be started. "
                    + "This test verifies migrations and is not a substitute for one - start Docker and rerun.";

    private DockerAvailability() {}

    static boolean isAvailable() {
        try {
            return DockerClientFactory.instance().isDockerAvailable();
        } catch (RuntimeException | LinkageError probeFailed) {
            return false;
        }
    }
}
