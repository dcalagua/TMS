package com.ebim.tms.database;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Savepoint;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;

/**
 * The V14 canonical Location migration at the database level: its constraints, its tenant
 * policies, and - the part nothing else can prove - what its backfill does to rows that already
 * existed.
 *
 * <p>The backfill nest deliberately migrates to V13, seeds legacy data, and only then applies
 * V14. A fully migrated database has no pre-V14 rows to transform, so running the backfill
 * against one asserts nothing at all.
 */
@EnabledIf(value = DockerAvailability.CONDITION, disabledReason = DockerAvailability.DISABLED_REASON)
class CanonicalLocationConstraintIntegrationTest {

    private static final String UNIQUE_VIOLATION = "23505";
    private static final String CHECK_VIOLATION = "23514";
    private static final String FOREIGN_KEY_VIOLATION = "23503";

    @Nested
    @DisplayName("constraints")
    class Constraints {

        private static String jdbcUrl;

        private Connection connection;

        @BeforeAll
        static void migrate() {
            jdbcUrl = PostgresTestDatabase.createMigratedDatabase("tms_location_constraints");
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
        @DisplayName("location codes are unique per company and free to repeat across companies")
        void codesAreScopedToTheirCompany() throws SQLException {
            UUID organization = insertOrganization("LOC-ORG");
            UUID companyA = insertCompany(organization, "LOC-A");
            UUID companyB = insertCompany(organization, "LOC-B");

            insertLocation(companyA, "NORTH-DC");
            insertLocation(companyB, "NORTH-DC");
            assertViolates(UNIQUE_VIOLATION, () -> insertLocation(companyA, "NORTH-DC"));
        }

        @Test
        @DisplayName("an external reference is unique per company and per system, and only when present")
        void externalIdentityIsUniquePerCompanyAndSystem() throws SQLException {
            UUID organization = insertOrganization("LOC-ORG");
            UUID company = insertCompany(organization, "LOC-A");

            executeRaw("INSERT INTO tms.location (company_id, code, name, external_system, external_reference)"
                    + " VALUES ('" + company + "', 'REF-A', 'Ref A', 'EWM', 'S-1')");
            // Same reference, different system: a different identity.
            executeRaw("INSERT INTO tms.location (company_id, code, name, external_system, external_reference)"
                    + " VALUES ('" + company + "', 'REF-B', 'Ref B', 'ERP', 'S-1')");
            // Two locations with no reference at all: the index is partial, so this is fine.
            insertLocation(company, "REF-C");
            insertLocation(company, "REF-D");

            assertViolates(UNIQUE_VIOLATION, () -> executeRaw(
                    "INSERT INTO tms.location (company_id, code, name, external_system, external_reference)"
                            + " VALUES ('" + company + "', 'REF-E', 'Ref E', 'EWM', 'S-1')"));
        }

        @Test
        @DisplayName("an external reference with no system is refused: it deduplicates nothing")
        void externalIdentityMustBeComplete() throws SQLException {
            UUID organization = insertOrganization("LOC-ORG");
            UUID company = insertCompany(organization, "LOC-A");

            assertViolates(CHECK_VIOLATION, () -> executeRaw(
                    "INSERT INTO tms.location (company_id, code, name, external_reference)"
                            + " VALUES ('" + company + "', 'ORPHAN-REF', 'Orphan', 'S-9')"));
        }

        @Test
        @DisplayName("half a coordinate pair is refused, and out-of-range values with it")
        void coordinatesAreConstrained() throws SQLException {
            UUID organization = insertOrganization("LOC-ORG");
            UUID company = insertCompany(organization, "LOC-A");

            assertViolates(CHECK_VIOLATION, () -> executeRaw(
                    "INSERT INTO tms.location (company_id, code, name, latitude)"
                            + " VALUES ('" + company + "', 'HALF-PAIR', 'Half', 10.5)"));
            assertViolates(CHECK_VIOLATION, () -> executeRaw(
                    "INSERT INTO tms.location (company_id, code, name, latitude, longitude)"
                            + " VALUES ('" + company + "', 'BAD-LAT', 'Bad', 95.0, 10.0)"));
            assertViolates(CHECK_VIOLATION, () -> executeRaw(
                    "INSERT INTO tms.location (company_id, code, name, latitude, longitude)"
                            + " VALUES ('" + company + "', 'BAD-LNG', 'Bad', 10.0, 200.0)"));
        }

        @Test
        @DisplayName("the geography column is derived by the database, not supplied by the writer")
        void geoPointIsGenerated() throws SQLException {
            UUID organization = insertOrganization("LOC-ORG");
            UUID company = insertCompany(organization, "LOC-A");

            executeRaw("INSERT INTO tms.location (company_id, code, name, latitude, longitude)"
                    + " VALUES ('" + company + "', 'GEO', 'Geo', -12.045600, -77.031700)");
            insertLocation(company, "NO-GEO");

            assertThat(singleValue("SELECT ST_AsText(geo_point::geometry) FROM tms.location WHERE code = 'GEO'"))
                    .isEqualTo("POINT(-77.0317 -12.0456)");
            assertThat(singleValue("SELECT geo_point::text FROM tms.location WHERE code = 'NO-GEO'"))
                    .as("no coordinates means no point, not an error")
                    .isNull();
        }

        @Test
        @DisplayName("a location cannot reference a zone of another company")
        void zoneMustBelongToTheSameCompany() throws SQLException {
            UUID organization = insertOrganization("LOC-ORG");
            UUID companyA = insertCompany(organization, "LOC-A");
            UUID companyB = insertCompany(organization, "LOC-B");
            UUID zoneInB = insertZone(companyB, "ZONE-B");

            assertViolates(FOREIGN_KEY_VIOLATION, () -> executeRaw(
                    "INSERT INTO tms.location (company_id, code, name, zone_id)"
                            + " VALUES ('" + companyA + "', 'CROSS-ZONE', 'Cross', '" + zoneInB + "')"));
        }

        @Test
        @DisplayName("an origin cannot point at a location of another company")
        void projectionLinkIsTenantSafe() throws SQLException {
            UUID organization = insertOrganization("LOC-ORG");
            UUID companyA = insertCompany(organization, "LOC-A");
            UUID companyB = insertCompany(organization, "LOC-B");
            UUID locationInB = insertLocation(companyB, "IN-B");

            assertViolates(FOREIGN_KEY_VIOLATION, () -> executeRaw(
                    "INSERT INTO tms.origin (company_id, code, name, time_zone, location_id)"
                            + " VALUES ('" + companyA + "', 'CROSS-LOC', 'Cross', 'UTC', '" + locationInB + "')"));
        }

        @Test
        @DisplayName("one location has at most one origin and one destination")
        void theProjectionLinkIsOneToOne() throws SQLException {
            UUID organization = insertOrganization("LOC-ORG");
            UUID company = insertCompany(organization, "LOC-A");
            UUID location = insertLocation(company, "ONE-TO-ONE");

            executeRaw("INSERT INTO tms.origin (company_id, code, name, time_zone, location_id)"
                    + " VALUES ('" + company + "', 'ORG-1', 'Origin 1', 'UTC', '" + location + "')");
            assertViolates(UNIQUE_VIOLATION, () -> executeRaw(
                    "INSERT INTO tms.origin (company_id, code, name, time_zone, location_id)"
                            + " VALUES ('" + company + "', 'ORG-2', 'Origin 2', 'UTC', '" + location + "')"));
        }

        @Test
        @DisplayName("a role is an operational use, held once, and only ORIGIN or DESTINATION")
        void rolesAreConstrained() throws SQLException {
            UUID organization = insertOrganization("LOC-ORG");
            UUID company = insertCompany(organization, "LOC-A");
            UUID location = insertLocation(company, "ROLES");

            // One place that both ships and receives - the case the whole model exists for.
            executeRaw("INSERT INTO tms.location_role (location_id, role) VALUES ('" + location + "', 'ORIGIN')");
            executeRaw("INSERT INTO tms.location_role (location_id, role) VALUES ('" + location
                    + "', 'DESTINATION')");

            assertViolates(UNIQUE_VIOLATION, () -> executeRaw(
                    "INSERT INTO tms.location_role (location_id, role) VALUES ('" + location + "', 'ORIGIN')"));
            // A kind of place is location_type's answer, never a role's. V23 removed the five
            // classification values V14 shipped; the database is what stops them coming back.
            for (String classification : new String[] {"WAREHOUSE", "STORE", "DC", "PLANT", "HUB", "OTHER"}) {
                assertViolates(CHECK_VIOLATION, () -> executeRaw(
                        "INSERT INTO tms.location_role (location_id, role) VALUES ('" + location + "', '"
                                + classification + "')"));
            }
            assertViolates(CHECK_VIOLATION, () -> executeRaw(
                    "INSERT INTO tms.location_role (location_id, role) VALUES ('" + location + "', 'SHIP_TO')"));
        }

        @Test
        @DisplayName("the runtime role cannot read or write a location of another company")
        void tenantPolicyIsolatesLocations() throws SQLException {
            UUID organization = insertOrganization("LOC-ORG");
            UUID companyA = insertCompany(organization, "LOC-A");
            UUID companyB = insertCompany(organization, "LOC-B");
            UUID inA = insertLocation(companyA, "RLS-A");
            executeRaw("INSERT INTO tms.location_role (location_id, role) VALUES ('" + inA + "', 'DESTINATION')");
            insertLocation(companyB, "RLS-B");

            // The application enters the runtime role and publishes its tenant exactly like
            // TenantScopedDataSource does; the owner connection is RLS-exempt, so this is the
            // only way to observe the policy at all.
            executeRaw("SET LOCAL ROLE tms_app");
            executeRaw("SELECT set_config('tms.company_id', '" + companyA + "', true)");

            assertThat(strings("SELECT code FROM tms.location ORDER BY 1"))
                    .as("company B's rows must not be visible at all, not merely filtered later")
                    .containsExactly("RLS-A");
            assertThat(singleValue("SELECT count(*)::text FROM tms.location_role"))
                    .as("a child row is reachable exactly when its parent is")
                    .isEqualTo("1");

            Savepoint savepoint = savepoint();
            Throwable thrown = catchThrowable(() -> executeRaw(
                    "INSERT INTO tms.location (company_id, code, name) VALUES ('" + companyB
                            + "', 'SNEAK', 'Sneak')"));
            rollbackTo(savepoint);
            assertThat(thrown)
                    .as("a USING-only policy would have allowed this write and merely hidden it")
                    .isInstanceOf(SQLException.class);
        }

        @Test
        @DisplayName("deleting a location takes its roles with it: they mean nothing on their own")
        void rolesCascadeFromTheirLocation() throws SQLException {
            UUID organization = insertOrganization("LOC-ORG");
            UUID company = insertCompany(organization, "LOC-A");
            UUID location = insertLocation(company, "CASCADE");
            executeRaw("INSERT INTO tms.location_role (location_id, role) VALUES ('" + location
                    + "', 'DESTINATION')");

            executeRaw("DELETE FROM tms.location WHERE id = '" + location + "'");

            assertThat(singleValue("SELECT count(*)::text FROM tms.location_role WHERE location_id = '"
                    + location + "'")).isEqualTo("0");
        }

        // ------------------------------------------------------------------
        // Fixture helpers
        // ------------------------------------------------------------------

        private UUID insertOrganization(String code) throws SQLException {
            return uuid("INSERT INTO tms.organization (code, name) VALUES ('" + code + "', '" + code
                    + " name') RETURNING id");
        }

        private UUID insertCompany(UUID organizationId, String code) throws SQLException {
            return uuid("INSERT INTO tms.company (organization_id, code, name, time_zone) VALUES ('"
                    + organizationId + "', '" + code + "', '" + code + " name', 'America/Lima') RETURNING id");
        }

        private UUID insertZone(UUID companyId, String code) throws SQLException {
            return uuid("INSERT INTO tms.zone (company_id, code, name) VALUES ('" + companyId + "', '" + code
                    + "', '" + code + " name') RETURNING id");
        }

        private UUID insertLocation(UUID companyId, String code) throws SQLException {
            return uuid(insertLocationSql(companyId, code) + " RETURNING id");
        }

        private static String insertLocationSql(UUID companyId, String code) {
            return "INSERT INTO tms.location (company_id, code, name) VALUES ('" + companyId + "', '" + code
                    + "', '" + code + " name')";
        }

        private UUID uuid(String sql) throws SQLException {
            try (Statement statement = connection.createStatement();
                    ResultSet resultSet = statement.executeQuery(sql)) {
                resultSet.next();
                return UUID.fromString(resultSet.getString(1));
            }
        }

        private List<String> strings(String sql) throws SQLException {
            List<String> values = new ArrayList<>();
            try (Statement statement = connection.createStatement();
                    ResultSet resultSet = statement.executeQuery(sql)) {
                while (resultSet.next()) {
                    values.add(resultSet.getString(1));
                }
            }
            return values;
        }

        private String singleValue(String sql) throws SQLException {
            try (Statement statement = connection.createStatement();
                    ResultSet resultSet = statement.executeQuery(sql)) {
                return resultSet.next() ? resultSet.getString(1) : null;
            }
        }

        private void executeRaw(String sql) throws SQLException {
            try (Statement statement = connection.createStatement()) {
                statement.execute(sql);
            }
        }

        /**
         * Runs inside a savepoint so a deliberate violation does not poison the surrounding
         * transaction, matching {@code MasterDataConstraintIntegrationTest}.
         */
        private void assertViolates(String sqlState, ThrowingCallable insert) {
            Savepoint savepoint = savepoint();
            Throwable thrown = catchThrowable(insert);
            rollbackTo(savepoint);

            assertThat(thrown).isInstanceOf(SQLException.class);
            assertThat(((SQLException) thrown).getSQLState()).isEqualTo(sqlState);
        }

        private Savepoint savepoint() {
            try {
                return connection.setSavepoint();
            } catch (SQLException e) {
                throw new IllegalStateException("could not open a savepoint", e);
            }
        }

        private void rollbackTo(Savepoint savepoint) {
            try {
                connection.rollback(savepoint);
            } catch (SQLException e) {
                throw new IllegalStateException("could not roll back to the savepoint", e);
            }
        }
    }

