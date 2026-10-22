package com.ebim.tms.orders.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ebim.tms.orders.application.PublicTrackingService;
import com.ebim.tms.orders.application.PublicTrackingView;
import com.ebim.tms.orders.application.TrackingLinkProperties;
import com.ebim.tms.orders.domain.CustomerTrackingStatus;
import com.ebim.tms.orders.domain.TrackingToken;
import com.ebim.tms.shared.api.ApiExceptionHandler;
import com.ebim.tms.shared.api.ApiExceptionResponder;
import com.ebim.tms.shared.api.ResourceNotFoundException;
import com.ebim.tms.shared.config.ApplicationConfig;
import com.ebim.tms.shared.security.PublicApiPaths;
import com.ebim.tms.shared.security.PublicTrackingSecurityConfig;
import com.ebim.tms.shared.security.SecurityConfig;
import com.ebim.tms.shared.security.SecurityTestConfiguration;
import com.ebim.tms.shared.security.TestJwts;
import com.ebim.tms.shared.security.TestPrincipals;
import com.ebim.tms.shared.security.TmsAccessDeniedHandler;
import com.ebim.tms.shared.security.TmsAuthenticationEntryPoint;
import com.ebim.tms.shared.security.TmsJwtAuthenticationConverter;
import com.ebim.tms.shared.security.TmsSecurityProperties;
import com.ebim.tms.shared.web.WebConfig;
import com.jayway.jsonpath.JsonPath;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * The anonymous chain of ADR-029 (T6, T7, T10, T11), through the real filter chains with the service
 * mocked: what is served, what is not, with which headers, and when the brakes engage. Needs no Docker.
 */
@WebMvcTest(controllers = PublicTrackingController.class)
@Import({
    ApplicationConfig.class,
    SecurityConfig.class,
    PublicTrackingSecurityConfig.class,
    PublicApiPaths.class,
    TmsJwtAuthenticationConverter.class,
    TmsAuthenticationEntryPoint.class,
    TmsAccessDeniedHandler.class,
    ApiExceptionResponder.class,
    ApiExceptionHandler.class,
    WebConfig.class,
    PublicTrackingRateLimiter.class,
    SecurityTestConfiguration.class
})
@EnableConfigurationProperties({TmsSecurityProperties.class, TrackingLinkProperties.class})
@TestPropertySource(properties = {
    "tms.tracking-links.rate-limit.requests-per-minute=5",
    "tms.tracking-links.rate-limit.misses-per-ten-minutes=3",
    "tms.tracking-links.rate-limit.views-per-link-per-minute=4"
})
@ActiveProfiles("test")
class PublicTrackingSecurityTest {

    private static final String PUBLIC = "/api/v1/public/tracking/";
    private static final AtomicInteger ADDRESS = new AtomicInteger(1);

    /** Everything the page may receive - and nothing else. A new key here is a disclosure decision. */
    private static final Set<String> TOP_LEVEL_KEYS = Set.of("companyName", "orderNumber", "reference",
            "destinationName", "status", "timeline", "arrivalWindow", "carrierName", "vehiclePlate",
            "proofOfDelivery", "timeZone", "expiresAt", "generatedAt");

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private PublicTrackingService service;

