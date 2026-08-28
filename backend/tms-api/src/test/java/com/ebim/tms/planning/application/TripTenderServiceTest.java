package com.ebim.tms.planning.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.ebim.tms.planning.domain.ShipmentEventType;
import com.ebim.tms.planning.domain.TenderResponseSource;
import com.ebim.tms.planning.domain.TenderStatus;
import com.ebim.tms.planning.domain.Trip;
import com.ebim.tms.planning.domain.TripStatus;
import com.ebim.tms.planning.domain.TripTender;
import com.ebim.tms.planning.infrastructure.PlanningRunRepository;
import com.ebim.tms.planning.infrastructure.TripRepository;
import com.ebim.tms.planning.infrastructure.TripTenderRepository;
import com.ebim.tms.shared.api.ConflictException;
import com.ebim.tms.shared.api.InvalidRequestException;
import com.ebim.tms.shared.api.ResourceNotFoundException;
import com.ebim.tms.shared.audit.AuditActorProvider;
import com.ebim.tms.shared.reference.CarrierLookupPort;
import com.ebim.tms.shared.reference.CarrierTenderOffer;
import com.ebim.tms.shared.reference.OriginLookupPort;
import com.ebim.tms.shared.security.CompanyScope;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * The rules {@link TripTenderService} adds on top of the transition table: which shipments may be
 * offered, the one-live-offer and one-acceptance invariants, what each transition publishes, and
 * how a lapse is resolved without a scheduler.
 *
 * <p>Mocked rather than database-backed, for the reason {@link TripExecutionServiceTest} gives:
 * none of these rules is about persistence, and the persistence half - the row lock, the two partial
 * unique indexes, the V31 CHECK constraints, the outbox row actually landing - belongs to the
 * Testcontainers tests. This file runs everywhere.
 *
 * <p><b>The trip is given an id.</b> {@code Trip}'s own id is assigned by JPA and stays null without
 * a database, and the service legitimately compares a tender's {@code tripId} against it to refuse a
 * tender id borrowed from another of this company's trips. Setting the field is what lets that
 * guard be exercised rather than skipped.
 */
class TripTenderServiceTest {

    private static final UUID COMPANY = UUID.randomUUID();
    private static final UUID RUN = UUID.randomUUID();
    private static final UUID TRIP_ID = UUID.randomUUID();
    private static final UUID VEHICLE = UUID.randomUUID();
    private static final UUID CARRIER = UUID.randomUUID();
    private static final UUID PLANNER = UUID.randomUUID();
    private static final UUID CLIENT = UUID.randomUUID();
    private static final UUID TENDER_ID = UUID.randomUUID();

    private static final String SHIPMENT = "SH-00000001";
    private static final LocalDate PLANNING_DATE = LocalDate.of(2026, 8, 24);
    private static final OffsetDateTime PLANNED_DEPARTURE = OffsetDateTime.parse("2026-08-24T06:00:00Z");

    private static final CompanyScope SCOPE = new CompanyScope(COMPANY, "CO-A", "Company A", "America/Lima",
            UUID.randomUUID(), "ORG", "Organization", Set.of());

    private TripRepository tripRepository;
    private TripTenderRepository tenderRepository;
    private ShipmentEventPublisher events;
    private TripTenderService service;
    private TenderWaterfallService waterfall;

    @BeforeEach
    void setUp() {
        tripRepository = mock(TripRepository.class);
        tenderRepository = mock(TripTenderRepository.class);
        events = mock(ShipmentEventPublisher.class);
        AuditActorProvider actors = mock(AuditActorProvider.class);
        when(actors.requireAppUserId()).thenReturn(PLANNER);
        when(actors.writerAppUserId()).thenReturn(PLANNER);

        // A waterfall that is never on any of these shipments: every assertion in this class is
        // about hand-made tendering, which must go on behaving exactly as it did.
        waterfall = mock(TenderWaterfallService.class);
        org.springframework.beans.factory.ObjectProvider<TenderWaterfallService> waterfallProvider =
                mock(org.springframework.beans.factory.ObjectProvider.class);
        when(waterfallProvider.getObject()).thenReturn(waterfall);

        service = new TripTenderService(tripRepository, tenderRepository, mock(PlanningRunRepository.class),
                mock(CarrierLookupPort.class), mock(OriginLookupPort.class), events,
                mock(TripAlertPublisher.class), actors,
                waterfallProvider);

        when(tenderRepository.saveAndFlush(any(TripTender.class))).thenAnswer(call -> call.getArgument(0));
    }

