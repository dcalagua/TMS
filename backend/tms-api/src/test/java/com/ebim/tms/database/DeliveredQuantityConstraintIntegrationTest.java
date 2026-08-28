package com.ebim.tms.database;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.UUID;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.postgresql.util.PSQLException;

/**
 * What the database itself refuses about delivered quantities (migration V45, closing debt D3).
 *
 * <p>The service refuses these first, with sentences a dispatcher can act on, and
 * {@code DeliveryQuantitiesTest} refuses them in the value object. This is the third layer - the one
 * that holds when a raw data fix, a restore or a future writer reaches the table another way.
 *
 * <p>The rule that matters most is {@link Absence}: a delivery with <b>no</b> quantities must stay
 * legal forever. Every row written before V45 has none, and a constraint that required them would
 * have made this migration unapplyable against real data - or, worse, invited a back-fill of zeros
 * that would assert nothing was ever delivered.
 */
@EnabledIf(value = DockerAvailability.CONDITION, disabledReason = DockerAvailability.DISABLED_REASON)
class DeliveredQuantityConstraintIntegrationTest {

    private static final String CHECK_VIOLATION = "23514";
    private static final String FOREIGN_KEY_VIOLATION = "23503";

    private static String jdbcUrl;
    private Connection connection;

    @BeforeAll
    static void migrate() {
        jdbcUrl = PostgresTestDatabase.createMigratedDatabase("tms_delivered_quantity");
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

    // --- the fixture: one order on one stop of one trip ---------------------------------

    /** Everything a delivery row needs to exist, built once per test and rolled back after. */
    private record Fixture(UUID companyId, UUID tripId, UUID stopId, UUID orderId, UUID orderLineId,
            UUID actorId) {
    }

    private Fixture fixture(String prefix) throws SQLException {
        UUID organization = insertReturningId("INSERT INTO tms.organization (code, name) VALUES ('ORG-"
                + prefix + "', 'Org') RETURNING id");
        UUID company = insertReturningId("INSERT INTO tms.company (organization_id, code, name) VALUES ('"
                + organization + "', 'CO-" + prefix + "', 'Company') RETURNING id");
        UUID origin = insertReturningId("INSERT INTO tms.location (company_id, code, name) VALUES ('" + company
                + "', 'ORIGIN-" + prefix + "', 'Origin') RETURNING id");
        UUID destination = insertReturningId("INSERT INTO tms.location (company_id, code, name, country)"
                + " VALUES ('" + company + "', 'DEST-" + prefix + "', 'Destination', 'PE') RETURNING id");
        UUID run = insertReturningId("INSERT INTO tms.planning_run"
                + " (company_id, plan_number, origin_id, planning_date, status) VALUES ('" + company
                + "', 'PL-" + prefix + "', '" + origin + "', '2026-04-01', 'DRAFT') RETURNING id");
        UUID trip = insertReturningId("INSERT INTO tms.trip (company_id, planning_run_id, planning_date,"
                + " trip_number) VALUES ('" + company + "', '" + run + "', '2026-04-01', 1) RETURNING id");
        UUID stop = insertReturningId("INSERT INTO tms.trip_stop (trip_id, company_id, destination_id,"
                + " sequence) VALUES ('" + trip + "', '" + company + "', '" + destination + "', 1) RETURNING id");
        UUID order = insertReturningId("INSERT INTO tms.transport_order (company_id, order_number, origin_id,"
                + " destination_id, service_date) VALUES ('" + company + "', 'TO-" + prefix + "', '" + origin
                + "', '" + destination + "', '2026-04-01') RETURNING id");
        UUID line = insertReturningId("INSERT INTO tms.transport_order_line (order_id, line_number,"
                + " material_code, material_description, quantity, uom) VALUES ('" + order
                + "', 1, 'MAT-1', 'Material one', 100, 'BOX') RETURNING id");
        // ck_order_delivery_actor_xor and ck_order_delivery_operator_is_person: an OPERATOR
        // delivery names a person. The fixture has to be as legal as a real one.
        UUID actor = insertReturningId("INSERT INTO tms.app_user (auth_user_id, email, full_name, active)"
                + " VALUES (gen_random_uuid(), '" + prefix.toLowerCase(java.util.Locale.ROOT)
                + "@example.invalid', 'Delivery Clerk', true) RETURNING id");
        return new Fixture(company, trip, stop, order, line, actor);
    }

    /** A delivery row with an explicit quantity block, or with none when {@code quantities} is null. */
    private UUID insertDelivery(Fixture fixture, String result, String quantities) throws SQLException {
        // ck_order_delivery_shortfall_requires_notes (V28): PARTIAL, REJECTED and FAILED must say
        // why. The fixture obeys every existing rule, not only the ones V45 added.
        String columns = "company_id, trip_id, trip_stop_id, order_id, result, source, delivered_at,"
                + " actor_app_user_id, notes";
        String values = "'" + fixture.companyId + "', '" + fixture.tripId + "', '" + fixture.stopId + "', '"
                + fixture.orderId + "', '" + result + "', 'OPERATOR', now(), '" + fixture.actorId
                + "', 'Recorded by the delivered-quantity constraint fixture.'";
        if (quantities != null) {
            columns += ", attempted_weight_kg, attempted_volume_m3, attempted_pallets,"
                    + " delivered_weight_kg, delivered_volume_m3, delivered_pallets,"
                    + " refused_weight_kg, refused_volume_m3, refused_pallets";
            values += ", " + quantities;
        }
        return insertReturningId("INSERT INTO tms.order_delivery (" + columns + ") VALUES ("
                + values + ") RETURNING id");
    }

    // --- absence stays legal ------------------------------------------------------------

    @Nested
    @DisplayName("a delivery with no quantities")
    class Absence {

        /**
         * The backward-compatibility rule the whole migration rests on. Every delivery written
         * before V45 has no amounts, and must keep meaning what it meant: an outcome, and no claim.
         */
        @Test
        @DisplayName("is still legal, because every delivery written before V45 is one")
        void isStillLegal() throws SQLException {
            Fixture fixture = fixture("DQ-ABSENT");

            UUID delivery = insertDelivery(fixture, "DELIVERED", null);

            assertThat(scalar("SELECT attempted_weight_kg FROM tms.order_delivery WHERE id = '"
                    + delivery + "'")).isNull();
        }

        @Test
        @DisplayName("half a block is refused: 800 delivered of what?")
        void halfABlockIsRefused() throws SQLException {
            Fixture fixture = fixture("DQ-HALF");

            // attempted weight present, delivered and refused weight absent.
            assertViolates(CHECK_VIOLATION, () -> execute("INSERT INTO tms.order_delivery"
                    + " (company_id, trip_id, trip_stop_id, order_id, result, source, delivered_at,"
                    + " actor_app_user_id, attempted_weight_kg) VALUES ('" + fixture.companyId + "', '"
                    + fixture.tripId + "', '" + fixture.stopId + "', '" + fixture.orderId
                    + "', 'DELIVERED', 'OPERATOR', now(), '" + fixture.actorId + "', 100)"));
        }
    }

    // --- the invariant ------------------------------------------------------------------

    @Nested
    @DisplayName("nothing can be delivered beyond what was attempted")
    class TheInvariant {

        @Test
        @DisplayName("delivered plus refused above attempted is refused")
        void overDeliveryIsRefused() throws SQLException {
            Fixture fixture = fixture("DQ-OVER");

            assertViolates(CHECK_VIOLATION, () -> insertDelivery(fixture, "PARTIAL",
                    "100, 10, 5,  70, 7, 3.5,  40, 4, 2"));
        }

        @Test
        @DisplayName("delivered plus refused equal to attempted is accepted")
        void exactIsAccepted() throws SQLException {
            Fixture fixture = fixture("DQ-EXACT");

            UUID delivery = insertDelivery(fixture, "PARTIAL", "100, 10, 5,  70, 7, 3.5,  30, 3, 1.5");

            assertThat(scalar("SELECT delivered_weight_kg FROM tms.order_delivery WHERE id = '"
                    + delivery + "'")).isEqualTo("70.000");
        }

        /**
         * Deliberately {@code <=}. Goods carried back to the depot are neither delivered nor
         * refused, and forbidding that would forbid a real operational state.
         */
        @Test
        @DisplayName("goods can come back without anybody refusing them")
        void outstandingIsAllowed() throws SQLException {
            Fixture fixture = fixture("DQ-OUTSTANDING");

            UUID delivery = insertDelivery(fixture, "PARTIAL", "100, 10, 5,  70, 7, 3.5,  0, 0, 0");

            assertThat(scalar("SELECT refused_weight_kg FROM tms.order_delivery WHERE id = '"
                    + delivery + "'")).isEqualTo("0.000");
        }

        @Test
        @DisplayName("negative amounts are refused")
        void negativeIsRefused() throws SQLException {
            Fixture fixture = fixture("DQ-NEGATIVE");

            assertViolates(CHECK_VIOLATION, () -> insertDelivery(fixture, "PARTIAL",
                    "100, 10, 5,  -1, 0, 0,  0, 0, 0"));
        }

        /** Goods that never came off the vehicle cannot have been handed over. */
        @Test
        @DisplayName("NOT_ATTEMPTED cannot deliver anything")
        void notAttemptedDeliversNothing() throws SQLException {
            Fixture fixture = fixture("DQ-NOTATT");

            // NOT_ATTEMPTED also forbids a delivery time (V28), so the row is built by hand here.
            assertViolates(CHECK_VIOLATION, () -> execute("INSERT INTO tms.order_delivery"
                    + " (company_id, trip_id, trip_stop_id, order_id, result, source, actor_app_user_id,"
                    + " notes, attempted_weight_kg, attempted_volume_m3, attempted_pallets,"
                    + " delivered_weight_kg, delivered_volume_m3, delivered_pallets,"
                    + " refused_weight_kg, refused_volume_m3, refused_pallets) VALUES ('"
                    + fixture.companyId + "', '" + fixture.tripId + "', '" + fixture.stopId + "', '"
                    + fixture.orderId + "', 'NOT_ATTEMPTED', 'OPERATOR', '" + fixture.actorId
                    + "', 'Never taken off the vehicle.', 100, 10, 5, 10, 1, 0.5, 0, 0, 0)"));
        }
    }

    // --- per line -----------------------------------------------------------------------

    @Nested
    @DisplayName("the per-line result")
    class Lines {

        @Test
        @DisplayName("records a quantity in the line's own unit")
        void recordsInTheLinesUnit() throws SQLException {
            Fixture fixture = fixture("DQL-OK");
            UUID delivery = insertDelivery(fixture, "PARTIAL", "100, 10, 5,  70, 7, 3.5,  30, 3, 1.5");

            execute("INSERT INTO tms.order_delivery_line (company_id, order_delivery_id, order_id,"
                    + " order_line_id, uom, quantity_attempted, quantity_delivered, quantity_refused)"
                    + " VALUES ('" + fixture.companyId + "', '" + delivery + "', '" + fixture.orderId
                    + "', '" + fixture.orderLineId + "', 'BOX', 100, 70, 30)");

            assertThat(scalar("SELECT uom FROM tms.order_delivery_line WHERE order_delivery_id = '"
                    + delivery + "'")).isEqualTo("BOX");
        }

        @Test
        @DisplayName("cannot deliver more of a line than was attempted")
        void cannotOverDeliverALine() throws SQLException {
            Fixture fixture = fixture("DQL-OVER");
            UUID delivery = insertDelivery(fixture, "PARTIAL", "100, 10, 5,  70, 7, 3.5,  30, 3, 1.5");

            assertViolates(CHECK_VIOLATION, () -> execute("INSERT INTO tms.order_delivery_line"
                    + " (company_id, order_delivery_id, order_id, order_line_id, uom,"
                    + " quantity_attempted, quantity_delivered, quantity_refused) VALUES ('"
                    + fixture.companyId + "', '" + delivery + "', '" + fixture.orderId + "', '"
                    + fixture.orderLineId + "', 'BOX', 100, 80, 30)"));
        }

        /**
         * The composite foreign key added by V45. Without it a delivery could record a quantity
         * against another order's line - same company, wrong goods - and nothing would notice.
         */
        @Test
        @DisplayName("a line of a different order cannot be attached to this delivery")
        void lineMustBelongToTheOrder() throws SQLException {
            Fixture fixture = fixture("DQL-WRONGORDER");
            Fixture other = fixture("DQL-OTHERORDER");
            UUID delivery = insertDelivery(fixture, "PARTIAL", "100, 10, 5,  70, 7, 3.5,  30, 3, 1.5");

            assertViolates(FOREIGN_KEY_VIOLATION, () -> execute("INSERT INTO tms.order_delivery_line"
                    + " (company_id, order_delivery_id, order_id, order_line_id, uom,"
                    + " quantity_attempted, quantity_delivered, quantity_refused) VALUES ('"
                    + fixture.companyId + "', '" + delivery + "', '" + fixture.orderId + "', '"
                    + other.orderLineId + "', 'BOX', 10, 10, 0)"));
        }

        /** Tenancy, at the grain V45 introduced. */
        @Test
        @DisplayName("a line result cannot belong to a different company than its delivery")
        void isTenantScoped() throws SQLException {
            Fixture fixture = fixture("DQL-TENANT-A");
            Fixture other = fixture("DQL-TENANT-B");
            UUID delivery = insertDelivery(fixture, "PARTIAL", "100, 10, 5,  70, 7, 3.5,  30, 3, 1.5");

            assertViolates(FOREIGN_KEY_VIOLATION, () -> execute("INSERT INTO tms.order_delivery_line"
                    + " (company_id, order_delivery_id, order_id, order_line_id, uom,"
                    + " quantity_attempted, quantity_delivered, quantity_refused) VALUES ('"
                    + other.companyId + "', '" + delivery + "', '" + fixture.orderId + "', '"
                    + fixture.orderLineId + "', 'BOX', 10, 10, 0)"));
        }

        @Test
        @DisplayName("one result per line per delivery - a correction overwrites, never appends")
        void oneResultPerLine() throws SQLException {
            Fixture fixture = fixture("DQL-DUP");
            UUID delivery = insertDelivery(fixture, "PARTIAL", "100, 10, 5,  70, 7, 3.5,  30, 3, 1.5");
            String insert = "INSERT INTO tms.order_delivery_line (company_id, order_delivery_id, order_id,"
                    + " order_line_id, uom, quantity_attempted, quantity_delivered, quantity_refused)"
                    + " VALUES ('" + fixture.companyId + "', '" + delivery + "', '" + fixture.orderId
                    + "', '" + fixture.orderLineId + "', 'BOX', 100, 70, 30)";
            execute(insert);

            assertViolates("23505", () -> execute(insert));
        }
    }

    // --- plumbing -----------------------------------------------------------------------

    private UUID insertReturningId(String sql) throws SQLException {
        try (Statement statement = connection.createStatement();
                ResultSet rows = statement.executeQuery(sql)) {
            rows.next();
            return UUID.fromString(rows.getString(1));
        }
    }

    private void execute(String sql) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }

    private String scalar(String sql) throws SQLException {
        try (Statement statement = connection.createStatement();
                ResultSet rows = statement.executeQuery(sql)) {
            rows.next();
            return rows.getString(1);
        }
    }

    /**
     * Asserts the SQLSTATE rather than the message, and rolls the failed statement back so the
     * connection stays usable for the assertion that follows.
     */
    private void assertViolates(String sqlState, ThrowingCallable statement) {
        assertThatThrownBy(statement)
                .isInstanceOf(PSQLException.class)
                .satisfies(thrown -> assertThat(((PSQLException) thrown).getSQLState()).isEqualTo(sqlState));
        try {
            connection.rollback();
            connection.setAutoCommit(false);
        } catch (SQLException failed) {
            throw new IllegalStateException("could not reset the probe transaction", failed);
        }
    }
}
