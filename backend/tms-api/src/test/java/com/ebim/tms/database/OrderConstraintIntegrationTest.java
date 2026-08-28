package com.ebim.tms.database;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Savepoint;
import java.sql.Statement;
import java.util.UUID;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;

/**
 * Proves the V10 order constraints - and V17's declared-totals and import-batch additions - hold
 * at the database level, independent of the Java validation in {@code OrderService}: the same
 * defense-in-depth proof {@link MasterDataRouteConstraintIntegrationTest} gives V8's tables,
 * extended to orders.
 */
@EnabledIf(value = DockerAvailability.CONDITION, disabledReason = DockerAvailability.DISABLED_REASON)
class OrderConstraintIntegrationTest {

    private static final String UNIQUE_VIOLATION = "23505";
    private static final String CHECK_VIOLATION = "23514";
    private static final String FOREIGN_KEY_VIOLATION = "23503";
    private static final String NOT_NULL_VIOLATION = "23502";

    private static String jdbcUrl;

    private Connection connection;

    @BeforeAll
    static void migrate() {
        jdbcUrl = PostgresTestDatabase.createMigratedDatabase("tms_order_constraints");
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

    @Test
    @DisplayName("order_number is unique installation-wide, unlike every company-scoped code")
    void orderNumberIsGloballyUnique() throws SQLException {
        UUID organization = insertOrganization("ORD-ORG");
        UUID companyA = insertCompany(organization, "ORD-A");
        UUID companyB = insertCompany(organization, "ORD-B");
        UUID originA = insertOrigin(companyA, "ORIGIN-A");
        UUID destinationA = insertDestination(companyA, "DEST-A");
        UUID originB = insertOrigin(companyB, "ORIGIN-B");
        UUID destinationB = insertDestination(companyB, "DEST-B");

        insertOrder(companyA, "TO-00000001", originA, destinationA);
        assertViolates(UNIQUE_VIOLATION, () -> insertOrder(companyB, "TO-00000001", originB, destinationB));
    }

    @Test
    @DisplayName("the external reference pair is unique per company, and free to repeat across companies")
    void externalReferenceIsScopedToItsCompany() throws SQLException {
        UUID organization = insertOrganization("ORD-ORG");
        UUID companyA = insertCompany(organization, "ORD-A");
        UUID companyB = insertCompany(organization, "ORD-B");
        UUID originA = insertOrigin(companyA, "ORIGIN-A");
        UUID destinationA = insertDestination(companyA, "DEST-A");
        UUID originB = insertOrigin(companyB, "ORIGIN-B");
        UUID destinationB = insertDestination(companyB, "DEST-B");

        insertOrderWithExternalReference(companyA, "TO-00000001", originA, destinationA, "ERP", "EXT-1");
        insertOrderWithExternalReference(companyB, "TO-00000002", originB, destinationB, "ERP", "EXT-1"); // allowed
        assertViolates(UNIQUE_VIOLATION,
                () -> insertOrderWithExternalReference(companyA, "TO-00000003", originA, destinationA, "ERP", "EXT-1"));
    }

    @Test
    @DisplayName("two orders may share the same external reference when their source differs")
    void externalReferenceIsScopedToItsSource() throws SQLException {
        UUID organization = insertOrganization("ORD-ORG");
        UUID company = insertCompany(organization, "ORD-A");
        UUID origin = insertOrigin(company, "ORIGIN-A");
        UUID destination = insertDestination(company, "DEST-A");

        insertOrderWithExternalReference(company, "TO-00000001", origin, destination, "ERP", "EXT-1");
        insertOrderWithExternalReference(company, "TO-00000002", origin, destination, "EDI", "EXT-1"); // allowed
    }

    @Test
    @DisplayName("an external reference without a source is rejected: the idempotency pair must be complete")
    void externalReferenceRequiresASource() throws SQLException {
        UUID organization = insertOrganization("ORD-ORG");
        UUID company = insertCompany(organization, "ORD-A");
        UUID origin = insertOrigin(company, "ORIGIN-A");
        UUID destination = insertDestination(company, "DEST-A");

        assertViolates(CHECK_VIOLATION, () -> execute("INSERT INTO tms.transport_order"
                + " (company_id, order_number, external_reference, origin_id, destination_id, service_date)"
                + " VALUES ('" + company + "', 'TO-00000001', 'EXT-1', '" + origin + "', '" + destination
                + "', '2026-01-01')"));
    }

    @Test
    @DisplayName("an order's origin and destination must belong to its own company, even though the FK columns are separate")
    void originAndDestinationMustBelongToTheSameCompany() throws SQLException {
        UUID organization = insertOrganization("ORD-ORG");
        UUID companyA = insertCompany(organization, "ORD-A");
        UUID companyB = insertCompany(organization, "ORD-B");
        UUID originA = insertOrigin(companyA, "ORIGIN-A");
        UUID destinationA = insertDestination(companyA, "DEST-A");
        UUID originB = insertOrigin(companyB, "ORIGIN-B");
        UUID destinationB = insertDestination(companyB, "DEST-B");

        assertViolates(FOREIGN_KEY_VIOLATION, () -> insertOrder(companyA, "TO-00000001", originB, destinationA));
        assertViolates(FOREIGN_KEY_VIOLATION, () -> insertOrder(companyA, "TO-00000002", originA, destinationB));
    }

    @Test
    @DisplayName("priority is restricted to its catalogue")
    void priorityIsRestricted() throws SQLException {
        UUID organization = insertOrganization("ORD-ORG");
        UUID company = insertCompany(organization, "ORD-A");
        UUID origin = insertOrigin(company, "ORIGIN-A");
        UUID destination = insertDestination(company, "DEST-A");

        assertViolates(CHECK_VIOLATION, () -> execute("INSERT INTO tms.transport_order"
                + " (company_id, order_number, origin_id, destination_id, service_date, priority)"
                + " VALUES ('" + company + "', 'TO-00000001', '" + origin + "', '" + destination
                + "', '2026-01-01', 'CRITICAL')"));
    }

    @Test
    @DisplayName("status is restricted to its catalogue, which V36 widened to the execution states")
    void statusIsRestricted() throws SQLException {
        UUID organization = insertOrganization("ORD-ORG");
        UUID company = insertCompany(organization, "ORD-A");
        UUID origin = insertOrigin(company, "ORIGIN-A");
        UUID destination = insertDestination(company, "DEST-A");

        // Every one of the eight is accepted. Asserted rather than assumed: this test used to use
        // 'DELIVERED' as its example of a value outside the catalogue, and the day that became a
        // real state was the day the assertion silently stopped meaning anything.
        String[] catalogue = {"NOT_READY", "READY_FOR_PLANNING", "PLANNED", "IN_EXECUTION",
                "DELIVERED", "PARTIALLY_DELIVERED", "DELIVERY_FAILED", "CANCELLED"};
        int sequence = 1;
        for (String status : catalogue) {
            String orderNumber = String.format("TO-%08d", sequence++);
            execute("INSERT INTO tms.transport_order"
                    + " (company_id, order_number, origin_id, destination_id, service_date, status)"
                    + " VALUES ('" + company + "', '" + orderNumber + "', '" + origin + "', '" + destination
                    + "', '2026-01-01', '" + status + "')");
        }

        // And something genuinely outside it is still refused.
        assertViolates(CHECK_VIOLATION, () -> execute("INSERT INTO tms.transport_order"
                + " (company_id, order_number, origin_id, destination_id, service_date, status)"
                + " VALUES ('" + company + "', 'TO-00000099', '" + origin + "', '" + destination
                + "', '2026-01-01', 'DISPATCHED')"));
    }

    @Test
    @DisplayName("a requested time window must be both present or both absent, and start before end")
    void timeWindowIsValidated() throws SQLException {
        UUID organization = insertOrganization("ORD-ORG");
        UUID company = insertCompany(organization, "ORD-A");
        UUID origin = insertOrigin(company, "ORIGIN-A");
        UUID destination = insertDestination(company, "DEST-A");

        assertViolates(CHECK_VIOLATION, () -> execute("INSERT INTO tms.transport_order"
                + " (company_id, order_number, origin_id, destination_id, service_date, requested_window_start)"
                + " VALUES ('" + company + "', 'TO-00000001', '" + origin + "', '" + destination
                + "', '2026-01-01', '08:00')"));
        assertViolates(CHECK_VIOLATION, () -> execute("INSERT INTO tms.transport_order"
                + " (company_id, order_number, origin_id, destination_id, service_date,"
                + " requested_window_start, requested_window_end)"
                + " VALUES ('" + company + "', 'TO-00000002', '" + origin + "', '" + destination
                + "', '2026-01-01', '12:00', '08:00')"));
    }

    @Test
    @DisplayName("a cancel reason may only be set on a cancelled order")
    void cancelReasonRequiresCancelledStatus() throws SQLException {
        UUID organization = insertOrganization("ORD-ORG");
        UUID company = insertCompany(organization, "ORD-A");
        UUID origin = insertOrigin(company, "ORIGIN-A");
        UUID destination = insertDestination(company, "DEST-A");

        assertViolates(CHECK_VIOLATION, () -> execute("INSERT INTO tms.transport_order"
                + " (company_id, order_number, origin_id, destination_id, service_date, cancel_reason)"
                + " VALUES ('" + company + "', 'TO-00000001', '" + origin + "', '" + destination
                + "', '2026-01-01', 'customer request')"));
    }

    @Test
    @DisplayName("totals cannot be negative")
    void totalsMustBeNonnegative() throws SQLException {
        UUID organization = insertOrganization("ORD-ORG");
        UUID company = insertCompany(organization, "ORD-A");
        UUID origin = insertOrigin(company, "ORIGIN-A");
        UUID destination = insertDestination(company, "DEST-A");

        assertViolates(CHECK_VIOLATION, () -> execute("INSERT INTO tms.transport_order"
                + " (company_id, order_number, origin_id, destination_id, service_date, total_weight_kg)"
                + " VALUES ('" + company + "', 'TO-00000001', '" + origin + "', '" + destination
                + "', '2026-01-01', -1)"));
    }

    @Test
    @DisplayName("a line's quantity must be strictly positive")
    void lineQuantityMustBePositive() throws SQLException {
        UUID organization = insertOrganization("ORD-ORG");
        UUID company = insertCompany(organization, "ORD-A");
        UUID origin = insertOrigin(company, "ORIGIN-A");
        UUID destination = insertDestination(company, "DEST-A");
        UUID order = insertOrder(company, "TO-00000001", origin, destination);

        assertViolates(CHECK_VIOLATION, () -> execute("INSERT INTO tms.transport_order_line"
                + " (order_id, line_number, material_code, material_description, quantity, uom)"
                + " VALUES ('" + order + "', 1, 'SKU-1', 'Widget', 0, 'EA')"));
    }

    @Test
    @DisplayName("a line's uom must already be normalized: upper case, trimmed, not blank")
    void lineUomMustBeNormalized() throws SQLException {
        UUID organization = insertOrganization("ORD-ORG");
        UUID company = insertCompany(organization, "ORD-A");
        UUID origin = insertOrigin(company, "ORIGIN-A");
        UUID destination = insertDestination(company, "DEST-A");
        UUID order = insertOrder(company, "TO-00000001", origin, destination);

        assertViolates(CHECK_VIOLATION, () -> execute("INSERT INTO tms.transport_order_line"
                + " (order_id, line_number, material_code, material_description, quantity, uom)"
                + " VALUES ('" + order + "', 1, 'SKU-1', 'Widget', 1, 'ea')"));
    }

    @Test
    @DisplayName("a line's optional unit weight/volume must be positive when present")
    void lineUnitWeightAndVolumeMustBePositiveWhenPresent() throws SQLException {
        UUID organization = insertOrganization("ORD-ORG");
        UUID company = insertCompany(organization, "ORD-A");
        UUID origin = insertOrigin(company, "ORIGIN-A");
        UUID destination = insertDestination(company, "DEST-A");
        UUID order = insertOrder(company, "TO-00000001", origin, destination);

        assertViolates(CHECK_VIOLATION, () -> execute("INSERT INTO tms.transport_order_line"
                + " (order_id, line_number, material_code, material_description, quantity, uom, unit_weight_kg)"
                + " VALUES ('" + order + "', 1, 'SKU-1', 'Widget', 1, 'EA', 0)"));
        assertViolates(CHECK_VIOLATION, () -> execute("INSERT INTO tms.transport_order_line"
                + " (order_id, line_number, material_code, material_description, quantity, uom, unit_volume_m3)"
                + " VALUES ('" + order + "', 2, 'SKU-2', 'Gadget', 1, 'EA', -1)"));
    }

    @Test
    @DisplayName("a line number must be positive")
    void lineNumberMustBePositive() throws SQLException {
        UUID organization = insertOrganization("ORD-ORG");
        UUID company = insertCompany(organization, "ORD-A");
        UUID origin = insertOrigin(company, "ORIGIN-A");
        UUID destination = insertDestination(company, "DEST-A");
        UUID order = insertOrder(company, "TO-00000001", origin, destination);

        assertViolates(CHECK_VIOLATION, () -> execute("INSERT INTO tms.transport_order_line"
                + " (order_id, line_number, material_code, material_description, quantity, uom)"
                + " VALUES ('" + order + "', 0, 'SKU-1', 'Widget', 1, 'EA')"));
    }

    @Test
    @DisplayName("a genuine duplicate line number still fails, just at commit instead of at the statement")
    void duplicateLineNumberFailsAtCommit() throws SQLException {
        // uq_transport_order_line_order_line_number is DEFERRABLE INITIALLY DEFERRED (see the V10
        // migration comment): TransportOrder.applyLines deletes and re-creates its whole line set
        // on every update, which transiently duplicates a line number within one flush - so a
        // genuine, never-resolved duplicate only fails at COMMIT, the same proof
        // MasterDataRouteConstraintIntegrationTest gives uq_route_stop_route_sequence.
        // order_number is globally unique (12.1) and this test commits for real, so it uses a
        // dedicated number - reusing "TO-00000001" like every rolled-back test in this class
        // would leak a permanent collision into whichever test happens to run afterwards.
        UUID organization = insertOrganization("ORD-ORG-DUPLINE");
        UUID company = insertCompany(organization, "ORD-DUPLINE");
        UUID origin = insertOrigin(company, "ORIGIN-A");
        UUID destination = insertDestination(company, "DEST-A");
        UUID order = insertOrder(company, "TO-DUPLINE-0001", origin, destination);

        insertLine(order, 1, "SKU-1");
        insertLine(order, 1, "SKU-2"); // does not throw yet: the check is deferred to commit

        Throwable thrown = catchThrowable(() -> connection.commit());
        assertThat(thrown).as("commit was expected to fail: two lines never resolved to distinct line numbers").isNotNull();
        assertThat(thrown).isInstanceOf(SQLException.class);
        assertThat(((SQLException) thrown).getSQLState()).isEqualTo(UNIQUE_VIOLATION);
    }

    @Test
    @DisplayName("replacing a line set in place (delete old, insert new) survives commit despite the transient duplicate")
    void replacingLineNumbersInPlaceSurvivesCommit() throws SQLException {
        // Mirrors what TransportOrder.applyLines actually does on update: insert the new rows
        // before deleting the old ones (Hibernate's flush order), which momentarily duplicates
        // line_number 1. See MasterDataRouteConstraintIntegrationTest.reorderingStopsInPlaceSurvivesCommit
        // for the same proof against uq_route_stop_route_sequence.
        // Same reasoning as duplicateLineNumberFailsAtCommit above: a dedicated order_number
        // because this test commits for real.
        UUID organization = insertOrganization("ORD-ORG-REPLACELINE");
        UUID company = insertCompany(organization, "ORD-REPLACELINE");
        UUID origin = insertOrigin(company, "ORIGIN-A");
        UUID destination = insertDestination(company, "DEST-A");
        UUID order = insertOrder(company, "TO-REPLACELINE-0001", origin, destination);
        UUID oldLine = insertLine(order, 1, "SKU-OLD");

        UUID newLine = insertReturningId("INSERT INTO tms.transport_order_line"
                + " (order_id, line_number, material_code, material_description, quantity, uom) VALUES ('" + order
                + "', 1, 'SKU-NEW', 'Line description', 1, 'EA') RETURNING id"); // transient duplicate of line_number 1
        execute("DELETE FROM tms.transport_order_line WHERE id = '" + oldLine + "'");

        connection.commit();

        assertThat(count("SELECT count(*) FROM tms.transport_order_line WHERE id = '" + newLine
                + "' AND line_number = 1")).isOne();
        assertThat(count("SELECT count(*) FROM tms.transport_order_line WHERE id = '" + oldLine + "'")).isZero();
    }

    @Test
    @DisplayName("order lines are deleted when their order is deleted (cascade, no orphans)")
    void linesCascadeFromOrder() throws SQLException {
        UUID organization = insertOrganization("ORD-ORG");
        UUID company = insertCompany(organization, "ORD-A");
        UUID origin = insertOrigin(company, "ORIGIN-A");
        UUID destination = insertDestination(company, "DEST-A");
        UUID order = insertOrder(company, "TO-00000001", origin, destination);
        insertLine(order, 1, "SKU-1");

        execute("DELETE FROM tms.transport_order WHERE id = '" + order + "'");

        assertThat(count("SELECT count(*) FROM tms.transport_order_line WHERE order_id = '" + order + "'")).isZero();
    }

    @Test
    @DisplayName("orders default to NOT_READY, NORMAL priority, zero totals and version zero, and record who changed them")
    void defaultsAndActorColumns() throws SQLException {
        UUID organization = insertOrganization("ORD-ORG");
        UUID company = insertCompany(organization, "ORD-A");
        UUID origin = insertOrigin(company, "ORIGIN-A");
        UUID destination = insertDestination(company, "DEST-A");
        UUID actor = insertAppUser("ord.actor@example.test");

        UUID orderId = insertOrder(company, "TO-00000001", origin, destination);
        assertThat(count("SELECT count(*) FROM tms.transport_order WHERE id = '" + orderId
                + "' AND status = 'NOT_READY' AND priority = 'NORMAL' AND version = 0"
                + " AND total_weight_kg = 0 AND total_volume_m3 = 0 AND total_pallets = 0")).isOne();

        execute("UPDATE tms.transport_order SET updated_by = '" + actor + "' WHERE id = '" + orderId + "'");
        assertThat(count("SELECT count(*) FROM tms.transport_order WHERE id = '" + orderId
                + "' AND updated_by = '" + actor + "'")).isOne();

        assertViolates(FOREIGN_KEY_VIOLATION, () -> execute("UPDATE tms.transport_order SET updated_by = '"
                + UUID.randomUUID() + "' WHERE id = '" + orderId + "'"));
    }

    @Test
    @DisplayName("an order requires a real company, origin and destination")
    void orderRequiresRealReferences() throws SQLException {
        assertViolates(NOT_NULL_VIOLATION, () -> execute("INSERT INTO tms.transport_order"
                + " (order_number, origin_id, destination_id, service_date)"
                + " VALUES ('TO-00000001', '" + UUID.randomUUID() + "', '" + UUID.randomUUID() + "', '2026-01-01')"));
    }

    // --- helpers -----------------------------------------------------------------

    // --- V17: declared totals -------------------------------------------------------

    @Test
    @DisplayName("a declared figure may be zero but never negative")
    void declaredFiguresAreNonNegative() throws SQLException {
        UUID organization = insertOrganization("ORD-ORG");
        UUID company = insertCompany(organization, "ORD-A");
        UUID origin = insertOrigin(company, "ORIGIN-A");
        UUID destination = insertDestination(company, "DEST-A");

        // Zero is a statement ("this order has no pallets"), which is why it is allowed while
        // NULL means something different again ("nothing was said about pallets").
        execute("INSERT INTO tms.transport_order (company_id, order_number, origin_id, destination_id,"
                + " service_date, declared_pallets) VALUES ('" + company + "', 'TO-DEC-0001', '" + origin + "', '"
                + destination + "', '2026-01-01', 0)");

        assertViolates(CHECK_VIOLATION, () -> execute("INSERT INTO tms.transport_order (company_id, order_number,"
                + " origin_id, destination_id, service_date, declared_weight_kg) VALUES ('" + company
                + "', 'TO-DEC-0002', '" + origin + "', '" + destination + "', '2026-01-01', -1)"));
    }

    @Test
    @DisplayName("totals_source accepts only the two strategies, and defaults to DECLARED")
    void totalsSourceIsConstrained() throws SQLException {
        UUID organization = insertOrganization("ORD-ORG");
        UUID company = insertCompany(organization, "ORD-A");
        UUID origin = insertOrigin(company, "ORIGIN-A");
        UUID destination = insertDestination(company, "DEST-A");

        UUID orderId = insertOrder(company, "TO-SRC-0001", origin, destination);
        // A raw insert - a fixture, a future data migration - gets DECLARED, which is correct for
        // the header-with-no-lines row such an insert almost always creates. See V17.
        assertThat(queryText("SELECT totals_source FROM tms.transport_order WHERE id = '" + orderId + "'"))
                .isEqualTo("DECLARED");

        assertViolates(CHECK_VIOLATION, () -> execute("UPDATE tms.transport_order SET totals_source = 'GUESSED'"
                + " WHERE id = '" + orderId + "'"));
    }

    // --- V17: the import batch audit row ----------------------------------------------

    @Test
    @DisplayName("an import batch is company-scoped, format-checked and carries a real SHA-256")
    void importBatchIsConstrained() throws SQLException {
        UUID organization = insertOrganization("ORD-ORG");
        UUID company = insertCompany(organization, "ORD-A");
        String digest = "a".repeat(64);

        insertImportBatch(company, "XLSX", digest);

        assertViolates(CHECK_VIOLATION, () -> insertImportBatch(company, "PDF", digest));
        // Not hexadecimal, and not 64 characters: both are a sign the column was filled with
        // something other than a digest, which is the one thing it is for.
        assertViolates(CHECK_VIOLATION, () -> insertImportBatch(company, "CSV", "not-a-digest"));
        assertViolates(CHECK_VIOLATION, () -> insertImportBatch(company, "CSV", "A".repeat(64)));
        assertViolates(FOREIGN_KEY_VIOLATION, () -> insertImportBatch(UUID.randomUUID(), "CSV", digest));
    }

    @Test
    @DisplayName("import batch counts cannot be negative")
    void importBatchCountsAreNonNegative() throws SQLException {
        UUID organization = insertOrganization("ORD-ORG");
        UUID company = insertCompany(organization, "ORD-A");

        assertViolates(CHECK_VIOLATION, () -> execute("INSERT INTO tms.order_import_batch (company_id,"
                + " external_source, file_name, file_format, file_sha256, row_count, created_count, skipped_count)"
                + " VALUES ('" + company + "', 'ERP', 'orders.csv', 'CSV', '" + "a".repeat(64) + "', -1, 0, 0)"));
    }

    private void insertImportBatch(UUID companyId, String format, String digest) throws SQLException {
        execute("INSERT INTO tms.order_import_batch (company_id, external_source, file_name, file_format,"
                + " file_sha256, row_count, created_count, skipped_count) VALUES ('" + companyId
                + "', 'ERP', 'orders.csv', '" + format + "', '" + digest + "', 3, 2, 1)");
    }

    private String queryText(String sql) throws SQLException {
        try (Statement statement = connection.createStatement();
                ResultSet resultSet = statement.executeQuery(sql)) {
            resultSet.next();
            return resultSet.getString(1);
        }
    }

    private UUID insertOrganization(String code) throws SQLException {
        return insertReturningId("INSERT INTO tms.organization (code, name) VALUES ('" + code + "', '" + code
                + " name') RETURNING id");
    }

    private UUID insertCompany(UUID organizationId, String code) throws SQLException {
        return insertReturningId("INSERT INTO tms.company (organization_id, code, name) VALUES ('" + organizationId
                + "', '" + code + "', '" + code + " name') RETURNING id");
    }

    private UUID insertAppUser(String email) throws SQLException {
        return insertReturningId(
                "INSERT INTO tms.app_user (email, full_name) VALUES ('" + email + "', 'Test person') RETURNING id");
    }

    /**
     * A canonical location to be used as an origin. No {@code tms.location_role} row: this suite
     * proves database constraints, and which roles a location holds is an application rule the
     * services enforce - {@code RouteApiIntegrationTest} and {@code OrderApiIntegrationTest} are
     * where that is asserted.
     */
    private UUID insertOrigin(UUID companyId, String code) throws SQLException {
        return insertReturningId("INSERT INTO tms.location (company_id, code, name) VALUES ('" + companyId + "', '"
                + code + "', '" + code + " name') RETURNING id");
    }

    /** A canonical location to be used as a destination; see {@link #insertOrigin}. */
    private UUID insertDestination(UUID companyId, String code) throws SQLException {
        return insertReturningId("INSERT INTO tms.location (company_id, code, name, country) VALUES ('"
                + companyId + "', '" + code + "', '" + code + " name', 'PE') RETURNING id");
    }

    private UUID insertOrder(UUID companyId, String orderNumber, UUID originId, UUID destinationId) throws SQLException {
        return insertReturningId("INSERT INTO tms.transport_order"
                + " (company_id, order_number, origin_id, destination_id, service_date) VALUES ('" + companyId
                + "', '" + orderNumber + "', '" + originId + "', '" + destinationId + "', '2026-01-01') RETURNING id");
    }

    private UUID insertOrderWithExternalReference(UUID companyId, String orderNumber, UUID originId, UUID destinationId,
            String externalSource, String externalReference) throws SQLException {
        return insertReturningId("INSERT INTO tms.transport_order"
                + " (company_id, order_number, external_source, external_reference, origin_id, destination_id, service_date)"
                + " VALUES ('" + companyId + "', '" + orderNumber + "', '" + externalSource + "', '" + externalReference
                + "', '" + originId + "', '" + destinationId + "', '2026-01-01') RETURNING id");
    }

    private UUID insertLine(UUID orderId, int lineNumber, String materialCode) throws SQLException {
        return insertReturningId("INSERT INTO tms.transport_order_line"
                + " (order_id, line_number, material_code, material_description, quantity, uom) VALUES ('" + orderId
                + "', " + lineNumber + ", '" + materialCode + "', 'Line description', 1, 'EA') RETURNING id");
    }

    private UUID insertReturningId(String sql) throws SQLException {
        try (Statement statement = connection.createStatement();
                ResultSet resultSet = statement.executeQuery(sql)) {
            resultSet.next();
            return UUID.fromString(resultSet.getString(1));
        }
    }

    private void execute(String sql) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }

    private long count(String sql) throws SQLException {
        try (Statement statement = connection.createStatement();
                ResultSet resultSet = statement.executeQuery(sql)) {
            resultSet.next();
            return resultSet.getLong(1);
        }
    }

    /** See {@code TenancyConstraintIntegrationTest} for why a savepoint is used here. */
    private void assertViolates(String sqlState, ThrowingCallable statement) {
        try {
            Savepoint savepoint = connection.setSavepoint();
            try {
                Throwable thrown = catchThrowable(statement);
                assertThat(thrown).as("the database was expected to refuse the statement").isNotNull();
                assertThat(thrown).isInstanceOf(SQLException.class);
                assertThat(((SQLException) thrown).getSQLState()).isEqualTo(sqlState);
            } finally {
                connection.rollback(savepoint);
            }
        } catch (SQLException e) {
            throw new IllegalStateException("savepoint handling failed", e);
        }
    }
}
