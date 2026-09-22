package com.ebim.tms.planning.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.ebim.tms.planning.domain.TenderStatus;
import com.ebim.tms.planning.domain.TenderWaterfall;
import com.ebim.tms.planning.domain.TenderWaterfallCandidate;
import com.ebim.tms.planning.domain.Trip;
import com.ebim.tms.planning.domain.TripStatus;
import com.ebim.tms.planning.domain.TripTender;
import com.ebim.tms.planning.domain.WaterfallCandidateStatus;
import com.ebim.tms.planning.domain.WaterfallStatus;
import com.ebim.tms.planning.infrastructure.TenderWaterfallRepository;
import com.ebim.tms.planning.infrastructure.TripRepository;
import com.ebim.tms.planning.infrastructure.TripTenderRepository;
import com.ebim.tms.shared.audit.AuditActor;
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
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * What {@link TenderWaterfallService#tenderAnswered} does with an answer, and in particular the one
 * thing it must do differently depending on <em>who</em> answered.
 *
 * <p>Recording an answer and sending the next offer are two different rights. Anybody who may
 * answer may have their answer written down; only a person may commit the company to another offer,
 * because {@code TripTenderService.createFor} goes through
 * {@code AuditActorProvider.requireAppUserId} and that refuses a machine by design. So a rejection
 * arriving over the integration API has to settle the candidate and stop, and a rejection typed in
 * by a planner has to walk on to rank 2 - and neither may throw the other's answer away.
 *
 * <p>Mocked rather than database-backed, for the reason {@code TripTenderServiceTest} gives: none of
 * this is about persistence, and the persistence half belongs to the Testcontainers tests.
 */
class TenderWaterfallResponseRoutingTest {

    private static final UUID COMPANY = UUID.randomUUID();
    private static final UUID TRIP_ID = UUID.randomUUID();
    private static final UUID RUN = UUID.randomUUID();
    private static final UUID VEHICLE = UUID.randomUUID();
    private static final UUID CARRIER_A = UUID.nameUUIDFromBytes("carrier-a".getBytes());
    private static final UUID CARRIER_B = UUID.nameUUIDFromBytes("carrier-b".getBytes());
    private static final UUID TENDER_ID = UUID.randomUUID();
    private static final UUID PLANNER = UUID.randomUUID();
    private static final String SHIPMENT = "SH-00000042";

    private static final CompanyScope SCOPE = new CompanyScope(COMPANY, "CO-A", "Company A", "America/Lima",
            UUID.randomUUID(), "ORG", "Organization", java.util.Set.of());

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

        service = new TenderWaterfallService(waterfallRepository, mock(TripRepository.class),
                tenderRepository, tenderService, mock(CarrierLookupPort.class),
                mock(CarrierQuotationPort.class), mock(TripCostingLookupPort.class), actors,
                mock(AuditRecorder.class), new SimpleMeterRegistry(),
                Clock.fixed(Instant.parse("2026-08-28T09:00:00Z"), ZoneOffset.UTC));
    }

    @Test
    @DisplayName("a planner's rejection settles rank 1 and offers rank 2")
    void aPersonsRejectionWalksOn() {
        TenderWaterfall waterfall = running();
        TripTender tender = offeredTender(waterfall);
        actingAs(AuditActor.person(PLANNER, "planner@example.com", COMPANY, SCOPE.organizationId(), null));
        // What createFor leaves behind for offerNext to pick up and send.
        TripTender nextOffer = new TripTender(COMPANY, TRIP_ID, CARRIER_B, 2,
                new BigDecimal("910.00"), "PEN", null, null, PLANNER);
        ReflectionTestUtils.setField(nextOffer, "id", UUID.randomUUID());
        when(tenderRepository.findLive(COMPANY, TRIP_ID)).thenReturn(Optional.of(nextOffer));

        service.tenderAnswered(SCOPE, trip(), tender, TenderStatus.REJECTED);

        assertThat(candidate(waterfall, 1).status()).isEqualTo(WaterfallCandidateStatus.REJECTED);
        assertThat(candidate(waterfall, 2).status()).isEqualTo(WaterfallCandidateStatus.OFFERED);
        verify(tenderService).createFor(any(), any(), any(), any());
    }

    @Test
    @DisplayName("a carrier's own rejection settles rank 1 and waits for a dispatcher")
    void aMachinesRejectionIsRecordedAndStopsThere() {
        TenderWaterfall waterfall = running();
        TripTender tender = offeredTender(waterfall);
        actingAs(AuditActor.machine("acme-edi", COMPANY, SCOPE.organizationId(), null));

        service.tenderAnswered(SCOPE, trip(), tender, TenderStatus.REJECTED);

        // The fact is written down - this is the whole reason the integration path reports here at
        // all - and the waterfall is left at rank 1 for a dispatcher to advance, because an offer
        // to a carrier is a commercial commitment and a machine has no app_user to sign it.
        assertThat(candidate(waterfall, 1).status()).isEqualTo(WaterfallCandidateStatus.REJECTED);
        assertThat(candidate(waterfall, 2).status()).isEqualTo(WaterfallCandidateStatus.PENDING);
        assertThat(waterfall.status()).isEqualTo(WaterfallStatus.ACTIVE);
        verify(tenderService, never()).createFor(any(), any(), any(), any());
    }

    @Test
    @DisplayName("a carrier's own acceptance ends the waterfall, machine or not")
    void aMachinesAcceptanceEndsIt() {
        TenderWaterfall waterfall = running();
        TripTender tender = offeredTender(waterfall);
        actingAs(AuditActor.machine("acme-edi", COMPANY, SCOPE.organizationId(), null));

        service.tenderAnswered(SCOPE, trip(), tender, TenderStatus.ACCEPTED);

        // Ending a waterfall commits the company to nothing, so the actor rule does not apply:
        // leaving it ACTIVE over a shipment already placed is the ambiguous state this exists to
        // prevent. Rank 2 is SKIPPED rather than PENDING - it was on the list and was never asked.
        assertThat(waterfall.status()).isEqualTo(WaterfallStatus.ACCEPTED);
        assertThat(candidate(waterfall, 1).status()).isEqualTo(WaterfallCandidateStatus.ACCEPTED);
        assertThat(candidate(waterfall, 2).status()).isEqualTo(WaterfallCandidateStatus.SKIPPED);
        verify(tenderService, never()).createFor(any(), any(), any(), any());
    }

    @Test
    @DisplayName("a withdrawal ends the waterfall instead of routing around it")
    void aWithdrawalEndsIt() {
        TenderWaterfall waterfall = running();
        TripTender tender = offeredTender(waterfall);
        actingAs(AuditActor.person(PLANNER, "planner@example.com", COMPANY, SCOPE.organizationId(), null));

        service.tenderAnswered(SCOPE, trip(), tender, TenderStatus.CANCELLED);

        assertThat(waterfall.status()).isEqualTo(WaterfallStatus.CANCELLED);
        verify(tenderService, never()).createFor(any(), any(), any(), any());
    }

    @Test
    @DisplayName("a shipment that stops being offerable ends its waterfall with no tender to point at")
    void aCancelledShipmentEndsIt() {
        TenderWaterfall waterfall = running();
        // No offer out: the waterfall is between two candidates, which is exactly the state a
        // tender-shaped notification cannot describe.
        when(waterfallRepository.findByCompanyIdAndTripIdAndStatus(COMPANY, TRIP_ID, WaterfallStatus.ACTIVE))
                .thenReturn(Optional.of(waterfall));

        service.shipmentNoLongerOfferable(SCOPE, trip(), "Shipment SH-00000042 was cancelled.");

        assertThat(waterfall.status()).isEqualTo(WaterfallStatus.CANCELLED);
        assertThat(waterfall.candidates()).allMatch(c -> c.status() == WaterfallCandidateStatus.SKIPPED);
    }

    @Test
    @DisplayName("a shipment on no waterfall is left entirely alone")
    void noWaterfallIsANoOp() {
        when(waterfallRepository.findByCompanyIdAndTripIdAndStatus(COMPANY, TRIP_ID, WaterfallStatus.ACTIVE))
                .thenReturn(Optional.empty());

        service.shipmentNoLongerOfferable(SCOPE, trip(), "Shipment cancelled.");

        verify(waterfallRepository, never()).saveAndFlush(any());
    }

    // ---------------------------------------------------------------------------------------

    /** Two candidates, rank 1 and rank 2, on an ACTIVE waterfall the repository will hand back. */
    private TenderWaterfall running() {
        TenderWaterfall waterfall = new TenderWaterfall(COMPANY, TRIP_ID, 4, 120,
                OffsetDateTime.parse("2026-08-28T06:00:00Z"), PLANNER);
        waterfall.addCandidate(CARRIER_A, new BigDecimal("820.00"), "PEN", UUID.randomUUID());
        waterfall.addCandidate(CARRIER_B, new BigDecimal("910.00"), "PEN", UUID.randomUUID());
        when(waterfallRepository.findByCompanyIdAndTripIdAndStatus(COMPANY, TRIP_ID, WaterfallStatus.ACTIVE))
                .thenReturn(Optional.of(waterfall));
        return waterfall;
    }

    /** Rank 1's offer, out and unanswered, with the tender that carries it. */
    private static TripTender offeredTender(TenderWaterfall waterfall) {
        TripTender tender = new TripTender(COMPANY, TRIP_ID, CARRIER_A, 1,
                new BigDecimal("820.00"), "PEN", null, null, PLANNER);
        // JPA would have done this. The service matches a candidate to its tender by id, so without
        // it every assertion below would be about the "not on this waterfall" branch instead.
        ReflectionTestUtils.setField(tender, "id", TENDER_ID);
        candidate(waterfall, 1).offered(TENDER_ID);
        return tender;
    }

    private static TenderWaterfallCandidate candidate(TenderWaterfall waterfall, int rank) {
        return waterfall.candidates().stream().filter(entry -> entry.rank() == rank).findFirst().orElseThrow();
    }

    private void actingAs(AuditActor actor) {
        when(actors.current()).thenReturn(Optional.of(actor));
        when(actors.requireAppUserId()).thenReturn(actor.appUserId());
    }

    private static Trip trip() {
        Trip trip = new Trip(COMPANY, RUN, LocalDate.of(2026, 8, 28), 1, SHIPMENT, VEHICLE, CARRIER_A,
                OffsetDateTime.parse("2026-08-28T06:00:00Z"), PLANNER);
        trip.confirm(BigDecimal.valueOf(8000), BigDecimal.valueOf(32), 18, PLANNER);
        ReflectionTestUtils.setField(trip, "id", TRIP_ID);
        assertThat(trip.status()).isEqualTo(TripStatus.CONFIRMED);
        return trip;
    }
}