    @Nested
    @DisplayName("offering a shipment")
    class Creating {

        @Test
        @DisplayName("prepares a draft on a confirmed shipment")
        void aConfirmedShipmentCanBeOffered() {
            locked(TripStatus.CONFIRMED);

            service.create(SCOPE, TRIP_ID, new TenderRequest(new BigDecimal("1240.00"), "pen", " Gate B ", null));

            TripTender saved = savedTender();
            assertThat(saved.status()).isEqualTo(TenderStatus.DRAFT);
            assertThat(saved.carrierId()).isEqualTo(CARRIER);
            assertThat(saved.attempt()).isEqualTo(1);
            // The currency is normalised and the notes are trimmed by the service, not by the entity.
            assertThat(saved.currency()).isEqualTo("PEN");
            assertThat(saved.notes()).isEqualTo("Gate B");
            // A draft publishes nothing: nobody has been told anything yet.
            verify(events, never()).publish(any(), any(), any(), any(), anyMap());
        }

        @Test
        @DisplayName("counts attempts from the highest already used")
        void attemptsAreNumbered() {
            locked(TripStatus.CONFIRMED);
            when(tenderRepository.maxAttempt(TRIP_ID)).thenReturn(2);

            service.create(SCOPE, TRIP_ID, new TenderRequest(null, null, null, null));

            assertThat(savedTender().attempt()).isEqualTo(3);
        }

        @Test
        @DisplayName("refuses a shipment that is still a draft")
        void aDraftShipmentIsNotOfferable() {
            locked(TripStatus.DRAFT);

            assertThatExceptionOfType(ConflictException.class)
                    .isThrownBy(() -> service.create(SCOPE, TRIP_ID, empty()))
                    .withMessageContaining("DRAFT")
                    .withMessageContaining("cannot be offered");
        }

        @Test
        @DisplayName("refuses a shipment that has already left")
        void aDepartedShipmentIsNotOfferable() {
            locked(TripStatus.IN_TRANSIT);

            assertThatExceptionOfType(ConflictException.class)
                    .isThrownBy(() -> service.create(SCOPE, TRIP_ID, empty()))
                    .withMessageContaining("IN_TRANSIT");
        }

        @Test
        @DisplayName("refuses a second live offer on the same shipment")
        void oneLiveOfferAtATime() {
            locked(TripStatus.CONFIRMED);
            when(tenderRepository.findLive(COMPANY, TRIP_ID)).thenReturn(Optional.of(sentTender(null)));

            assertThatExceptionOfType(ConflictException.class)
                    .isThrownBy(() -> service.create(SCOPE, TRIP_ID, empty()))
                    .withMessageContaining("already has an open tender");
        }

        @Test
        @DisplayName("refuses to re-offer a shipment a carrier has already accepted")
        void anAcceptedShipmentIsPlaced() {
            locked(TripStatus.CONFIRMED);
            TripTender accepted = sentTender(null);
            accepted.accept(OffsetDateTime.now(), TenderResponseSource.OPERATOR, PLANNER, null, null);
            when(tenderRepository.findByCompanyIdAndTripIdAndStatus(COMPANY, TRIP_ID, TenderStatus.ACCEPTED))
                    .thenReturn(Optional.of(accepted));

            assertThatExceptionOfType(ConflictException.class)
                    .isThrownBy(() -> service.create(SCOPE, TRIP_ID, empty()))
                    .withMessageContaining("already been accepted");
        }

        @Test
        @DisplayName("frees the live slot when the offer holding it has lapsed")
        void aLapsedOfferDoesNotBlockTheNextAttempt() {
            locked(TripStatus.CONFIRMED);
            TripTender lapsed = sentTender(OffsetDateTime.now().minusHours(1));
            when(tenderRepository.findLive(COMPANY, TRIP_ID)).thenReturn(Optional.of(lapsed));

            service.create(SCOPE, TRIP_ID, empty());

            assertThat(lapsed.status()).isEqualTo(TenderStatus.EXPIRED);
            assertThat(publishedEvents()).contains(ShipmentEventType.TENDER_EXPIRED);
        }
    }

    @Nested
    @DisplayName("sending")
    class Sending {

