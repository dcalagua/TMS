package com.ebim.tms.orders.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ebim.tms.database.DockerAvailability;
import com.ebim.tms.database.PostgresTestDatabase;
import com.ebim.tms.orders.domain.TrackingToken;
import com.ebim.tms.shared.api.ApiHeaders;
import com.ebim.tms.shared.security.TestJwts;
import com.jayway.jsonpath.JsonPath;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
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
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * The customer tracking link end to end on a real PostgreSQL (migration V74, ADR-029): the office
 * generates and revokes, an anonymous phone reads, and the database holds the line - hash only, RLS,
 * grants, the guard trigger. The rate limits are relaxed here; {@code PublicTrackingSecurityTest} and
 * {@code PublicTrackingRateLimiterTest} hold them.
 */
@EnabledIf(value = DockerAvailability.CONDITION, disabledReason = DockerAvailability.DISABLED_REASON)
@SpringBootTest(properties = {
    "tms.tracking-links.rate-limit.requests-per-minute=10000",
    "tms.tracking-links.rate-limit.misses-per-ten-minutes=10000",
    "tms.tracking-links.rate-limit.views-per-link-per-minute=10000"
})
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(TrackingLinkApiIntegrationTest.JwtDecoderOverride.class)
class TrackingLinkApiIntegrationTest {

    private static final String PLANNING = "/api/v1/planning";
    private static final String TRIPS = PLANNING + "/trips";
    private static final String PUBLIC = "/api/v1/public/tracking/";
    private static final ZoneId LIMA = ZoneId.of("America/Lima");

    private static final UUID ORG_A = UUID.fromString("74747474-0000-4000-8000-0000000000a1");
    private static final UUID ORG_B = UUID.fromString("74747474-0000-4000-8000-0000000000b1");
    private static final UUID COMPANY_A = UUID.fromString("74747474-0000-4000-8000-0000000000a2");
    private static final UUID COMPANY_B = UUID.fromString("74747474-0000-4000-8000-0000000000b2");
    private static final UUID ADMIN_A = UUID.fromString("74747474-0000-4000-8000-0000000000a3");
    private static final UUID ADMIN_B = UUID.fromString("74747474-0000-4000-8000-0000000000b3");
    private static final UUID VIEWER_A = UUID.fromString("74747474-0000-4000-8000-0000000000a4");

    /** Everything the page may receive - and nothing else (ADR-029 T5). */
    private static final Set<String> PUBLIC_KEYS = Set.of("companyName", "orderNumber", "reference",
            "destinationName", "status", "timeline", "arrivalWindow", "carrierName", "vehiclePlate",
            "proofOfDelivery", "timeZone", "expiresAt", "generatedAt");

    private static final AtomicInteger SEQUENCE = new AtomicInteger();

    private static String jdbcUrl;
    private static String originA;
    private static String destinationA;
    private static String carrierA;
    private static String originB;
    private static String destinationB;