    @Nested
    @DisplayName("data migration of rows that existed before the canonical Location master")
    class Backfill {

        private static String jdbcUrl;
        private static UUID companyA;
        private static UUID companyB;

        /**
         * Migrate to V13, seed data in the shape the product had then, and migrate to head - so
         * this fixture proves V14's backfill and V23's unification as one chain, on rows that
         * predate both.
         *
         * <p>The four cases V14's backfill has to distinguish:
         *
         * <ol>
         *   <li>an origin with no same-code destination;</li>
         *   <li>a destination with no same-code origin;</li>
         *   <li>an origin and a destination sharing a code - the duplicate-DC case that merges;</li>
         *   <li>two rows of one company carrying the same {@code external_reference}, which V6/V7
         *       allowed and {@code uq_location_external} does not.</li>
         * </ol>
         *
         * <p>Plus the consumers V23 has to repoint: a route with a stop, and an order whose two
         * ends are the same physical place recorded twice - which is the duplication this whole
         * domain change removes, and the one case where the repoint is visible as an equality.
         */
        @BeforeAll
        static void seedThenMigrate() throws SQLException {
            jdbcUrl = PostgresTestDatabase.createEmptyDatabase("tms_location_backfill");
            PostgresTestDatabase.flywayTo(jdbcUrl, "13").migrate();

            try (Connection connection = PostgresTestDatabase.connect(jdbcUrl);
                    Statement statement = connection.createStatement()) {
                statement.execute("""
                        INSERT INTO tms.organization (id, code, name)
                        VALUES ('55555555-0000-4000-8000-000000000001', 'BF-ORG', 'Backfill Organization');
                        INSERT INTO tms.company (id, organization_id, code, name, time_zone) VALUES
                            ('55555555-0000-4000-8000-0000000000c1',
                             '55555555-0000-4000-8000-000000000001', 'BF-A', 'Company A', 'America/Lima'),
                            ('55555555-0000-4000-8000-0000000000c2',
                             '55555555-0000-4000-8000-000000000001', 'BF-B', 'Company B', 'Europe/Madrid');
                        """);
                companyA = UUID.fromString("55555555-0000-4000-8000-0000000000c1");
                companyB = UUID.fromString("55555555-0000-4000-8000-0000000000c2");

                statement.execute("""
                        INSERT INTO tms.origin
                            (company_id, code, name, origin_type, address, latitude, longitude, time_zone,
                             external_reference, active)
                        VALUES
                            ('%1$s', 'ONLY-ORG', 'Only Origin', 'PLANT', 'Av. Planta 1',
                             -12.000000, -77.000000, 'America/Lima', 'REF-ONLY-ORG', true),
                            ('%1$s', 'BOTH', 'Both Sides Origin', 'DISTRIBUTION_CENTER', 'Av. Origen 5',
                             -12.050000, -77.050000, 'America/Lima', 'REF-DUPLICATE', true),
                            ('%1$s', 'INACTIVE-ORG', 'Inactive Origin', 'HUB', NULL, NULL, NULL, 'UTC',
                             NULL, false);

                        INSERT INTO tms.destination
                            (company_id, code, name, destination_type, address, address_reference, district,
                             province, department, country, latitude, longitude, service_time_minutes,
                             external_reference, active)
                        VALUES
                            ('%1$s', 'ONLY-DST', 'Only Destination', 'CUSTOMER', 'Jr. Cliente 9', 'Casa azul',
                             'Miraflores', 'Lima', 'Lima', 'PE', -12.100000, -77.100000, 20, NULL, true),
                            ('%1$s', 'BOTH', 'Both Sides Destination', 'STORE', 'Av. Destino 7', 'Porton',
                             'Callao', 'Callao', 'Callao', 'PE', -12.060000, -77.060000, 35,
                             'REF-DUPLICATE', true),
                            ('%1$s', 'INACTIVE-ORG', 'Same Code As Inactive Origin', 'STORE', NULL, NULL,
                             NULL, NULL, NULL, 'PE', NULL, NULL, 0, NULL, true),
                            ('%2$s', 'ONLY-DST', 'Other Company Destination', 'CUSTOMER', NULL, NULL, NULL,
                             NULL, NULL, 'ES', NULL, NULL, 0, 'REF-DUPLICATE', true);
                        """.formatted(companyA, companyB));

                // The consumers, in the shape they had at V13: pointing at tms.origin and
                // tms.destination. V23 has to carry every one of them across without an operator
                // touching anything.
                statement.execute("""
                        INSERT INTO tms.route (id, company_id, code, name, origin_id)
                        SELECT '55555555-0000-4000-8000-0000000000r1', o.company_id, 'BF-ROUTE', 'Backfill route',
                               o.id
                        FROM tms.origin o WHERE o.company_id = '%1$s' AND o.code = 'ONLY-ORG';

                        INSERT INTO tms.route_stop (route_id, company_id, destination_id, sequence)
                        SELECT '55555555-0000-4000-8000-0000000000r1', d.company_id, d.id, 1
                        FROM tms.destination d WHERE d.company_id = '%1$s' AND d.code = 'ONLY-DST';

                        INSERT INTO tms.transport_order
                            (company_id, order_number, origin_id, destination_id, service_date)
                        SELECT o.company_id, 'BF-ORDER-1', o.id, d.id, DATE '2026-01-15'
                        FROM tms.origin o
                        JOIN tms.destination d ON d.company_id = o.company_id AND d.code = o.code
                        WHERE o.company_id = '%1$s' AND o.code = 'BOTH';
                        """.formatted(companyA));
            }

            PostgresTestDatabase.flyway(jdbcUrl).migrate();
        }

