package com.ebim.tms.routing.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.ebim.tms.routing.domain.RoutingProviderAdapter;
import com.ebim.tms.routing.domain.TravelEstimateRow;
import com.ebim.tms.routing.infrastructure.TravelEstimateRepository;
import com.ebim.tms.shared.reference.GeoPoint;
import com.ebim.tms.shared.reference.RoutingPort;
import com.ebim.tms.shared.reference.RoutingSource;
import com.ebim.tms.shared.reference.TravelEstimate;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DataIntegrityViolationException;

/**
 * The routing chain: cache, provider, fallback (migration V38).
 *
 * <p>No database and no network. The cache is a mock because what is under test is <em>which</em>
 * link answers and what each one costs, not whether JPA can write a row -
 * {@code RoutingApiIntegrationTest} covers that against real PostgreSQL.
 *
 * <p><b>No test here calls a real routing service, and none can.</b> The only adapter that ships
 * needs nothing but two coordinates; the "provider" in these tests is a mock. That is the
 * discipline the brief asks for, and it is structural rather than a convention somebody has to
 * remember: there is no vendor code in the tree to accidentally reach.
 */
class RoutingServiceTest {

    private static final UUID COMPANY = UUID.randomUUID();
    private static final Instant NOW = Instant.parse("2026-08-28T12:00:00Z");

    private static final GeoPoint LIMA = point("-12.046374", "-77.042793");
    private static final GeoPoint CALLAO = point("-12.052780", "-77.132790");
    private static final GeoPoint AREQUIPA = point("-16.409047", "-71.537451");

    private static GeoPoint point(String latitude, String longitude) {
        return new GeoPoint(new BigDecimal(latitude), new BigDecimal(longitude));
    }

    private TravelEstimateRepository cache;
    private MeterRegistry meterRegistry;
    private Clock clock;
    private LocalGeodesicRoutingProvider local;
    private RoutingProperties properties;

    @BeforeEach
    void setUp() {
        cache = mock(TravelEstimateRepository.class);
        meterRegistry = new SimpleMeterRegistry();
        clock = Clock.fixed(NOW, ZoneOffset.UTC);
        properties = new RoutingProperties(null, null, null, null, null, null);
        local = new LocalGeodesicRoutingProvider(properties, clock);
        when(cache.findLeg(any(), any(), any(), any(), any(), any())).thenReturn(Optional.empty());
        when(cache.saveAndFlush(any(TravelEstimateRow.class))).thenAnswer(call -> call.getArgument(0));
    }

    private RoutingService serviceWith(RoutingProviderAdapter... providers) {
        List<RoutingProviderAdapter> all = new java.util.ArrayList<>(List.of(providers));
        all.add(local);
        return new RoutingService(cache, all, local, properties, meterRegistry, clock);
    }

    private double counter(String metric, String outcome) {
        var found = meterRegistry.find(metric).tag("outcome", outcome).counter();
        return found == null ? 0 : found.count();
    }

    // --- the trivial cases -----------------------------------------------------------

    @Nested
    @DisplayName("cases that need neither cache nor provider")
    class Trivial {

        @Test
        @DisplayName("a missing coordinate is an unknown distance, not a failure")
        void missingCoordinates() {
            RoutingService service = serviceWith();

            assertThat(service.estimate(COMPANY, null, LIMA)).isEmpty();
            assertThat(service.estimate(COMPANY, LIMA, null)).isEmpty();
            assertThat(service.estimate(COMPANY, null, null)).isEmpty();

            verifyNoInteractions(cache);
            assertThat(counter("tms.routing.lookups", "unknown")).isEqualTo(3);
        }

        @Test
        @DisplayName("a point is zero from itself, and nothing is cached for it")
        void samePoint() {
            RoutingService service = serviceWith();

            TravelEstimate estimate = service.estimate(COMPANY, LIMA, LIMA).orElseThrow();

            assertThat(estimate.distanceKm()).isEqualByComparingTo("0");
            assertThat(estimate.travelDuration()).isZero();
            verifyNoInteractions(cache);
            assertThat(counter("tms.routing.lookups", "same-point")).isEqualTo(1);
        }

        @Test
        @DisplayName("the same point at a different scale is still the same point")
        void samePointDifferentScale() {
            RoutingService service = serviceWith();

            TravelEstimate estimate = service
                    .estimate(COMPANY, LIMA, point("-12.0463740", "-77.0427930")).orElseThrow();

            assertThat(estimate.distanceKm()).isEqualByComparingTo("0");
        }
    }

    // --- the cache -------------------------------------------------------------------

