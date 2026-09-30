package com.ebim.tms.database;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;

/**
 * V52 at the database level (ADR-013): the backfill of trips that departed before it, and the
 * exclusive-ors that replace V25's "a person dispatched" pairs.
 *
 * <p>Migrates to V51, seeds a departed trip the way V25 left it, and only then applies V52. A fully
 * migrated database has no pre-V52 departure to backfill, so asserting the backfill there would
 * prove nothing - the reason {@code CanonicalLocationConstraintIntegrationTest} does the same.
 */
@EnabledIf(value = DockerAvailability.CONDITION, disabledReason = DockerAvailability.DISABLED_REASON)
class DispatchSourceMigrationIntegrationTest {

    private static final String CHECK_VIOLATION = "23514";

    private static String jdbcUrl;
    private static UUID company;
    private static UUID person;
    private static UUID client;
    private static UUID departedBeforeV52;
    private static String updatedAtBeforeV52;

    @BeforeAll
    static void migrateAcrossV52() throws SQLException {
        jdbcUrl = PostgresTestDatabase.createEmptyDatabase("tms_dispatch_source_v52");
        PostgresTestDatabase.flywayTo(jdbcUrl, "51").migrate();

        UUID organization = id("INSERT INTO tms.organization (code, name) VALUES ('V52-ORG', 'V52') RETURNING id");
        company = id("INSERT INTO tms.company (organization_id, code, name, time_zone) VALUES ('" + organization
                + "', 'V52-CO', 'V52 Co', 'America/Lima') RETURNING id");
        person = id("INSERT INTO tms.app_user (auth_user_id, email, full_name) VALUES (gen_random_uuid(),"
                + " 'v52@example.invalid', 'V52 Dispatcher') RETURNING id");
        client = id("INSERT INTO tms.integration_client (company_id, client_id, name, secret_hash, created_by)"
                + " VALUES ('" + company + "', 'tmsc_v52dispatchsource00000', 'EWM V52', repeat('a', 64), '" + person
                + "') RETURNING id");
        departedBeforeV52 = departedTrip("SH-V52-OLD", "dispatched_by", person);
        updatedAtBeforeV52 = value("SELECT updated_at::text FROM tms.trip WHERE id = '" + departedBeforeV52 + "'");

        PostgresTestDatabase.flyway(jdbcUrl).migrate();
    }

    @Test
    @DisplayName("a trip that departed before V52 is backfilled as dispatched by a person, and not re-stamped")
    void historicalDeparturesAreOperatorDispatches() throws SQLException {
        assertThat(value("SELECT dispatch_source FROM tms.trip WHERE id = '" + departedBeforeV52 + "'"))
                .isEqualTo("OPERATOR");
        // The reconciliation feed is ordered by updated_at; the backfill must not push history into it.
        assertThat(value("SELECT updated_at::text FROM tms.trip WHERE id = '" + departedBeforeV52 + "'"))
                .isEqualTo(updatedAtBeforeV52);
    }

    @Test
    @DisplayName("a credential may dispatch as INTEGRATION, naming the credential and no person")
    void aCredentialDispatches() throws SQLException {
        UUID trip = departedTrip("SH-V52-INT", "dispatched_by_client", client, "'INTEGRATION'");
        assertThat(value("SELECT dispatch_source FROM tms.trip WHERE id = '" + trip + "'")).isEqualTo("INTEGRATION");
    }

    @Test
    @DisplayName("the source and the actor must agree: a person never dispatches as INTEGRATION, nor a credential as OPERATOR")
    void sourceAndActorAgree() {
        assertViolates(() -> departedTrip("SH-V52-BAD1", "dispatched_by", person, "'INTEGRATION'"));
        assertViolates(() -> departedTrip("SH-V52-BAD2", "dispatched_by_client", client, "'OPERATOR'"));
        assertViolates(() -> departedTrip("SH-V52-BAD3", "dispatched_by", person, "'SOMEONE'"));
    }

    @Test
    @DisplayName("a departure always names its source: none is refused")
    void aDepartureHasASource() {
        assertViolates(() -> departedTrip("SH-V52-BAD4", "dispatched_by", person, "NULL"));
    }

    @Test
    @DisplayName("the ready step names exactly one actor, a person or a credential")
    void theReadyStepNamesOneActor() throws SQLException {
        UUID trip = departedTrip("SH-V52-READY", "dispatched_by_client", client, "'INTEGRATION'");
        assertViolates(() -> execute("UPDATE tms.trip SET ready_by = '" + person + "' WHERE id = '" + trip + "'"));
        assertViolates(() -> execute("UPDATE tms.trip SET ready_by_client = NULL WHERE id = '" + trip + "'"));
    }