        @Test
        @DisplayName("every legacy row ends up linked to a canonical location")
        void everyLegacyRowIsLinked() throws SQLException {
            assertThat(value("SELECT count(*)::text FROM tms.origin WHERE location_id IS NULL")).isEqualTo("0");
            assertThat(value("SELECT count(*)::text FROM tms.destination WHERE location_id IS NULL")).isEqualTo("0");
        }

        @Test
        @DisplayName("an origin's location carries the origin's own id, so a later repoint changes nothing")
        void originsKeepTheirId() throws SQLException {
            assertThat(value("SELECT count(*)::text FROM tms.origin WHERE location_id <> id"))
                    .as("the whole point of the id choice in ADR_LOCATION_MODEL section 3")
                    .isEqualTo("0");
        }

        @Test
        @DisplayName("an origin and a destination sharing a code become one location holding both roles")
        void sameCodeMergesIntoOneLocationWithBothRoles() throws SQLException {
            assertThat(value("SELECT count(*)::text FROM tms.location WHERE company_id = '" + companyA
                    + "' AND code = 'BOTH'")).isEqualTo("1");

            assertThat(strings("SELECT r.role FROM tms.location l JOIN tms.location_role r ON r.location_id = l.id"
                    + " WHERE l.company_id = '" + companyA + "' AND l.code = 'BOTH' ORDER BY 1"))
                    .containsExactly("DESTINATION", "ORIGIN");

            assertThat(value("SELECT l.id::text = o.id::text FROM tms.location l JOIN tms.origin o"
                    + " ON o.location_id = l.id WHERE l.company_id = '" + companyA + "' AND l.code = 'BOTH'"))
                    .isEqualTo("true");
            assertThat(value("SELECT d.location_id::text = o.id::text FROM tms.destination d JOIN tms.origin o"
                    + " ON o.company_id = d.company_id AND o.code = d.code WHERE d.company_id = '" + companyA
                    + "' AND d.code = 'BOTH'"))
                    .as("the destination points at the merged location, which carries the origin's id")
                    .isEqualTo("true");
        }

