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
 * Proves the V9 fleet constraints hold at the database level, independent of the Java
 * validation in {@code CarrierService}/{@code VehicleTypeService}/{@code VehicleService}
 * (defense in depth: the checks here must refuse a bad row even if a future writer bypasses the
 * application layer entirely). Follows {@code MasterDataRouteConstraintIntegrationTest}'s shape.
 *
 * <p>Each test runs in a transaction that is rolled back, matching every other constraint test
 * in this package.
 */
@EnabledIf(value = DockerAvailability.CONDITION, disabledReason = DockerAvailability.DISABLED_REASON)
class FleetConstraintIntegrationTest {

    private static final String UNIQUE_VIOLATION = "23505";
    private static final String CHECK_VIOLATION = "23514";
    private static final String FOREIGN_KEY_VIOLATION = "23503";
    private static final String NOT_NULL_VIOLATION = "23502";

    private static String jdbcUrl;

    private Connection connection;

    @BeforeAll
    static void migrate() {
        jdbcUrl = PostgresTestDatabase.createMigratedDatabase("tms_fleet_constraints");
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

    // --- carrier -------------------------------------------------------------------

    @Test
    @DisplayName("carrier codes are unique per company, and free to repeat across companies")
    void carrierCodesAreScopedToTheirCompany() throws SQLException {
        UUID organization = insertOrganization("FL-ORG");
        UUID companyA = insertCompany(organization, "FL-A");
        UUID companyB = insertCompany(organization, "FL-B");

        insertCarrier(companyA, "ACME", "RUC", "20100000001");
        insertCarrier(companyB, "ACME", "RUC", "20100000002"); // same code, different company: allowed
        assertViolates(UNIQUE_VIOLATION, () -> insertCarrier(companyA, "ACME", "RUC", "20100000003"));
    }

    @Test
    @DisplayName("a carrier's tax identification is unique per company, and free to repeat across companies")
    void carrierTaxIdIsScopedToTheirCompany() throws SQLException {
        UUID organization = insertOrganization("FL-ORG");
        UUID companyA = insertCompany(organization, "FL-A");
        UUID companyB = insertCompany(organization, "FL-B");

        insertCarrier(companyA, "CARRIER-1", "RUC", "20100000001");
        insertCarrier(companyB, "CARRIER-2", "RUC", "20100000001"); // same tax id, different company: allowed
        assertViolates(UNIQUE_VIOLATION, () -> insertCarrier(companyA, "CARRIER-3", "RUC", "20100000001"));
    }

    @Test
    @DisplayName("a carrier cannot belong to a company that does not exist")
    void carrierRequiresARealCompany() {
        assertViolates(FOREIGN_KEY_VIOLATION,
                () -> execute("INSERT INTO tms.carrier (company_id, code, business_name, tax_id_type, tax_id_value)"
                        + " VALUES ('" + UUID.randomUUID() + "', 'ORPHAN', 'Orphan Carrier', 'RUC', '20100000009')"));
    }

    @Test
    @DisplayName("carrier code and the tax-id pair must already be normalized")
    void carrierCodeAndTaxIdMustBeNormalized() throws SQLException {
        UUID organization = insertOrganization("FL-ORG");
        UUID company = insertCompany(organization, "FL-A");

        assertViolates(CHECK_VIOLATION, () -> insertCarrier(company, "lower-case", "RUC", "20100000001"));
        assertViolates(CHECK_VIOLATION, () -> insertCarrier(company, "GOOD-CODE", "ruc", "20100000001"));
        assertViolates(CHECK_VIOLATION, () -> insertCarrier(company, "GOOD-CODE", "RUC", "not upper"));
    }

    @Test
    @DisplayName("carrier email, when present, must be normalized and shaped like an address")
    void carrierEmailIsValidatedWhenPresent() throws SQLException {
        UUID organization = insertOrganization("FL-ORG");
        UUID company = insertCompany(organization, "FL-A");

        assertViolates(CHECK_VIOLATION, () -> execute(
                "INSERT INTO tms.carrier (company_id, code, business_name, tax_id_type, tax_id_value, email)"
                        + " VALUES ('" + company + "', 'BAD-EMAIL', 'Bad Email Carrier', 'RUC', '20100000001',"
                        + " 'Not-An-Email')"));
        assertViolates(CHECK_VIOLATION, () -> execute(
                "INSERT INTO tms.carrier (company_id, code, business_name, tax_id_type, tax_id_value, email)"
                        + " VALUES ('" + company + "', 'BAD-CASE', 'Bad Case Carrier', 'RUC', '20100000002',"
                        + " 'Ops@Example.com')"));

        execute("INSERT INTO tms.carrier (company_id, code, business_name, tax_id_type, tax_id_value, email)"
                + " VALUES ('" + company + "', 'GOOD-EMAIL', 'Good Email Carrier', 'RUC', '20100000003',"
                + " 'ops@example.com')");
        assertThat(count("SELECT count(*) FROM tms.carrier WHERE code = 'GOOD-EMAIL'"
                + " AND email = 'ops@example.com'")).isOne();

        // no email at all is a legitimate carrier
        execute("INSERT INTO tms.carrier (company_id, code, business_name, tax_id_type, tax_id_value)"
                + " VALUES ('" + company + "', 'NO-EMAIL', 'No Email Carrier', 'RUC', '20100000004')");
        assertThat(count("SELECT count(*) FROM tms.carrier WHERE code = 'NO-EMAIL' AND email IS NULL")).isOne();
    }

    @Test
    @DisplayName("carrier external_reference, when present, must not be blank")
    void carrierExternalReferenceIsValidatedWhenPresent() throws SQLException {
        UUID organization = insertOrganization("FL-ORG");
        UUID company = insertCompany(organization, "FL-A");

        assertViolates(CHECK_VIOLATION, () -> execute(
                "INSERT INTO tms.carrier (company_id, code, business_name, tax_id_type, tax_id_value,"
                        + " external_reference) VALUES ('" + company + "', 'BAD-EXTREF', 'Bad Extref Carrier', 'RUC',"
                        + " '20100000010', '   ')"));

        execute("INSERT INTO tms.carrier (company_id, code, business_name, tax_id_type, tax_id_value,"
                + " external_reference) VALUES ('" + company + "', 'GOOD-EXTREF', 'Good Extref Carrier', 'RUC',"
                + " '20100000011', 'ERP-CARR-001')");
        assertThat(count("SELECT count(*) FROM tms.carrier WHERE code = 'GOOD-EXTREF'"
                + " AND external_reference = 'ERP-CARR-001'")).isOne();

        // no external reference at all is legitimate
        execute("INSERT INTO tms.carrier (company_id, code, business_name, tax_id_type, tax_id_value)"
                + " VALUES ('" + company + "', 'NO-EXTREF', 'No Extref Carrier', 'RUC', '20100000012')");
        assertThat(count("SELECT count(*) FROM tms.carrier WHERE code = 'NO-EXTREF' AND external_reference IS NULL"))
                .isOne();
    }

    @Test
    @DisplayName("carrier defaults to active and records who changed it")
    void carrierDefaultsAndActorColumns() throws SQLException {
        UUID organization = insertOrganization("FL-ORG");
        UUID company = insertCompany(organization, "FL-A");
        UUID actor = insertAppUser("fl.actor@example.test");

        UUID carrierId = insertCarrier(company, "DEFAULTS", "RUC", "20100000005");
        assertThat(count("SELECT count(*) FROM tms.carrier WHERE id = '" + carrierId + "' AND active")).isOne();

        execute("UPDATE tms.carrier SET active = false, updated_by = '" + actor + "' WHERE id = '" + carrierId + "'");
        assertThat(count("SELECT count(*) FROM tms.carrier WHERE id = '" + carrierId
                + "' AND NOT active AND updated_by = '" + actor + "'")).isOne();
    }

    // --- vehicle_type ----------------------------------------------------------------

    @Test
    @DisplayName("vehicle type codes are unique per company, and free to repeat across companies")
    void vehicleTypeCodesAreScopedToTheirCompany() throws SQLException {
        UUID organization = insertOrganization("FL-ORG");
        UUID companyA = insertCompany(organization, "FL-A");
        UUID companyB = insertCompany(organization, "FL-B");

        insertVehicleType(companyA, "TRUCK-10T", "10000.00", "40.000");
        insertVehicleType(companyB, "TRUCK-10T", "10000.00", "40.000"); // allowed in a different company
        assertViolates(UNIQUE_VIOLATION, () -> insertVehicleType(companyA, "TRUCK-10T", "8000.00", "30.000"));
    }

    @Test
    @DisplayName("max weight and max volume must be strictly positive; max pallets may be zero but not negative")
    void vehicleTypeCapacitiesAreValidated() throws SQLException {
        UUID organization = insertOrganization("FL-ORG");
        UUID company = insertCompany(organization, "FL-A");

        assertViolates(CHECK_VIOLATION, () -> execute(
                "INSERT INTO tms.vehicle_type (company_id, code, name, max_weight_kg, max_volume_m3)"
                        + " VALUES ('" + company + "', 'ZERO-WEIGHT', 'Zero weight', 0, 10)"));
        assertViolates(CHECK_VIOLATION, () -> execute(
                "INSERT INTO tms.vehicle_type (company_id, code, name, max_weight_kg, max_volume_m3)"
                        + " VALUES ('" + company + "', 'NEG-WEIGHT', 'Negative weight', -1, 10)"));
        assertViolates(CHECK_VIOLATION, () -> execute(
                "INSERT INTO tms.vehicle_type (company_id, code, name, max_weight_kg, max_volume_m3)"
                        + " VALUES ('" + company + "', 'ZERO-VOLUME', 'Zero volume', 1000, 0)"));
        assertViolates(CHECK_VIOLATION, () -> execute(
                "INSERT INTO tms.vehicle_type (company_id, code, name, max_weight_kg, max_volume_m3, max_pallets)"
                        + " VALUES ('" + company + "', 'NEG-PALLETS', 'Negative pallets', 1000, 10, -1)"));

        // zero pallets is legitimate (a tanker/bulk-liquid type carries none)
        execute("INSERT INTO tms.vehicle_type (company_id, code, name, max_weight_kg, max_volume_m3, max_pallets)"
                + " VALUES ('" + company + "', 'TANKER', 'Tanker', 15000, 20, 0)");
        assertThat(count("SELECT count(*) FROM tms.vehicle_type WHERE code = 'TANKER' AND max_pallets = 0")).isOne();
    }

    @Test
    @DisplayName("optional dimensions must be positive when present, body_type is restricted, axles must be at least 1")
    void vehicleTypePhysicalFieldsAreValidated() throws SQLException {
        UUID organization = insertOrganization("FL-ORG");
        UUID company = insertCompany(organization, "FL-A");

        assertViolates(CHECK_VIOLATION, () -> execute(
                "INSERT INTO tms.vehicle_type (company_id, code, name, max_weight_kg, max_volume_m3, length_m)"
                        + " VALUES ('" + company + "', 'BAD-LENGTH', 'Bad length', 1000, 10, 0)"));
        assertViolates(CHECK_VIOLATION, () -> execute(
                "INSERT INTO tms.vehicle_type (company_id, code, name, max_weight_kg, max_volume_m3, body_type)"
                        + " VALUES ('" + company + "', 'BAD-BODY', 'Bad body', 1000, 10, 'SPACESHIP')"));
        assertViolates(CHECK_VIOLATION, () -> execute(
                "INSERT INTO tms.vehicle_type (company_id, code, name, max_weight_kg, max_volume_m3, axles)"
                        + " VALUES ('" + company + "', 'BAD-AXLES', 'Bad axles', 1000, 10, 0)"));

        execute("INSERT INTO tms.vehicle_type"
                + " (company_id, code, name, max_weight_kg, max_volume_m3, body_type, axles)"
                + " VALUES ('" + company + "', 'FLATBED', 'Flatbed', 12000, 18, 'FLATBED', 2)");
        assertThat(count("SELECT count(*) FROM tms.vehicle_type WHERE code = 'FLATBED' AND body_type = 'FLATBED'"
                + " AND axles = 2")).isOne();
    }

    @Test
    @DisplayName("a temperature range only makes sense when temperature_controlled is true, and min must not exceed max")
    void vehicleTypeTemperatureRangeIsValidated() throws SQLException {
        UUID organization = insertOrganization("FL-ORG");
        UUID company = insertCompany(organization, "FL-A");

        assertViolates(CHECK_VIOLATION, () -> execute(
                "INSERT INTO tms.vehicle_type"
                        + " (company_id, code, name, max_weight_kg, max_volume_m3, temperature_controlled,"
                        + " min_temperature_celsius)"
                        + " VALUES ('" + company + "', 'NOT-CONTROLLED', 'Not controlled', 1000, 10, false, 2)"));
        assertViolates(CHECK_VIOLATION, () -> execute(
                "INSERT INTO tms.vehicle_type"
                        + " (company_id, code, name, max_weight_kg, max_volume_m3, temperature_controlled,"
                        + " min_temperature_celsius, max_temperature_celsius)"
                        + " VALUES ('" + company + "', 'INVERTED', 'Inverted range', 1000, 10, true, 5, 2)"));

        execute("INSERT INTO tms.vehicle_type"
                + " (company_id, code, name, max_weight_kg, max_volume_m3, temperature_controlled,"
                + " min_temperature_celsius, max_temperature_celsius)"
                + " VALUES ('" + company + "', 'REEFER', 'Reefer', 9000, 25, true, -18, -2)");
        assertThat(count("SELECT count(*) FROM tms.vehicle_type WHERE code = 'REEFER'"
                + " AND min_temperature_celsius = -18 AND max_temperature_celsius = -2")).isOne();
    }

    // --- vehicle -----------------------------------------------------------------

    @Test
    @DisplayName("vehicle codes and license plates are unique per company, and free to repeat across companies")
    void vehicleCodeAndPlateAreScopedToTheirCompany() throws SQLException {
        UUID organization = insertOrganization("FL-ORG");
        UUID companyA = insertCompany(organization, "FL-A");
        UUID companyB = insertCompany(organization, "FL-B");
        UUID typeA = insertVehicleType(companyA, "TYPE-A", "10000.00", "40.000");
        UUID typeB = insertVehicleType(companyB, "TYPE-B", "10000.00", "40.000");

        insertVehicle(companyA, "TRUCK-1", "ABC-123", typeA, null);
        insertVehicle(companyB, "TRUCK-1", "ABC-123", typeB, null); // same code/plate, different company: allowed
        assertViolates(UNIQUE_VIOLATION, () -> insertVehicle(companyA, "TRUCK-1", "XYZ-999", typeA, null));
        assertViolates(UNIQUE_VIOLATION, () -> insertVehicle(companyA, "TRUCK-2", "ABC-123", typeA, null));
    }

    @Test
    @DisplayName("license plate must already be normalized: upper case, trimmed, and a plausible shape")
    void licensePlateMustBeNormalized() throws SQLException {
        UUID organization = insertOrganization("FL-ORG");
        UUID company = insertCompany(organization, "FL-A");
        UUID type = insertVehicleType(company, "TYPE-A", "10000.00", "40.000");

        assertViolates(CHECK_VIOLATION, () -> insertVehicle(company, "LOWER-PLATE", "abc-123", type, null));
        assertViolates(CHECK_VIOLATION, () -> insertVehicle(company, "SHORT-PLATE", "AB", type, null));
    }

    @Test
    @DisplayName("a vehicle must reference a real vehicle type; carrier is optional")
    void vehicleTypeIsMandatoryCarrierIsOptional() throws SQLException {
        UUID organization = insertOrganization("FL-ORG");
        UUID company = insertCompany(organization, "FL-A");

        assertViolates(FOREIGN_KEY_VIOLATION,
                () -> insertVehicle(company, "ORPHAN-TYPE", "ORP-001", UUID.randomUUID(), null));
        assertViolates(NOT_NULL_VIOLATION, () -> execute(
                "INSERT INTO tms.vehicle (company_id, code, license_plate) VALUES ('" + company
                        + "', 'NO-TYPE', 'NOT-001')"));

        UUID type = insertVehicleType(company, "TYPE-A", "10000.00", "40.000");
        UUID vehicleId = insertVehicle(company, "OWNED", "OWN-001", type, null);
        assertThat(count("SELECT count(*) FROM tms.vehicle WHERE id = '" + vehicleId + "' AND carrier_id IS NULL"))
                .isOne();
    }

    @Test
    @DisplayName("a vehicle's carrier and vehicle type must both belong to the vehicle's own company")
    void vehicleCarrierAndTypeMustBelongToTheSameCompany() throws SQLException {
        UUID organization = insertOrganization("FL-ORG");
        UUID companyA = insertCompany(organization, "FL-A");
        UUID companyB = insertCompany(organization, "FL-B");
        UUID typeA = insertVehicleType(companyA, "TYPE-A", "10000.00", "40.000");
        UUID typeB = insertVehicleType(companyB, "TYPE-B", "10000.00", "40.000");
        UUID carrierA = insertCarrier(companyA, "CARRIER-A", "RUC", "20100000001");
        UUID carrierB = insertCarrier(companyB, "CARRIER-B", "RUC", "20100000002");

        // a company-B carrier assigned to a company-A vehicle is refused even though both rows exist
        assertViolates(FOREIGN_KEY_VIOLATION,
                () -> insertVehicle(companyA, "CROSS-CARRIER", "CRX-001", typeA, carrierB));
        // a company-B vehicle type assigned to a company-A vehicle is refused the same way
        assertViolates(FOREIGN_KEY_VIOLATION,
                () -> insertVehicle(companyA, "CROSS-TYPE", "CRX-002", typeB, carrierA));

        // same-company assignment succeeds
        UUID vehicleId = insertVehicle(companyA, "SAME-COMPANY", "SAM-001", typeA, carrierA);
        assertThat(count("SELECT count(*) FROM tms.vehicle WHERE id = '" + vehicleId + "' AND carrier_id = '"
                + carrierA + "' AND vehicle_type_id = '" + typeA + "'")).isOne();
    }

    @Test
    @DisplayName("override capacities must be positive/nonnegative when present, and availability_status is restricted")
    void vehicleOverridesAndAvailabilityAreValidated() throws SQLException {
        UUID organization = insertOrganization("FL-ORG");
        UUID company = insertCompany(organization, "FL-A");
        UUID type = insertVehicleType(company, "TYPE-A", "10000.00", "40.000");

        assertViolates(CHECK_VIOLATION, () -> execute(
                "INSERT INTO tms.vehicle (company_id, code, license_plate, vehicle_type_id, max_weight_override_kg)"
                        + " VALUES ('" + company + "', 'BAD-OVERRIDE', 'BAD-001', '" + type + "', 0)"));
        assertViolates(CHECK_VIOLATION, () -> execute(
                "INSERT INTO tms.vehicle"
                        + " (company_id, code, license_plate, vehicle_type_id, max_pallets_override)"
                        + " VALUES ('" + company + "', 'BAD-PALLETS', 'BAD-002', '" + type + "', -1)"));
        assertViolates(CHECK_VIOLATION, () -> execute(
                "INSERT INTO tms.vehicle (company_id, code, license_plate, vehicle_type_id, availability_status)"
                        + " VALUES ('" + company + "', 'BAD-STATUS', 'BAD-003', '" + type + "', 'ON_FIRE')"));

        execute("INSERT INTO tms.vehicle"
                + " (company_id, code, license_plate, vehicle_type_id, max_pallets_override, availability_status)"
                + " VALUES ('" + company + "', 'ZERO-PALLETS-OK', 'ZPO-001', '" + type + "', 0, 'IN_MAINTENANCE')");
        assertThat(count("SELECT count(*) FROM tms.vehicle WHERE code = 'ZERO-PALLETS-OK'"
                + " AND max_pallets_override = 0 AND availability_status = 'IN_MAINTENANCE'")).isOne();
    }

    @Test
    @DisplayName("vehicle external_reference, when present, must not be blank")
    void vehicleExternalReferenceIsValidatedWhenPresent() throws SQLException {
        UUID organization = insertOrganization("FL-ORG");
        UUID company = insertCompany(organization, "FL-A");
        UUID type = insertVehicleType(company, "TYPE-A", "10000.00", "40.000");

        assertViolates(CHECK_VIOLATION, () -> execute(
                "INSERT INTO tms.vehicle (company_id, code, license_plate, vehicle_type_id, external_reference)"
                        + " VALUES ('" + company + "', 'BAD-EXTREF', 'BXR-001', '" + type + "', '')"));

        execute("INSERT INTO tms.vehicle (company_id, code, license_plate, vehicle_type_id, external_reference)"
                + " VALUES ('" + company + "', 'GOOD-EXTREF', 'GXR-001', '" + type + "', 'ERP-VEH-001')");
        assertThat(count("SELECT count(*) FROM tms.vehicle WHERE code = 'GOOD-EXTREF'"
                + " AND external_reference = 'ERP-VEH-001'")).isOne();
    }

    @Test
    @DisplayName("vehicle defaults to active/available and records who changed it")
    void vehicleDefaultsAndActorColumns() throws SQLException {
        UUID organization = insertOrganization("FL-ORG");
        UUID company = insertCompany(organization, "FL-A");
        UUID type = insertVehicleType(company, "TYPE-A", "10000.00", "40.000");
        UUID actor = insertAppUser("fl.vehicle.actor@example.test");

        UUID vehicleId = insertVehicle(company, "DEFAULTS", "DEF-001", type, null);
        assertThat(count("SELECT count(*) FROM tms.vehicle WHERE id = '" + vehicleId
                + "' AND active AND availability_status = 'AVAILABLE'")).isOne();

        execute("UPDATE tms.vehicle SET active = false, updated_by = '" + actor + "' WHERE id = '" + vehicleId + "'");
        assertThat(count("SELECT count(*) FROM tms.vehicle WHERE id = '" + vehicleId
                + "' AND NOT active AND updated_by = '" + actor + "'")).isOne();
    }

    // --- helpers -----------------------------------------------------------------

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

    private UUID insertCarrier(UUID companyId, String code, String taxIdType, String taxIdValue) throws SQLException {
        return insertReturningId("INSERT INTO tms.carrier (company_id, code, business_name, tax_id_type, tax_id_value)"
                + " VALUES ('" + companyId + "', '" + code + "', '" + code + " business', '" + taxIdType + "', '"
                + taxIdValue + "') RETURNING id");
    }

    private UUID insertVehicleType(UUID companyId, String code, String maxWeightKg, String maxVolumeM3)
            throws SQLException {
        return insertReturningId("INSERT INTO tms.vehicle_type (company_id, code, name, max_weight_kg, max_volume_m3)"
                + " VALUES ('" + companyId + "', '" + code + "', '" + code + " name', " + maxWeightKg + ", "
                + maxVolumeM3 + ") RETURNING id");
    }

    private UUID insertVehicle(UUID companyId, String code, String licensePlate, UUID vehicleTypeId, UUID carrierId)
            throws SQLException {
        String carrierValue = carrierId == null ? "NULL" : "'" + carrierId + "'";
        return insertReturningId("INSERT INTO tms.vehicle (company_id, code, license_plate, vehicle_type_id, carrier_id)"
                + " VALUES ('" + companyId + "', '" + code + "', '" + licensePlate + "', '" + vehicleTypeId + "', "
                + carrierValue + ") RETURNING id");
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
