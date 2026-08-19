package com.ebim.tms.database;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationInfo;
import org.flywaydb.core.api.MigrationState;
import org.flywaydb.core.api.output.MigrateResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;

/**
 * Proves the migration history itself: it applies to an empty database, it is idempotent,
 * it validates, and replaying it produces byte-for-byte the same schema.
 *
 * <p>Runs against a throwaway container only. Never a shared or remote database.
 */
@EnabledIf(value = DockerAvailability.CONDITION, disabledReason = DockerAvailability.DISABLED_REASON)
class FlywayMigrationIntegrationTest {

    @Test
    @DisplayName("the whole history applies to an empty database, in order, without failures")
    void migrationsApplyFromAnEmptyDatabase() throws SQLException {
        String jdbcUrl = PostgresTestDatabase.createEmptyDatabase("tms_migrate_from_empty");
        Flyway flyway = PostgresTestDatabase.flyway(jdbcUrl);

        MigrateResult result = flyway.migrate();

        int expectedScripts = MigrationScripts.scripts().size();
        assertThat(result.success).isTrue();
        assertThat(result.migrationsExecuted).isEqualTo(expectedScripts);

        // Flyway also records a versionless "<< Flyway Schema Creation >>" row when it creates
        // the tms schema; the versioned rows are the migration history proper.
        List<MigrationInfo> applied = Arrays.stream(flyway.info().applied())
                .filter(info -> info.getVersion() != null)
                .toList();
        assertThat(applied).hasSize(expectedScripts);
        assertThat(applied).allSatisfy(info -> assertThat(info.getState()).isEqualTo(MigrationState.SUCCESS));
        assertThat(applied.stream().map(info -> info.getVersion().toString()).toList())
                .containsExactlyElementsOf(
                        MigrationScripts.scripts().stream().map(s -> String.valueOf(MigrationScripts.version(s))).toList());

        // Flyway keeps its history inside the application schema, not in public.
        assertThat(singleValue(jdbcUrl, "SELECT to_regclass('tms.flyway_schema_history')::text"))
                .isEqualTo("tms.flyway_schema_history");

        // Nothing leaked into public: the Supabase Data API exposes public, we do not use it.
        assertThat(singleValue(jdbcUrl,
                        "SELECT count(*)::text FROM information_schema.tables"
                                + " WHERE table_schema = 'public' AND table_name LIKE '%flyway%'"))
                .isEqualTo("0");
    }

    @Test
    @DisplayName("validate passes and a second migrate is a no-op")
    void migrateIsIdempotentAndValidates() {
        String jdbcUrl = PostgresTestDatabase.createMigratedDatabase("tms_migrate_idempotent");
        Flyway flyway = PostgresTestDatabase.flyway(jdbcUrl);

        flyway.validate(); // throws if a checksum or a version diverged

        MigrateResult second = flyway.migrate();

        assertThat(second.success).isTrue();
        assertThat(second.migrationsExecuted).isZero();
        assertThat(flyway.info().current().getVersion().toString())
                .isEqualTo(String.valueOf(MigrationScripts.version(MigrationScripts.scripts().getLast())));
    }

    @Test
    @DisplayName("replaying the history on another empty database yields an identical schema")
    void replayIsDeterministic() throws SQLException {
        String first = PostgresTestDatabase.createMigratedDatabase("tms_replay_a");
        String second = PostgresTestDatabase.createMigratedDatabase("tms_replay_b");

        assertThat(schemaFingerprint(second)).isEqualTo(schemaFingerprint(first));
    }

    @Test
    @DisplayName("PostGIS is available for the generated location columns of Steps 05/06")
    void postgisExtensionIsInstalled() throws SQLException {
        String jdbcUrl = PostgresTestDatabase.createMigratedDatabase("tms_postgis");

        assertThat(singleValue(jdbcUrl, "SELECT extname FROM pg_extension WHERE extname = 'postgis'"))
                .isEqualTo("postgis");
        assertThat(singleValue(jdbcUrl, "SELECT postgis_lib_version()")).isNotBlank();
    }

    /**
     * A stable textual description of everything the migrations create: columns, constraints,
     * indexes, triggers and reference rows. Two databases built from the same history must
     * produce the same string.
     */
    private static String schemaFingerprint(String jdbcUrl) throws SQLException {
        StringBuilder fingerprint = new StringBuilder();
        fingerprint.append(rows(jdbcUrl, """
                SELECT table_name, ordinal_position, column_name, data_type, is_nullable,
                       coalesce(column_default, '-'), coalesce(generation_expression, '-')
                FROM information_schema.columns
                WHERE table_schema = 'tms' AND table_name <> 'flyway_schema_history'
                ORDER BY table_name, ordinal_position
                """));
        fingerprint.append(rows(jdbcUrl, """
                SELECT conrelid::regclass::text, conname, pg_get_constraintdef(oid)
                FROM pg_constraint
                WHERE connamespace = 'tms'::regnamespace
                ORDER BY 1, 2
                """));
        fingerprint.append(rows(jdbcUrl, """
                SELECT tablename, indexname, indexdef
                FROM pg_indexes
                WHERE schemaname = 'tms' AND tablename <> 'flyway_schema_history'
                ORDER BY 1, 2
                """));
        fingerprint.append(rows(jdbcUrl, """
                SELECT c.relname, t.tgname, pg_get_triggerdef(t.oid)
                FROM pg_trigger t
                JOIN pg_class c ON c.oid = t.tgrelid
                WHERE NOT t.tgisinternal AND c.relnamespace = 'tms'::regnamespace
                ORDER BY 1, 2
                """));
        fingerprint.append(rows(jdbcUrl, """
                SELECT r.code, p.code
                FROM tms.role_permission rp
                JOIN tms.role r ON r.id = rp.role_id
                JOIN tms.permission p ON p.id = rp.permission_id
                ORDER BY 1, 2
                """));
        return fingerprint.toString();
    }

    private static String rows(String jdbcUrl, String sql) throws SQLException {
        List<String> lines = new ArrayList<>();
        try (Connection connection = PostgresTestDatabase.connect(jdbcUrl);
                Statement statement = connection.createStatement();
                ResultSet resultSet = statement.executeQuery(sql)) {
            int columns = resultSet.getMetaData().getColumnCount();
            while (resultSet.next()) {
                StringBuilder line = new StringBuilder();
                for (int column = 1; column <= columns; column++) {
                    line.append(resultSet.getString(column)).append('|');
                }
                lines.add(line.toString());
            }
        }
        return String.join("\n", lines) + "\n--\n";
    }

    private static String singleValue(String jdbcUrl, String sql) throws SQLException {
        try (Connection connection = PostgresTestDatabase.connect(jdbcUrl);
                Statement statement = connection.createStatement();
                ResultSet resultSet = statement.executeQuery(sql)) {
            return resultSet.next() ? resultSet.getString(1) : null;
        }
    }
}