        @Test
        @DisplayName("a merged location takes the richer destination address and the origin's time zone")
        void mergePrecedenceFollowsTheAdr() throws SQLException {
            assertThat(row("SELECT name, location_type, address, address_reference, district, country,"
                    + " time_zone, latitude::text, longitude::text, service_time_minutes::text"
                    + " FROM tms.location WHERE company_id = '" + companyA + "' AND code = 'BOTH'"))
                    .containsExactly("Both Sides Origin", "DISTRIBUTION_CENTER", "Av. Destino 7", "Porton",
                            "Callao", "PE", "America/Lima", "-12.050000", "-77.050000", "35");
        }

        @Test
        @DisplayName("a one-sided row keeps everything it had, and a destination inherits its company's time zone")
        void oneSidedRowsAreCarriedAcrossIntact() throws SQLException {
            assertThat(row("SELECT location_type, address, time_zone, external_reference, external_system"
                    + " FROM tms.location WHERE company_id = '" + companyA + "' AND code = 'ONLY-ORG'"))
                    .containsExactly("PLANT", "Av. Planta 1", "America/Lima", "REF-ONLY-ORG", "LEGACY");

            assertThat(row("SELECT location_type, address_reference, district, service_time_minutes::text,"
                    + " time_zone FROM tms.location WHERE company_id = '" + companyA + "' AND code = 'ONLY-DST'"))
                    .containsExactly("CUSTOMER", "Casa azul", "Miraflores", "20", "America/Lima");

            assertThat(value("SELECT time_zone FROM tms.location WHERE company_id = '" + companyB
                    + "' AND code = 'ONLY-DST'"))
                    .as("a destination has no time zone of its own, so it inherits its company's")
                    .isEqualTo("Europe/Madrid");
        }

