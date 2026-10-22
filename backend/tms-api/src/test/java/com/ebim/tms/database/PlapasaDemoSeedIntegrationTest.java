package com.ebim.tms.database;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ebim.tms.integration.domain.IntegrationSecrets;
import com.ebim.tms.shared.api.ApiHeaders;
import com.ebim.tms.shared.security.TestJwts;
import com.jayway.jsonpath.JsonPath;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Locale;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Keeps the PLAPASA demo tenant honest: {@code supabase/seeds/plapasa_demo_seed.sql} (plus its
 * fallback material profiles) applied twice to a database Flyway built from V1, the ERP's orders
 * from {@code scripts/demo/plapasa/orders.json} sent through the real integration API, and the
 * numbers the demo guide ({@code docs/demo/PLAPASA_TMS_DEMO.md}) quotes asserted against the real
 * planning and dispatch services.
 *
 * <p>The QAS seed is applied first, so QAS-LIMA exists beside PLAPASA and tenant isolation is
 * measured against a real neighbour. {@code auth.users} is a stub with two of the three PLAPASA
 * accounts, so the "linked by email, null when missing" rule is exercised in both directions.
 *
 * <p>The clock is fixed at the morning of the demo's dispatch date: the orders carry literal service
 * dates, and an order whose date has passed is (correctly) not released by an integration.
 */
