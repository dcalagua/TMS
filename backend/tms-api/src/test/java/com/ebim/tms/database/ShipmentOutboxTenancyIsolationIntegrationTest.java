package com.ebim.tms.database;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;

/**
 * Tenant isolation of the outbound shipment feed (migration V20), asserted against PostgreSQL
 * itself.
 *
 * <p>{@code tms.shipment_outbox_event} deserves its own class because of what consumes it: a
 * partner polling {@code GET /integration/v1/shipments/events} with a machine credential and no
 * human reading the answer. A leak here does not show up as a strange screen - it ships another
 * tenant's confirmed shipment numbers straight into a third party's system, and nobody notices.
 *
 * <p>{@code IntegrationShipmentApiTest} already proves the application never asks for another
 * company's events. This proves the database would refuse if it did, which is the ADR-005 second
 * line of defense, plus the composite foreign key that holds even when RLS is not in play at all.
 *
 * <p>Written as SQL rather than through the repositories on purpose, exactly like
 * {@link IntegrationTenancyIsolationIntegrationTest}: what is under test is the answer to
 * statements the backend would never write.
 */
@EnabledIf(value = DockerAvailability.CONDITION, disabledReason = DockerAvailability.DISABLED_REASON)
class ShipmentOutboxTenancyIsolationIntegrationTest {

    private static final String INSUFFICIENT_PRIVILEGE = "42501";
    private static final String FOREIGN_KEY_VIOLATION = "23503";

    private static final UUID ORGANIZATION = UUID.fromString("00000000-0000-0000-0000-0000000000d0");
    private static final UUID COMPANY_A = UUID.fromString("00000000-0000-0000-0000-0000000000d1");
    private static final UUID COMPANY_B = UUID.fromString("00000000-0000-0000-0000-0000000000d2");
    private static final UUID TRIP_A = UUID.fromString("00000000-0000-0000-0000-0000000000da");
    private static final UUID TRIP_B = UUID.fromString("00000000-0000-0000-0000-0000000000db");

    private static String jdbcUrl;

    private Connection connection;

    @BeforeAll
    static void migrateAndSeed() throws SQLException {
        jdbcUrl = PostgresTestDatabase.createMigratedDatabase("tms_shipment_outbox_tenancy");
        try (Connection owner = PostgresTestDatabase.connect(jdbcUrl);
                Statement statement = owner.createStatement()) {
            statement.execute("""
                    INSERT INTO tms.organization (id, code, name)
                    VALUES ('%s', 'OUTBOX', 'Outbox isolation organization')
                    """.formatted(ORGANIZATION));
            statement.execute("""
                    INSERT INTO tms.company (id, organization_id, code, name) VALUES
                        ('%s', '%s', 'OUTBOX-A', 'Company A'),
                        ('%s', '%s', 'OUTBOX-B', 'Company B')
                    """.formatted(COMPANY_A, ORGANIZATION, COMPANY_B, ORGANIZATION));

            seedShipment(statement, COMPANY_A, TRIP_A, "A");
            seedShipment(statement, COMPANY_B, TRIP_B, "B");
        }
    }

