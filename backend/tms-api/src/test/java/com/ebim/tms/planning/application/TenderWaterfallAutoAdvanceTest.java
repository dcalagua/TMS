package com.ebim.tms.planning.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.ebim.tms.planning.application.TenderWaterfallService.AutoAdvanceOutcome;
import com.ebim.tms.planning.domain.TenderWaterfall;
import com.ebim.tms.planning.domain.TenderWaterfallCandidate;
import com.ebim.tms.planning.domain.Trip;
import com.ebim.tms.planning.domain.TripTender;
import com.ebim.tms.planning.domain.WaterfallCandidateStatus;
import com.ebim.tms.planning.domain.WaterfallStatus;
import com.ebim.tms.planning.infrastructure.TenderWaterfallRepository;
import com.ebim.tms.planning.infrastructure.TripRepository;
import com.ebim.tms.planning.infrastructure.TripTenderRepository;
import com.ebim.tms.shared.audit.AuditActorProvider;
import com.ebim.tms.shared.audit.AuditRecorder;
import com.ebim.tms.shared.reference.CarrierLookupPort;
import com.ebim.tms.shared.reference.CarrierQuotationPort;
import com.ebim.tms.shared.reference.TripCostingLookupPort;
import com.ebim.tms.shared.security.CompanyScope;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * {@link TenderWaterfallService#autoAdvance} (ITEM 10) over stubbed repositories: never ahead of the
 * deadline, signed with whoever started the waterfall, and never a conflict - a sweep that finds
 * nothing to do says so.
 *
 * <p>The locking and the two-node race are a database question and are proved in
 * {@code TenderWaterfallAutoAdvanceIntegrationTest}; this is the decision itself.
 */
class TenderWaterfallAutoAdvanceTest {

    private static final UUID COMPANY = UUID.randomUUID();
    private static final UUID TRIP_ID = UUID.randomUUID();
    private static final UUID CARRIER_A = UUID.nameUUIDFromBytes("auto-a".getBytes());
    private static final UUID CARRIER_B = UUID.nameUUIDFromBytes("auto-b".getBytes());
    private static final UUID STARTER = UUID.randomUUID();
    private static final OffsetDateTime NOW = OffsetDateTime.parse("2026-08-28T09:00:00Z");

    private static final CompanyScope SCOPE = new CompanyScope(COMPANY, "CO-A", "Company A", "America/Lima",
            UUID.randomUUID(), "ORG", "Organization", Set.of());

    private TenderWaterfallRepository waterfallRepository;
    private TripTenderRepository tenderRepository;
    private TripTenderService tenderService;
    private AuditActorProvider actors;
    private TenderWaterfallService service;

    @BeforeEach
    void setUp() {
        waterfallRepository = mock(TenderWaterfallRepository.class);
        tenderRepository = mock(TripTenderRepository.class);
        tenderService = mock(TripTenderService.class);
        actors = mock(AuditActorProvider.class);
        TripRepository tripRepository = mock(TripRepository.class);
        when(tripRepository.findByIdAndCompanyIdForUpdate(TRIP_ID, COMPANY)).thenReturn(Optional.of(trip()));

        service = new TenderWaterfallService(waterfallRepository, tripRepository, tenderRepository, tenderService,
                mock(CarrierLookupPort.class), mock(CarrierQuotationPort.class), mock(TripCostingLookupPort.class),
                actors, mock(AuditRecorder.class), new SimpleMeterRegistry(),
                Clock.fixed(Instant.from(NOW), ZoneOffset.UTC), true);
    }

    @Test
    @DisplayName("an offer still inside its deadline is left alone, and nothing is offered")
    void notDue() {
        TenderWaterfall waterfall = running(STARTER);
        TripTender live = sentTender(waterfall, NOW.plusHours(1));
        when(tenderRepository.findLive(COMPANY, TRIP_ID)).thenReturn(Optional.of(live));

        assertThat(service.autoAdvance(SCOPE, TRIP_ID)).isEqualTo(AutoAdvanceOutcome.NOT_DUE);

        assertThat(candidate(waterfall, 1).status()).isEqualTo(WaterfallCandidateStatus.OFFERED);
        verify(tenderService, never()).createFor(any(), any(), any(), any(), any());
        verify(tenderService, never()).resolveLiveLapse(any(), any());
    }

    @Test
    @DisplayName("a lapsed offer is closed and rank 2 is offered, signed with whoever started the waterfall")
    void lapsedAdvances() {
        TenderWaterfall waterfall = running(STARTER);
        TripTender lapsed = sentTender(waterfall, NOW.minusMinutes(5));
        TripTender next = new TripTender(COMPANY, TRIP_ID, CARRIER_B, 2, new BigDecimal("910.00"), "PEN", null,
                NOW.plusHours(2), STARTER);
        ReflectionTestUtils.setField(next, "id", UUID.randomUUID());
        // findLive answers the lapsed one while it is being closed, then the one createFor opened.
        when(tenderRepository.findLive(COMPANY, TRIP_ID)).thenReturn(Optional.of(lapsed), Optional.of(next));

        assertThat(service.autoAdvance(SCOPE, TRIP_ID)).isEqualTo(AutoAdvanceOutcome.ADVANCED);

        assertThat(candidate(waterfall, 1).status()).isEqualTo(WaterfallCandidateStatus.EXPIRED);
        assertThat(candidate(waterfall, 2).status()).isEqualTo(WaterfallCandidateStatus.OFFERED);
        verify(tenderService).resolveLiveLapse(eq(SCOPE), any());
        verify(tenderService).createFor(eq(SCOPE), eq(TRIP_ID), eq(CARRIER_B), any(), eq(STARTER));
        verify(tenderService).send(SCOPE, TRIP_ID, next.id(), STARTER);
        // The person is never asked for: the job has none, and that is the point.
        verify(actors, never()).requireAppUserId();
    }

    @Test
    @DisplayName("a lapse on the last candidate ends the waterfall EXHAUSTED")
    void lastCandidateExhausts() {
        TenderWaterfall waterfall = new TenderWaterfall(COMPANY, TRIP_ID, 1, 60, NOW.minusHours(3), STARTER);
        waterfall.addCandidate(CARRIER_A, new BigDecimal("820.00"), "PEN", null);
        when(waterfallRepository.findByCompanyIdAndTripIdAndStatus(COMPANY, TRIP_ID, WaterfallStatus.ACTIVE))
                .thenReturn(Optional.of(waterfall));
        TripTender lapsed = sentTender(waterfall, NOW.minusMinutes(1));
        when(tenderRepository.findLive(COMPANY, TRIP_ID)).thenReturn(Optional.of(lapsed));

        assertThat(service.autoAdvance(SCOPE, TRIP_ID)).isEqualTo(AutoAdvanceOutcome.EXHAUSTED);
        assertThat(waterfall.status()).isEqualTo(WaterfallStatus.EXHAUSTED);
        verify(tenderService, never()).createFor(any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("a waterfall nobody is recorded as starting waits for a dispatcher, untouched")
    void noSignatory() {
        TenderWaterfall waterfall = running(null);
        TripTender lapsed = sentTender(waterfall, NOW.minusMinutes(5));
        when(tenderRepository.findLive(COMPANY, TRIP_ID)).thenReturn(Optional.of(lapsed));

        assertThat(service.autoAdvance(SCOPE, TRIP_ID)).isEqualTo(AutoAdvanceOutcome.NO_SIGNATORY);
        assertThat(candidate(waterfall, 1).status()).isEqualTo(WaterfallCandidateStatus.OFFERED);
        verify(tenderService, never()).resolveLiveLapse(any(), any());
    }

    @Test
    @DisplayName("a shipment with no running waterfall is reported, not refused")
    void notRunning() {
        when(waterfallRepository.findByCompanyIdAndTripIdAndStatus(COMPANY, TRIP_ID, WaterfallStatus.ACTIVE))
                .thenReturn(Optional.empty());

        assertThat(service.autoAdvance(SCOPE, TRIP_ID)).isEqualTo(AutoAdvanceOutcome.NOT_RUNNING);
        verifyNoInteractions(tenderService);
    }

    @Test
    @DisplayName("the view carries whether this deployment advances on its own")
    void viewSaysAutoAdvance() {
        TenderWaterfall waterfall = running(STARTER);
        when(waterfallRepository.findByCompanyIdAndTripIdOrderByStartedAtDesc(COMPANY, TRIP_ID))
                .thenReturn(java.util.List.of(waterfall));

        assertThat(service.current(SCOPE, TRIP_ID)).hasValueSatisfying(view ->
                assertThat(view.autoAdvance()).isTrue());
    }

    // ---------------------------------------------------------------------------------------

    private TenderWaterfall running(UUID startedBy) {
        TenderWaterfall waterfall = new TenderWaterfall(COMPANY, TRIP_ID, 4, 120, NOW.minusHours(3), startedBy);
        waterfall.addCandidate(CARRIER_A, new BigDecimal("820.00"), "PEN", null);
        waterfall.addCandidate(CARRIER_B, new BigDecimal("910.00"), "PEN", null);
        when(waterfallRepository.findByCompanyIdAndTripIdAndStatus(COMPANY, TRIP_ID, WaterfallStatus.ACTIVE))
                .thenReturn(Optional.of(waterfall));
        return waterfall;
    }

    /** Rank 1's offer, sent two hours ago, with the given deadline. */
    private static TripTender sentTender(TenderWaterfall waterfall, OffsetDateTime expiresAt) {
        TripTender tender = new TripTender(COMPANY, TRIP_ID, CARRIER_A, 1, new BigDecimal("820.00"), "PEN", null,
                expiresAt, STARTER);
        UUID id = UUID.randomUUID();
        ReflectionTestUtils.setField(tender, "id", id);
        tender.send(NOW.minusHours(2), STARTER);
        candidate(waterfall, 1).offered(id);
        return tender;
    }

    private static TenderWaterfallCandidate candidate(TenderWaterfall waterfall, int rank) {
        return waterfall.candidates().stream().filter(entry -> entry.rank() == rank).findFirst().orElseThrow();
    }

    private static Trip trip() {
        Trip trip = new Trip(COMPANY, UUID.randomUUID(), LocalDate.of(2026, 8, 28), 1, "SH-00000077",
                UUID.randomUUID(), CARRIER_A, NOW.minusHours(1), STARTER);
        ReflectionTestUtils.setField(trip, "id", TRIP_ID);
        return trip;
    }
}
