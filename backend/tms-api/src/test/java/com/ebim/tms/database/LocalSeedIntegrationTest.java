package com.ebim.tms.database;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Locale;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;

/**
 * Keeps the local development seed honest.
 *
 * <p>{@code supabase/seeds/local_dev_seed.sql} is intentionally outside the Flyway history -
 * demo tenants and users must never be part of canonical migrations. The price of that
 * separation is that the seed can silently rot when the schema moves, so it is applied here
 * to a throwaway database and its result is asserted.
 */
@EnabledIf(value = DockerAvailability.CONDITION, disabledReason = DockerAvailability.DISABLED_REASON)
class LocalSeedIntegrationTest {

    private static final Path SEED =
            MigrationScripts.repositoryRoot().resolve("supabase/seeds/local_dev_seed.sql");

    @Test
    @DisplayName("the local seed applies to a migrated database and is re-runnable")
    void seedAppliesAndIsIdempotent() throws SQLException {
        String jdbcUrl = PostgresTestDatabase.createMigratedDatabase("tms_local_seed");

        applySeed(jdbcUrl);
        applySeed(jdbcUrl); // ON CONFLICT DO NOTHING everywhere: running it twice changes nothing

        try (Connection connection = PostgresTestDatabase.connect(jdbcUrl);
                Statement statement = connection.createStatement()) {
            assertThat(count(statement, "SELECT count(*) FROM tms.organization")).isOne();
            assertThat(count(statement, "SELECT count(*) FROM tms.company")).isEqualTo(2);
            assertThat(count(statement, "SELECT count(*) FROM tms.app_user")).isEqualTo(3);
            assertThat(count(statement, "SELECT count(*) FROM tms.membership")).isEqualTo(3);
            assertThat(count(statement, "SELECT count(*) FROM tms.membership_role")).isEqualTo(3);

            // The administrator is organization-wide, the other two are company scoped.
            assertThat(count(statement, "SELECT count(*) FROM tms.membership WHERE company_id IS NULL")).isOne();
            assertThat(count(statement, """
                    SELECT count(*) FROM tms.membership_role mr
                    JOIN tms.role r ON r.id = mr.role_id
                    WHERE r.code IN ('ORGANIZATION_ADMIN', 'PLANNER', 'VIEWER')
                    """)).isEqualTo(3);
        }
    }

    @Test
    @DisplayName("the seed carries no credential and no auth.users dependency")
    void seedContainsNoSecret() {
        String sql = MigrationScripts.withoutComments(read(SEED)).toLowerCase(Locale.ROOT);

        assertThat(sql).doesNotContain("password").doesNotContain("service_role").doesNotContain("secret");
        assertThat(sql)
                .as("the seed must not depend on Supabase-managed tables")
                .doesNotContain("insert into auth.")
                .doesNotContain("from auth.users");
    }

    private static void applySeed(String jdbcUrl) throws SQLException {
        try (Connection connection = PostgresTestDatabase.connect(jdbcUrl);
                Statement statement = connection.createStatement()) {
            statement.execute(read(SEED));
        }
    }

    private static String read(Path path) {
        try {
            return Files.readString(path, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("local seed is missing or unreadable: " + path, e);
        }
    }

    private static long count(Statement statement, String sql) throws SQLException {
        try (ResultSet resultSet = statement.executeQuery(sql)) {
            resultSet.next();
            return resultSet.getLong(1);
        }
    }
}