    @Nested
    @DisplayName("the cache")
    class Cache {

        @Test
        @DisplayName("a miss computes, stores, and says it was a miss")
        void miss() {
            RoutingService service = serviceWith();

            TravelEstimate estimate = service.estimate(COMPANY, LIMA, AREQUIPA).orElseThrow();

            assertThat(estimate.source()).isEqualTo(RoutingSource.FALLBACK);
            assertThat(estimate.servedFromCache()).isFalse();
            assertThat(estimate.provider()).isEqualTo(LocalGeodesicRoutingProvider.NAME);
            verify(cache).saveAndFlush(any(TravelEstimateRow.class));
            assertThat(counter("tms.routing.lookups", "miss")).isEqualTo(1);
        }

        @Test
        @DisplayName("a fresh hit is served without computing or storing anything")
        void hit() {
            TravelEstimateRow row = row(LIMA, AREQUIPA, "1000.000", 900, NOW.plusSeconds(3600));
            when(cache.findLeg(any(), any(), any(), any(), any(), any())).thenReturn(Optional.of(row));
            RoutingService service = serviceWith();

            TravelEstimate estimate = service.estimate(COMPANY, LIMA, AREQUIPA).orElseThrow();

            // Served from the cache, and still honest about having been a straight-line estimate:
            // storing a figure must not promote it to a measurement.
            assertThat(estimate.servedFromCache()).isTrue();
            assertThat(estimate.source()).isEqualTo(RoutingSource.FALLBACK);
            assertThat(estimate.isEstimated()).isTrue();
            assertThat(estimate.distanceKm()).isEqualByComparingTo("1000.000");
            assertThat(estimate.travelDuration()).isEqualTo(Duration.ofMinutes(900));
            verify(cache, never()).saveAndFlush(any(TravelEstimateRow.class));
            assertThat(counter("tms.routing.lookups", "hit")).isEqualTo(1);
        }

        @Test
        @DisplayName("an expired row is refreshed in place, not duplicated")
        void expired() {
            TravelEstimateRow stale = row(LIMA, AREQUIPA, "1000.000", 900, NOW.minusSeconds(1));
            when(cache.findLeg(any(), any(), any(), any(), any(), any())).thenReturn(Optional.of(stale));
            RoutingService service = serviceWith();

            TravelEstimate estimate = service.estimate(COMPANY, LIMA, AREQUIPA).orElseThrow();

            assertThat(estimate.source()).isEqualTo(RoutingSource.FALLBACK);
            ArgumentCaptor<TravelEstimateRow> saved = ArgumentCaptor.forClass(TravelEstimateRow.class);
            verify(cache).saveAndFlush(saved.capture());
            // The same row object, refreshed - a second row for the same leg could not be inserted
            // anyway (uq_travel_estimate_leg), and trying would be a caught race rather than a save.
            assertThat(saved.getValue()).isSameAs(stale);
            assertThat(saved.getValue().expiresAt()).isAfter(OffsetDateTime.ofInstant(NOW, ZoneOffset.UTC));
            assertThat(counter("tms.routing.lookups", "expired")).isEqualTo(1);
        }

        @Test
        @DisplayName("losing a race to cache the same leg is not an error for the caller")
        void racedInsertIsAbsorbed() {
            when(cache.saveAndFlush(any(TravelEstimateRow.class)))
                    .thenThrow(new DataIntegrityViolationException("uq_travel_estimate_leg"));
            RoutingService service = serviceWith();

            TravelEstimate estimate = service.estimate(COMPANY, LIMA, AREQUIPA).orElseThrow();

            assertThat(estimate.distanceKm()).isGreaterThan(BigDecimal.ZERO);
            assertThat(counter("tms.routing.lookups", "raced")).isEqualTo(1);
        }

        @Test
        @DisplayName("the cache is asked with the company that asked, never a shared key")
        void cacheIsCompanyScoped() {
            RoutingService service = serviceWith();
            UUID otherCompany = UUID.randomUUID();

            service.estimate(COMPANY, LIMA, AREQUIPA);
            service.estimate(otherCompany, LIMA, AREQUIPA);

            verify(cache).findLeg(org.mockito.ArgumentMatchers.eq(COMPANY), any(), any(), any(), any(), any());
            verify(cache).findLeg(org.mockito.ArgumentMatchers.eq(otherCompany), any(), any(), any(), any(), any());
        }
    }

    // --- providers -------------------------------------------------------------------

    @Nested
    @DisplayName("the provider chain")
    class Providers {

