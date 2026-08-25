package com.ebim.tms.tracking.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.ebim.tms.shared.reference.TrackedTrip;
import com.ebim.tms.shared.reference.TrackingIntakeOutcome;
import com.ebim.tms.shared.reference.TrackingIntakeResult;
import com.ebim.tms.shared.reference.TrackingReport;
import com.ebim.tms.shared.reference.TripTrackingLookupPort;
import com.ebim.tms.shared.security.CompanyScope;
import com.ebim.tms.tracking.domain.TrackingPosition;
import com.ebim.tms.tracking.infrastructure.TrackingPositionRepository;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * The three rules {@link TrackingIngestionService} applies to a reported position: is it usable, is
 * there a shipment on the road to attach it to, and do we want to keep it.
 *
 * <p>The sampling rule is the one worth the most assertions, because it is what makes this table's
 * size a function of the fleet rather than of a vendor's push rate - and because it is invisible in
 * production: a partner whose points are thinned gets a 200 either way, so a regression here would
 * show up as a database growing sixty times faster and nothing else.
 *
 * <p>The clock is fixed and every instant is derived from it. A test that used the wall clock would
 * pass or fail depending on the second it ran, which the execution tests in {@code planning} learned
 * first.
 */
class TrackingIngestionServiceTest {

    private static final UUID COMPANY = UUID.randomUUID();
    private static final UUID TRIP = UUID.randomUUID();
    private static final String SHIPMENT = "SH-00000042";
    private static final String PROVIDER = "acme-telematics";

    private static final OffsetDateTime NOW = OffsetDateTime.of(2026, 8, 21, 10, 0, 0, 0, ZoneOffset.UTC);
    private static final Clock CLOCK = Clock.fixed(NOW.toInstant(), ZoneOffset.UTC);

    private static final CompanyScope SCOPE = new CompanyScope(COMPANY, "CO-A", "Company A", "America/Lima",
            UUID.randomUUID(), "ORG", "Organization", Set.of());

    private TrackingPositionRepository positionRepository;
    private TripTrackingLookupPort tripLookup;
    private TrackingIngestionService service;

    @BeforeEach
    void setUp() {
        positionRepository = mock(TrackingPositionRepository.class);
        tripLookup = mock(TripTrackingLookupPort.class);
        service = new TrackingIngestionService(positionRepository, tripLookup,
                new TrackingProperties(Duration.ofSeconds(60), Duration.ofHours(24), Duration.ofMinutes(5), 200),
                new SimpleMeterRegistry(), CLOCK);

        // Nothing stored yet unless a test says otherwise, and saveAll hands back what it was
        // given - the ids are Hibernate's job, and this service only has to keep them in order.
        when(positionRepository.findWatermarks(eq(COMPANY), any(), anyString())).thenReturn(List.of());
        when(positionRepository.saveAll(anyList())).thenAnswer(invocation -> {
            Collection<TrackingPosition> submitted = invocation.getArgument(0);
            return new ArrayList<>(submitted);
        });
        trackable(SHIPMENT, TRIP);
    }

    private void trackable(String shipmentNumber, UUID tripId) {
        when(tripLookup.findByShipmentNumbers(eq(COMPANY), any())).thenReturn(Map.of(shipmentNumber,
                new TrackedTrip(tripId, shipmentNumber, "IN_TRANSIT", true, "VH-1", "ABC-123", NOW.minusHours(2))));
    }

    private static TrackingReport at(OffsetDateTime occurredAt) {
        return new TrackingReport(SHIPMENT, PROVIDER, occurredAt, new BigDecimal("-12.046374"),
                new BigDecimal("-77.042793"), null, null, null, null);
    }

    private List<TrackingIntakeOutcome> outcomesOf(List<TrackingReport> reports) {
        return service.record(SCOPE, reports).stream().map(TrackingIntakeResult::outcome).toList();
    }

    @Nested
    @DisplayName("the sampling rule")
    class Sampling {