        @Test
        @DisplayName("a merged location is active when either side was, so nothing disappears from a list")
        void activeIsTheUnionOfBothSides() throws SQLException {
            assertThat(value("SELECT active::text FROM tms.location WHERE company_id = '" + companyA
                    + "' AND code = 'INACTIVE-ORG'"))
                    .as("the origin was inactive and the destination was not; the place is still in use")
                    .isEqualTo("true");
        }

        @Test
        @DisplayName("a duplicate external reference is claimed once and left NULL on the losers")
        void duplicateExternalReferencesAreDeduplicated() throws SQLException {
            assertThat(value("SELECT count(*)::text FROM tms.location WHERE company_id = '" + companyA
                    + "' AND external_reference = 'REF-DUPLICATE'"))
                    .as("uq_location_external allows exactly one per company and system")
                    .isEqualTo("1");

            assertThat(value("SELECT count(*)::text FROM tms.destination WHERE company_id = '" + companyA
                    + "' AND external_reference = 'REF-DUPLICATE'"))
                    .as("the legacy row keeps its own value; nothing is erased from it")
                    .isEqualTo("1");

            assertThat(value("SELECT count(*)::text FROM tms.location WHERE company_id = '" + companyB
                    + "' AND external_reference = 'REF-DUPLICATE'"))
                    .as("the reference is scoped to a company, so the other company keeps its own")
                    .isEqualTo("1");
        }