        @Test
        @DisplayName("publishes TENDER_SENT")
        void sendPublishes() {
            locked(TripStatus.CONFIRMED);
            TripTender draft = draftTender(null);
            when(tenderRepository.findByIdAndCompanyId(TENDER_ID, COMPANY)).thenReturn(Optional.of(draft));

            service.send(SCOPE, TRIP_ID, TENDER_ID);

            assertThat(draft.status()).isEqualTo(TenderStatus.SENT);
            assertThat(draft.sentBy()).isEqualTo(PLANNER);
            assertThat(publishedEvents()).containsExactly(ShipmentEventType.TENDER_SENT);
        }

        @Test
        @DisplayName("answers a retry with the sent offer instead of an error")
        void sendIsIdempotent() {
            locked(TripStatus.CONFIRMED);
            TripTender already = sentTender(null);
            when(tenderRepository.findByIdAndCompanyId(TENDER_ID, COMPANY)).thenReturn(Optional.of(already));

            service.send(SCOPE, TRIP_ID, TENDER_ID);

            verify(events, never()).publish(any(), any(), any(), any(), anyMap());
        }

        @Test
        @DisplayName("refuses a draft whose deadline has already gone by")
        void aStaleDeadlineIsRefusedBeforeSending() {
            locked(TripStatus.CONFIRMED);
            when(tenderRepository.findByIdAndCompanyId(TENDER_ID, COMPANY))
                    .thenReturn(Optional.of(draftTender(OffsetDateTime.now().minusMinutes(5))));

            assertThatExceptionOfType(InvalidRequestException.class)
                    .isThrownBy(() -> service.send(SCOPE, TRIP_ID, TENDER_ID))
                    .withMessageContaining("has already");
        }

        @Test
        @DisplayName("refuses to send on a shipment that has been cancelled since the draft was written")
        void theShipmentIsRecheckedAtSendTime() {
            locked(TripStatus.CANCELLED);
            when(tenderRepository.findByIdAndCompanyId(TENDER_ID, COMPANY))
                    .thenReturn(Optional.of(draftTender(null)));

            assertThatExceptionOfType(ConflictException.class)
                    .isThrownBy(() -> service.send(SCOPE, TRIP_ID, TENDER_ID))
                    .withMessageContaining("CANCELLED");
        }

        @Test
        @DisplayName("refuses a tender id that belongs to another of this company's shipments")
        void aTenderIsScopedToItsShipment() {
            locked(TripStatus.CONFIRMED);
            TripTender otherTrips = new TripTender(COMPANY, UUID.randomUUID(), CARRIER, 1, null, null, null, null,
                    PLANNER);
            when(tenderRepository.findByIdAndCompanyId(TENDER_ID, COMPANY)).thenReturn(Optional.of(otherTrips));

            assertThatExceptionOfType(ResourceNotFoundException.class)
                    .isThrownBy(() -> service.send(SCOPE, TRIP_ID, TENDER_ID));
        }
    }

    @Nested
    @DisplayName("answering in the UI")
    class Answering {

        @Test
        @DisplayName("stamps an acceptance as the operator's, not the carrier's own")
        void acceptIsStampedOperator() {
            locked(TripStatus.CONFIRMED);
            TripTender tender = sentTender(null);
            when(tenderRepository.findByIdAndCompanyId(TENDER_ID, COMPANY)).thenReturn(Optional.of(tender));

            service.accept(SCOPE, TRIP_ID, TENDER_ID, new TenderResponseRequest("12t confirmed"));

            assertThat(tender.status()).isEqualTo(TenderStatus.ACCEPTED);
            assertThat(tender.responseSource()).isEqualTo(TenderResponseSource.OPERATOR);
            assertThat(tender.respondedBy()).isEqualTo(PLANNER);
            assertThat(publishedEvents()).containsExactly(ShipmentEventType.TENDER_ACCEPTED);
        }

        @Test
        @DisplayName("refuses a rejection with no reason")
        void rejectNeedsAReason() {
            locked(TripStatus.CONFIRMED);
            when(tenderRepository.findByIdAndCompanyId(TENDER_ID, COMPANY)).thenReturn(Optional.of(sentTender(null)));

            assertThatExceptionOfType(InvalidRequestException.class)
                    .isThrownBy(() -> service.reject(SCOPE, TRIP_ID, TENDER_ID, new TenderResponseRequest("  ")))
                    .withMessageContaining("notes are required");
        }