    /**
     * One origin, vehicle, confirmed plan, confirmed trip and its published event, per company.
     *
     * <p>The trip is seeded in the full CONFIRMED shape - vehicle, departure time and frozen
     * capacity snapshot - because {@code ck_trip_confirmed_is_complete} (V11) refuses anything
     * less. That is the point: the fixture is the row the outbox actually publishes, not a
     * simplified stand-in that would let the test pass against a shape production never produces.
     */
    private static void seedShipment(Statement statement, UUID company, UUID trip, String suffix)
            throws SQLException {
        UUID origin = UUID.randomUUID();
        UUID vehicleType = UUID.randomUUID();
        UUID vehicle = UUID.randomUUID();
        UUID run = UUID.randomUUID();
        statement.execute("""
                INSERT INTO tms.location (id, company_id, code, name)
                VALUES ('%s', '%s', 'ORIGIN-%s', 'Origin %s')
                """.formatted(origin, company, suffix, suffix));
        statement.execute("""
                INSERT INTO tms.vehicle_type
                    (id, company_id, code, name, max_weight_kg, max_volume_m3, max_pallets)
                VALUES ('%s', '%s', 'TYPE-%s', 'Type %s', 10000, 40, 20)
                """.formatted(vehicleType, company, suffix, suffix));
        statement.execute("""
                INSERT INTO tms.vehicle (id, company_id, code, license_plate, vehicle_type_id)
                VALUES ('%s', '%s', 'VEH-%s', 'PLT-000%s', '%s')
                """.formatted(vehicle, company, suffix, suffix, vehicleType));
        statement.execute("""
                INSERT INTO tms.planning_run
                    (id, company_id, plan_number, origin_id, planning_date, status, confirmed_at)
                VALUES ('%s', '%s', 'PLAN-%s', '%s', '2026-04-01', 'CONFIRMED', now())
                """.formatted(run, company, suffix, origin));
        statement.execute("""
                INSERT INTO tms.trip
                    (id, company_id, planning_run_id, planning_date, trip_number, shipment_number,
                     vehicle_id, planned_departure_at, status, confirmed_at,
                     snapshot_max_weight_kg, snapshot_max_volume_m3, snapshot_max_pallets,
                     capacity_snapshot_at)
                VALUES ('%s', '%s', '%s', '2026-04-01', 1, 'SH-%s',
                        '%s', '2026-04-01 08:00+00', 'CONFIRMED', now(),
                        10000, 40, 20, now())
                """.formatted(trip, company, run, suffix, vehicle));
        statement.execute("""
                INSERT INTO tms.shipment_outbox_event
                    (company_id, trip_id, shipment_number, event_type)
                VALUES ('%s', '%s', 'SH-%s', 'SHIPMENT_CONFIRMED')
                """.formatted(company, trip, suffix));
    }

    @BeforeEach
    void openTransaction() throws SQLException {
        connection = PostgresTestDatabase.connect(jdbcUrl);
        connection.setAutoCommit(false);
    }

    @AfterEach
    void rollback() throws SQLException {
        connection.rollback();
        connection.close();
    }

    // -----------------------------------------------------------------
    // Row Level Security
    // -----------------------------------------------------------------

    @Test
    @DisplayName("the change feed shows only the scoped company's events")
    void eventsAreFilteredByTenant() throws SQLException {
        actAs(COMPANY_A);
        assertThat(query("SELECT shipment_number FROM tms.shipment_outbox_event ORDER BY 1"))
                .as("a partner of company A polling the feed must never see company B's shipments")
                .containsExactly("SH-A");

        resetRole();
        actAs(COMPANY_B);
        assertThat(query("SELECT shipment_number FROM tms.shipment_outbox_event ORDER BY 1"))
                .containsExactly("SH-B");
    }