        @Test
        @DisplayName("the same code in two companies produces two locations, not one")
        void codesDoNotMergeAcrossCompanies() throws SQLException {
            assertThat(value("SELECT count(*)::text FROM tms.location WHERE code = 'ONLY-DST'")).isEqualTo("2");
        }

        @Test
        @DisplayName("a route and its stop follow their places to tms.location, unchanged in meaning")
        void routesAreRepointed() throws SQLException {
            assertThat(value("""
                    SELECT (r.origin_id = l.id)::text
                    FROM tms.route r JOIN tms.location l
                      ON l.company_id = r.company_id AND l.code = 'ONLY-ORG'
                    WHERE r.code = 'BF-ROUTE'
                    """))
                    .as("the route still departs from the same physical place, now named canonically")
                    .isEqualTo("true");

            assertThat(value("""
                    SELECT (s.destination_id = l.id)::text
                    FROM tms.route_stop s JOIN tms.route r ON r.id = s.route_id
                    JOIN tms.location l ON l.company_id = s.company_id AND l.code = 'ONLY-DST'
                    WHERE r.code = 'BF-ROUTE'
                    """))
                    .isEqualTo("true");
        }

        @Test
        @DisplayName("an order whose two ends were one place becomes an order with one location at both ends")
        void ordersAreRepointedAndDeduplicated() throws SQLException {
            assertThat(value("""
                    SELECT (t.origin_id = t.destination_id)::text
                    FROM tms.transport_order t WHERE t.order_number = 'BF-ORDER-1'
                    """))
                    .as("BOTH was one distribution centre recorded twice - once as an origin, once "
                            + "as a destination. After V23 there is one row, and the order points "
                            + "at it from both ends, which is the entire claim of the model")
                    .isEqualTo("true");

            assertThat(value("""
                    SELECT (t.origin_id = l.id)::text
                    FROM tms.transport_order t JOIN tms.location l
                      ON l.company_id = t.company_id AND l.code = 'BOTH'
                    WHERE t.order_number = 'BF-ORDER-1'
                    """))
                    .isEqualTo("true");
        }