        @Test
        void keeps_one_point_per_interval_and_accepts_the_rest_without_storing_them() {
            // A device pushing every 20 seconds against a deployment keeping one a minute. The
            // sender did nothing wrong and is told so - THINNED is an accepted outcome.
            List<TrackingIntakeOutcome> outcomes = outcomesOf(List.of(
                    at(NOW.minusMinutes(5)),
                    at(NOW.minusMinutes(5).plusSeconds(20)),
                    at(NOW.minusMinutes(5).plusSeconds(40)),
                    at(NOW.minusMinutes(4))));

            assertThat(outcomes).containsExactly(
                    TrackingIntakeOutcome.RECORDED,
                    TrackingIntakeOutcome.THINNED,
                    TrackingIntakeOutcome.THINNED,
                    TrackingIntakeOutcome.RECORDED);
            assertThat(outcomes).allSatisfy(outcome -> assertThat(outcome.accepted()).isTrue());
        }

        @Test
        void decides_on_the_order_the_vehicle_travelled_not_the_order_the_sender_used() {
            // The same four points as above, delivered backwards - which is what a device flushing
            // a buffer routinely does. Sorting before deciding is what stops the same delivery
            // producing a different answer depending on how it was serialised.
            List<TrackingIntakeResult> results = service.record(SCOPE, List.of(
                    at(NOW.minusMinutes(4)),
                    at(NOW.minusMinutes(5).plusSeconds(40)),
                    at(NOW.minusMinutes(5).plusSeconds(20)),
                    at(NOW.minusMinutes(5))));

            assertThat(results).hasSize(4);
            assertThat(outcomeAt(results, 3)).isEqualTo(TrackingIntakeOutcome.RECORDED);
            assertThat(outcomeAt(results, 0)).isEqualTo(TrackingIntakeOutcome.RECORDED);
            assertThat(outcomeAt(results, 1)).isEqualTo(TrackingIntakeOutcome.THINNED);
            assertThat(outcomeAt(results, 2)).isEqualTo(TrackingIntakeOutcome.THINNED);
        }

        @Test
        void continues_from_what_is_already_stored_rather_than_restarting_per_delivery() {
            when(positionRepository.findWatermarks(eq(COMPANY), any(), eq(PROVIDER)))
                    .thenReturn(List.of(watermark(TRIP, PROVIDER, NOW.minusMinutes(1))));

            // Measured from the stored watermark, not from the start of this batch: 30s after it
            // is inside the floor, a full 60s after it is the next point worth keeping. Without
            // the watermark the first of these would be RECORDED, which is the whole claim -
            // and the second is deliberately at exactly the interval, because the boundary is
            // where "one point per minute" either holds or quietly becomes "one per 61 seconds".
            assertThat(outcomesOf(List.of(at(NOW.minusSeconds(30)), at(NOW))))
                    .containsExactly(TrackingIntakeOutcome.THINNED, TrackingIntakeOutcome.RECORDED);
        }

        @Test
        void treats_a_replay_of_the_same_instant_as_a_duplicate_and_an_older_one_as_stale() {
            when(positionRepository.findWatermarks(eq(COMPANY), any(), eq(PROVIDER)))
                    .thenReturn(List.of(watermark(TRIP, PROVIDER, NOW.minusMinutes(10))));

            // The two together are why an at-least-once sender needs no cursor: re-sending an hour
            // of pings changes nothing and reports no error.
            assertThat(outcomesOf(List.of(at(NOW.minusMinutes(10)), at(NOW.minusMinutes(30)))))
                    .containsExactly(TrackingIntakeOutcome.DUPLICATE, TrackingIntakeOutcome.STALE);
        }

