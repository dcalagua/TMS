package com.ebim.tms.settlement.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ebim.tms.database.DockerAvailability;
import com.ebim.tms.database.PostgresTestDatabase;
import com.ebim.tms.shared.api.ApiHeaders;
import com.ebim.tms.shared.security.TestJwts;
import com.jayway.jsonpath.JsonPath;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
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

/**
 * The carbon footprint end to end (migration V73): a real trip, measured by the real routing port,
 * times the vehicle type's factor, split between its orders on the company's cost-allocation basis -
 * and "no data", never zero, for a vehicle type without a factor.
 */
@EnabledIf(value = DockerAvailability.CONDITION, disabledReason = DockerAvailability.DISABLED_REASON)
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(EmissionApiIntegrationTest.JwtDecoderOverride.class)
class EmissionApiIntegrationTest {

    private static final String PLANNING = "/api/v1/planning";
    private static final String EMISSIONS = "/api/v1/settlement/emissions";

    private static final UUID ORGANIZATION = UUID.fromString("66666666-0000-4000-8000-000000000001");
    private static final UUID COMPANY_A = UUID.fromString("66666666-0000-4000-8000-0000000000c1");
    private static final UUID COMPANY_B = UUID.fromString("66666666-0000-4000-8000-0000000000c2");
    private static final UUID ADMIN_AUTH = UUID.fromString("66666666-0000-4000-8000-0000000000e1");
    private static final UUID VIEWER_AUTH = UUID.fromString("66666666-0000-4000-8000-0000000000e2");

    private static final AtomicInteger SEQUENCE = new AtomicInteger();

    private static String jdbcUrl;
    private static String origin;
    private static String destinationNorth;
    private static String destinationSouth;

    @Autowired
    private MockMvc mockMvc;

    private String adminToken;
    private String viewerToken;

