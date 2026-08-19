package com.ebim.tms.database;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import org.flywaydb.core.Flyway;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Disposable PostgreSQL for the database integration tests.
 *
 * <p>One container is started per JVM and reused by every test class; Testcontainers' Ryuk
 * sidecar removes it when the JVM exits. Each test class asks for its <em>own</em> freshly
 * created database inside that container, so "apply the whole history to an empty database"
 * is a real assertion and never depends on what another test left behind.
 *
 * <p>The image is PostGIS-enabled because migration V1 creates the {@code postgis}
 * extension. Databases created here derive from {@code template1}, which does not carry the
 * extension, so {@code CREATE EXTENSION} genuinely executes rather than short-circuiting.
 *
 * <p>No test may ever point at a shared or remote database. The connection coordinates come
 * from the container and from nowhere else.
 */
public final class PostgresTestDatabase {

    /** Overridable for CI mirrors: {@code -Dtms.test.postgres.image=...}. Must support PostGIS. */
    private static final String IMAGE_PROPERTY = "tms.test.postgres.image";

    private static final String DEFAULT_IMAGE = "postgis/postgis:17-3.5";

    private static final String ADMIN_DATABASE = "tms_test";
    private static final String USERNAME = "tms_test";
    private static final String PASSWORD = "tms_test_local_only";

    private static volatile PostgreSQLContainer<?> container;

    private PostgresTestDatabase() {}

    public static PostgreSQLContainer<?> container() {
        PostgreSQLContainer<?> running = container;
        if (running == null) {
            synchronized (PostgresTestDatabase.class) {
                running = container;
                if (running == null) {
                    DockerImageName image = DockerImageName.parse(System.getProperty(IMAGE_PROPERTY, DEFAULT_IMAGE))
                            .asCompatibleSubstituteFor("postgres");
                    running = new PostgreSQLContainer<>(image)
                            .withDatabaseName(ADMIN_DATABASE)
                            .withUsername(USERNAME)
                            .withPassword(PASSWORD);
                    running.start();
                    container = running;
                }
            }
        }
        return running;
    }

    /** Creates an empty database inside the container and returns its JDBC URL. */
    public static String createEmptyDatabase(String databaseName) {
        PostgreSQLContainer<?> postgres = container();
        try (Connection admin = connect(jdbcUrl(ADMIN_DATABASE));
                Statement statement = admin.createStatement()) {
            // Identifier is a test-local constant, never user input.
            statement.execute("CREATE DATABASE \"" + databaseName + "\"");
        } catch (SQLException e) {
            throw new IllegalStateException("could not create test database " + databaseName, e);
        }
        return jdbcUrl(databaseName, postgres);
    }

    public static String jdbcUrl(String databaseName) {
        return jdbcUrl(databaseName, container());
    }

    private static String jdbcUrl(String databaseName, PostgreSQLContainer<?> postgres) {
        return "jdbc:postgresql://" + postgres.getHost() + ":"
                + postgres.getMappedPort(PostgreSQLContainer.POSTGRESQL_PORT) + "/" + databaseName;
    }

    public static String username() {
        return USERNAME;
    }

    public static String password() {
        return PASSWORD;
    }

    public static Connection connect(String jdbcUrl) throws SQLException {
        return DriverManager.getConnection(jdbcUrl, USERNAME, PASSWORD);
    }

    /**
     * Flyway configured exactly like the application (same locations, same schema, clean
     * disabled), so what the tests exercise is what production runs.
     */
    public static Flyway flyway(String jdbcUrl) {
        return Flyway.configure()
                .dataSource(jdbcUrl, USERNAME, PASSWORD)
                .locations("classpath:db/migration")
                .schemas("tms")
                .defaultSchema("tms")
                .createSchemas(true)
                .baselineOnMigrate(false)
                .validateOnMigrate(true)
                .cleanDisabled(true)
                .load();
    }

    /** Creates an empty database and applies the whole migration history to it. */
    public static String createMigratedDatabase(String databaseName) {
        String jdbcUrl = createEmptyDatabase(databaseName);
        flyway(jdbcUrl).migrate();
        return jdbcUrl;
    }
}