        @Test
        void samples_each_feed_of_a_shipment_independently() {
            // Two providers reporting the same truck. One feed's point must not thin the other's:
            // they are separate claims, and the unique index keys on the provider for that reason.
            TrackingReport other = new TrackingReport(SHIPMENT, "second-feed", NOW.minusMinutes(5).plusSeconds(10),
                    new BigDecimal("-12.05"), new BigDecimal("-77.05"), null, null, null, null);

            assertThat(outcomesOf(List.of(at(NOW.minusMinutes(5)), other)))
                    .containsExactly(TrackingIntakeOutcome.RECORDED, TrackingIntakeOutcome.RECORDED);
        }
    }

    @Nested
    @DisplayName("what a position must satisfy")
    class Validation {

        @Test
        void refuses_a_time_from_the_future_beyond_the_allowed_skew() {
            // Accepting it would park the vehicle at the top of every "latest position" query until
            // real time caught up - and thin every genuine point reported in between.
            assertThat(outcomesOf(List.of(at(NOW.plusMinutes(30)))))
                    .containsExactly(TrackingIntakeOutcome.INVALID);
        }

        @Test
        void allows_a_small_clock_skew_because_devices_drift() {
            assertThat(outcomesOf(List.of(at(NOW.plusMinutes(2)))))
                    .containsExactly(TrackingIntakeOutcome.RECORDED);
        }

        @Test
        void refuses_a_position_older_than_this_deployment_keeps() {
            assertThat(outcomesOf(List.of(at(NOW.minusDays(3)))))
                    .containsExactly(TrackingIntakeOutcome.INVALID);
        }

        @Test
        void refuses_coordinates_outside_the_globe_and_speeds_that_are_unit_mistakes() {
            TrackingReport offGlobe = new TrackingReport(SHIPMENT, PROVIDER, NOW.minusMinutes(1),
                    new BigDecimal("91"), new BigDecimal("-77.04"), null, null, null, null);
            TrackingReport wrongUnit = new TrackingReport(SHIPMENT, PROVIDER, NOW.minusMinutes(2),
                    new BigDecimal("-12.04"), new BigDecimal("-77.04"), new BigDecimal("980"), null, null, null);

            assertThat(outcomesOf(List.of(offGlobe, wrongUnit)))
                    .containsExactly(TrackingIntakeOutcome.INVALID, TrackingIntakeOutcome.INVALID);
        }

        @Test
        void refuses_a_provider_slug_the_schema_would_reject_before_looking_anything_up() {
            TrackingReport badSlug = new TrackingReport(SHIPMENT, "not a slug!", NOW.minusMinutes(1),
                    new BigDecimal("-12.04"), new BigDecimal("-77.04"), null, null, null, null);

            assertThat(outcomesOf(List.of(badSlug))).containsExactly(TrackingIntakeOutcome.INVALID);
            // Nothing is resolved for a report that could never be stored: a malformed delivery
            // must not cost a lookup, which is what makes a misconfigured feed cheap to refuse.
            verify(tripLookup, never()).findByShipmentNumbers(any(), any());
        }

        @Test
        void accepts_a_shipment_number_a_partner_echoed_back_in_lower_case() {
            TrackingReport lowerCase = new TrackingReport(SHIPMENT.toLowerCase(Locale.ROOT), PROVIDER,
                    NOW.minusMinutes(1), new BigDecimal("-12.04"), new BigDecimal("-77.04"), null, null, null, null);

            assertThat(outcomesOf(List.of(lowerCase))).containsExactly(TrackingIntakeOutcome.RECORDED);
        }
    }

    @Nested
    @DisplayName("what a position may be attached to")
    class Attachment {

        @Test
        void refuses_a_shipment_this_company_does_not_have_without_saying_whether_it_exists() {
            when(tripLookup.findByShipmentNumbers(eq(COMPANY), any())).thenReturn(Map.of());

            List<TrackingIntakeResult> results = service.record(SCOPE, List.of(at(NOW.minusMinutes(1))));

            assertThat(results.get(0).outcome()).isEqualTo(TrackingIntakeOutcome.UNKNOWN_SHIPMENT);
            assertThat(results.get(0).accepted()).isFalse();
            assertThat(results.get(0).reason()).contains("this company").doesNotContain("company " + COMPANY);
        }