    @Autowired
    private MockMvc mockMvc;

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
        jdbcUrl = PostgresTestDatabase.createMigratedDatabase("tms_tracking_link");
        seedFixture();
        registry.add("spring.datasource.url", () -> jdbcUrl);
        registry.add("spring.datasource.username", PostgresTestDatabase::username);
        registry.add("spring.datasource.password", PostgresTestDatabase::password);
    }

    private static void seedFixture() {
        execute("""
                INSERT INTO tms.organization (id, code, name) VALUES ('%1$s', 'TRK-ORG-A', 'Tracking Org A'),
                                                                     ('%2$s', 'TRK-ORG-B', 'Tracking Org B');
                INSERT INTO tms.company (id, organization_id, code, name, time_zone) VALUES
                    ('%3$s', '%1$s', 'TRK-A', 'Tracking Company A', 'America/Lima'),
                    ('%4$s', '%2$s', 'TRK-B', 'Tracking Company B', 'America/Guayaquil');
                INSERT INTO tms.app_user (auth_user_id, email, full_name) VALUES
                    ('%5$s', 'trk.admin.a@example.invalid', 'TRK Admin A'),
                    ('%6$s', 'trk.admin.b@example.invalid', 'TRK Admin B'),
                    ('%7$s', 'trk.viewer.a@example.invalid', 'TRK Viewer A');
                """.formatted(ORG_A, ORG_B, COMPANY_A, COMPANY_B, ADMIN_A, ADMIN_B, VIEWER_A));
        member("trk.admin.a@example.invalid", ORG_A, COMPANY_A, "COMPANY_ADMIN");
        member("trk.admin.b@example.invalid", ORG_B, COMPANY_B, "COMPANY_ADMIN");
        member("trk.viewer.a@example.invalid", ORG_A, COMPANY_A, "VIEWER");
        originA = insertLocation(COMPANY_A, "TRK-A-ORIGIN", "Almacén Central", "ORIGIN");
        destinationA = insertLocation(COMPANY_A, "TRK-A-DEST", "Tienda Norte", "DESTINATION");
        originB = insertLocation(COMPANY_B, "TRK-B-ORIGIN", "Bodega Sur", "ORIGIN");
        destinationB = insertLocation(COMPANY_B, "TRK-B-DEST", "Cliente B", "DESTINATION");
        carrierA = insertReturningId("INSERT INTO tms.carrier (company_id, code, business_name, tax_id_type,"
                + " tax_id_value) VALUES ('" + COMPANY_A + "', 'TRK-CARR', 'Transportes Andinos', 'RUC', '20100000017')");
    }

    // --- the whole life of one link ------------------------------------------------------------

    @Test
    @DisplayName("generated, read anonymously through planned / en ruta / llegó / entregado, and revoked")
    void theLifeOfALink() throws Exception {
        LocalDate date = nextDate();
        String order = order(COMPANY_A, originA, destinationA, date, "READY_FOR_PLANNING");

        String issued = mockMvc.perform(asAdminA(post(links(order))))
                .andExpect(status().isCreated())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, org.hamcrest.Matchers.containsString("no-store")))
                .andExpect(jsonPath("$.link.state").value("ACTIVE"))
                .andReturn().getResponse().getContentAsString();
        String token = JsonPath.read(issued, "$.token");
        String linkId = JsonPath.read(issued, "$.link.id");
        assertThat(token).hasSize(43);
        assertThat((String) JsonPath.read(issued, "$.path")).isEqualTo("/t/" + token);

        // T2: the hash is stored, the token is not - in this table or in the audit trail.
        assertThat(queryString("SELECT token_hash FROM tms.tracking_link WHERE id = '" + linkId + "'"))
                .isEqualTo(TrackingToken.hashOf(token));
        assertThat(count("SELECT count(*) FROM tms.tracking_link t WHERE position('" + token
                + "' in t::text) > 0")).isZero();
        assertThat(count("SELECT count(*) FROM tms.audit_event WHERE action = 'TRACKING_LINK_CREATED'"
                + " AND aggregate_id = '" + order + "'")).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM tms.audit_event a WHERE position('" + token + "' in a::text) > 0"))
                .isZero();

        // The office never sees the token again.
        String listed = mockMvc.perform(asAdminA(get(links(order)))).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(listed).doesNotContain(token).doesNotContain(TrackingToken.hashOf(token));

        // Received: no confirmed trip yet.
        String received = publicView(token).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertPublicShape(received);
        assertThat((String) JsonPath.read(received, "$.status")).isEqualTo("RECEIVED");
        assertThat((String) JsonPath.read(received, "$.companyName")).isEqualTo("Tracking Company A");
        assertThat((String) JsonPath.read(received, "$.destinationName")).isEqualTo("Tienda Norte");
        assertThat((String) JsonPath.read(received, "$.reference")).isEqualTo("PED-" + order.substring(0, 8));
        assertThat((String) JsonPath.read(received, "$.timeZone")).isEqualTo("America/Lima");
        assertThat(count("SELECT view_count FROM tms.tracking_link WHERE id = '" + linkId + "'")).isEqualTo(1);

        // Planned on a confirmed trip: the carrier and a masked plate appear.
        String run = newRun(date);
        String trip = newTrip(run, date, "ABC-" + String.format(Locale.ROOT, "%03d", SEQUENCE.incrementAndGet() % 1000));
        assignWhole(trip, order);
        confirm(run);
        String planned = publicView(token).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat((String) JsonPath.read(planned, "$.status")).isEqualTo("PLANNED");
        assertThat((String) JsonPath.read(planned, "$.carrierName")).isEqualTo("Transportes Andinos");
        String plate = JsonPath.read(planned, "$.vehiclePlate");
        assertThat(plate).startsWith("•••-").hasSize(7).doesNotContain("ABC");

        // En ruta, then llegó.
        transition(trip, "ready");
        transition(trip, "dispatch");
        assertThat((String) JsonPath.read(publicBody(token), "$.status")).isEqualTo("IN_TRANSIT");
        String stop = stopIdsOf(trip).get(0);
        stopAction(trip, stop, "arrive");
        assertThat((String) JsonPath.read(publicBody(token), "$.status")).isEqualTo("ARRIVED");

        // Entregado, with the receiver abbreviated.
        mockMvc.perform(asAdminA(put(TRIPS + "/" + trip + "/stops/" + stop + "/orders/" + order + "/delivery"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"result\":\"DELIVERED\",\"deliveredAt\":\"" + OffsetDateTime.now()
                                + "\",\"receiverName\":\"María López Quispe\",\"receiverDocument\":\"40123456\"}"))
                .andExpect(status().isOk());
        String delivered = publicBody(token);
        assertPublicShape(delivered);
        assertThat((String) JsonPath.read(delivered, "$.status")).isEqualTo("DELIVERED");
        assertThat((String) JsonPath.read(delivered, "$.proofOfDelivery.receiverName")).isEqualTo("María L.");
        assertThat(delivered).doesNotContain("López").doesNotContain("Quispe").doesNotContain("40123456");
        assertThat((Object) JsonPath.read(delivered, "$.arrivalWindow")).isNull();

        // T5: no internal id of any kind travels.
        for (String id : List.of(order, trip, stop, linkId, COMPANY_A.toString(), carrierA, destinationA)) {
            assertThat(delivered).doesNotContain(id);
        }

        // The plate follows the company setting.
        execute("INSERT INTO tms.company_settings (company_id, tracking_plate_display) VALUES ('" + COMPANY_A
                + "', 'HIDDEN') ON CONFLICT (company_id) DO UPDATE SET tracking_plate_display = 'HIDDEN'");
        assertThat((Object) JsonPath.read(publicBody(token), "$.vehiclePlate")).isNull();
        execute("UPDATE tms.company_settings SET tracking_plate_display = 'MASKED' WHERE company_id = '"
                + COMPANY_A + "'");

        // Revoked: the same 404 as a token that never existed.
        String revoked = mockMvc.perform(asAdminA(post(links(order) + "/" + linkId + "/revoke"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"sent to the wrong customer\"}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat((String) JsonPath.read(revoked, "$.state")).isEqualTo("REVOKED");
        String afterRevoke = publicView(token).andExpect(status().isNotFound())
                .andReturn().getResponse().getContentAsString();
        String unknown = publicView(TrackingToken.generate().value()).andExpect(status().isNotFound())
                .andReturn().getResponse().getContentAsString();
        assertSameNotFound(afterRevoke, unknown);
        assertThat(count("SELECT count(*) FROM tms.audit_event WHERE action = 'TRACKING_LINK_REVOKED'"
                + " AND aggregate_id = '" + order + "'")).isEqualTo(1);

        mockMvc.perform(asAdminA(post(links(order) + "/" + linkId + "/revoke"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"again\"}"))
                .andExpect(status().isConflict());
    }

    @Test
    @DisplayName("a link past its hard expiry is the same 404, and so is a malformed token")
    void expiredLinksAnswerNotFound() throws Exception {
        String order = order(COMPANY_A, originA, destinationA, nextDate(), "READY_FOR_PLANNING");
        TrackingToken token = TrackingToken.generate();
        execute("INSERT INTO tms.tracking_link (company_id, order_id, token_hash, created_at, expires_at, created_by)"
                + " SELECT '" + COMPANY_A + "', '" + order + "', '" + token.hash() + "', now() - interval '40 days',"
                + " now() - interval '10 days', id FROM tms.app_user WHERE email = 'trk.admin.a@example.invalid'");

        String expired = publicView(token.value()).andExpect(status().isNotFound())
                .andReturn().getResponse().getContentAsString();
        String malformed = publicView("not-a-token").andExpect(status().isNotFound())
                .andReturn().getResponse().getContentAsString();
        assertSameNotFound(expired, malformed);
        assertThat(count("SELECT view_count FROM tms.tracking_link WHERE token_hash = '" + token.hash() + "'"))
                .as("a dead link is not counted as viewed").isZero();
    }

    @Test
    @DisplayName("an order that closed more than seven days ago gets no new link")
    void closedLongAgo() throws Exception {
        String order = insertReturningId("INSERT INTO tms.transport_order (company_id, order_number, origin_id,"
                + " destination_id, service_date, status, cancel_reason, total_weight_kg, total_volume_m3,"
                + " total_pallets, created_at, updated_at) VALUES ('" + COMPANY_A + "', 'TO-TRK-OLD-"
                + SEQUENCE.incrementAndGet() + "', '" + originA + "', '" + destinationA + "', '" + nextDate()
                + "', 'CANCELLED', 'customer withdrew', 10, 1, 1, now() - interval '20 days', now() - interval '10 days')");

        mockMvc.perform(asAdminA(post(links(order)))).andExpect(status().isConflict());
    }

    @Test
    @DisplayName("an order holds at most five live links; revoking one makes room")
    void perOrderCap() throws Exception {
        String order = order(COMPANY_A, originA, destinationA, nextDate(), "READY_FOR_PLANNING");
        String first = null;
        for (int i = 0; i < 5; i++) {
            String body = mockMvc.perform(asAdminA(post(links(order)))).andExpect(status().isCreated())
                    .andReturn().getResponse().getContentAsString();
            if (first == null) {
                first = JsonPath.read(body, "$.link.id");
            }
        }
        mockMvc.perform(asAdminA(post(links(order)))).andExpect(status().isConflict());
        mockMvc.perform(asAdminA(post(links(order) + "/" + first + "/revoke"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"rotate\"}"))
                .andExpect(status().isOk());
        mockMvc.perform(asAdminA(post(links(order)))).andExpect(status().isCreated());
    }

    // --- authorization and tenancy ---------------------------------------------------------------

    @Test
    @DisplayName("a VIEWER reads orders but may not publish one, nor list its links")
    void viewerCannotShare() throws Exception {
        String order = order(COMPANY_A, originA, destinationA, nextDate(), "READY_FOR_PLANNING");
        mockMvc.perform(as(VIEWER_A, COMPANY_A, post(links(order)))).andExpect(status().isForbidden());
        mockMvc.perform(as(VIEWER_A, COMPANY_A, get(links(order)))).andExpect(status().isForbidden());
        mockMvc.perform(post(links(order))).andExpect(status().isUnauthorized());
        assertThat(count("SELECT count(*) FROM tms.tracking_link WHERE order_id = '" + order + "'")).isZero();
    }

    @Test
    @DisplayName("company B can neither list, generate nor revoke for A's order; each token shows only its own company")
    void noCrossTenantLeakage() throws Exception {
        String orderA = order(COMPANY_A, originA, destinationA, nextDate(), "READY_FOR_PLANNING");
        String orderB = order(COMPANY_B, originB, destinationB, nextDate(), "READY_FOR_PLANNING");
        String issuedA = mockMvc.perform(asAdminA(post(links(orderA)))).andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String linkA = JsonPath.read(issuedA, "$.link.id");

        mockMvc.perform(as(ADMIN_B, COMPANY_B, get(links(orderA)))).andExpect(status().isNotFound());
        mockMvc.perform(as(ADMIN_B, COMPANY_B, post(links(orderA)))).andExpect(status().isNotFound());
        mockMvc.perform(as(ADMIN_B, COMPANY_B, post(links(orderA) + "/" + linkA + "/revoke"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"x\"}"))
                .andExpect(status().isNotFound());
        // A's link id under B's own order is not found either.
        mockMvc.perform(as(ADMIN_B, COMPANY_B, post(links(orderB) + "/" + linkA + "/revoke"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"x\"}"))
                .andExpect(status().isNotFound());

        String issuedB = mockMvc.perform(as(ADMIN_B, COMPANY_B, post(links(orderB)))).andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String viewA = publicBody(JsonPath.read(issuedA, "$.token"));
        String viewB = publicBody(JsonPath.read(issuedB, "$.token"));
        assertThat((String) JsonPath.read(viewA, "$.companyName")).isEqualTo("Tracking Company A");
        assertThat(viewA).doesNotContain("Tracking Company B").doesNotContain("Cliente B");
        assertThat((String) JsonPath.read(viewB, "$.companyName")).isEqualTo("Tracking Company B");
        assertThat((String) JsonPath.read(viewB, "$.timeZone")).isEqualTo("America/Guayaquil");
        assertThat(viewB).doesNotContain("Tracking Company A").doesNotContain("Tienda Norte");
    }

    @Test
    @DisplayName("the plate display is a company setting: saved by the settings screen, kept when omitted, audited")
    void plateDisplayIsACompanySetting() throws Exception {
        String profile = """
                {"name":"Tracking Company B","timeZone":"America/Guayaquil","defaultCountry":"EC",
                 "orderNumberPrefix":"TO-","shipmentNumberPrefix":"SH-"%s}""";
        mockMvc.perform(as(ADMIN_B, COMPANY_B, get("/api/v1/admin/companies/current")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.settings.trackingPlateDisplay").value("MASKED"));
        mockMvc.perform(as(ADMIN_B, COMPANY_B, put("/api/v1/admin/companies/current"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(profile.formatted(",\"trackingPlateDisplay\":\"HIDDEN\"")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.settings.trackingPlateDisplay").value("HIDDEN"));
        mockMvc.perform(as(ADMIN_B, COMPANY_B, put("/api/v1/admin/companies/current"))
                        .contentType(MediaType.APPLICATION_JSON).content(profile.formatted("")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.settings.trackingPlateDisplay").value("HIDDEN"));
        assertThat(count("SELECT count(*) FROM tms.audit_event WHERE company_id = '" + COMPANY_B
                + "' AND aggregate_type = 'COMPANY' AND metadata::jsonb ->> 'trackingPlateDisplay' = 'HIDDEN'")).isEqualTo(1);
        mockMvc.perform(as(ADMIN_B, COMPANY_B, put("/api/v1/admin/companies/current"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(profile.formatted(",\"trackingPlateDisplay\":\"FULL\"")))
                .andExpect(status().isBadRequest());
        mockMvc.perform(as(ADMIN_B, COMPANY_B, put("/api/v1/admin/companies/current"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(profile.formatted(",\"trackingPlateDisplay\":\"MASKED\"")))
                .andExpect(status().isOk());
    }

    // --- the database holds the line ---------------------------------------------------------------

    @Test
    @DisplayName("RLS: tms_app bound to company B sees none of A's links; a carrier transaction sees none at all")
    void rowLevelSecurity() throws Exception {
        String order = order(COMPANY_A, originA, destinationA, nextDate(), "READY_FOR_PLANNING");
        mockMvc.perform(asAdminA(post(links(order)))).andExpect(status().isCreated());

        assertThat(countAs(COMPANY_B, "", "SELECT count(*) FROM tms.tracking_link WHERE order_id = '" + order + "'"))
                .isZero();
        assertThat(countAs(COMPANY_A, "", "SELECT count(*) FROM tms.tracking_link WHERE order_id = '" + order + "'"))
                .isEqualTo(1);
        assertThat(countAs(COMPANY_A, carrierA,
                "SELECT count(*) FROM tms.tracking_link WHERE order_id = '" + order + "'"))
                .as("ADR-018: a carrier session reads none of the shipper's customer links").isZero();
        assertThat(count("SELECT count(*) FROM pg_policies WHERE schemaname = 'tms' AND tablename = 'tracking_link'"
                + " AND policyname = 'p_carrier_scope' AND permissive = 'RESTRICTIVE'")).isEqualTo(1);
    }

    @Test
    @DisplayName("grants and the guard: tms_app cannot delete or rewrite a token; not even the owner can")
    void grantsAndGuard() throws Exception {
        String order = order(COMPANY_A, originA, destinationA, nextDate(), "READY_FOR_PLANNING");
        String body = mockMvc.perform(asAdminA(post(links(order)))).andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String linkId = JsonPath.read(body, "$.link.id");
        String where = " WHERE id = '" + linkId + "'";

        assertThat(sqlStateAs(COMPANY_A, "DELETE FROM tms.tracking_link" + where)).isEqualTo("42501");
        assertThat(sqlStateAs(COMPANY_A, "UPDATE tms.tracking_link SET token_hash = repeat('a', 64)" + where))
                .isEqualTo("42501");
        assertThat(sqlStateAs(COMPANY_A, "UPDATE tms.tracking_link SET expires_at = now() + interval '1 year'" + where))
                .isEqualTo("42501");
        assertThat(sqlState("UPDATE tms.tracking_link SET expires_at = now() + interval '1 year'" + where))
                .as("the owner, too").isEqualTo("42501");
        assertThat(sqlState("DELETE FROM tms.tracking_link" + where)).isEqualTo("42501");
        assertThat(sqlState("INSERT INTO tms.tracking_link (company_id, order_id, token_hash, expires_at, created_by)"
                + " SELECT company_id, order_id, 'NOT-HEX', expires_at, created_by FROM tms.tracking_link" + where))
                .as("the hash shape is checked").isEqualTo("23514");

        execute("UPDATE tms.tracking_link SET revoked_at = now(), revoked_by = created_by, revoke_reason = 'x'" + where);
        assertThat(sqlState("UPDATE tms.tracking_link SET revoked_at = NULL, revoked_by = NULL, revoke_reason = NULL"
                + where)).as("a revoked link stays revoked").isEqualTo("42501");
    }

    // --- helpers -----------------------------------------------------------------------------------

    private void assertPublicShape(String body) {
        Map<String, Object> json = JsonPath.read(body, "$");
        assertThat(json.keySet()).containsExactlyInAnyOrderElementsOf(PUBLIC_KEYS);
        String lower = body.toLowerCase(Locale.ROOT);
        assertThat(lower).doesNotContain("weight", "volume", "pallet", "price", "amount", "cost", "driver",
                "phone", "address", "latitude", "longitude", "\"id\"");
    }

    private static void assertSameNotFound(String first, String second) {
        for (String field : List.of("$.code", "$.title", "$.detail", "$.status", "$.type")) {
            Object a = JsonPath.read(first, field);
            Object b = JsonPath.read(second, field);
            assertThat(a).as(field).isEqualTo(b);
        }
    }

    private ResultActions publicView(String token) throws Exception {
        return mockMvc.perform(get(PUBLIC + token));
    }

    private String publicBody(String token) throws Exception {
        return publicView(token).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
    }

    private static String links(String orderId) {
        return "/api/v1/orders/" + orderId + "/tracking-links";
    }

    private MockHttpServletRequestBuilder asAdminA(MockHttpServletRequestBuilder builder) {
        return as(ADMIN_A, COMPANY_A, builder);
    }

    private static MockHttpServletRequestBuilder as(UUID authUser, UUID company, MockHttpServletRequestBuilder builder) {
        return builder.header("Authorization", "Bearer " + TestJwts.validFor(authUser))
                .header(ApiHeaders.COMPANY_ID, company.toString());
    }

    private void transition(String tripId, String action) throws Exception {
        mockMvc.perform(asAdminA(post(TRIPS + "/" + tripId + "/" + action))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"version\":" + versionOfTrip(tripId) + "}"))
                .andExpect(status().isOk());
    }

    private void stopAction(String tripId, String stopId, String action) throws Exception {
        mockMvc.perform(asAdminA(post(TRIPS + "/" + tripId + "/stops/" + stopId + "/" + action))
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isOk());
    }

    private List<String> stopIdsOf(String tripId) throws Exception {
        String body = mockMvc.perform(asAdminA(get(TRIPS + "/" + tripId))).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return JsonPath.read(body, "$.stops[*].id");
    }

    private long versionOfTrip(String tripId) throws Exception {
        String body = mockMvc.perform(asAdminA(get(TRIPS + "/" + tripId))).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        Number version = JsonPath.read(body, "$.trip.version");
        return version.longValue();
    }

    private void assignWhole(String tripId, String orderId) throws Exception {
        mockMvc.perform(asAdminA(post(TRIPS + "/" + tripId + "/assignments"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"orderId\":\"" + orderId + "\"}"))
                .andExpect(status().isOk());
    }

    private void confirm(String runId) throws Exception {
        mockMvc.perform(asAdminA(post(PLANNING + "/runs/" + runId + "/confirm"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"version\":0}"))
                .andExpect(status().isOk());
    }

    private String newRun(LocalDate date) throws Exception {
        String response = mockMvc.perform(asAdminA(post(PLANNING + "/runs"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"originId\":\"" + originA + "\",\"planningDate\":\"" + date + "\"}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return JsonPath.read(response, "$.run.id");
    }

    private String newTrip(String runId, LocalDate date, String plate) throws Exception {
        String departure = date.atTime(8, 0).atZone(LIMA).toOffsetDateTime().toString();
        String response = mockMvc.perform(asAdminA(post(PLANNING + "/runs/" + runId + "/trips"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"vehicleId\":\"" + vehicle(plate) + "\",\"plannedDepartureAt\":\"" + departure
                                + "\",\"version\":0}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return JsonPath.read(response, "$.trip.id");
    }

    private static String vehicle(String plate) {
        int number = SEQUENCE.incrementAndGet();
        String type = insertReturningId("INSERT INTO tms.vehicle_type (company_id, code, name, max_weight_kg,"
                + " max_volume_m3, max_pallets) VALUES ('" + COMPANY_A + "', 'TRK-TYPE-" + number
                + "', 'Tracking type', 100000, 400, 1000)");
        return insertReturningId("INSERT INTO tms.vehicle (company_id, code, license_plate, carrier_id,"
                + " vehicle_type_id) VALUES ('" + COMPANY_A + "', 'TRK-VEH-" + number + "', '" + plate + "', '"
                + carrierA + "', '" + type + "')");
    }

    private static LocalDate nextDate() {
        return LocalDate.of(2026, 7, 1).plusDays(SEQUENCE.incrementAndGet());
    }

    private static String order(UUID company, String origin, String destination, LocalDate serviceDate,
            String status) {
        String id = UUID.randomUUID().toString();
        execute("INSERT INTO tms.transport_order (id, company_id, order_number, origin_id, destination_id,"
                + " service_date, status, external_source, external_reference, total_weight_kg, total_volume_m3,"
                + " total_pallets) VALUES ('" + id + "', '" + company + "', 'TO-TRK-"
                + String.format(Locale.ROOT, "%06d", SEQUENCE.incrementAndGet()) + "', '" + origin + "', '"
                + destination + "', '" + serviceDate + "', '" + status + "', 'ERP', 'PED-" + id.substring(0, 8)
                + "', 100, 1, 1)");
        return id;
    }

    private static void member(String email, UUID organization, UUID company, String role) {
        execute("""
                INSERT INTO tms.membership (app_user_id, organization_id, company_id)
                SELECT id, '%2$s', '%3$s' FROM tms.app_user WHERE email = '%1$s';
                INSERT INTO tms.membership_role (membership_id, role_id)
                SELECT m.id, r.id FROM tms.membership m
                JOIN tms.app_user u ON u.id = m.app_user_id AND u.email = '%1$s'
                JOIN tms.role r ON r.code = '%4$s'
                WHERE m.company_id = '%3$s';
                """.formatted(email, organization, company, role));
    }

    private static String insertLocation(UUID company, String code, String name, String role) {
        String id = insertReturningId("INSERT INTO tms.location (company_id, code, name) VALUES ('" + company
                + "', '" + code + "', '" + name + "')");
        execute("INSERT INTO tms.location_role (location_id, role) VALUES ('" + id + "', '" + role + "')");
        return id;
    }

    /** A statement as {@code tms_app} bound to one company (and optionally one carrier), like a request. */
    private static long countAs(UUID company, String carrier, String sql) {
        try (var connection = PostgresTestDatabase.connect(jdbcUrl); var statement = connection.createStatement()) {
            connection.setAutoCommit(false);
            statement.execute("SELECT set_config('tms.company_id', '" + company + "', true),"
                    + " set_config('tms.carrier_id', '" + carrier + "', true)");
            statement.execute("SET LOCAL ROLE tms_app");
            try (var rs = statement.executeQuery(sql)) {
                rs.next();
                long value = rs.getLong(1);
                connection.rollback();
                return value;
            }
        } catch (SQLException failed) {
            throw new IllegalStateException("could not query as tms_app", failed);
        }
    }

    private static String sqlStateAs(UUID company, String sql) {
        try (var connection = PostgresTestDatabase.connect(jdbcUrl); var statement = connection.createStatement()) {
            connection.setAutoCommit(false);
            statement.execute("SELECT set_config('tms.company_id', '" + company + "', true),"
                    + " set_config('tms.carrier_id', '', true)");
            statement.execute("SET LOCAL ROLE tms_app");
            Throwable failure = catchThrowable(() -> statement.execute(sql));
            connection.rollback();
            return failure instanceof SQLException sqlFailure ? sqlFailure.getSQLState() : "none";
        } catch (SQLException failed) {
            throw new IllegalStateException("could not run as tms_app", failed);
        }
    }

    private static String sqlState(String sql) {
        try (var connection = PostgresTestDatabase.connect(jdbcUrl); var statement = connection.createStatement()) {
            Throwable failure = catchThrowable(() -> statement.execute(sql));
            return failure instanceof SQLException sqlFailure ? sqlFailure.getSQLState() : "none";
        } catch (SQLException failed) {
            throw new IllegalStateException("could not run as the owner", failed);
        }
    }

    private static long count(String sql) {
        return Long.parseLong(queryString(sql));
    }

    private static String insertReturningId(String sql) {
        try (var connection = PostgresTestDatabase.connect(jdbcUrl);
                var statement = connection.createStatement();
                var resultSet = statement.executeQuery(sql + " RETURNING id")) {
            resultSet.next();
            return resultSet.getString(1);
        } catch (SQLException failed) {
            throw new IllegalStateException("could not seed a tracking-link fixture", failed);
        }
    }

    private static String queryString(String sql) {
        try (var connection = PostgresTestDatabase.connect(jdbcUrl);
                var statement = connection.createStatement();
                var resultSet = statement.executeQuery(sql)) {
            resultSet.next();
            return resultSet.getString(1);
        } catch (SQLException failed) {
            throw new IllegalStateException("could not read the tracking-link fixture", failed);
        }
    }

    private static void execute(String sql) {
        try (var connection = PostgresTestDatabase.connect(jdbcUrl); var statement = connection.createStatement()) {
            statement.execute(sql);
        } catch (SQLException failed) {
            throw new IllegalStateException("could not seed the tracking-link fixture", failed);
        }
    }
}