        @Test
        @DisplayName("every consumer's foreign key now names tms.location, not a legacy projection")
        void foreignKeysTargetTheCanonicalTable() throws SQLException {
            assertThat(strings("""
                    SELECT c.conrelid::regclass::text || '.' || c.conname
                    FROM pg_constraint c
                    WHERE c.contype = 'f'
                      AND c.confrelid IN ('tms.origin'::regclass, 'tms.destination'::regclass)
                      AND c.conrelid NOT IN ('tms.origin'::regclass, 'tms.destination'::regclass)
                    ORDER BY 1
                    """))
                    .as("a foreign key still pointing at a legacy table would mean that table is "
                            + "still a source of truth for somebody")
                    .isEmpty();

            assertThat(strings("""
                    SELECT c.conrelid::regclass::text || '.' || c.conname
                    FROM pg_constraint c
                    WHERE c.contype = 'f' AND c.confrelid = 'tms.location'::regclass
                      AND c.conrelid IN ('tms.route'::regclass, 'tms.route_stop'::regclass,
                                         'tms.transport_order'::regclass, 'tms.planning_run'::regclass,
                                         'tms.trip_stop'::regclass)
                    ORDER BY 1
                    """))
                    .as("both the plain and the composite tenant key, for all six columns")
                    .hasSize(12);
        }