        @Test
        void refuses_a_shipment_that_has_not_left() {
            when(tripLookup.findByShipmentNumbers(eq(COMPANY), any())).thenReturn(Map.of(SHIPMENT,
                    new TrackedTrip(TRIP, SHIPMENT, "READY_FOR_DISPATCH", false, "VH-1", "ABC-123", null)));

            List<TrackingIntakeResult> results = service.record(SCOPE, List.of(at(NOW.minusMinutes(1))));

            assertThat(results.get(0).outcome()).isEqualTo(TrackingIntakeOutcome.NOT_TRACKABLE);
            // The status is in the message: a partner should be able to tell "wrong shipment" from
            // "right shipment, too early" without opening a support ticket.
            assertThat(results.get(0).reason()).contains("READY_FOR_DISPATCH");
        }

        @Test
        void costs_the_rest_of_the_run_nothing_when_one_item_is_refused() {
            TrackingReport unknown = new TrackingReport("SH-99999999", PROVIDER, NOW.minusMinutes(3),
                    new BigDecimal("-12.04"), new BigDecimal("-77.04"), null, null, null, null);

            assertThat(outcomesOf(List.of(unknown, at(NOW.minusMinutes(2)), at(NOW.minusMinutes(1)))))
                    .containsExactly(TrackingIntakeOutcome.UNKNOWN_SHIPMENT,
                            TrackingIntakeOutcome.RECORDED, TrackingIntakeOutcome.RECORDED);
        }
    }

    @Test
    void stores_the_company_of_the_scope_and_never_anything_the_report_carried() {
        service.record(SCOPE, List.of(new TrackingReport(SHIPMENT, "ACME-Telematics", NOW.minusMinutes(1),
                new BigDecimal("-12.046374"), new BigDecimal("-77.042793"), new BigDecimal("54.5"),
                new BigDecimal("183.4"), "TRK-0431", "  ping-991  ")));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<TrackingPosition>> captor = ArgumentCaptor.forClass(List.class);
        verify(positionRepository).saveAll(captor.capture());

        TrackingPosition stored = captor.getValue().get(0);
        assertThat(stored.companyId()).isEqualTo(COMPANY);
        assertThat(stored.tripId()).isEqualTo(TRIP);
        // Lower-cased to match ck_tracking_position_provider_shape, and the references trimmed:
        // a feed that pads its own identifiers must not create two feeds in the data.
        assertThat(stored.provider()).isEqualTo("acme-telematics");
        assertThat(stored.correlationReference()).isEqualTo("ping-991");
        assertThat(stored.externalVehicleReference()).isEqualTo("TRK-0431");
        assertThat(stored.speedKph()).isEqualByComparingTo("54.5");
        assertThat(stored.headingDegrees()).isEqualByComparingTo("183.4");
    }

    @Test
    void does_nothing_at_all_for_an_empty_run() {
        assertThat(service.record(SCOPE, List.of())).isEmpty();
        verify(tripLookup, never()).findByShipmentNumbers(any(), any());
        verify(positionRepository, never()).saveAll(anyList());
    }

    private static TrackingIntakeOutcome outcomeAt(List<TrackingIntakeResult> results, int index) {
        return results.stream()
                .filter(result -> result.index() == index)
                .map(TrackingIntakeResult::outcome)
                .findFirst()
                .orElseThrow();
    }

    /** The projection the repository returns, as a plain value - it is an interface, not a record. */
    private static TrackingPositionRepository.FeedWatermark watermark(UUID tripId, String provider,
            OffsetDateTime latest) {
        return new TrackingPositionRepository.FeedWatermark() {
            @Override
            public UUID getTripId() {
                return tripId;
            }

            @Override
            public String getProvider() {
                return provider;
            }

            @Override
            public OffsetDateTime getLatest() {
                return latest;
            }
        };
    }
}