    @Test
    @DisplayName("a transaction with no company selected reads no event at all: it fails closed")
    void anUnscopedTransactionReadsNothing() throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute("SET ROLE tms_app");
        }
        assertThat(query("SELECT shipment_number FROM tms.shipment_outbox_event"))
                .as("an unset tms.company_id must deny, never fall back to 'all companies'")
                .isEmpty();
    }

    @Test
    @DisplayName("an event cannot be written into another company: WITH CHECK refuses it")
    void eventsCannotBeWrittenIntoAnotherCompany() throws SQLException {
        actAs(COMPANY_A);
        SQLException refusal = catchThrowableOfType(SQLException.class, () -> {
            try (Statement statement = connection.createStatement()) {
                statement.execute("""
                        INSERT INTO tms.shipment_outbox_event
                            (company_id, trip_id, shipment_number, event_type)
                        VALUES ('%s', '%s', 'SH-B', 'SHIPMENT_CONFIRMED')
                        """.formatted(COMPANY_B, TRIP_B));
            }
        });

        // Cast: SQLException is itself an Iterable<Throwable>, so the assertThat overload
        // would otherwise be ambiguous.
        assertThat((Throwable) refusal)
                .as("a USING-only policy would have allowed this write and merely hidden it")
                .isNotNull();
        assertThat(refusal.getSQLState()).isEqualTo(INSUFFICIENT_PRIVILEGE);
    }

    @Test
    @DisplayName("another company's event cannot be rewritten or deleted without a company predicate")
    void eventsOfAnotherCompanyCannotBeWritten() throws SQLException {
        actAs(COMPANY_A);
        try (Statement statement = connection.createStatement()) {
            int updated = statement.executeUpdate(
                    "UPDATE tms.shipment_outbox_event SET event_type = 'SHIPMENT_CANCELLED'"
                            + " WHERE shipment_number = 'SH-B'");
            assertThat(updated)
                    .as("company B's event is not visible to company A, so it cannot be touched")
                    .isZero();

            int deleted = statement.executeUpdate(
                    "DELETE FROM tms.shipment_outbox_event WHERE shipment_number = 'SH-B'");
            assertThat(deleted)
                    .as("replaying a partner's feed by deleting another tenant's watermark must "
                            + "not be reachable either")
                    .isZero();
        }
    }

    // -----------------------------------------------------------------
    // Structural guarantees, independent of RLS
    // -----------------------------------------------------------------

    @Test
    @DisplayName("an event can never name a trip of a different company")
    void theCompositeForeignKeyForbidsAMismatchedPair() throws SQLException {
        // As the schema owner: RLS is not what is under test here, the composite foreign key is.
        // It is the guarantee that survives even a bug in the tenant plumbing.
        SQLException refusal = catchThrowableOfType(SQLException.class, () -> {
            try (Statement statement = connection.createStatement()) {
                statement.execute("""
                        INSERT INTO tms.shipment_outbox_event
                            (company_id, trip_id, shipment_number, event_type)
                        VALUES ('%s', '%s', 'SH-B', 'SHIPMENT_CONFIRMED')
                        """.formatted(COMPANY_A, TRIP_B));
            }
        });

        assertThat((Throwable) refusal)
                .as("company A's feed must not be able to reference company B's trip - that is "
                        + "exactly how another tenant's shipment would reach a partner")
                .isNotNull();
        assertThat(refusal.getSQLState()).isEqualTo(FOREIGN_KEY_VIOLATION);
    }

    @Test
    @DisplayName("an event naming a trip of its own company is accepted")
    void theCompositeForeignKeyAllowsTheMatchingPair() throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute("""
                    INSERT INTO tms.shipment_outbox_event
                        (company_id, trip_id, shipment_number, event_type)
                    VALUES ('%s', '%s', 'SH-A', 'SHIPMENT_CANCELLED')
                    """.formatted(COMPANY_A, TRIP_A));
        }
        assertThat(query("SELECT event_type FROM tms.shipment_outbox_event"
                + " WHERE company_id = '" + COMPANY_A + "' ORDER BY 1"))
                .as("the composite key must constrain the tenant without blocking ordinary writes")
                .containsExactly("SHIPMENT_CANCELLED", "SHIPMENT_CONFIRMED");
    }

    // --- helpers -----------------------------------------------------------------

    /** Reproduces what {@code TenantScopedDataSource} does for a company-scoped request. */
    private void actAs(UUID companyId) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute("SELECT set_config('tms.company_id', '" + companyId + "', false)");
            statement.execute("SET ROLE tms_app");
        }
    }

    private void resetRole() throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute("RESET ROLE");
        }
    }

    private List<String> query(String sql) throws SQLException {
        List<String> values = new ArrayList<>();
        try (Statement statement = connection.createStatement();
                ResultSet resultSet = statement.executeQuery(sql)) {
            while (resultSet.next()) {
                values.add(resultSet.getString(1));
            }
        }
        return values;
    }
}