        @Test
        @DisplayName("keeps the carrier's reason on a refusal and publishes it")
        void rejectPublishes() {
            locked(TripStatus.CONFIRMED);
            TripTender tender = sentTender(null);
            when(tenderRepository.findByIdAndCompanyId(TENDER_ID, COMPANY)).thenReturn(Optional.of(tender));

            service.reject(SCOPE, TRIP_ID, TENDER_ID, new TenderResponseRequest("No 12t on the 24th"));

            assertThat(tender.status()).isEqualTo(TenderStatus.REJECTED);
            assertThat(tender.responseNotes()).isEqualTo("No 12t on the 24th");
            assertThat(publishedEvents()).containsExactly(ShipmentEventType.TENDER_REJECTED);
        }

        @Test
        @DisplayName("refuses an answer that arrives after the deadline")
        void aLateAnswerIsRefused() {
            locked(TripStatus.CONFIRMED);
            when(tenderRepository.findByIdAndCompanyId(TENDER_ID, COMPANY))
                    .thenReturn(Optional.of(sentTender(OffsetDateTime.now().minusMinutes(1))));

            // The refusal is computed from the effective status and needs no write, which is what
            // makes it correct even though this call's own attempt to materialise the lapse is
            // rolled back with the transaction. Whether the row catches up is asserted on the paths
            // that succeed - see Creating.aLapsedOfferDoesNotBlockTheNextAttempt.
            assertThatExceptionOfType(ConflictException.class)
                    .isThrownBy(() -> service.accept(SCOPE, TRIP_ID, TENDER_ID, new TenderResponseRequest(null)))
                    .withMessageContaining("EXPIRED");
        }
    }

    @Nested
    @DisplayName("withdrawing")
    class Withdrawing {

        @Test
        @DisplayName("publishes TENDER_CANCELLED when the offer had gone out")
        void withdrawingASentOfferTellsTheCarrier() {
            locked(TripStatus.CONFIRMED);
            TripTender tender = sentTender(null);
            when(tenderRepository.findByIdAndCompanyId(TENDER_ID, COMPANY)).thenReturn(Optional.of(tender));

            service.withdraw(SCOPE, TRIP_ID, TENDER_ID, new TenderWithdrawRequest("Replanned"));

            assertThat(tender.status()).isEqualTo(TenderStatus.CANCELLED);
            assertThat(tender.cancelReason()).isEqualTo("Replanned");
            assertThat(publishedEvents()).containsExactly(ShipmentEventType.TENDER_CANCELLED);
        }

        @Test
        @DisplayName("publishes nothing when the offer was still a draft")
        void discardingADraftTellsNobody() {
            locked(TripStatus.CONFIRMED);
            TripTender draft = draftTender(null);
            when(tenderRepository.findByIdAndCompanyId(TENDER_ID, COMPANY)).thenReturn(Optional.of(draft));

            service.withdraw(SCOPE, TRIP_ID, TENDER_ID, new TenderWithdrawRequest("Mistake"));

            assertThat(draft.status()).isEqualTo(TenderStatus.CANCELLED);
            verify(events, never()).publish(any(), any(), any(), any(), anyMap());
        }

        @Test
        @DisplayName("takes a live offer off a shipment whose lifecycle has moved on")
        void withdrawOpenIsWhatTheLifecycleCalls() {
            Trip trip = locked(TripStatus.CANCELLED);
            TripTender tender = sentTender(null);
            when(tenderRepository.findLive(COMPANY, TRIP_ID)).thenReturn(Optional.of(tender));

            service.withdrawOpen(SCOPE, trip, "Shipment cancelled");

            assertThat(tender.status()).isEqualTo(TenderStatus.CANCELLED);
            assertThat(publishedEvents()).containsExactly(ShipmentEventType.TENDER_CANCELLED);
        }

        @Test
        @DisplayName("expires rather than withdraws an offer the deadline already killed")
        void theDeadlineGetsThereFirst() {
            Trip trip = locked(TripStatus.IN_TRANSIT);
            TripTender lapsed = sentTender(OffsetDateTime.now().minusHours(2));
            when(tenderRepository.findLive(COMPANY, TRIP_ID)).thenReturn(Optional.of(lapsed));

            service.withdrawOpen(SCOPE, trip, "Departed without an answer");

            assertThat(lapsed.status()).isEqualTo(TenderStatus.EXPIRED);
            assertThat(publishedEvents()).containsExactly(ShipmentEventType.TENDER_EXPIRED);
        }