    @Test
    @DisplayName("the override permission is held by the two administrator roles and not by PLANNER or VIEWER")
    void overridePermissionGrants() throws SQLException {
        assertThat(value("SELECT string_agg(r.code, ',' ORDER BY r.code) FROM tms.role_permission rp"
                + " JOIN tms.role r ON r.id = rp.role_id JOIN tms.permission p ON p.id = rp.permission_id"
                + " WHERE p.code = 'planning.trip:dispatch-override'"))
                .isEqualTo("COMPANY_ADMIN,ORGANIZATION_ADMIN");
    }

    @Test
    @DisplayName("the dispatch mode defaults to MANUAL and accepts only the three modes")
    void dispatchMode() throws SQLException {
        execute("INSERT INTO tms.company_settings (company_id) VALUES ('" + company + "') ON CONFLICT DO NOTHING");
        assertThat(value("SELECT dispatch_confirmation_mode FROM tms.company_settings WHERE company_id = '"
                + company + "'")).isEqualTo("MANUAL");
        assertViolates(() -> execute("UPDATE tms.company_settings SET dispatch_confirmation_mode = 'EWM'"
                + " WHERE company_id = '" + company + "'"));
    }

    // --- fixture -------------------------------------------------------------------------

    /** A trip as V25 left a departed one, before V52 existed: dispatched_by only. */
    private static UUID departedTrip(String shipmentNumber, String actorColumn, UUID actor) throws SQLException {
        return insertDepartedTrip(shipmentNumber, actorColumn, actor, null);
    }

    private static UUID departedTrip(String shipmentNumber, String actorColumn, UUID actor, String source)
            throws SQLException {
        return insertDepartedTrip(shipmentNumber, actorColumn, actor, source);
    }

    private static UUID insertDepartedTrip(String shipmentNumber, String actorColumn, UUID actor, String source)
            throws SQLException {
        String suffix = shipmentNumber.replace("SH-", "");
        UUID origin = id("INSERT INTO tms.location (company_id, code, name) VALUES ('" + company + "', 'O-" + suffix
                + "', 'Origin') RETURNING id");
        execute("INSERT INTO tms.location_role (location_id, role) VALUES ('" + origin + "', 'ORIGIN')");
        UUID carrier = id("INSERT INTO tms.carrier (company_id, code, business_name, tax_id_type, tax_id_value)"
                + " VALUES ('" + company + "', 'C-" + suffix + "', 'Carrier', 'RUC', '2010000" + Math.abs(suffix.hashCode() % 10000)
                + "') RETURNING id");
        UUID type = id("INSERT INTO tms.vehicle_type (company_id, code, name, max_weight_kg, max_volume_m3, max_pallets)"
                + " VALUES ('" + company + "', 'T-" + suffix + "', 'Type', 1000, 10, 10) RETURNING id");
        UUID vehicle = id("INSERT INTO tms.vehicle (company_id, code, license_plate, carrier_id, vehicle_type_id)"
                + " VALUES ('" + company + "', 'V-" + suffix + "', 'P-" + suffix + "', '" + carrier + "', '" + type
                + "') RETURNING id");
        UUID run = id("INSERT INTO tms.planning_run (company_id, plan_number, origin_id, planning_date, status,"
                + " confirmed_at) VALUES ('" + company + "', 'PL-" + suffix + "', '" + origin
                + "', DATE '2026-09-01', 'CONFIRMED', now() - interval '3 hours') RETURNING id");
        String readyColumn = "dispatched_by_client".equals(actorColumn) ? "ready_by_client" : "ready_by";
        String sourceColumn = source == null ? "" : ", dispatch_source";
        String sourceValue = source == null ? "" : ", " + source;
        return id("INSERT INTO tms.trip (company_id, planning_run_id, trip_number, planning_date, shipment_number,"
                + " status, vehicle_id, carrier_id, planned_departure_at, snapshot_max_weight_kg,"
                + " snapshot_max_volume_m3, snapshot_max_pallets, capacity_snapshot_at, confirmed_at, ready_at, "
                + readyColumn + ", actual_departure_at, " + actorColumn + sourceColumn + ") VALUES ('" + company
                + "', '" + run + "', 1, DATE '2026-09-01', '" + shipmentNumber + "', 'IN_TRANSIT', '" + vehicle
                + "', '" + carrier + "', now(), 1000, 10, 10, now() - interval '3 hours',"
                + " now() - interval '3 hours', now() - interval '2 hours', '" + actor + "', now() - interval '1 hour', '"
                + actor + "'" + sourceValue + ") RETURNING id");
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
        try (Connection connection = PostgresTestDatabase.connect(jdbcUrl);
                var statement = connection.createStatement();
                var resultSet = statement.executeQuery(sql)) {
            resultSet.next();
            return resultSet.getString(1);
        }
    }

    private static void execute(String sql) throws SQLException {
        try (Connection connection = PostgresTestDatabase.connect(jdbcUrl);
                var statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }
}