        @Test
        @DisplayName("with no provider configured, the local estimate is the whole of routing")
        void noProvider() {
            RoutingService service = serviceWith();

            TravelEstimate estimate = service.estimate(COMPANY, LIMA, CALLAO).orElseThrow();

            assertThat(estimate.source()).isEqualTo(RoutingSource.FALLBACK);
            assertThat(estimate.isEstimated()).isTrue();
            assertThat(service.activeProviderName()).isEqualTo(LocalGeodesicRoutingProvider.NAME);
        }

        @Test
        @DisplayName("an available provider answers and its number is what is served and stored")
        void providerAnswers() {
            RoutingProviderAdapter vendor = adapter("VENDOR_X", true,
                    Optional.of(TravelEstimate.computed(new BigDecimal("42.500"), Duration.ofMinutes(55),
                            "VENDOR_X", RoutingSource.PROVIDER, OffsetDateTime.ofInstant(NOW, ZoneOffset.UTC))));
            RoutingService service = serviceWith(vendor);

            TravelEstimate estimate = service.estimate(COMPANY, LIMA, CALLAO).orElseThrow();

            assertThat(estimate.source()).isEqualTo(RoutingSource.PROVIDER);
            assertThat(estimate.distanceKm()).isEqualByComparingTo("42.500");
            assertThat(estimate.provider()).isEqualTo("VENDOR_X");
            assertThat(counter("tms.routing.provider.calls", "ok")).isEqualTo(1);
        }

        @Test
        @DisplayName("an unavailable provider is skipped without being called")
        void unavailableProviderIsNotCalled() {
            RoutingProviderAdapter vendor = adapter("VENDOR_X", false, Optional.empty());
            RoutingService service = serviceWith(vendor);

            TravelEstimate estimate = service.estimate(COMPANY, LIMA, CALLAO).orElseThrow();

            assertThat(estimate.source()).isEqualTo(RoutingSource.FALLBACK);
            verify(vendor, never()).estimate(any(), any());
        }

        @Test
        @DisplayName("a provider that returns nothing degrades to the local estimate")
        void providerReturnsEmpty() {
            RoutingProviderAdapter vendor = adapter("VENDOR_X", true, Optional.empty());
            RoutingService service = serviceWith(vendor);

            TravelEstimate estimate = service.estimate(COMPANY, LIMA, CALLAO).orElseThrow();

            assertThat(estimate.source()).isEqualTo(RoutingSource.FALLBACK);
            assertThat(counter("tms.routing.provider.calls", "empty")).isEqualTo(1);
        }

        /**
         * The case the whole fallback exists for: a timeout arriving as an unchecked exception from
         * somebody else's SDK must degrade a distance, never fail a planning run.
         */
        @Test
        @DisplayName("a provider that times out degrades to the local estimate and is counted")
        void providerTimesOut() {
            RoutingProviderAdapter vendor = mock(RoutingProviderAdapter.class);
            when(vendor.name()).thenReturn("VENDOR_X");
            when(vendor.isAvailable()).thenReturn(true);
            when(vendor.estimate(any(), any()))
                    .thenThrow(new IllegalStateException("read timed out after 5000ms"));
            RoutingService service = serviceWith(vendor);

            TravelEstimate estimate = service.estimate(COMPANY, LIMA, CALLAO).orElseThrow();

            assertThat(estimate.source()).isEqualTo(RoutingSource.FALLBACK);
            assertThat(estimate.distanceKm()).isGreaterThan(BigDecimal.ZERO);
            assertThat(counter("tms.routing.provider.calls", "error")).isEqualTo(1);
        }

        @Test
        @DisplayName("the first available provider wins; later ones are not consulted")
        void firstAvailableWins() {
            RoutingProviderAdapter down = adapter("VENDOR_DOWN", false, Optional.empty());
            RoutingProviderAdapter up = adapter("VENDOR_UP", true,
                    Optional.of(TravelEstimate.computed(new BigDecimal("10.000"), Duration.ofMinutes(20),
                            "VENDOR_UP", RoutingSource.PROVIDER, OffsetDateTime.ofInstant(NOW, ZoneOffset.UTC))));
            RoutingProviderAdapter never = adapter("VENDOR_NEVER", true, Optional.empty());
            RoutingService service = serviceWith(down, up, never);

            assertThat(service.estimate(COMPANY, LIMA, CALLAO).orElseThrow().provider()).isEqualTo("VENDOR_UP");
            verify(never, never()).estimate(any(), any());
        }