    @Test
    @DisplayName("a valid token is served anonymously, no-store, noindex, no-referrer, and with exactly the published keys")
    void servesTheProjection() throws Exception {
        String token = TrackingToken.generate().value();
        when(service.view(token)).thenReturn(view());

        String body = mockMvc.perform(from(get(PUBLIC + token)))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, org.hamcrest.Matchers.containsString("no-store")))
                .andExpect(header().string("X-Robots-Tag", "noindex, nofollow"))
                .andExpect(header().string("Referrer-Policy", "no-referrer"))
                .andExpect(header().string("X-Frame-Options", "DENY"))
                .andExpect(header().doesNotExist(HttpHeaders.SET_COOKIE))
                .andReturn().getResponse().getContentAsString();

        Map<String, Object> json = JsonPath.read(body, "$");
        assertThat(json.keySet()).containsExactlyInAnyOrderElementsOf(TOP_LEVEL_KEYS);
        assertThat(((Map<String, Object>) JsonPath.read(body, "$.timeline[0]")).keySet())
                .containsExactlyInAnyOrder("code", "state", "at", "outcome");
        assertThat(((Map<String, Object>) JsonPath.read(body, "$.arrivalWindow")).keySet())
                .containsExactlyInAnyOrder("from", "to", "basis");
        assertThat(((Map<String, Object>) JsonPath.read(body, "$.proofOfDelivery")).keySet())
                .containsExactlyInAnyOrder("receiverName", "deliveredAt");
        assertThat(body.toLowerCase()).doesNotContain("\"id\"", "price", "amount", "cost", "weight",
                "address", "driver", "phone", "document", "companyid", "orderid");
    }

    @Test
    @DisplayName("an Authorization header on the public path is not even read: garbage neither helps nor hurts")
    void bearerIsIgnored() throws Exception {
        String token = TrackingToken.generate().value();
        when(service.view(token)).thenReturn(view());

        mockMvc.perform(from(get(PUBLIC + token)).header(HttpHeaders.AUTHORIZATION, "Bearer not.a.jwt"))
                .andExpect(status().isOk());
        mockMvc.perform(from(get(PUBLIC + token))
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + TestJwts.forgedFor(TestPrincipals.PLANNER_AUTH_USER)))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("unknown and malformed tokens get the same 404 - the body tells nothing apart")
    void sameNotFound() throws Exception {
        String unknown = TrackingToken.generate().value();
        when(service.view(anyString())).thenThrow(new ResourceNotFoundException(
                "This tracking link does not exist or is no longer available."));

        String a = mockMvc.perform(from(get(PUBLIC + unknown))).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("resource-not-found"))
                .andReturn().getResponse().getContentAsString();
        String b = mockMvc.perform(from(get(PUBLIC + "short"))).andExpect(status().isNotFound())
                .andReturn().getResponse().getContentAsString();

        for (String field : List.of("$.code", "$.title", "$.detail", "$.status", "$.type")) {
            Object first = JsonPath.read(a, field);
            Object second = JsonPath.read(b, field);
            assertThat(first).as(field).isEqualTo(second);
        }
    }

    @Test
    @DisplayName("nothing else under /public is served: other methods, other paths and deeper paths are 404")
    void exposesNothingElse() throws Exception {
        String token = TrackingToken.generate().value();
        for (MockHttpServletRequestBuilder request : List.of(post(PUBLIC + token), put(PUBLIC + token),
                patch(PUBLIC + token), delete(PUBLIC + token), get("/api/v1/public/tracking"),
                get("/api/v1/public/tracking/"), get("/api/v1/public/tracking/" + token + "/extra"),
                get("/api/v1/public/orders"), get("/api/v1/public/"), post("/api/v1/public/tracking"))) {
            mockMvc.perform(from(request))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.code").value("resource-not-found"));
        }
        verify(service, never()).view(anyString());
    }

    @Test
    @DisplayName("a tracking token is not a credential anywhere else: the business API still answers 401")
    void tokenOpensNothingElse() throws Exception {
        String token = TrackingToken.generate().value();
        mockMvc.perform(from(get("/api/v1/orders/" + java.util.UUID.randomUUID() + "/tracking-links"))
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(from(get("/api/v1/me")).param("token", token))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("an address over its per-minute quota gets 429 with Retry-After, before the service is asked")
    void perAddressLimit() throws Exception {
        String address = nextAddress();
        when(service.view(anyString())).thenReturn(view());
        for (int i = 0; i < 5; i++) {
            mockMvc.perform(at(get(PUBLIC + TrackingToken.generate().value()), address)).andExpect(status().isOk());
        }
        mockMvc.perform(at(get(PUBLIC + TrackingToken.generate().value()), address))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.code").value("rate-limited"))
                .andExpect(header().exists(HttpHeaders.RETRY_AFTER));
    }

    @Test
    @DisplayName("three wrong tokens and the address is refused even with a good one: enumeration stops")
    void missesBlock() throws Exception {
        String address = nextAddress();
        String good = TrackingToken.generate().value();
        when(service.view(anyString())).thenThrow(new ResourceNotFoundException("gone"));
        for (int i = 0; i < 3; i++) {
            mockMvc.perform(at(get(PUBLIC + TrackingToken.generate().value()), address))
                    .andExpect(status().isNotFound());
        }
        mockMvc.perform(at(get(PUBLIC + good), address))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().exists(HttpHeaders.RETRY_AFTER));
        mockMvc.perform(at(get(PUBLIC + good), nextAddress()))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("one link has a ceiling of its own, whichever addresses open it")
    void perLinkLimit() throws Exception {
        String token = TrackingToken.generate().value();
        when(service.view(token)).thenReturn(view());
        for (int i = 0; i < 4; i++) {
            mockMvc.perform(at(get(PUBLIC + token), nextAddress())).andExpect(status().isOk());
        }
        mockMvc.perform(at(get(PUBLIC + token), nextAddress())).andExpect(status().isTooManyRequests());
    }

    private static MockHttpServletRequestBuilder from(MockHttpServletRequestBuilder builder) {
        return at(builder, nextAddress());
    }

    private static MockHttpServletRequestBuilder at(MockHttpServletRequestBuilder builder, String address) {
        return builder.with(request -> {
            request.setRemoteAddr(address);
            return request;
        });
    }

    private static String nextAddress() {
        int n = ADDRESS.getAndIncrement();
        return "203.0." + (n / 250) + "." + (n % 250);
    }

    private static PublicTrackingView view() {
        OffsetDateTime at = OffsetDateTime.of(2026, 10, 22, 11, 32, 0, 0, ZoneOffset.ofHours(-5));
        return new PublicTrackingView("PLAPASA", "SO-2026-10045", "PED-778", "Tienda Norte",
                CustomerTrackingStatus.DELIVERED,
                List.of(new PublicTrackingView.Step("RECEIVED", "DONE", at.minusDays(1), null),
                        new PublicTrackingView.Step("OUTCOME", "DONE", at, CustomerTrackingStatus.DELIVERED)),
                new PublicTrackingView.ArrivalWindow(at.minusHours(1), at, "ESTIMATE"),
                "Transportes Andinos", "•••-123",
                new PublicTrackingView.ProofOfDelivery("María L.", at),
                "America/Guayaquil", at.plusDays(7), at);
    }
}