        @Test
        @DisplayName("does nothing at all on a shipment that was never offered")
        void nothingLiveIsANoOp() {
            Trip trip = locked(TripStatus.CONFIRMED);

            service.withdrawOpen(SCOPE, trip, "Shipment cancelled");

            verify(events, never()).publish(any(), any(), any(), any(), anyMap());
        }
    }

    @Nested
    @DisplayName("the carrier answering for itself")
    class CarrierResponse {

        @Test
        @DisplayName("stamps the credential rather than a person")
        void anIntegrationAnswerNamesItsKey() {
            lockedByNumber(TripStatus.CONFIRMED);
            TripTender tender = sentTender(null);
            when(tenderRepository.findByCompanyIdAndTripIdOrderByAttemptDesc(COMPANY, TRIP_ID))
                    .thenReturn(List.of(tender));

            CarrierTenderOffer answered =
                    service.respondAsCarrier(SCOPE, CARRIER, SHIPMENT, true, "Sending the 12t", CLIENT);

            assertThat(tender.status()).isEqualTo(TenderStatus.ACCEPTED);
            assertThat(tender.responseSource()).isEqualTo(TenderResponseSource.INTEGRATION);
            assertThat(tender.respondedByClient()).isEqualTo(CLIENT);
            assertThat(tender.respondedBy()).isNull();
            assertThat(answered.status()).isEqualTo("ACCEPTED");
            assertThat(answered.shipmentNumber()).isEqualTo(SHIPMENT);
            assertThat(publishedEvents()).containsExactly(ShipmentEventType.TENDER_ACCEPTED);
        }

        @Test
        @DisplayName("replays the recorded answer when the same decision arrives twice")
        void resendingTheSameDecisionIsANoOp() {
            lockedByNumber(TripStatus.CONFIRMED);
            TripTender tender = sentTender(null);
            tender.accept(OffsetDateTime.now(), TenderResponseSource.INTEGRATION, null, CLIENT, null);
            when(tenderRepository.findByCompanyIdAndTripIdOrderByAttemptDesc(COMPANY, TRIP_ID))
                    .thenReturn(List.of(tender));

            CarrierTenderOffer answered = service.respondAsCarrier(SCOPE, CARRIER, SHIPMENT, true, null, CLIENT);

            assertThat(answered.status()).isEqualTo("ACCEPTED");
            verify(events, never()).publish(any(), any(), any(), any(), anyMap());
        }

        @Test
        @DisplayName("refuses to reverse an answer already given")
        void thereIsNoTakingItBack() {
            lockedByNumber(TripStatus.CONFIRMED);
            TripTender tender = sentTender(null);
            tender.accept(OffsetDateTime.now(), TenderResponseSource.INTEGRATION, null, CLIENT, null);
            when(tenderRepository.findByCompanyIdAndTripIdOrderByAttemptDesc(COMPANY, TRIP_ID))
                    .thenReturn(List.of(tender));

            assertThatExceptionOfType(ConflictException.class)
                    .isThrownBy(() -> service.respondAsCarrier(SCOPE, CARRIER, SHIPMENT, false, "changed", CLIENT))
                    .withMessageContaining("cannot be reversed");
        }

        @Test
        @DisplayName("hides a shipment this carrier was never offered behind the same 404")
        void anotherCarriersShipmentIsNotDiscoverable() {
            lockedByNumber(TripStatus.CONFIRMED);
            when(tenderRepository.findByCompanyIdAndTripIdOrderByAttemptDesc(COMPANY, TRIP_ID))
                    .thenReturn(List.of(sentTender(null)));

            assertThatExceptionOfType(ResourceNotFoundException.class)
                    .isThrownBy(() -> service.respondAsCarrier(
                            SCOPE, UUID.randomUUID(), SHIPMENT, true, null, CLIENT))
                    .withMessage("No tender was found for this shipment.");
        }

        @Test
        @DisplayName("answers an unknown shipment number with exactly the same sentence")
        void anUnknownShipmentLooksIdentical() {
            when(tripRepository.findByShipmentNumberAndCompanyId("SH-00009999", COMPANY))
                    .thenReturn(Optional.empty());

            assertThatExceptionOfType(ResourceNotFoundException.class)
                    .isThrownBy(() -> service.respondAsCarrier(
                            SCOPE, CARRIER, "SH-00009999", true, null, CLIENT))
                    .withMessage("No tender was found for this shipment.");
        }