        /**
         * The cache key includes the provider, so switching routers does not silently serve the old
         * one's numbers - and comparing the two is how anybody justifies paying for a real one.
         */
        @Test
        @DisplayName("the cache is keyed on the provider that would answer")
        void cacheIsKeyedOnProvider() {
            RoutingProviderAdapter vendor = adapter("VENDOR_X", true, Optional.empty());
            RoutingService service = serviceWith(vendor);

            service.estimate(COMPANY, LIMA, CALLAO);

            verify(cache).findLeg(any(), org.mockito.ArgumentMatchers.eq("VENDOR_X"),
                    any(), any(), any(), any());
        }
    }

    // --- the matrix ------------------------------------------------------------------

    @Nested
    @DisplayName("the matrix")
    class Matrix {

        @Test
        @DisplayName("N x N answers every off-diagonal pair")
        void nByN() {
            RoutingService service = serviceWith();
            List<GeoPoint> points = List.of(LIMA, CALLAO, AREQUIPA);

            Map<RoutingPort.Leg, TravelEstimate> answers = service.matrix(COMPANY, points, points);

            // 3 x 3 minus the diagonal: the diagonal is answered without touching cache or provider.
            assertThat(answers).hasSize(6);
            assertThat(answers).containsKey(new RoutingPort.Leg(LIMA, AREQUIPA));
            assertThat(answers).containsKey(new RoutingPort.Leg(AREQUIPA, LIMA));
        }

        @Test
        @DisplayName("both directions are asked about separately: a road is not assumed symmetric")
        void directional() {
            RoutingService service = serviceWith();

            Map<RoutingPort.Leg, TravelEstimate> answers =
                    service.matrix(COMPANY, List.of(LIMA), List.of(CALLAO));
            Map<RoutingPort.Leg, TravelEstimate> reverse =
                    service.matrix(COMPANY, List.of(CALLAO), List.of(LIMA));

            assertThat(answers).containsOnlyKeys(new RoutingPort.Leg(LIMA, CALLAO));
            assertThat(reverse).containsOnlyKeys(new RoutingPort.Leg(CALLAO, LIMA));
        }

        @Test
        @DisplayName("a repeated point costs one lookup, not two")
        void deduplicates() {
            RoutingService service = serviceWith();

            service.matrix(COMPANY, List.of(LIMA, LIMA), List.of(CALLAO));

            verify(cache, times(1)).findLeg(any(), any(), any(), any(), any(), any());
        }

        @Test
        @DisplayName("a point with no coordinates is skipped rather than failing the matrix")
        void nullPointsAreSkipped() {
            RoutingService service = serviceWith();
            List<GeoPoint> origins = java.util.Arrays.asList(LIMA, null);

            Map<RoutingPort.Leg, TravelEstimate> answers = service.matrix(COMPANY, origins, List.of(CALLAO));

            assertThat(answers).hasSize(1);
        }

        @Test
        @DisplayName("an empty request asks nothing and answers nothing")
        void empty() {
            RoutingService service = serviceWith();

            assertThat(service.matrix(COMPANY, List.of(), List.of(LIMA))).isEmpty();
            verifyNoInteractions(cache);
        }

        @Test
        @DisplayName("a matrix beyond the configured limit is refused rather than attempted")
        void limitIsEnforced() {
            RoutingProperties tiny = new RoutingProperties(null, null, null, null, null, 2);
            RoutingService service = new RoutingService(cache, List.of(local), local, tiny, meterRegistry, clock);

            assertThatThrownBy(() -> service.matrix(COMPANY, List.of(LIMA, CALLAO, AREQUIPA),
                    List.of(LIMA, CALLAO, AREQUIPA)))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("exceeds the configured limit");
        }
    }

    // --- fixtures --------------------------------------------------------------------

    private static RoutingProviderAdapter adapter(String name, boolean available, Optional<TravelEstimate> answer) {
        RoutingProviderAdapter adapter = mock(RoutingProviderAdapter.class);
        when(adapter.name()).thenReturn(name);
        when(adapter.isAvailable()).thenReturn(available);
        when(adapter.estimate(any(), any())).thenReturn(answer);
        return adapter;
    }

    private static TravelEstimateRow row(GeoPoint origin, GeoPoint destination, String km, int minutes,
            Instant expiresAt) {
        TravelEstimate estimate = TravelEstimate.computed(new BigDecimal(km), Duration.ofMinutes(minutes),
                LocalGeodesicRoutingProvider.NAME, RoutingSource.FALLBACK,
                OffsetDateTime.ofInstant(NOW.minusSeconds(60), ZoneOffset.UTC));
        return new TravelEstimateRow(COMPANY, origin, destination, estimate,
                OffsetDateTime.ofInstant(expiresAt, ZoneOffset.UTC));
    }
}