@EnabledIf(value = DockerAvailability.CONDITION, disabledReason = DockerAvailability.DISABLED_REASON)
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(PlapasaDemoSeedIntegrationTest.Overrides.class)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class PlapasaDemoSeedIntegrationTest {

    private static final Path ROOT = MigrationScripts.repositoryRoot();
    private static final Path SEED = ROOT.resolve("supabase/seeds/plapasa_demo_seed.sql");
    private static final Path FALLBACK = ROOT.resolve("supabase/seeds/plapasa_demo_material_profiles_fallback.sql");
    private static final Path QAS_SEED = ROOT.resolve("supabase/seeds/qas_seed.sql");
    private static final Path ORDERS = ROOT.resolve("scripts/demo/plapasa/orders.json");
    private static final Path GUIDE = ROOT.resolve("scripts/demo/plapasa/remission_guide.json");

    private static final UUID ADMIN_AUTH = UUID.fromString("5a5a0000-0000-4000-8000-0000000000a1");
    private static final UUID PLANNER_AUTH = UUID.fromString("5a5a0000-0000-4000-8000-0000000000b2");
    private static final UUID FINANCE_AUTH = UUID.fromString("5a5a0000-0000-4000-8000-0000000000c3");

    /** 2026-10-20 09:00 in Guayaquil: the morning the orders of the first run are dispatched. */
    private static final Instant NOW = Instant.parse("2026-10-20T14:00:00Z");

    private static final ObjectMapper JSON = new ObjectMapper();

    private static String jdbcUrl;
    private static String erpToken;
    private static String ewmToken;
    private static String companyId;

    private static String runTomorrowId;
    private static String runTodayId;
    private static String agroTripId;
    private static String hardwareTripId;
    private static String hardwareShipment;

    @Autowired
    private MockMvc mockMvc;

    @TestConfiguration(proxyBeanMethods = false)
    static class Overrides {
        @Bean
        @Primary
        JwtDecoder testJwtDecoder() {
            return TestJwts.decoder();
        }

        @Bean
        @Primary
        Clock plapasaDemoClock() {
            return Clock.fixed(NOW, ZoneId.of("UTC"));
        }
    }

    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        jdbcUrl = PostgresTestDatabase.createMigratedDatabase("tms_plapasa_demo_seed");
        execute("""
                CREATE SCHEMA auth;
                CREATE TABLE auth.users (id uuid PRIMARY KEY, email text NOT NULL);
                INSERT INTO auth.users (id, email) VALUES
                    ('%s', 'admin@plapasa.demo'), ('%s', 'planner@plapasa.demo'), ('%s', 'finanzas@plapasa.demo');
                """.formatted(ADMIN_AUTH, PLANNER_AUTH, FINANCE_AUTH));
        execute(read(QAS_SEED));
        for (int run = 0; run < 2; run++) {
            execute(read(SEED));
            execute(read(FALLBACK));
        }
        companyId = value("SELECT id FROM tms.company WHERE code = 'QAS-PLAPASA'");
        erpToken = credential("ERP PLAPASA (prueba)", "integration.order:write", "integration.document:write");
        ewmToken = credential("EWM PLAPASA (prueba)", "integration.shipment:read", "integration.dispatch:write",
                "integration.warehouse-milestone:write", "integration.material:write", "integration.order:read",
                "integration.warehouse-order-event:write");
        registry.add("spring.datasource.url", () -> jdbcUrl);
        registry.add("spring.datasource.username", PostgresTestDatabase::username);
        registry.add("spring.datasource.password", PostgresTestDatabase::password);
    }

    // --- 1. the seed -----------------------------------------------------------------------

    @Test
    @Order(1)
    @DisplayName("1. two runs of the seed leave one PLAPASA tenant, configured for Ecuador")
    void seedIsCompleteAndIdempotent() {
        assertThat(value("SELECT count(*) FROM tms.organization WHERE code = 'QAS-PLAPASA'")).isEqualTo("1");
        assertThat(value("SELECT concat_ws('|', c.name, c.time_zone, c.tax_identifier, s.default_country,"
                + " s.dispatch_confirmation_mode, s.reference_load_height_m, s.load_floor_fill_factor,"
                + " s.cost_allocation_basis, s.order_number_prefix, s.shipment_number_prefix)"
                + " FROM tms.company c JOIN tms.company_settings s ON s.company_id = c.id WHERE c.code = 'QAS-PLAPASA'"))
                .isEqualTo("PLAPASA (demo)|America/Guayaquil|0999999999001|EC|HYBRID|2.40|0.850|EFFECTIVE_VOLUME|OT-|VJ-");
        // No fiscal override: default_country EC is what puts the company in Ecuador's regime (ADR-027).
        assertThat(value("SELECT count(*) FROM tms.company_settings s JOIN tms.company c ON c.id = s.company_id"
                + " WHERE c.code = 'QAS-PLAPASA' AND (s.fiscal_country IS NOT NULL OR s.vat_rate IS NOT NULL"
                + " OR s.freight_vat_rate IS NOT NULL)")).isEqualTo("0");
        assertThat(countIn("company_tax_withholding_setting")).isZero();

        assertThat(countIn("location")).isEqualTo(5);
        assertThat(countIn("zone")).isEqualTo(3);
        assertThat(countIn("route")).isEqualTo(4);
        assertThat(countIn("route_stop")).isEqualTo(4);
        assertThat(countIn("carrier")).isEqualTo(1);
        assertThat(countIn("vehicle_type")).isEqualTo(5);
        assertThat(countIn("vehicle")).isEqualTo(4);
        assertThat(countIn("driver")).isEqualTo(3);
        assertThat(countIn("rate_card")).isEqualTo(1);
        assertThat(countIn("material_profile")).isEqualTo(25);
        assertThat(countIn("location_resource")).isEqualTo(3);
        assertThat(countIn("resource_calendar")).isEqualTo(18);
        // V72: four vehicles' and three drivers' papers, a five-type policy and the 30-day window; the
        // two "about to expire" and the one expired warn - none blocks a trip of the demo.
        assertThat(countIn("fleet_document")).isEqualTo(21);
        assertThat(countIn("fleet_document_policy")).isEqualTo(5);
        assertThat(value("SELECT expiry_alert_days FROM tms.fleet_compliance_settings WHERE company_id = '"
                + companyId + "'")).isEqualTo("30");
        assertThat(value("SELECT string_agg(v.code || ':' || d.document_type, ',' ORDER BY v.code)"
                + " FROM tms.fleet_document d JOIN tms.vehicle v ON v.id = d.vehicle_id"
                + " WHERE d.company_id = '" + companyId + "' AND d.expires_on < DATE '2026-12-01'"))
                .isEqualTo("CAM-02:TECHNICAL_INSPECTION,CAM-04:CARGO_INSURANCE");
        assertThat(value("SELECT enforcement FROM tms.fleet_document_policy WHERE company_id = '" + companyId
                + "' AND document_type = 'CARGO_INSURANCE'")).isEqualTo("WARN");
        // V73: every vehicle type has its demo factor, the 10 t truck at 0.9 kg CO2e/km.
        assertThat(value("SELECT count(*) FROM tms.vehicle_type WHERE company_id = '" + companyId
                + "' AND emission_factor_kg_co2e_per_km IS NOT NULL AND fuel_type = 'DIESEL'")).isEqualTo("5");
        assertThat(value("SELECT emission_factor_kg_co2e_per_km FROM tms.vehicle_type WHERE company_id = '"
                + companyId + "' AND code = 'CAMION-10T'")).isEqualTo("0.9000");
        assertThat(value("SELECT string_agg(license_plate, ',' ORDER BY code) FROM tms.vehicle"
                + " WHERE company_id = '" + companyId + "'")).isEqualTo("GBA-1234,GBC-5678,GSC-2468,GSD-1357");
        assertThat(value("SELECT concat_ws('|', external_system, external_reference, country) FROM tms.location"
                + " WHERE company_id = '" + companyId + "' AND code = 'GYE-CD'")).isEqualTo("EWM|GYE-CD|EC");

        // People: linked by email when the account exists, null (cannot sign in yet) when not.
        assertThat(value("SELECT string_agg(email || '=' || (auth_user_id IS NOT NULL), ',' ORDER BY email)"
                + " FROM tms.app_user WHERE email LIKE '%@plapasa.demo'"))
                .isEqualTo("admin@plapasa.demo=true,finanzas@plapasa.demo=true,planner@plapasa.demo=true,"
                        + "transportista.trlit@plapasa.demo=false");
        assertThat(value("SELECT string_agg(u.email || ':' || r.code || ':' || coalesce(ca.code, '-'), ','"
                + " ORDER BY u.email) FROM tms.membership m JOIN tms.app_user u ON u.id = m.app_user_id"
                + " JOIN tms.membership_role mr ON mr.membership_id = m.id JOIN tms.role r ON r.id = mr.role_id"
                + " LEFT JOIN tms.carrier ca ON ca.id = m.carrier_id WHERE u.email LIKE '%@plapasa.demo'"))
                .isEqualTo("admin@plapasa.demo:ORGANIZATION_ADMIN:-,finanzas@plapasa.demo:COMPANY_ADMIN:-,"
                        + "planner@plapasa.demo:PLANNER:-,"
                        + "transportista.trlit@plapasa.demo:CARRIER_OPERATOR:TRLIT");
    }

    @Test
    @Order(2)
    @DisplayName("2. the carrier's RUC is a valid Ecuadorian private-company RUC (mod 11) and the cédulas are valid")
    void identifiersAreValid() {
        String ruc = value("SELECT tax_id_value FROM tms.carrier WHERE company_id = '" + companyId + "' AND code = 'TRLIT'");
        assertThat(ruc).isEqualTo("0990000034001");
        assertThat(validPrivateCompanyRuc(ruc)).isTrue();
        assertThat(validPrivateCompanyRuc(value("SELECT tax_identifier FROM tms.company WHERE id = '" + companyId + "'")))
                .isTrue();
        // The EWM blueprint's TRLIT tax id does not pass the check: the reason the seed does not copy it.
        assertThat(validPrivateCompanyRuc("0990000000301")).isFalse();
        for (String cedula : value("SELECT string_agg(document_number, ',') FROM tms.driver WHERE company_id = '"
                + companyId + "'").split(",")) {
            assertThat(validCedula(cedula)).as(cedula).isTrue();
        }
    }

    @Test
    @Order(3)
    @DisplayName("3. RLS: the runtime role sees PLAPASA's rows only inside PLAPASA, and QAS-LIMA's only inside QAS-LIMA")
    void tenantIsolation() throws SQLException {
        String lima = value("SELECT id FROM tms.company WHERE code = 'QAS-LIMA'");
        try (Connection connection = PostgresTestDatabase.connect(jdbcUrl);
                Statement statement = connection.createStatement()) {
            connection.setAutoCommit(false);
            statement.execute("SET LOCAL ROLE tms_app");
            statement.execute("SELECT set_config('tms.company_id', '" + lima + "', true)");
            assertThat(scalar(statement, "SELECT count(*) FROM tms.location")).isEqualTo("4");
            assertThat(scalar(statement, "SELECT count(*) FROM tms.location WHERE code LIKE 'CLI-%' OR code = 'GYE-CD'"))
                    .isEqualTo("0");
            assertThat(scalar(statement, "SELECT count(*) FROM tms.material_profile")).isEqualTo("0");
            assertThat(scalar(statement, "SELECT count(*) FROM tms.carrier WHERE code = 'TRLIT'")).isEqualTo("0");

            statement.execute("SELECT set_config('tms.company_id', '" + companyId + "', true)");
            assertThat(scalar(statement, "SELECT count(*) FROM tms.location")).isEqualTo("5");
            assertThat(scalar(statement, "SELECT count(*) FROM tms.location WHERE code LIKE 'QAS-%'")).isEqualTo("0");
            assertThat(scalar(statement, "SELECT count(*) FROM tms.material_profile")).isEqualTo("25");
            connection.rollback();
        }
    }

    // --- 2. the ERP sends the orders --------------------------------------------------------

    @Test
    @Order(4)
    @DisplayName("4. the ERP's orders arrive through the integration API; a redelivery is UNCHANGED")
    void ordersArrive() throws Exception {
        String body = batchBody();
        mockMvc.perform(post("/integration/v1/orders/batch")
                        .header("Authorization", "Bearer " + erpToken)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.submitted").value(6))
                .andExpect(jsonPath("$.failed").value(0))
                .andExpect(jsonPath("$.results[0].result.outcome").value("CREATED"));
        mockMvc.perform(post("/integration/v1/orders/batch")
                        .header("Authorization", "Bearer " + erpToken)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.failed").value(0))
                .andExpect(jsonPath("$.results[*].result.outcome").value(
                        org.hamcrest.Matchers.everyItem(org.hamcrest.Matchers.is("UNCHANGED"))));

        assertThat(value("SELECT string_agg(external_reference || '=' || status, ',' ORDER BY external_reference)"
                + " FROM tms.transport_order WHERE company_id = '" + companyId + "'"))
                .isEqualTo("SO-2026-10021=NOT_READY,SO-2026-10034=READY_FOR_PLANNING,SO-2026-10040=READY_FOR_PLANNING,"
                        + "SO-2026-10041=READY_FOR_PLANNING,SO-2026-10045=READY_FOR_PLANNING,"
                        + "SO-2026-10046=READY_FOR_PLANNING");
        assertThat(value("SELECT string_agg(DISTINCT external_source, ',') FROM tms.transport_order"
                + " WHERE company_id = '" + companyId + "'")).isEqualTo("MANUAL");
        // Every line took the warehouse's profile: no line carried its own physical block.
        assertThat(value("SELECT count(*) FROM tms.transport_order_line l JOIN tms.transport_order o ON o.id = l.order_id"
                + " WHERE o.company_id = '" + companyId + "' AND l.physical_source IS DISTINCT FROM 'PROFILE'"))
                .isEqualTo("0");
    }

    @Test
    @Order(5)
    @DisplayName("5. the nesting effect: 1,000 cups are 10 stacks; 5,000 occupy 0.75 m3, not 9.50 m3")
    void geometryOfTheOrders() {
        assertThat(lineGeometry("SO-2026-10045", "PLP-571")).isEqualTo("MEASURED|50|1.363|0.7514|0.5513|9.5000");
        assertThat(orderGeometry("SO-2026-10045")).isEqualTo("9.5000|0.7514|0.5513|1.363|0");
        assertThat(lineGeometry("SO-2026-10046", "PLP-571")).isEqualTo("MEASURED|40|1.363|0.6011|0.4410|7.6000");
        assertThat(lineGeometry("SO-2026-10046", "PLP-N105")).isEqualTo("MEASURED|4|1.005|0.3740|0.3721|3.1320");
        assertThat(orderGeometry("SO-2026-10046")).isEqualTo("10.7320|0.9751|0.8131|1.363|0");

        // Stackable crates: 8 per stack at the 2.40 m reference height (the profile allows 10).
        assertThat(lineGeometry("SO-2026-10034", "PLP-1601")).isEqualTo("MEASURED|25|2.320|10.6575|4.5938|10.6600");
        assertThat(orderGeometry("SO-2026-10034")).isEqualTo("25.8200|25.8127|11.8456|2.320|0");

        // The hardware order: 40 chairs nest 25 to a stack (2.35 m), the utility boxes stack 6 high.
        // Declared 20.76 m3, occupies 4.58 m3: the chairs alone drop from 13.43 m3 to 1.51 m3.
        assertThat(orderGeometry("SO-2026-10040")).isEqualTo("20.7570|4.5834|2.3319|2.350|0");
        assertThat(lineGeometry("SO-2026-10040", "PLP-700")).isEqualTo("MEASURED|2|2.350|1.5125|0.7378|13.4280");

        // What the warehouse does not declare is counted, never estimated (EWM rule 199).
        assertThat(orderGeometry("SO-2026-10041")).isEqualTo("6.7128|4.6893|0.1071|1.980|2");
        assertThat(value("SELECT string_agg(l.material_code || ':' || l.geometry_reason, ',' ORDER BY l.line_number)"
                + " FROM tms.transport_order_line l JOIN tms.transport_order o ON o.id = l.order_id"
                + " WHERE o.external_reference = 'SO-2026-10041' AND l.geometry_status = 'UNMEASURED'"))
                .isEqualTo("PLP-1100-5:MISSING_STACK_MODE,PLP-803:MISSING_STACK_MODE");
    }

    // --- 3. planning ---------------------------------------------------------------------------

    @Test
    @Order(6)
    @DisplayName("6. the two cup orders that naive volume would split across two vans fit one van")
    void twoNestableOrdersInOneVan() throws Exception {
        BigDecimal naive = new BigDecimal(value("SELECT sum(total_volume_m3) FROM tms.transport_order"
                + " WHERE external_reference IN ('SO-2026-10045', 'SO-2026-10046')"));
        BigDecimal van = new BigDecimal(value("SELECT max_volume_m3 FROM tms.vehicle_type WHERE company_id = '"
                + companyId + "' AND code = 'NHR-35'"));
        assertThat(naive).isGreaterThan(van); // 20.2320 > 17.20: yesterday, two vans

        runTomorrowId = createRun("2026-10-21");
        String trip = createTrip(runTomorrowId, "CAM-03", "2026-10-21T07:00:00-05:00");
        assign(trip, "SO-2026-10045").andExpect(status().isOk());
        assign(trip, "SO-2026-10046").andExpect(status().isOk());

        mockMvc.perform(asPlanner(get("/api/v1/planning/trips/" + trip + "/capacity")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.orderCount").value(2))
                .andExpect(jsonPath("$.withinCapacity").value(true))
                .andExpect(jsonPath("$.volume.used").value(1.7265))
                .andExpect(jsonPath("$.volume.limit").value(17.2))
                .andExpect(jsonPath("$.floor.used").value(1.3644))
                .andExpect(jsonPath("$.height.used").value(1.363));
    }

    @Test
    @Order(7)
    @DisplayName("7. re-stacking per vehicle: the 2.20 m low box takes the hardware order with 22 chairs a stack,"
            + " and refuses the crates only because, re-stacked lower, they need more floor than it has")
    void reStackingOnTheLowBox() throws Exception {
        runTodayId = createRun("2026-10-20");
        hardwareTripId = createTrip(runTodayId, "CAM-04", "2026-10-20T10:00:00-05:00");

        // The crates: 8 a stack (2.32 m) at the reference height; re-stacked for 2.20 m they are 7, 7 and 6
        // a stack and need 13.09 m2 of floor against the box's 12.24. Refused for the floor, not the height.
        String refusal = assign(hardwareTripId, "SO-2026-10034")
                .andExpect(status().isConflict())
                .andReturn().getResponse().getContentAsString();
        assertThat(refusal).contains("floor area 13.093 m2 exceeds the capacity of 12.24 m2")
                .doesNotContain("stack height");

        // The hardware order: 25 nested chairs make 2.35 m at the reference height; the low box re-stacks
        // them 22 a stack (2.17 m) and takes the order. Same two stacks, so the same floor.
        assign(hardwareTripId, "SO-2026-10040").andExpect(status().isOk());
        mockMvc.perform(asPlanner(get("/api/v1/planning/trips/" + hardwareTripId + "/capacity")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.withinCapacity").value(true))
                .andExpect(jsonPath("$.height.used").value(2.17))
                .andExpect(jsonPath("$.height.limit").value(2.2))
                .andExpect(jsonPath("$.floor.used").value(2.3319))
                .andExpect(jsonPath("$.floor.limit").value(12.24))
                .andExpect(jsonPath("$.volume.used").value(4.5834))
                .andExpect(jsonPath("$.bindingDimension").value("HEIGHT"));

        agroTripId = createTrip(runTodayId, "CAM-01", "2026-10-20T10:30:00-05:00");
        assign(agroTripId, "SO-2026-10034").andExpect(status().isOk());
        mockMvc.perform(asPlanner(get("/api/v1/planning/trips/" + agroTripId + "/capacity")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.withinCapacity").value(true))
                .andExpect(jsonPath("$.floor.used").value(11.8456))
                .andExpect(jsonPath("$.floor.limit").value(12.648))
                .andExpect(jsonPath("$.height.used").value(2.32))
                .andExpect(jsonPath("$.volume.used").value(25.8127))
                .andExpect(jsonPath("$.bindingDimension").value("HEIGHT"));
    }

    @Test
    @Order(8)
    @DisplayName("8. confirmed: the warehouse's plan names the orders by the EWM's own key and the origin by GYE-CD")
    void confirmedPlanAsTheWarehouseReadsIt() throws Exception {
        route(hardwareTripId, "RUTA-GYE-SUR");
        route(agroTripId, "RUTA-LOS-RIOS");
        driver(hardwareTripId, "COND-02");
        driver(agroTripId, "COND-01");
        mockMvc.perform(asPlanner(post("/api/v1/planning/runs/" + runTodayId + "/confirm"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"version\":" + runVersion(runTodayId) + "}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.run.status").value("CONFIRMED"));
        hardwareShipment = value("SELECT shipment_number FROM tms.trip WHERE id = '" + hardwareTripId + "'");
        assertThat(hardwareShipment).startsWith("VJ-");

        mockMvc.perform(get("/integration/v1/shipments/" + hardwareShipment).header("Authorization", "Bearer " + ewmToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.shipment.originCode").value("GYE-CD"))
                .andExpect(jsonPath("$.shipment.originExternalReference").value("GYE-CD"))
                .andExpect(jsonPath("$.shipment.routeCode").value("RUTA-GYE-SUR"))
                .andExpect(jsonPath("$.shipment.carrierCode").value("TRLIT"))
                .andExpect(jsonPath("$.shipment.vehicleLicensePlate").value("GSD-1357"))
                .andExpect(jsonPath("$.orders[0].externalSource").value("MANUAL"))
                .andExpect(jsonPath("$.orders[0].externalReference").value("SO-2026-10040"))
                .andExpect(jsonPath("$.orders[0].weightKg").value(285.8))
                .andExpect(jsonPath("$.shipment.vehicleTypeCode").value("FURGON-5T-BAJO"))
                .andExpect(jsonPath("$.orders[0].volumeM3").value(4.5834))
                .andExpect(jsonPath("$.stops[0].serviceWindowStart").value("08:00:00"))
                .andExpect(jsonPath("$.stops[0].locationCode").value("CLI-FER-01"));
    }

    @Test
    @Order(9)
    @DisplayName("9. the warehouse's dispatch (SLS) of the hardware order dispatches the trip and matches the plan")
    void warehouseDispatch() throws Exception {
        String body = """
                {"sourceSystem": "EWM_EBIM", "dispatchReference": "SLS-000004", "revision": 1,
                 "transportReference": "%s", "loadReference": "CRG-000004", "warehouseCode": "GYE-CD",
                 "actualDispatchAt": "2026-10-20T09:00:00-05:00",
                 "carrier": {"code": "TRLIT", "name": "Transportes del Litoral S.A. (demo)"},
                 "vehicle": {"licensePlate": "GSD-1357", "type": "FURGON 5T CAJA BAJA"},
                 "driver": {"name": "Conductor Demo Dos", "documentNumber": "0920000015"},
                 "sealNumber": "PREC-000790",
                 "orders": [{"externalSource": "MANUAL", "externalReference": "SO-2026-10040",
                             "warehouseOrderNumber": "ORR-STD-00000003", "status": "SHIPPED",
                             "lines": [
                               {"lineNumber": 1, "materialCode": "PLP-1222", "quantity": 30, "uom": "UN"},
                               {"lineNumber": 2, "materialCode": "PLP-311", "quantity": 24, "uom": "UN"},
                               {"lineNumber": 3, "materialCode": "PLP-700", "quantity": 40, "uom": "UN"},
                               {"lineNumber": 4, "materialCode": "PLP-111", "quantity": 60, "uom": "UN"}]}]}
                """.formatted(hardwareShipment);
        mockMvc.perform(post("/integration/v1/dispatch-confirmations")
                        .header("Authorization", "Bearer " + ewmToken)
                        .header(ApiHeaders.IDEMPOTENCY_KEY, "ewm-disp-SLS-000004-r1")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.outcome").value("APPLIED"))
                .andExpect(jsonPath("$.verificationStatus").value("MATCHED"));
        assertThat(value("SELECT status FROM tms.trip WHERE id = '" + hardwareTripId + "'")).isEqualTo("IN_TRANSIT");
        assertThat(value("SELECT status FROM tms.transport_order WHERE external_reference = 'SO-2026-10040'"))
                .isEqualTo("IN_EXECUTION");
    }

    @Test
    @Order(11)
    @DisplayName("11. TRLIT's USD rate card prices the dispatched trip, including the loading metres")
    void tripCost() throws Exception {
        String response = mockMvc.perform(asPlanner(post("/api/v1/rates/trips/" + hardwareTripId + "/cost/estimate")))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat((String) JsonPath.read(response, "$.currency")).isEqualTo("USD");
        assertThat((String) JsonPath.read(response, "$.rateCardCode")).isEqualTo("TRLIT-2026-USD");
        // 45.00 per trip + 0.85/km over the estimated 19.2 km (16.30) + 6.50 per loading metre: 2.3319 m2
        // of floor over a 2.40 m x 0.85 load width = 1.14 loading metres (7.43). Total 68.73.
        assertThat(response).contains("\"component\":\"LOADING_METERS\",\"status\":\"APPLIED\"")
                .contains("\"quantitySource\":\"EFFECTIVE_GEOMETRY\"");
        assertThat(JsonPath.<Double>read(response, "$.estimatedAmount")).isEqualTo(68.73);
        String agro = mockMvc.perform(asPlanner(post("/api/v1/rates/trips/" + agroTripId + "/cost/estimate")))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        // Babahoyo: 45.00 + 76.1 km (64.69) + 11.8456 m2 / (2.40 x 0.85) = 5.81 loading metres (37.75).
        assertThat(JsonPath.<Double>read(agro, "$.estimatedAmount")).isEqualTo(147.44);
    }

    @Test
    @Order(12)
    @DisplayName("12. cancelling SO-2026-10041's transport publishes ORDER_CANCELLED, readable by the warehouse by its own key")
    void cancellationCrossesAsASignal() throws Exception {
        String order = value("SELECT id FROM tms.transport_order WHERE external_reference = 'SO-2026-10041'");
        String number = value("SELECT order_number FROM tms.transport_order WHERE id = '" + order + "'");
        mockMvc.perform(asPlanner(post("/api/v1/orders/" + order + "/cancel"))
                        .param("reason", "El cliente pospuso la compra (demo)"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLED"));
        assertThat(value("SELECT count(*) FROM tms.business_outbox_event WHERE company_id = '" + companyId
                + "' AND event_type = 'ORDER_CANCELLED'")).isEqualTo("1");
        mockMvc.perform(get("/integration/v1/orders/" + number).header("Authorization", "Bearer " + ewmToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLED"))
                .andExpect(jsonPath("$.externalSource").value("MANUAL"))
                .andExpect(jsonPath("$.externalReference").value("SO-2026-10041"))
                .andExpect(jsonPath("$.cancelReason").value("El cliente pospuso la compra (demo)"));
    }

    @Test
    @Order(13)
    @DisplayName("13. the ERP's SRI remission guide (06) links to the hardware order and shows on its trip")
    void remissionGuide() throws Exception {
        ObjectNode guide = (ObjectNode) JSON.readTree(Files.readString(GUIDE, StandardCharsets.UTF_8));
        guide.remove("$nota");
        String body = JSON.writeValueAsString(guide);
        mockMvc.perform(post("/integration/v1/logistics-documents")
                        .header("Authorization", "Bearer " + erpToken)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.outcome").value("CREATED"))
                .andExpect(jsonPath("$.linkedOrders").value(1))
                .andExpect(jsonPath("$.unknownOrders.length()").value(0));
        mockMvc.perform(post("/integration/v1/logistics-documents")
                        .header("Authorization", "Bearer " + erpToken)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.outcome").value("UNCHANGED"));
        String documents = mockMvc.perform(asPlanner(get("/api/v1/planning/trips/" + hardwareTripId + "/documents")))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(documents).contains("001-001-000000123").contains("REMISSION_GUIDE");
    }

    @Test
    @Order(14)
    @DisplayName("14. Ecuador by default: TRLIT's USD invoice at IVA 0% on freight, IR 1% code 310, payable net of it")
    void carrierInvoiceInEcuador() throws Exception {
        // No fiscal override in the seed: default_country EC is the whole configuration.
        mockMvc.perform(asAdmin(get("/api/v1/settlement/fiscal-settings")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.regime").value("EC"))
                .andExpect(jsonPath("$.fiscalCurrency").value("USD"))
                .andExpect(jsonPath("$.vatLabel").value("IVA"))
                .andExpect(jsonPath("$.vatRate").value(15.00))
                .andExpect(jsonPath("$.freightVatRate").value(0.00))
                .andExpect(jsonPath("$.withholdings[0].rate").value(1.00))
                .andExpect(jsonPath("$.withholdings[1].kind").value("EC_VAT_WITHHOLDING"))
                .andExpect(jsonPath("$.withholdings[1].enabled").value(false))
                .andExpect(jsonPath("$.withholdings[0].kind").value("EC_INCOME_TAX_WITHHOLDING"))
                .andExpect(jsonPath("$.withholdings[0].code").value("310"))
                .andExpect(jsonPath("$.withholdings[0].enabled").value(true));

        String carrier = value("SELECT id FROM tms.carrier WHERE company_id = '" + companyId + "' AND code = 'TRLIT'");
        String body = """
                {"carrierId": "%s", "documentNumber": "001-001-789", "issuerTaxId": "0990000034001",
                 "invoiceDate": "2026-10-20", "currency": "USD",
                 "subtotalAmount": 68.73, "taxRate": 0, "taxAmount": 0.00, "totalAmount": 68.73,
                 "lines": [{"tripId": "%s", "description": "Flete GYE-CD -> Ferretería El Constructor (VJ)",
                            "lineAmount": 68.73}]}
                """.formatted(carrier, hardwareTripId);
        String received = mockMvc.perform(asAdmin(post("/api/v1/settlement/invoices"))
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.invoiceNumber").value("001-001-000000789"))
                .andExpect(jsonPath("$.fiscal.fiscalCountry").value("EC"))
                .andExpect(jsonPath("$.fiscal.fiscalDocumentCode").value("01"))
                .andExpect(jsonPath("$.fiscal.taxRate").value(0.00))
                .andExpect(jsonPath("$.fiscal.withholdings.length()").value(1))
                .andExpect(jsonPath("$.fiscal.withholdings[0].code").value("310"))
                .andExpect(jsonPath("$.fiscal.withholdings[0].baseAmount").value(68.73))
                .andExpect(jsonPath("$.fiscal.withholdings[0].amount").value(0.69))
                .andExpect(jsonPath("$.fiscal.electronicValidation").value("EXTERNAL_DEPENDENCY"))
                .andReturn().getResponse().getContentAsString();
        String invoice = JsonPath.read(received, "$.id");

        mockMvc.perform(asAdmin(post("/api/v1/settlement/invoices/" + invoice + "/match")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("MATCHED"));
        // Four eyes: whoever recorded it cannot approve it; finanzas@ can.
        assertThat(mockMvc.perform(asAdmin(post("/api/v1/settlement/invoices/" + invoice + "/approve"))
                .contentType(MediaType.APPLICATION_JSON).content("{}")).andReturn().getResponse().getStatus())
                .isEqualTo(409);
        mockMvc.perform(post("/api/v1/settlement/invoices/" + invoice + "/approve")
                        .header("Authorization", "Bearer " + TestJwts.validFor(FINANCE_AUTH))
                        .header(ApiHeaders.COMPANY_ID, companyId)
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("APPROVED"));
        mockMvc.perform(asAdmin(post("/api/v1/settlement/invoices/" + invoice + "/export")))
                .andExpect(status().isOk());
        mockMvc.perform(asAdmin(get("/api/v1/settlement/payables/" + invoice)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.netAmount").value(68.73))
                .andExpect(jsonPath("$.withholdingTotalAmount").value(0.69))
                .andExpect(jsonPath("$.payableAmount").value(68.04))
                .andExpect(jsonPath("$.openBalance").value(68.04));
    }

    @Test
    @Order(15)
    @DisplayName("15. GYE-CD has three docks open Monday to Saturday 07:00-19:00, local to the plant")
    void plantDocks() throws Exception {
        String plant = value("SELECT id FROM tms.location WHERE company_id = '" + companyId + "' AND code = 'GYE-CD'");
        mockMvc.perform(asPlanner(get("/api/v1/appointments/resources").param("locationId", plant)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(3))
                .andExpect(jsonPath("$[0].code").value("MUE-01"))
                .andExpect(jsonPath("$[2].code").value("MUE-03"))
                .andExpect(jsonPath("$[2].defaultSlotMinutes").value(90))
                .andExpect(jsonPath("$[0].openingHours.length()").value(6))
                .andExpect(jsonPath("$[0].openingHours[0].day").value("MONDAY"))
                .andExpect(jsonPath("$[0].openingHours[5].day").value("SATURDAY"))
                .andExpect(jsonPath("$[0].openingHours[0].opensAt").value(org.hamcrest.Matchers.startsWith("07:00")))
                .andExpect(jsonPath("$[0].openingHours[0].closesAt").value(org.hamcrest.Matchers.startsWith("19:00")));
        String dock = value("SELECT r.id FROM tms.location_resource r WHERE r.company_id = '" + companyId
                + "' AND r.code = 'MUE-01'");

        // A planner books and does not configure (V41 §7): adding a door is an administrator's job.
        mockMvc.perform(asPlanner(post("/api/v1/appointments/resources")).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"locationId\":\"" + plant + "\",\"code\":\"MUE-09\",\"name\":\"Extra\","
                                + "\"resourceType\":\"DOCK\",\"defaultSlotMinutes\":60}"))
                .andExpect(status().isForbidden());

        // Wednesday 08:00 at the plant (UTC-5) is inside the calendar; Sunday has no row and is closed.
        mockMvc.perform(asPlanner(post("/api/v1/appointments")).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"resourceId\":\"" + dock + "\",\"purpose\":\"PICKUP\","
                                + "\"windowStart\":\"2026-10-21T08:00:00-05:00\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.durationMinutes").value(60));
        mockMvc.perform(asPlanner(post("/api/v1/appointments")).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"resourceId\":\"" + dock + "\",\"purpose\":\"PICKUP\","
                                + "\"windowStart\":\"2026-10-25T08:00:00-05:00\"}"))
                .andExpect(status().isConflict());
        // And 06:30 on a weekday is before the doors open.
        mockMvc.perform(asPlanner(post("/api/v1/appointments")).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"resourceId\":\"" + dock + "\",\"purpose\":\"PICKUP\","
                                + "\"windowStart\":\"2026-10-22T06:30:00-05:00\"}"))
                .andExpect(status().isConflict());
    }

    @Test
    @Order(10)
    @DisplayName("10. the seed carries no credential, and its fallback says what it is")
    void noSecretsInTheSeeds() {
        for (Path seed : new Path[] {SEED, FALLBACK}) {
            String sql = MigrationScripts.withoutComments(read(seed)).toLowerCase(Locale.ROOT);
            assertThat(sql).doesNotContain("password").doesNotContain("service_role").doesNotContain("secret")
                    .doesNotContain("insert into auth.").doesNotContain("integration_client");
        }
        assertThat(value("SELECT string_agg(DISTINCT source_system, ',') FROM tms.material_profile"
                + " WHERE company_id = '" + companyId + "'")).isEqualTo("PLAPASA_DEMO_SEED");
    }

    // --- helpers ---------------------------------------------------------------------------------

    private static String batchBody() {
        try {
            JsonNode file = JSON.readTree(Files.readString(ORDERS, StandardCharsets.UTF_8));
            ObjectNode batch = JSON.createObjectNode();
            ArrayNode orders = batch.putArray("orders");
            for (JsonNode order : file.get("orders")) {
                ObjectNode copy = ((ObjectNode) order).deepCopy();
                copy.remove("rebase");
                copy.put("externalSource", file.get("externalSource").asString());
                copy.put("originCode", file.get("originCode").asString());
                orders.add(copy);
            }
            return JSON.writeValueAsString(batch);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private String createRun(String date) throws Exception {
        String origin = value("SELECT id FROM tms.location WHERE company_id = '" + companyId + "' AND code = 'GYE-CD'");
        String response = mockMvc.perform(asPlanner(post("/api/v1/planning/runs"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"originId\":\"" + origin + "\",\"planningDate\":\"" + date + "\"}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return JsonPath.read(response, "$.run.id");
    }

    private String createTrip(String runId, String vehicleCode, String departure) throws Exception {
        String vehicle = value("SELECT id FROM tms.vehicle WHERE company_id = '" + companyId + "' AND code = '"
                + vehicleCode + "'");
        String response = mockMvc.perform(asPlanner(post("/api/v1/planning/runs/" + runId + "/trips"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"vehicleId\":\"" + vehicle + "\",\"plannedDepartureAt\":\"" + departure
                                + "\",\"version\":" + runVersion(runId) + "}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return JsonPath.read(response, "$.trip.id");
    }

    private static String vehicleId(String code) {
        return value("SELECT id FROM tms.vehicle WHERE company_id = '" + companyId + "' AND code = '" + code + "'");
    }

    private org.springframework.test.web.servlet.ResultActions assign(String tripId, String reference) throws Exception {
        String order = value("SELECT id FROM tms.transport_order WHERE external_reference = '" + reference + "'");
        return mockMvc.perform(asPlanner(post("/api/v1/planning/trips/" + tripId + "/assignments"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"orderId\":\"" + order + "\"}"));
    }

    private void route(String tripId, String routeCode) throws Exception {
        String route = value("SELECT id FROM tms.route WHERE company_id = '" + companyId + "' AND code = '"
                + routeCode + "'");
        mockMvc.perform(asPlanner(put("/api/v1/planning/trips/" + tripId + "/route"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"routeId\":\"" + route + "\",\"applySequence\":true,\"version\":"
                                + value("SELECT version FROM tms.trip WHERE id = '" + tripId + "'") + "}"))
                .andExpect(status().isOk());
    }

    private void driver(String tripId, String driverCode) throws Exception {
        String driver = value("SELECT id FROM tms.driver WHERE company_id = '" + companyId + "' AND code = '"
                + driverCode + "'");
        mockMvc.perform(asPlanner(put("/api/v1/planning/trips/" + tripId + "/driver"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"driverId\":\"" + driver + "\",\"version\":"
                                + value("SELECT version FROM tms.trip WHERE id = '" + tripId + "'") + "}"))
                .andExpect(status().isOk());
    }

    private static String runVersion(String runId) {
        return value("SELECT version FROM tms.planning_run WHERE id = '" + runId + "'");
    }

    private static MockHttpServletRequestBuilder asAdmin(MockHttpServletRequestBuilder request) {
        return request.header("Authorization", "Bearer " + TestJwts.validFor(ADMIN_AUTH))
                .header(ApiHeaders.COMPANY_ID, companyId);
    }

    private static MockHttpServletRequestBuilder asPlanner(MockHttpServletRequestBuilder request) {
        return request.header("Authorization", "Bearer " + TestJwts.validFor(PLANNER_AUTH))
                .header(ApiHeaders.COMPANY_ID, companyId);
    }

    private static String lineGeometry(String reference, String material) {
        return value("SELECT concat_ws('|', l.geometry_status, l.stacks, l.stack_height_m, l.effective_volume_m3,"
                + " l.floor_area_m2, l.line_volume_m3) FROM tms.transport_order_line l JOIN tms.transport_order o"
                + " ON o.id = l.order_id WHERE o.external_reference = '" + reference + "' AND l.material_code = '"
                + material + "'");
    }

    private static String orderGeometry(String reference) {
        return value("SELECT concat_ws('|', total_volume_m3, planning_volume_m3, floor_area_m2, max_stack_height_m,"
                + " geometry_unmeasured_lines) FROM tms.transport_order WHERE external_reference = '" + reference + "'");
    }

    private static long countIn(String table) {
        return Long.parseLong(value("SELECT count(*) FROM tms." + table + " WHERE company_id = '" + companyId + "'"));
    }

    private static String credential(String name, String... scopes) {
        String clientId = IntegrationSecrets.newClientId();
        String secretValue = IntegrationSecrets.newSecret();
        String row = value("INSERT INTO tms.integration_client (company_id, client_id, name, secret_hash) VALUES ('"
                + companyId + "', '" + clientId + "', '" + name + "', '" + IntegrationSecrets.hash(secretValue)
                + "') RETURNING id");
        for (String scope : scopes) {
            execute("INSERT INTO tms.integration_client_scope (integration_client_id, scope) VALUES ('" + row + "', '"
                    + scope + "')");
        }
        return IntegrationSecrets.toBearerToken(clientId, secretValue);
    }

    /** Ecuadorian private-company RUC: 13 digits, third 9, mod-11 check (weights 4,3,2,7,6,5,4,3,2), 001. */
    static boolean validPrivateCompanyRuc(String ruc) {
        if (ruc == null || !ruc.matches("^[0-9]{10}001$") || ruc.charAt(2) != '9') {
            return false;
        }
        int[] weights = {4, 3, 2, 7, 6, 5, 4, 3, 2};
        int sum = 0;
        for (int i = 0; i < 9; i++) {
            sum += (ruc.charAt(i) - '0') * weights[i];
        }
        int remainder = sum % 11;
        int check = remainder == 0 ? 0 : 11 - remainder;
        return check != 10 && check == ruc.charAt(9) - '0';
    }

    /** Ecuadorian cédula: 10 digits, third below 6, mod-10 check with coefficients 2,1,2,1,... */
    static boolean validCedula(String cedula) {
        if (cedula == null || !cedula.matches("^[0-9]{10}$") || cedula.charAt(2) >= '6') {
            return false;
        }
        int sum = 0;
        for (int i = 0; i < 9; i++) {
            int product = (cedula.charAt(i) - '0') * (i % 2 == 0 ? 2 : 1);
            sum += product > 9 ? product - 9 : product;
        }
        return (10 - sum % 10) % 10 == cedula.charAt(9) - '0';
    }

    private static String read(Path path) {
        try {
            return Files.readString(path, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("seed is missing or unreadable: " + path, e);
        }
    }

    private static void execute(String sql) {
        try (Connection connection = PostgresTestDatabase.connect(jdbcUrl);
                Statement statement = connection.createStatement()) {
            statement.execute(sql);
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String value(String sql) {
        try (Connection connection = PostgresTestDatabase.connect(jdbcUrl);
                Statement statement = connection.createStatement()) {
            return scalar(statement, sql);
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String scalar(Statement statement, String sql) throws SQLException {
        try (ResultSet resultSet = statement.executeQuery(sql)) {
            return resultSet.next() ? resultSet.getString(1) : null;
        }
    }
}
