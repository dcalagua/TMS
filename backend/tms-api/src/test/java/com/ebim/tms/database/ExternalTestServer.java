package com.ebim.tms.database;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * An already-running, <em>local and disposable</em> PostgreSQL used by the database integration
 * tests in place of the Testcontainers container, for machines that have no Docker daemon.
 *
 * <p>Opt-in only. It is used when {@code TMS_TEST_DB_URL} is set (environment variable, or a JVM
 * system property of the same name); without it nothing here runs and the harness behaves
 * exactly as it always has. Coordinates:
 *
 * <ul>
 *   <li>{@code TMS_TEST_DB_URL} - JDBC URL of an <em>administrative</em> database on that server,
 *       e.g. {@code jdbc:postgresql://localhost:55434/postgres}. Test databases are created next
 *       to it; nothing is ever written into it.
 *   <li>{@code TMS_TEST_DB_USER} / {@code TMS_TEST_DB_PASSWORD} - a superuser, as the container's
 *       {@code POSTGRES_USER} is.
 *   <li>{@code TMS_TEST_DB_KEEP=true} - optional: keep the databases at JVM exit to inspect a
 *       failure. By default they are dropped, as the container is.
 * </ul>
 *
 * <p>It replicates the container's isolation rather than approximating it. The container is new
 * per JVM, so every class's {@code CREATE DATABASE} is guaranteed to produce an empty database.
 * A persistent server outlives the JVM, so each physical database name carries a random per-JVM
 * suffix ({@code tms_smoke} becomes {@code tms_smoke_r1a2b3c4d}): each class still gets its own
 * freshly created database, never one a previous run (or a concurrent one) left behind, and the
 * databases created by this JVM are dropped when it exits, as Ryuk removes the container.
 *
 * <p>It refuses to run unless the server has the posture the container gives: loopback host,
 * PostgreSQL 17, superuser connection, {@code postgis} installable, UTF8 {@code template1}, and
 * every created database free of extensions so that V1's {@code CREATE EXTENSION} genuinely
 * executes. A server that differs fails loudly instead of producing evidence about a different
 * database. Never point this at a shared or remote database.
 */
final class ExternalTestServer {

    static final String URL_VARIABLE = "TMS_TEST_DB_URL";
    static final String USER_VARIABLE = "TMS_TEST_DB_USER";
    static final String PASSWORD_VARIABLE = "TMS_TEST_DB_PASSWORD";
    static final String KEEP_VARIABLE = "TMS_TEST_DB_KEEP";

    /** The container image is {@code postgis/postgis:17-3.5}. */
    private static final int EXPECTED_MAJOR_VERSION = 17;

    private static final String JDBC_PREFIX = "jdbc:postgresql://";
    private static final int MAX_IDENTIFIER_BYTES = 63;

    private final String authority;
    private final String adminDatabase;
    private final String query;
    private final String username;
    private final String password;
    private final String runSuffix;
    private final Set<String> createdDatabases = ConcurrentHashMap.newKeySet();

    private ExternalTestServer(
            String authority, String adminDatabase, String query, String username, String password) {
        this.authority = authority;
        this.adminDatabase = adminDatabase;
        this.query = query;
        this.username = username;
        this.password = password;
        byte[] random = new byte[4];
        new SecureRandom().nextBytes(random);
        this.runSuffix = "_r" + HexFormat.of().formatHex(random);
    }

    static boolean isConfigured() {
        return !isBlank(setting(URL_VARIABLE));
    }

    /** Reads the coordinates, verifies the server's posture and registers the JVM-exit cleanup. */
    static ExternalTestServer start() {
        String url = setting(URL_VARIABLE).trim();
        if (!url.startsWith(JDBC_PREFIX)) {
            throw misconfigured(URL_VARIABLE + " must be a " + JDBC_PREFIX + "host:port/database URL");
        }
        String rest = url.substring(JDBC_PREFIX.length());
        int queryStart = rest.indexOf('?');
        String query = queryStart < 0 ? "" : rest.substring(queryStart);
        String hostAndPath = queryStart < 0 ? rest : rest.substring(0, queryStart);
        int slash = hostAndPath.indexOf('/');
        String authority = slash < 0 ? hostAndPath : hostAndPath.substring(0, slash);
        String adminDatabase = slash < 0 ? "" : hostAndPath.substring(slash + 1);
        if (authority.isEmpty() || authority.contains(",")) {
            throw misconfigured(URL_VARIABLE + " must name exactly one host");
        }
        if (adminDatabase.isEmpty()) {
            adminDatabase = "postgres";
        }
        requireLoopback(hostOf(authority));

        String username = setting(USER_VARIABLE);
        if (isBlank(username)) {
            throw misconfigured(USER_VARIABLE + " is required when " + URL_VARIABLE + " is set");
        }
        String password = setting(PASSWORD_VARIABLE);

        ExternalTestServer server =
                new ExternalTestServer(authority, adminDatabase, query, username, password == null ? "" : password);
        server.verifyPosture();
        if (!Boolean.parseBoolean(setting(KEEP_VARIABLE))) {
            Runtime.getRuntime().addShutdownHook(new Thread(server::dropCreatedDatabases, "tms-test-db-cleanup"));
        }
        return server;
    }

    String username() {
        return username;
    }

    String password() {
        return password;
    }

    /** JDBC URL of the physical database behind a logical test database name. */
    String jdbcUrl(String databaseName) {
        return JDBC_PREFIX + authority + "/" + physicalName(databaseName) + query;
    }

    /** Creates an empty database for this JVM and returns its JDBC URL. */
    String createEmptyDatabase(String databaseName) {
        String physical = physicalName(databaseName);
        try (Connection admin = connect(adminUrl());
                Statement statement = admin.createStatement()) {
            // Identifier is a test-local constant plus a hex suffix, never user input.
            statement.execute("CREATE DATABASE \"" + physical + "\"");
            createdDatabases.add(physical);
        } catch (SQLException e) {
            throw new IllegalStateException("could not create test database " + physical
                    + " on the external test server " + authority, e);
        }
        String url = jdbcUrl(databaseName);
        requireNoExtensions(url, physical);
        return url;
    }

    private String physicalName(String databaseName) {
        String physical = databaseName + runSuffix;
        if (physical.getBytes(StandardCharsets.UTF_8).length > MAX_IDENTIFIER_BYTES) {
            throw new IllegalArgumentException("test database name too long for PostgreSQL once suffixed: " + physical);
        }
        return physical;
    }

    private String adminUrl() {
        return JDBC_PREFIX + authority + "/" + adminDatabase + query;
    }

    private Connection connect(String url) throws SQLException {
        return DriverManager.getConnection(url, username, password);
    }

    private void verifyPosture() {
        List<String> problems = new ArrayList<>();
        String version;
        String postgis;
        try (Connection admin = connect(adminUrl());
                Statement statement = admin.createStatement()) {
            version = single(statement, "SELECT current_setting('server_version')");
            int major = Integer.parseInt(single(statement, "SELECT current_setting('server_version_num')")) / 10000;
            if (major != EXPECTED_MAJOR_VERSION) {
                problems.add("PostgreSQL major version is " + major + ", the container runs "
                        + EXPECTED_MAJOR_VERSION);
            }
            if (!"t".equals(single(statement, "SELECT rolsuper FROM pg_roles WHERE rolname = current_user"))) {
                problems.add("user " + username + " is not a superuser; the container's POSTGRES_USER is");
            }
            postgis = single(statement, "SELECT default_version FROM pg_available_extensions WHERE name = 'postgis'");
            if (postgis == null) {
                problems.add("the postgis extension is not installed on this server; migration V1 creates it");
            }
            String encoding = single(statement,
                    "SELECT pg_encoding_to_char(encoding) FROM pg_database WHERE datname = 'template1'");
            if (!"UTF8".equals(encoding)) {
                problems.add("template1 encoding is " + encoding + ", the container's is UTF8 (initdb -E UTF8)");
            }
        } catch (SQLException e) {
            throw new IllegalStateException("external test server " + authority + " (" + URL_VARIABLE
                    + ") is not reachable as " + username + ": " + e.getMessage(), e);
        }
        if (!problems.isEmpty()) {
            throw misconfigured("external test server " + authority + " does not match the container: "
                    + String.join("; ", problems));
        }
        System.out.println("[tms-test-db] external disposable PostgreSQL " + version + " at " + authority
                + " (postgis " + postgis + " available), databases suffixed " + runSuffix);
    }

    private void requireNoExtensions(String url, String physical) {
        // The container's template1 carries only plpgsql; the postgis image loads PostGIS into its
        // template_postgis and POSTGRES_DB, never into template1. Same guarantee here.
        try (Connection connection = connect(url);
                Statement statement = connection.createStatement();
                ResultSet extensions = statement.executeQuery(
                        "SELECT string_agg(extname, ', ') FROM pg_extension WHERE extname <> 'plpgsql'")) {
            extensions.next();
            String unexpected = extensions.getString(1);
            if (unexpected != null) {
                throw misconfigured("template1 on the external test server carries extensions (" + unexpected
                        + "), so " + physical + " is not empty; the container's template1 carries none");
            }
        } catch (SQLException e) {
            throw new IllegalStateException("could not inspect test database " + physical, e);
        }
    }

    private void dropCreatedDatabases() {
        if (createdDatabases.isEmpty()) {
            return;
        }
        try (Connection admin = connect(adminUrl());
                Statement statement = admin.createStatement()) {
            for (String physical : createdDatabases) {
                try {
                    statement.execute("DROP DATABASE IF EXISTS \"" + physical + "\" WITH (FORCE)");
                } catch (SQLException e) {
                    System.err.println("[tms-test-db] could not drop " + physical + ": " + e.getMessage());
                }
            }
        } catch (SQLException e) {
            System.err.println("[tms-test-db] could not drop this run's databases: " + e.getMessage());
        }
    }

    private static String single(Statement statement, String sql) throws SQLException {
        try (ResultSet rows = statement.executeQuery(sql)) {
            return rows.next() ? rows.getString(1) : null;
        }
    }

    private static String hostOf(String authority) {
        if (authority.startsWith("[")) {
            int close = authority.indexOf(']');
            return close < 0 ? authority : authority.substring(1, close);
        }
        int colon = authority.indexOf(':');
        return colon < 0 ? authority : authority.substring(0, colon);
    }

    private static void requireLoopback(String host) {
        try {
            for (InetAddress address : InetAddress.getAllByName(host)) {
                if (!address.isLoopbackAddress()) {
                    throw misconfigured(URL_VARIABLE + " host " + host
                            + " is not a loopback address; the test database must be local and disposable");
                }
            }
        } catch (UnknownHostException e) {
            throw misconfigured(URL_VARIABLE + " host " + host + " does not resolve");
        }
    }

    private static String setting(String name) {
        String value = System.getenv(name);
        return isBlank(value) ? System.getProperty(name) : value;
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private static IllegalStateException misconfigured(String message) {
        return new IllegalStateException(message);
    }
}
