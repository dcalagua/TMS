package com.ebim.tms.database;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.SQLException;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;

/**
 * V62 at the database level (ADR-022 sections 4-6): the backfill of orders that predate it, the
 * restated ledger guard, and the new CHECKs. Migrates to V61, seeds an order the way V37 left it
 * (part of it allocated), and only then applies V62 - a fully migrated database has nothing to
 * backfill.
 */
@EnabledIf(value = DockerAvailability.CONDITION, disabledReason = DockerAvailability.DISABLED_REASON)
class LoadGeometryMigrationIntegrationTest {

    private static final String CHECK_VIOLATION = "23514";

    private static String jdbcUrl;
    private static UUID company;
    private static UUID origin;
    private static UUID destination;
    private static UUID orderBeforeV62;

    @BeforeAll
    static void migrateAcrossV62() throws SQLException {
        jdbcUrl = PostgresTestDatabase.createEmptyDatabase("tms_load_geometry_v62");
        PostgresTestDatabase.flywayTo(jdbcUrl, "61").migrate();

        UUID organization = id("INSERT INTO tms.organization (code, name) VALUES ('V62-ORG', 'V62') RETURNING id");
        company = id("INSERT INTO tms.company (organization_id, code, name, time_zone) VALUES ('" + organization
                + "', 'V62-CO', 'V62 Co', 'America/Lima') RETURNING id");
        origin = id("INSERT INTO tms.location (company_id, code, name) VALUES ('" + company
                + "', 'V62-CD', 'CD') RETURNING id");
        destination = id("INSERT INTO tms.location (company_id, code, name) VALUES ('" + company
                + "', 'V62-ST', 'Store') RETURNING id");
        orderBeforeV62 = id("INSERT INTO tms.transport_order (company_id, order_number, origin_id, destination_id,"
                + " service_date, status, total_weight_kg, total_volume_m3, total_pallets, allocated_volume_m3)"
                + " VALUES ('" + company + "', 'TO-V62-OLD', '" + origin + "', '" + destination + "', '2026-10-19',"
                + " 'READY_FOR_PLANNING', 100, 5, 2, 2) RETURNING id");

        PostgresTestDatabase.flyway(jdbcUrl).migrate();
    }

    @Test
    @DisplayName("an order that predates V62 counts its declared volume: planning = total, no floor, no height")
    void backfillKeepsTodaysBehaviour() throws SQLException {
        assertThat(value("SELECT concat_ws('|', planning_volume_m3, floor_area_m2, allocated_floor_area_m2,"
                + " coalesce(max_stack_height_m::text, '-'), geometry_unmeasured_lines) FROM tms.transport_order"
                + " WHERE id = '" + orderBeforeV62 + "'")).isEqualTo("5.0000|0.0000|0.0000|-|0");
    }

    @Test
    @DisplayName("allocated volume is measured against the planning volume, and floor against floor")
    void ledgerGuardIsRestated() {
        assertViolates(() -> execute("UPDATE tms.transport_order SET allocated_volume_m3 = 5.5 WHERE id = '"
                + orderBeforeV62 + "'"));
        assertViolates(() -> execute("UPDATE tms.transport_order SET allocated_floor_area_m2 = 0.1 WHERE id = '"
                + orderBeforeV62 + "'"));
    }

    @Test
    @DisplayName("a writer that names no planning volume gets the total")
    void triggerDefaultsPlanningVolume() throws SQLException {
        UUID order = id("INSERT INTO tms.transport_order (company_id, order_number, origin_id, destination_id,"
                + " service_date, total_volume_m3) VALUES ('" + company + "', 'TO-V62-NEW', '" + origin + "', '"
                + destination + "', '2026-10-19', 3.25) RETURNING id");
        assertThat(value("SELECT planning_volume_m3 FROM tms.transport_order WHERE id = '" + order + "'"))
                .isEqualTo("3.2500");
    }

    @Test
    @DisplayName("fill factors are 0 < x <= 1 and heights are positive")
    void factorsAndHeights() {
        assertViolates(() -> execute("INSERT INTO tms.vehicle_type (company_id, code, name, max_weight_kg,"
                + " max_volume_m3, load_floor_fill_factor) VALUES ('" + company + "', 'V62-T1', 'T', 1, 1, 1.5)"));
        assertViolates(() -> execute("INSERT INTO tms.company_settings (company_id, load_floor_fill_factor)"
                + " VALUES ('" + company + "', 0)"));
        assertViolates(() -> execute("UPDATE tms.transport_order SET max_stack_height_m = 0 WHERE id = '"
                + orderBeforeV62 + "'"));
    }

    private static void assertViolates(ThrowingRunnable statement) {
        assertThatThrownBy(statement::run)
                .isInstanceOf(SQLException.class)
                .satisfies(failure -> assertThat(((SQLException) failure).getSQLState()).isEqualTo(CHECK_VIOLATION));
    }

    @FunctionalInterface
    private interface ThrowingRunnable {
        void run() throws SQLException;
    }

    private static UUID id(String sql) throws SQLException {
        return UUID.fromString(value(sql));
    }

    private static String value(String sql) throws SQLException {
        try (var connection = PostgresTestDatabase.connect(jdbcUrl);
                var statement = connection.createStatement();
                var resultSet = statement.executeQuery(sql)) {
            resultSet.next();
            return resultSet.getString(1);
        }
    }

    private static void execute(String sql) throws SQLException {
        try (var connection = PostgresTestDatabase.connect(jdbcUrl);
                var statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }
}