        @Test
        @DisplayName("requires a reason to refuse, exactly as the UI does")
        void aCarrierRefusalAlsoSaysWhy() {
            lockedByNumber(TripStatus.CONFIRMED);
            when(tenderRepository.findByCompanyIdAndTripIdOrderByAttemptDesc(COMPANY, TRIP_ID))
                    .thenReturn(List.of(sentTender(null)));

            assertThatExceptionOfType(InvalidRequestException.class)
                    .isThrownBy(() -> service.respondAsCarrier(SCOPE, CARRIER, SHIPMENT, false, null, CLIENT));
        }

        @Test
        @DisplayName("leaves lapsed offers out of the carrier's queue")
        void theQueueHoldsOnlyAnswerableOffers() {
            when(tenderRepository.findByCompanyIdAndCarrierIdAndStatusOrderBySentAtAsc(
                    COMPANY, CARRIER, TenderStatus.SENT))
                    .thenReturn(List.of(sentTender(OffsetDateTime.now().minusMinutes(1))));

            assertThat(service.openOffers(SCOPE, CARRIER, null)).isEmpty();
        }
    }

    // ---------------------------------------------------------------------------------------

    private static TenderRequest empty() {
        return new TenderRequest(null, null, null, null);
    }

    /** A trip in {@code status}, given an id, locked and ready for the service to find. */
    private Trip locked(TripStatus status) {
        Trip trip = walkTo(status);
        when(tripRepository.findByIdAndCompanyIdForUpdate(TRIP_ID, COMPANY)).thenReturn(Optional.of(trip));
        when(tripRepository.findByIdAndCompanyId(TRIP_ID, COMPANY)).thenReturn(Optional.of(trip));
        return trip;
    }

    /** The same, additionally resolvable by shipment number - the carrier's way in. */
    private Trip lockedByNumber(TripStatus status) {
        Trip trip = locked(status);
        when(tripRepository.findByShipmentNumberAndCompanyId(SHIPMENT, COMPANY)).thenReturn(Optional.of(trip));
        return trip;
    }

    private static Trip walkTo(TripStatus target) {
        Trip trip = new Trip(COMPANY, RUN, PLANNING_DATE, 1, SHIPMENT, VEHICLE, CARRIER, PLANNED_DEPARTURE, PLANNER);
        if (target != TripStatus.DRAFT) {
            trip.confirm(BigDecimal.valueOf(8000), BigDecimal.valueOf(32), 18, PLANNER);
        }
        if (target == TripStatus.READY_FOR_DISPATCH || target == TripStatus.IN_TRANSIT) {
            trip.markReadyForDispatch(trip.confirmedAt(), PLANNER);
        }
        if (target == TripStatus.IN_TRANSIT) {
            trip.dispatch(trip.readyAt(), PLANNER);
        }
        if (target == TripStatus.CANCELLED) {
            trip.cancel("Not running", PLANNER);
        }
        // JPA would have done this. Without it the service's "does this tender belong to this trip"
        // guard could never pass, and every test below would be asserting a 404.
        ReflectionTestUtils.setField(trip, "id", TRIP_ID);
        return trip;
    }

    private static TripTender draftTender(OffsetDateTime expiresAt) {
        return new TripTender(COMPANY, TRIP_ID, CARRIER, 1, null, null, null, expiresAt, PLANNER);
    }

    private static TripTender sentTender(OffsetDateTime expiresAt) {
        TripTender tender = new TripTender(COMPANY, TRIP_ID, CARRIER, 1, null, null, null, expiresAt, PLANNER);
        // Sent long before any deadline a test sets, so the entity's own "a deadline must be after
        // the moment of sending" rule never fires in a fixture that is about something else. Every
        // deadline below is expressed relative to now, so this has to sit behind all of them.
        tender.send(OffsetDateTime.now().minusDays(1), PLANNER);
        return tender;
    }

    private TripTender savedTender() {
        ArgumentCaptor<TripTender> captor = ArgumentCaptor.forClass(TripTender.class);
        verify(tenderRepository).saveAndFlush(captor.capture());
        return captor.getValue();
    }

    private List<ShipmentEventType> publishedEvents() {
        ArgumentCaptor<ShipmentEventType> captor = ArgumentCaptor.forClass(ShipmentEventType.class);
        verify(events, atLeast(0)).publish(eq(SCOPE), any(Trip.class), captor.capture(), any(), anyMap());
        return captor.getAllValues();
    }
}
