package com.ebim.tms.tracking.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.ebim.tms.shared.api.ResourceNotFoundException;
import com.ebim.tms.shared.reference.TrackedTrip;
import com.ebim.tms.shared.reference.TrackingReport;
import com.ebim.tms.shared.reference.TripTrackingLookupPort;
import com.ebim.tms.shared.security.CompanyScope;
import com.ebim.tms.tracking.domain.TrackingProviderPort;
import com.ebim.tms.tracking.infrastructure.TrackingPositionRepository;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Limit;

/**
 * The read side, and specifically the three "no position" situations it must keep apart - see
 * {@link TripTrackingView}. Any of them could be answered with an empty field and the screen would
 * still render; the point of these assertions is that a dispatcher can tell "it has not left" from
 * "we have no feed" from "the feed has gone quiet", because they do something different about each.
 */
class TrackingQueryServiceTest {

    private static final UUID COMPANY = UUID.randomUUID();
    private static final UUID TRIP = UUID.randomUUID();
    private static final OffsetDateTime NOW = OffsetDateTime.of(2026, 8, 21, 10, 0, 0, 0, ZoneOffset.UTC);

    private static final CompanyScope SCOPE = new CompanyScope(COMPANY, "CO-A", "Company A", "America/Lima",
            UUID.randomUUID(), "ORG", "Organization", Set.of());

    private TrackingPositionRepository positionRepository;
    private TripTrackingLookupPort tripLookup;
    private TrackingProviderPort provider;
    private TrackingQueryService service;

    @BeforeEach
    void setUp() {
        positionRepository = mock(TrackingPositionRepository.class);
        tripLookup = mock(TripTrackingLookupPort.class);
        provider = mock(TrackingProviderPort.class);
        service = new TrackingQueryService(positionRepository, tripLookup, provider,
                new TrackingProperties(Duration.ofSeconds(60), Duration.ofHours(24), Duration.ofMinutes(5), 200));

        when(positionRepository.findByCompanyIdAndTripIdOrderByOccurredAtDesc(eq(COMPANY), eq(TRIP), any(Limit.class)))
                .thenReturn(List.of());
        when(tripLookup.findById(COMPANY, TRIP)).thenReturn(Optional.of(onTheRoad()));
    }

    private static TrackedTrip onTheRoad() {
        return new TrackedTrip(TRIP, "SH-00000042", "IN_TRANSIT", true, "VH-1", "ABC-123", NOW.minusHours(2));
    }

    @Test
    void answers_404_for_a_trip_of_another_company_rather_than_403() {
        // 403 would confirm the id exists somewhere, which is itself a cross-tenant leak.
        when(tripLookup.findById(COMPANY, TRIP)).thenReturn(Optional.empty());

        assertThatExceptionOfType(ResourceNotFoundException.class)
                .isThrownBy(() -> service.get(SCOPE, TRIP));
    }

    @Test
    void reports_no_provider_when_nothing_is_configured_and_nothing_was_ever_reported() {
        when(provider.isEnabled()).thenReturn(false);

        TripTrackingView view = service.get(SCOPE, TRIP);

        assertThat(view.providerConfigured()).isFalse();
        assertThat(view.lastPosition()).isNull();
        assertThat(view.track()).isEmpty();
        // Still trackable: the shipment is out, we simply have no way of seeing it.
        assertThat(view.trackable()).isTrue();
    }

    @Test
    void falls_back_to_the_provider_only_when_nothing_has_ever_been_reported() {
        when(provider.isEnabled()).thenReturn(true);
        when(provider.lastKnownPosition(eq(COMPANY), any())).thenReturn(Optional.of(new TrackingReport(
                "SH-00000042", "vendor-x", NOW.minusMinutes(3), new BigDecimal("-12.04"),
                new BigDecimal("-77.04"), null, null, null, null)));

        TripTrackingView view = service.get(SCOPE, TRIP);

        assertThat(view.providerConfigured()).isTrue();
        assertThat(view.lastPosition()).isNotNull();
        assertThat(view.lastPosition().provider()).isEqualTo("vendor-x");
        // A pulled position has no row, and says so: null id and null receivedAt, never "now".
        assertThat(view.lastPosition().id()).isNull();
        assertThat(view.lastPosition().receivedAt()).isNull();
        // ...and it is not in the track, because the track is what was stored.
        assertThat(view.track()).isEmpty();
    }

    @Test
    void never_asks_a_provider_about_a_shipment_that_is_not_out() {
        when(provider.isEnabled()).thenReturn(true);
        when(tripLookup.findById(COMPANY, TRIP)).thenReturn(Optional.of(
                new TrackedTrip(TRIP, "SH-00000042", "CONFIRMED", false, "VH-1", "ABC-123", null)));

        TripTrackingView view = service.get(SCOPE, TRIP);

        assertThat(view.trackable()).isFalse();
        assertThat(view.lastPosition()).isNull();
        // A vendor would happily report where the truck is parked tonight. Showing that against a
        // shipment which has not left would be a true fact answering a different question.
        verify(provider, never()).lastKnownPosition(any(), any());
    }
}