        @Test
        @DisplayName("the application role cannot write the frozen legacy tables at all")
        void legacyTablesAreReadOnlyForTheRuntimeRole() throws SQLException {
            assertThat(value("""
                    SELECT count(*)::text FROM information_schema.role_table_grants
                    WHERE grantee = 'tms_app' AND table_schema = 'tms'
                      AND table_name IN ('origin', 'destination')
                      AND privilege_type IN ('INSERT', 'UPDATE', 'DELETE')
                    """))
                    .as("'not a source of truth' is a grant after V23, not a convention")
                    .isEqualTo("0");

            assertThat(value("""
                    SELECT count(*)::text FROM information_schema.role_table_grants
                    WHERE grantee = 'tms_app' AND table_schema = 'tms'
                      AND table_name IN ('origin', 'destination') AND privilege_type = 'SELECT'
                    """))
                    .as("kept readable: they are the recovery path for a V14 merge that united two "
                            + "genuinely different places")
                    .isEqualTo("2");
        }

        @Test
        @DisplayName("replaying the migration history on this database is a no-op")
        void migrationIsIdempotent() {
            assertThat(PostgresTestDatabase.flyway(jdbcUrl).migrate().migrationsExecuted).isZero();
        }

        private static String value(String sql) throws SQLException {
            try (Connection connection = PostgresTestDatabase.connect(jdbcUrl);
                    Statement statement = connection.createStatement();
                    ResultSet resultSet = statement.executeQuery(sql)) {
                return resultSet.next() ? resultSet.getString(1) : null;
            }
        }

        private static List<String> strings(String sql) throws SQLException {
            List<String> values = new ArrayList<>();
            try (Connection connection = PostgresTestDatabase.connect(jdbcUrl);
                    Statement statement = connection.createStatement();
                    ResultSet resultSet = statement.executeQuery(sql)) {
                while (resultSet.next()) {
                    values.add(resultSet.getString(1));
                }
            }
            return values;
        }

        /** The first row as a list of its column values, for asserting several fields at once. */
        private static List<String> row(String sql) throws SQLException {
            List<String> values = new ArrayList<>();
            try (Connection connection = PostgresTestDatabase.connect(jdbcUrl);
                    Statement statement = connection.createStatement();
                    ResultSet resultSet = statement.executeQuery(sql)) {
                if (resultSet.next()) {
                    for (int column = 1; column <= resultSet.getMetaData().getColumnCount(); column++) {
                        values.add(resultSet.getString(column));
                    }
                }
            }
            return values;
        }
    }
}