    @TestConfiguration(proxyBeanMethods = false)
    static class JwtDecoderOverride {
        @Bean
        @Primary
        JwtDecoder testJwtDecoder() {
            return TestJwts.decoder();
        }
    }

    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        jdbcUrl = PostgresTestDatabase.createMigratedDatabase("tms_emission_api");
        seedFixture();
        registry.add("spring.datasource.url", () -> jdbcUrl);
        registry.add("spring.datasource.username", PostgresTestDatabase::username);
        registry.add("spring.datasource.password", PostgresTestDatabase::password);
    }

    private static void seedFixture() {
        execute("INSERT INTO tms.organization (id, code, name) VALUES ('" + ORGANIZATION
                + "', 'EM-ORG', 'Emission Org')");
        execute("INSERT INTO tms.company (id, organization_id, code, name, time_zone) VALUES ('"
                + COMPANY_A + "', '" + ORGANIZATION + "', 'EM-A', 'Company A', 'America/Guayaquil'), ('"
                + COMPANY_B + "', '" + ORGANIZATION + "', 'EM-B', 'Company B', 'America/Guayaquil')");
        execute("INSERT INTO tms.app_user (auth_user_id, email, full_name) VALUES ('" + ADMIN_AUTH
                + "', 'em.admin@example.invalid', 'EM Admin'), ('" + VIEWER_AUTH
                + "', 'em.viewer@example.invalid', 'EM Viewer')");
        membership("em.admin@example.invalid", COMPANY_A, "COMPANY_ADMIN");
        membership("em.admin@example.invalid", COMPANY_B, "COMPANY_ADMIN");
        membership("em.viewer@example.invalid", COMPANY_A, "VIEWER");
        // Guayaquil, a store 10 km north and one 20 km south: measurable legs, estimated by the local
        // geodesic provider - so the footprint must come back flagged as an estimate too.
        origin = location("EM-GYE", "ORIGIN", "-2.1700", "-79.9000");
        destinationNorth = location("EM-NORTE", "DESTINATION", "-2.0800", "-79.9000");
        destinationSouth = location("EM-SUR", "DESTINATION", "-2.3500", "-79.9000");
    }

    @BeforeEach
    void mintTokens() {
        adminToken = TestJwts.validFor(ADMIN_AUTH);
        viewerToken = TestJwts.validFor(VIEWER_AUTH);
    }

    @Test
    @DisplayName("a trip's kg CO2e is its own distance times its type's factor, split between its orders by weight")
    void tripFootprint() throws Exception {
        LocalDate date = LocalDate.of(2027, 4, 5);
        String vehicle = vehicle("EMD1", "0.9000");
        String trip = tripWithOrders(date, vehicle, List.of(
                new OrderSpec(destinationNorth, "300", "Agroexportadora (demo)"),
                new OrderSpec(destinationSouth, "100", "Ferretería (demo)")));

        String detail = mockMvc.perform(asAdmin(get(PLANNING + "/trips/" + trip), COMPANY_A))
                .andReturn().getResponse().getContentAsString();
        BigDecimal distance = new BigDecimal(JsonPath.read(detail, "$.routing.totalDistanceKm").toString());
        assertThat(distance).isPositive();

        String response = mockMvc.perform(asAdmin(get(EMISSIONS + "/trips/" + trip), COMPANY_A))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CALCULATED"))
                .andExpect(jsonPath("$.estimated").value(true))
                .andExpect(jsonPath("$.fuelType").value("DIESEL"))
                .andExpect(jsonPath("$.basis").value("WEIGHT"))
                .andExpect(jsonPath("$.orders.length()").value(2))
                .andReturn().getResponse().getContentAsString();

        BigDecimal kg = new BigDecimal(JsonPath.read(response, "$.kgCo2e").toString());
        assertThat(kg).isEqualByComparingTo(distance.multiply(new BigDecimal("0.9")).setScale(2, RoundingMode.HALF_UP));
        BigDecimal first = new BigDecimal(JsonPath.read(response, "$.orders[0].kgCo2e").toString());
        BigDecimal second = new BigDecimal(JsonPath.read(response, "$.orders[1].kgCo2e").toString());
        assertThat(first.add(second)).isEqualByComparingTo(kg);
        // 300 kg against 100 kg: three quarters of the carbon.
        assertThat(first).isCloseTo(kg.multiply(new BigDecimal("0.75")), org.assertj.core.api.Assertions.within(
                new BigDecimal("0.01")));
    }

    @Test
    @DisplayName("a vehicle type without a factor has no footprint - 'no data', never zero - and the report counts it")
    void noFactorIsNoData() throws Exception {
        LocalDate date = LocalDate.of(2027, 4, 12);
        String withFactor = vehicle("EMF1", "1.2000");
        String without = vehicle("EMN1", null);
        tripWithOrders(date, withFactor, List.of(new OrderSpec(destinationNorth, "500", "Agroexportadora (demo)")));
        // The next day: one draft run per origin and day.
        String bare = tripWithOrders(date.plusDays(1), without,
                List.of(new OrderSpec(destinationSouth, "200", "Supermercado (demo)")));

        mockMvc.perform(asAdmin(get(EMISSIONS + "/trips/" + bare), COMPANY_A))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("NO_FACTOR"))
                .andExpect(jsonPath("$.kgCo2e").doesNotExist())
                .andExpect(jsonPath("$.orders[0].kgCo2e").doesNotExist());

        mockMvc.perform(asViewer(get(EMISSIONS).param("from", date.toString())
                        .param("to", date.plusDays(1).toString()), COMPANY_A))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totals.trips").value(2))
                .andExpect(jsonPath("$.totals.tripsCalculated").value(1))
                .andExpect(jsonPath("$.totals.tripsWithoutFactor").value(1))
                .andExpect(jsonPath("$.groups.length()").value(1))
                .andExpect(jsonPath("$.groups[0].label").value("Agroexportadora (demo)"));

        mockMvc.perform(asAdmin(get(EMISSIONS + "/export").param("from", date.toString())
                        .param("to", date.toString()), COMPANY_A))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", org.hamcrest.Matchers.startsWith("text/csv")));
    }

    @Test
    @DisplayName("another company cannot read a trip's footprint, and a range above a quarter is refused")
    void isolationAndRange() throws Exception {
        LocalDate date = LocalDate.of(2027, 4, 20);
        String trip = tripWithOrders(date, vehicle("EMI1", "0.5000"),
                List.of(new OrderSpec(destinationNorth, "100", "Cliente (demo)")));
        mockMvc.perform(asAdmin(get(EMISSIONS + "/trips/" + trip), COMPANY_B)).andExpect(status().isNotFound());
        mockMvc.perform(asAdmin(get(EMISSIONS).param("from", "2027-01-01").param("to", "2027-12-31"), COMPANY_A))
                .andExpect(status().isBadRequest());
    }

    // --- plumbing --------------------------------------------------------------------

    private record OrderSpec(String destinationId, String weightKg, String customer) {
    }

    private String tripWithOrders(LocalDate date, String vehicleId, List<OrderSpec> orders) throws Exception {
        String run = JsonPath.read(mockMvc.perform(asAdmin(post(PLANNING + "/runs"), COMPANY_A)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"originId\":\"" + origin + "\",\"planningDate\":\"" + date + "\"}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString(), "$.run.id");
        String departure = date.atTime(8, 0).atZone(ZoneId.of("America/Guayaquil")).toOffsetDateTime().toString();
        String trip = JsonPath.read(mockMvc.perform(asAdmin(post(PLANNING + "/runs/" + run + "/trips"), COMPANY_A)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"vehicleId\":\"" + vehicleId + "\",\"plannedDepartureAt\":\"" + departure
                                + "\",\"version\":0}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString(), "$.trip.id");
        for (OrderSpec spec : orders) {
            String order = idOf("INSERT INTO tms.transport_order (company_id, order_number, origin_id,"
                    + " destination_id, service_date, status, total_weight_kg, total_volume_m3, total_pallets,"
                    + " customer_name) VALUES ('" + COMPANY_A + "', 'TO-EM-" + SEQUENCE.incrementAndGet() + "', '"
                    + origin + "', '" + spec.destinationId() + "', '" + date + "', 'READY_FOR_PLANNING', "
                    + spec.weightKg() + ", 1, 1, '" + spec.customer() + "') RETURNING id");
            mockMvc.perform(asAdmin(post(PLANNING + "/trips/" + trip + "/assignments"), COMPANY_A)
                            .contentType(MediaType.APPLICATION_JSON).content("{\"orderId\":\"" + order + "\"}"))
                    .andExpect(status().isOk());
        }
        return trip;
    }

    private static String vehicle(String code, String factor) {
        String type = idOf("INSERT INTO tms.vehicle_type (company_id, code, name, max_weight_kg, max_volume_m3,"
                + " max_pallets, fuel_type, emission_factor_kg_co2e_per_km) VALUES ('" + COMPANY_A + "', 'VT-" + code
                + "', 'Rigid', 10000, 40, 20, " + (factor == null ? "NULL, NULL" : "'DIESEL', " + factor)
                + ") RETURNING id");
        return idOf("INSERT INTO tms.vehicle (company_id, vehicle_type_id, code, license_plate) VALUES ('"
                + COMPANY_A + "', '" + type + "', '" + code + "', 'PLT-" + code + "') RETURNING id");
    }

    private static String location(String code, String role, String latitude, String longitude) {
        String id = idOf("INSERT INTO tms.location (company_id, code, name, latitude, longitude) VALUES ('"
                + COMPANY_A + "', '" + code + "', '" + code + "', " + latitude + ", " + longitude + ") RETURNING id");
        execute("INSERT INTO tms.location_role (location_id, role) VALUES ('" + id + "', '" + role + "')");
        return id;
    }

    private static void membership(String email, UUID companyId, String roleCode) {
        execute("INSERT INTO tms.membership (app_user_id, organization_id, company_id)"
                + " SELECT id, '" + ORGANIZATION + "', '" + companyId + "' FROM tms.app_user WHERE email = '"
                + email + "'");
        execute("INSERT INTO tms.membership_role (membership_id, role_id) SELECT m.id, r.id"
                + " FROM tms.membership m JOIN tms.app_user u ON u.id = m.app_user_id AND u.email = '"
                + email + "' JOIN tms.role r ON r.code = '" + roleCode + "' WHERE m.company_id = '"
                + companyId + "'");
    }

    private MockHttpServletRequestBuilder asAdmin(MockHttpServletRequestBuilder builder, UUID companyId) {
        return builder.header("Authorization", "Bearer " + adminToken)
                .header(ApiHeaders.COMPANY_ID, companyId.toString());
    }

    private MockHttpServletRequestBuilder asViewer(MockHttpServletRequestBuilder builder, UUID companyId) {
        return builder.header("Authorization", "Bearer " + viewerToken)
                .header(ApiHeaders.COMPANY_ID, companyId.toString());
    }

    private static String idOf(String sql) {
        try (Connection connection = PostgresTestDatabase.connect(jdbcUrl);
                Statement statement = connection.createStatement();
                ResultSet rows = statement.executeQuery(sql)) {
            rows.next();
            return rows.getString(1);
        } catch (SQLException failed) {
            throw new IllegalStateException("could not seed the emission fixture", failed);
        }
    }

    private static void execute(String sql) {
        try (Connection connection = PostgresTestDatabase.connect(jdbcUrl);
                Statement statement = connection.createStatement()) {
            statement.execute(sql);
        } catch (SQLException failed) {
            throw new IllegalStateException("could not seed the emission fixture", failed);
        }
    }
}
