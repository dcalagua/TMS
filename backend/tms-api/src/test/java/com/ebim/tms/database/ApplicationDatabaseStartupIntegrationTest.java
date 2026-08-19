package com.ebim.tms.database;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import javax.sql.DataSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Boots the real application context against a disposable PostgreSQL.
 *
 * <p>Step 01 could only smoke-test the API with persistence auto-configuration excluded.
 * This test closes that gap: datasource, JPA and Flyway start together, the application's
 * own Flyway settings (schema {@code tms}, validate on migrate, clean disabled) are the ones
 * exercised, and the schema ends up where the configuration says it should.
 */
@EnabledIf(value = DockerAvailability.CONDITION, disabledReason = DockerAvailability.DISABLED_REASON)
@SpringBootTest(properties = {
    "spring.flyway.enabled=true",
    "management.health.db.enabled=true"
})
@ActiveProfiles("test")
class ApplicationDatabaseStartupIntegrationTest {

    @Autowired
    private DataSource dataSource;

    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        String jdbcUrl = PostgresTestDatabase.createEmptyDatabase("tms_application_startup");
        registry.add("spring.datasource.url", () -> jdbcUrl);
        registry.add("spring.datasource.username", PostgresTestDatabase::username);
        registry.add("spring.datasource.password", PostgresTestDatabase::password);
    }

    @Test
    @DisplayName("the application starts, Flyway migrates into the tms schema, JPA is wired")
    void applicationStartsAgainstAMigratedDatabase() throws SQLException {
        assertThat(dataSource).isNotNull();

        try (Connection connection = dataSource.getConnection();
                Statement statement = connection.createStatement()) {
            // Versioned rows only: Flyway also records a versionless schema-creation entry.
            assertThat(scalar(statement, "SELECT count(*)::text FROM tms.flyway_schema_history"
                            + " WHERE success AND version IS NOT NULL"))
                    .isEqualTo(String.valueOf(MigrationScripts.scripts().size()));
            assertThat(scalar(statement, "SELECT count(*)::text FROM tms.flyway_schema_history WHERE NOT success"))
                    .isEqualTo("0");
            assertThat(scalar(statement, "SELECT count(*)::text FROM tms.role")).isEqualTo("4");
            assertThat(scalar(statement, "SELECT to_regclass('public.membership')::text")).isNull();
        }
    }

    private static String scalar(Statement statement, String sql) throws SQLException {
        try (ResultSet resultSet = statement.executeQuery(sql)) {
            return resultSet.next() ? resultSet.getString(1) : null;
        }
    }
}
