package com.ebim.tms.planning.application;

import com.ebim.tms.planning.domain.PlanningRun;
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
import com.ebim.tms.shared.notification.NotificationType;
import com.ebim.tms.shared.reference.CarrierLookupPort;
import com.ebim.tms.shared.reference.CarrierTenderOffer;
import com.ebim.tms.shared.reference.MasterReference;
import com.ebim.tms.shared.reference.OriginLookupPort;
import com.ebim.tms.shared.security.CompanyScope;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Carrier tendering: offering a planned shipment to the carrier that is meant to run it, and
 * recording what they answered (migration V31, {@code docs/domain/CARRIER_TENDERING_V1.md}).
 *
 * <p><b>Which shipments may be offered.</b> {@code TENDERABLE} - confirmed, or ready and not yet
 * gone. Never a draft: the outbound shipment API refuses to expose one because its stops, load and
 * vehicle can all still change, and showing a carrier a plan that can be rewritten under them would
 * be worse than not showing it at all. Never after departure: at that point who is running the
 * shipment is a fact, not an offer.
 *
 * <p><b>Which carrier.</b> The trip's own, always. A trip's carrier comes from the vehicle planned
 * on it and the vehicle may only be swapped while the trip is a draft, so by the time a shipment is
 * offerable there is exactly one carrier it could be offered to - and letting a tender name another
 * would produce a shipment whose accepted tender and whose {@code carrier_id} disagree. Migration
 * V31 says what that costs and what widening it would need.
 *
 * <p><b>Concurrency.</b> Every mutation takes the trip's row lock first
 * ({@link TripRepository#findByIdAndCompanyIdForUpdate}), the same serialization point
 * {@code TripService} and {@code TripExecutionService} use - so a planner withdrawing an offer and
 * a carrier accepting it cannot interleave into a shipment that is both. The two partial unique
 * indexes ({@code uq_trip_tender_live}, {@code uq_trip_tender_accepted}) are the backstop for what
 * a row lock cannot cover: two requests served by two application instances at the same instant.
 *
 * <p><b>Expiry, with no scheduler.</b> Tendering has no timer of its own - the webhook dispatcher
 * (migration V35) is the only scheduled task in the product, and it delivers, it does not expire
 * anything. So a lapse is resolved in two places. Every read reports a sent offer past its deadline
 * as
 * {@link TenderStatus#EXPIRED} ({@code TripTender.effectiveStatus}), which is what makes it
 * unanswerable; {@link #resolveLapse} materialises it into the table on the next write that touches
 * the trip's tenders <em>and succeeds</em>, which is what frees the live slot and puts it in the
 * audit trail. A call that resolves a lapse and then refuses rolls its own write back with
 * everything else, and the next caller resolves it again - no correctness depends on which of the
 * two happens, because every refusal is computed from the effective status. Migration V31, section
 * 1b, states the consequence plainly.
 */
@Service
public class TripTenderService {

    /**
     * The states in which a shipment may be offered: committed to, and not yet gone. A trip that
     * has departed, completed or been cancelled has nothing left to offer, and a draft has nothing
     * stable to offer.
     */
    private static final Set<TripStatus> TENDERABLE =
            Set.of(TripStatus.CONFIRMED, TripStatus.READY_FOR_DISPATCH);

    /** Money, everywhere, at the scale {@code numeric(14,2)} stores. */
    private static final int MONEY_SCALE = 2;

    private final TripRepository tripRepository;
    private final TripTenderRepository tenderRepository;
    private final PlanningRunRepository planningRunRepository;
    private final CarrierLookupPort carrierLookupPort;
    private final OriginLookupPort originLookupPort;
    private final ShipmentEventPublisher events;
    private final TripAlertPublisher alerts;
    private final AuditActorProvider auditActorProvider;
    /**
     * The waterfall, if the shipment is on one (migration V40).
     *
     * <p>An {@code ObjectProvider} because the dependency is genuinely circular: a waterfall opens
     * tenders through this service, and this service tells the waterfall what a carrier answered.
     * The cycle is in the domain rather than in the wiring - offering and answering are two halves
     * of one conversation - and the alternatives are worse. An event would make the two records
     * eventually consistent when they must be written in one transaction; merging the classes would
     * put hand-made and automatic tendering in one file for no reason.
     */
    private final org.springframework.beans.factory.ObjectProvider<TenderWaterfallService> waterfallProvider;

    public TripTenderService(TripRepository tripRepository, TripTenderRepository tenderRepository,
            PlanningRunRepository planningRunRepository, CarrierLookupPort carrierLookupPort,
            OriginLookupPort originLookupPort, ShipmentEventPublisher events, TripAlertPublisher alerts,
            AuditActorProvider auditActorProvider,
            org.springframework.beans.factory.ObjectProvider<TenderWaterfallService> waterfallProvider) {
        this.waterfallProvider = waterfallProvider;
        this.tripRepository = tripRepository;
        this.tenderRepository = tenderRepository;
        this.planningRunRepository = planningRunRepository;
        this.carrierLookupPort = carrierLookupPort;
        this.originLookupPort = originLookupPort;
        this.events = events;
        this.alerts = alerts;
        this.auditActorProvider = auditActorProvider;
    }

    /** The waterfall service, resolved on use rather than injected - see the field. */
    private TenderWaterfallService waterfall() {
        return waterfallProvider.getObject();
    }

    // -----------------------------------------------------------------------------------------
    // Reads
    // -----------------------------------------------------------------------------------------

    /**
     * Every attempt made on one shipment, newest first.
     *
     * <p>The whole history and not only the live one: "we offered this to ACME twice and they said
     * no twice" is what somebody opening this card is looking for, and a screen showing only the
     * current attempt would hide exactly the thing that explains why the shipment is still unplaced.
     */
    @Transactional(readOnly = true)
    public List<TripTenderView> list(CompanyScope scope, UUID tripId) {
        Trip trip = tripRepository.findByIdAndCompanyId(tripId, scope.companyId())
                .orElseThrow(() -> new ResourceNotFoundException("Trip not found."));
        return toViews(scope, trip, OffsetDateTime.now());
    }

    // -----------------------------------------------------------------------------------------
    // The planner's side
    // -----------------------------------------------------------------------------------------

    /**
     * Prepares an offer. Draft: nothing has left this company, nothing is published and nothing is
     * audited until it is sent.
     *
     * <p>Three refusals, each with its own sentence. The shipment must be offerable, it must not
     * already be placed - an accepted tender is final, and re-offering a shipment somebody has
     * committed to is the first half of a double booking - and it must not already have a live
     * attempt. The third check runs <em>after</em> {@link #resolveLapse}, which is what lets a
     * planner tender again the moment the previous offer's deadline has passed.
     */
    @Transactional
    public List<TripTenderView> create(CompanyScope scope, UUID tripId, TenderRequest request) {
        return createFor(scope, tripId, null, request);
    }

    /**
     * Opens an offer to a <em>named</em> carrier, which the tender waterfall needs (JOB 07).
     *
     * <p>{@code create} offers to the shipment's own carrier - the one that owns its vehicle - and
     * that is the ordinary case. A waterfall offers the same shipment to carriers that do <b>not</b>
     * own its vehicle, which is what subcontracting is, so the carrier has to be stated rather than
     * derived.
     *
     * <p><b>What accepting does not do.</b> It records that this carrier agreed to run the
     * shipment. It does <em>not</em> reassign the trip's vehicle, because the vehicle is what
     * determines {@code trip.carrierId} today and silently changing it would leave a shipment whose
     * carrier and whose vehicle's owner disagreed. Putting one of the accepting carrier's vehicles
     * on the trip stays an explicit planner action - see the JOB 07 result's known limitations.
     *
     * @param carrierId the carrier to offer to, or null to offer to the shipment's own
     */
    @Transactional
    public List<TripTenderView> createFor(CompanyScope scope, UUID tripId, UUID carrierId,
            TenderRequest request) {
        Trip trip = lockedTrip(scope, tripId);
        requireTenderable(trip);
        UUID offeredTo = carrierId != null ? carrierId : requireCarrier(trip);
        UUID actorId = auditActorProvider.requireAppUserId();
        OffsetDateTime now = OffsetDateTime.now();

        requireNotPlaced(scope, trip);
        tenderRepository.findLive(scope.companyId(), trip.id()).ifPresent(live -> {
            resolveLapse(scope, trip, live, now, actorId);
            if (live.isLive()) {
                throw new ConflictException("Shipment " + trip.shipmentNumber() + " already has an open tender "
                        + "(attempt " + live.attempt() + "). Withdraw it before offering the shipment again.");
            }
        });

        TripTender tender = new TripTender(scope.companyId(), trip.id(), offeredTo,
                tenderRepository.maxAttempt(trip.id()) + 1, money(request.offeredAmount()),
                normalizeCurrency(request.currency()), blankToNull(request.notes()), request.expiresAt(), actorId);
        saveWithUniquenessBackstop(tender, trip);
        return toViews(scope, trip, now);
    }

    /**
     * Rewrites the terms of a draft. Refused once the offer has gone out - see
     * {@code TripTender.updateTerms} for why that refusal is the reason {@code DRAFT} is a state at
     * all.
     */
    @Transactional
    public List<TripTenderView> updateTerms(CompanyScope scope, UUID tripId, UUID tenderId, TenderRequest request) {
        Trip trip = lockedTrip(scope, tripId);
        TripTender tender = requireTender(scope, trip, tenderId);
        if (!tender.status().allowsTermEdits()) {
            throw new ConflictException("Tender " + tender.attempt() + " on shipment " + trip.shipmentNumber()
                    + " is " + tender.status() + " and its terms can no longer be changed.");
        }

        tender.updateTerms(money(request.offeredAmount()), normalizeCurrency(request.currency()),
                blankToNull(request.notes()), request.expiresAt(), auditActorProvider.requireAppUserId());
        tenderRepository.saveAndFlush(tender);
        return toViews(scope, trip, OffsetDateTime.now());
    }

    /**
     * Sends the offer out: the terms freeze, the outbox row lands and the carrier can answer.
     *
     * <p>The shipment is re-checked here and not only when the draft was prepared. A draft written
     * last night must not be sendable this morning on a shipment that has since been cancelled or
     * has already left - the same reasoning {@code TripExecutionService} gives for revalidating the
     * vehicle at every transition rather than trusting the board.
     *
     * <p>A retry of a send that already succeeded is answered with the sent tender rather than an
     * error, the rule every transition in this module follows.
     */
    @Transactional
    public List<TripTenderView> send(CompanyScope scope, UUID tripId, UUID tenderId) {
        Trip trip = lockedTrip(scope, tripId);
        TripTender tender = requireTender(scope, trip, tenderId);
        OffsetDateTime now = OffsetDateTime.now();
        if (tender.status() == TenderStatus.SENT) {
            return toViews(scope, trip, now);
        }
        requireTenderable(trip);
        requireTransition(trip, tender, TenderStatus.SENT);
        if (tender.expiresAt() != null && !tender.expiresAt().isAfter(now)) {
            throw new InvalidRequestException("This tender's deadline (" + tender.expiresAt() + ") has already "
                    + "passed. Change it before sending the offer.");
        }

        UUID actorId = auditActorProvider.requireAppUserId();
        tender.send(now, actorId);
        tenderRepository.saveAndFlush(tender);
        publish(scope, trip, tender, ShipmentEventType.TENDER_SENT, now);
        return toViews(scope, trip, now);
    }

    /**
     * Records that the carrier accepted, as told to a person - the phone call, the mail.
     *
     * <p>Stamped {@link TenderResponseSource#OPERATOR}, which is not a detail: it says the evidence
     * is a colleague's word rather than the carrier's own system, and that is what a dispute turns
     * on. The carrier answering for themselves goes through {@link #respondAsCarrier}.
     */
    @Transactional
    public List<TripTenderView> accept(CompanyScope scope, UUID tripId, UUID tenderId,
            TenderResponseRequest request) {
        Trip trip = lockedTrip(scope, tripId);
        TripTender tender = requireAnswerable(scope, trip, tenderId);
        UUID actorId = auditActorProvider.requireAppUserId();
        OffsetDateTime now = OffsetDateTime.now();

        tender.accept(now, TenderResponseSource.OPERATOR, actorId, null, blankToNull(request.notes()));
        saveWithUniquenessBackstop(tender, trip);
        recordAcceptanceOnTrip(trip, tender, actorId);
        publish(scope, trip, tender, ShipmentEventType.TENDER_ACCEPTED, now);
        // The waterfall, if this shipment is on one (V40). Told from here rather than from the
        // carrier-facing path as well, so the two records - the tender's status and the candidate's
        // - are written in the same transaction and cannot disagree.
        waterfall().tenderAnswered(scope, trip, tender, TenderStatus.ACCEPTED);
        return toViews(scope, trip, now);
    }

    /** Records that the carrier declined, with the reason they gave - which is mandatory. */
    @Transactional
    public List<TripTenderView> reject(CompanyScope scope, UUID tripId, UUID tenderId,
            TenderResponseRequest request) {
        Trip trip = lockedTrip(scope, tripId);
        TripTender tender = requireAnswerable(scope, trip, tenderId);
        String reason = requireReason(request.notes());
        UUID actorId = auditActorProvider.requireAppUserId();
        OffsetDateTime now = OffsetDateTime.now();

        tender.reject(now, TenderResponseSource.OPERATOR, actorId, null, reason);
        tenderRepository.saveAndFlush(tender);
        publish(scope, trip, tender, ShipmentEventType.TENDER_REJECTED, now);
        // A rejection is what a waterfall exists to route around: it offers the shipment to the
        // next carrier in the same transaction, so a "no" at 19:40 does not wait for morning.
        waterfall().tenderAnswered(scope, trip, tender, TenderStatus.REJECTED);
        return toViews(scope, trip, now);
    }

    /**
     * Pulls an offer back.
     *
     * <p>Publishes only when the offer had actually gone out: withdrawing a draft tells nobody
     * anything, because nobody was told about it in the first place - the same rule that keeps
     * {@link #create} out of the audit trail.
     */
    @Transactional
    public List<TripTenderView> withdraw(CompanyScope scope, UUID tripId, UUID tenderId,
            TenderWithdrawRequest request) {
        Trip trip = lockedTrip(scope, tripId);
        TripTender tender = requireTender(scope, trip, tenderId);
        OffsetDateTime now = OffsetDateTime.now();
        UUID actorId = auditActorProvider.requireAppUserId();

        resolveLapse(scope, trip, tender, now, actorId);
        // Two states are answered with the tender rather than an error, and no others. CANCELLED is
        // the ordinary retry rule this module follows everywhere: the intent was reached. EXPIRED is
        // the case where the deadline passed between the planner opening the screen and pressing the
        // button - the offer is already off, so the intent was reached there too, and refusing would
        // roll back the lapse this very call just materialised and leave the row SENT for the next
        // caller to resolve all over again.
        //
        // ACCEPTED and REJECTED deliberately fall through to requireTransition and its 409: a
        // planner trying to pull back an offer the carrier has answered has to be told so.
        if (tender.status() == TenderStatus.CANCELLED || tender.status() == TenderStatus.EXPIRED) {
            return toViews(scope, trip, now);
        }
        requireTransition(trip, tender, TenderStatus.CANCELLED);
        cancelAndPublish(scope, trip, tender, request.reason().trim(), actorId, now);
        return toViews(scope, trip, now);
    }

    // -----------------------------------------------------------------------------------------
    // Called by the trip's own lifecycle
    // -----------------------------------------------------------------------------------------

    /**
     * Takes any live offer off a shipment that has stopped being offerable, in the caller's
     * transaction.
     *
     * <p>Called from {@code TripService.cancel} and {@code TripExecutionService.dispatch}, and both
     * calls are load-bearing rather than tidy-up. Without the first, a carrier could accept a
     * shipment that is not happening. Without the second, a shipment that left without an answer
     * would keep an offer live that nobody can act on and that occupies the trip's live slot for
     * good.
     *
     * <p>A lapsed offer is expired rather than withdrawn: the deadline got there first, and saying
     * otherwise would put the wrong reason in the history.
     *
     * @param reason why the offer is off - recorded on the tender and published to the carrier
     */
    void withdrawOpen(CompanyScope scope, Trip trip, String reason) {
        tenderRepository.findLive(scope.companyId(), trip.id()).ifPresent(tender -> {
            // requireAppUserId and not writerAppUserId, even though this is a lifecycle path: a
            // withdrawal writes cancelled_by, and ck_trip_tender_cancelled_actor_pair requires it
            // beside cancelled_at. Both callers are a person's action today (they take the same
            // actor themselves before they get here), so this never refuses anything real - and if
            // an unattended caller ever appears, this throws instead of producing a row the
            // database would reject halfway through a cancellation. The V27 argument for
            // trip_exception.reported_by, in the other direction: keep the actor mandatory until
            // something actually needs it optional.
            UUID actorId = auditActorProvider.requireAppUserId();
            OffsetDateTime now = OffsetDateTime.now();
            resolveLapse(scope, trip, tender, now, actorId);
            if (tender.isLive()) {
                cancelAndPublish(scope, trip, tender, reason, actorId, now);
            }
        });
    }

    // -----------------------------------------------------------------------------------------
    // The carrier's side, reached through CarrierTenderPort
    // -----------------------------------------------------------------------------------------

    /**
     * The offers one carrier can still answer, oldest first.
     *
     * <p>Lapsed ones are filtered out here rather than in the query, so that "has this expired" is
     * decided by {@code TripTender.hasLapsedAt} everywhere instead of by a {@code now()} inside SQL
     * that no test could control. They are deliberately <em>not</em> materialised on this path: it
     * is a read, and making it write would put a read-write transaction on every poll a carrier
     * makes.
     *
     * @param shipmentNumber optional filter, for a carrier fetching the one offer it was told about
     */
    @Transactional(readOnly = true)
    public List<CarrierTenderOffer> openOffers(CompanyScope scope, UUID carrierId, String shipmentNumber) {
        OffsetDateTime now = OffsetDateTime.now();
        List<TripTender> outstanding = tenderRepository
                .findByCompanyIdAndCarrierIdAndStatusOrderBySentAtAsc(scope.companyId(), carrierId, TenderStatus.SENT)
                .stream()
                .filter(tender -> tender.awaitsResponseAt(now))
                .toList();
        if (outstanding.isEmpty()) {
            return List.of();
        }

        Map<UUID, Trip> trips = tripRepository
                .findAllById(outstanding.stream().map(TripTender::tripId).collect(Collectors.toSet())).stream()
                // Re-scoped after findAllById, which is not company-scoped. The ids came from
                // company-scoped tenders so this can only ever be a no-op - and it is the kind of
                // no-op that stays true when somebody later changes where the ids come from.
                .filter(trip -> trip.companyId().equals(scope.companyId()))
                .collect(Collectors.toMap(Trip::id, trip -> trip));
        Map<UUID, MasterReference> origins = originsOf(trips.values(), scope.companyId());

        String wanted = blankToNull(shipmentNumber);
        return outstanding.stream()
                .map(tender -> {
                    Trip trip = trips.get(tender.tripId());
                    return trip == null ? null : offerOf(tender, trip, origins, now);
                })
                .filter(Objects::nonNull)
                .filter(offer -> wanted == null || wanted.equalsIgnoreCase(offer.shipmentNumber()))
                .toList();
    }

    /**
     * The carrier's own answer, over the M2M API.
     *
     * <p>Two things make this safe to expose to somebody outside the company, and both are
     * parameters rather than payload fields: the tenant comes from the credential's company scope,
     * and the carrier from the credential's own {@code carrier_id}. A carrier naming a shipment they
     * were never offered gets a {@link ResourceNotFoundException} with the same message a shipment
     * that does not exist gets, so this endpoint cannot be used to discover the shipper's other
     * business.
     *
     * <p>Re-sending the same decision returns the recorded answer, which is what an at-least-once
     * sender needs. Sending the opposite decision is refused: reversing a commitment is not a retry.
     */
    @Transactional
    public CarrierTenderOffer respondAsCarrier(CompanyScope scope, UUID carrierId, String shipmentNumber,
            boolean accepted, String notes, UUID integrationClientId) {
        Trip trip = lockedTripByShipmentNumber(scope, shipmentNumber);
        TripTender tender = tenderRepository.findByCompanyIdAndTripIdOrderByAttemptDesc(scope.companyId(), trip.id())
                .stream()
                .filter(candidate -> candidate.carrierId().equals(carrierId))
                .findFirst()
                .orElseThrow(() -> new ResourceNotFoundException("No tender was found for this shipment."));

        OffsetDateTime now = OffsetDateTime.now();
        Map<UUID, MasterReference> origins = originsOf(List.of(trip), scope.companyId());
        TenderStatus already = tender.effectiveStatus(now);
        if (already == TenderStatus.ACCEPTED || already == TenderStatus.REJECTED) {
            if ((already == TenderStatus.ACCEPTED) == accepted) {
                return offerOf(tender, trip, origins, now);
            }
            throw new ConflictException("Shipment " + trip.shipmentNumber() + " was already "
                    + already.name().toLowerCase(Locale.ROOT) + " and that answer cannot be reversed here. "
                    + "Contact the shipper.");
        }

        // The lapse is materialised here rather than only reported, unlike on the read above: this
        // is already a read-write transaction. It sticks only if the call goes on to succeed - the
        // refusal below rolls the whole transaction back, this write included, and the next caller
        // resolves it again. That is the honest cost of having no scheduler, and it costs nothing in
        // correctness: the refusal itself is computed from the effective status either way. The
        // actor is null because the caller is a machine and has no app_user (AuditActorProvider).
        resolveLapse(scope, trip, tender, now, null);
        if (!tender.awaitsResponseAt(now)) {
            throw new ConflictException("The offer on shipment " + trip.shipmentNumber() + " is "
                    + tender.effectiveStatus(now).name().toLowerCase(Locale.ROOT)
                    + " and can no longer be answered.");
        }

        if (accepted) {
            tender.accept(now, TenderResponseSource.INTEGRATION, null, integrationClientId, blankToNull(notes));
            saveWithUniquenessBackstop(tender, trip);
            // No app user: a carrier answered over the integration API, so there is no person to
            // name. requireAppUserId would reject the machine by design (JOB 07), and inventing a
            // principal to satisfy an audit column is exactly what that refusal was about.
            recordAcceptanceOnTrip(trip, tender, null);
            publish(scope, trip, tender, ShipmentEventType.TENDER_ACCEPTED, now);
        } else {
            tender.reject(now, TenderResponseSource.INTEGRATION, null, integrationClientId, requireReason(notes));
            tenderRepository.saveAndFlush(tender);
            publish(scope, trip, tender, ShipmentEventType.TENDER_REJECTED, now);
        }
        return offerOf(tender, trip, origins, now);
    }

    // -----------------------------------------------------------------------------------------
    // Internals
    // -----------------------------------------------------------------------------------------

    /**
     * Materialises a lapse the reads have been reporting all along, and publishes it once.
     *
     * <p>Idempotent by construction: {@code hasLapsedAt} is only ever true for a stored
     * {@link TenderStatus#SENT}, and this method's first act is to leave that state.
     *
     * @param actorId whoever happened to trigger the resolution, or null for a machine. Recorded in
     *     {@code updated_by} and nowhere else - the audit event says a deadline expired, not that
     *     this person expired it
     */
    private void resolveLapse(CompanyScope scope, Trip trip, TripTender tender, OffsetDateTime now, UUID actorId) {
        if (!tender.hasLapsedAt(now)) {
            return;
        }
        OffsetDateTime lapsedAt = tender.expiresAt();
        tender.expire(now, actorId);
        tenderRepository.saveAndFlush(tender);
        // Published at the deadline and not at the moment of resolution: the offer died when it said
        // it would, and a timeline that put it where somebody happened to click would be reporting
        // our own scheduling gap as a business fact.
        publish(scope, trip, tender, ShipmentEventType.TENDER_EXPIRED, lapsedAt);
    }

    private void cancelAndPublish(CompanyScope scope, Trip trip, TripTender tender, String reason, UUID actorId,
            OffsetDateTime now) {
        boolean wasSent = tender.status() == TenderStatus.SENT;
        tender.cancel(reason, actorId);
        tenderRepository.saveAndFlush(tender);
        if (wasSent) {
            publish(scope, trip, tender, ShipmentEventType.TENDER_CANCELLED, now);
        }
    }

    /**
     * One tender transition, told to all three audiences at once - see
     * {@link ShipmentEventPublisher}.
     *
     * <p>The metadata is what makes an audit row answer a question on its own: which attempt, which
     * carrier, what was offered and - on a refusal or a withdrawal - why. Never the notes of an
     * acceptance, which are operational colour rather than a compliance fact.
     */
    private void publish(CompanyScope scope, Trip trip, TripTender tender, ShipmentEventType eventType,
            OffsetDateTime occurredAt) {
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("tenderId", tender.id() == null ? null : tender.id().toString());
        detail.put("attempt", tender.attempt());
        detail.put("carrierId", tender.carrierId().toString());
        if (tender.offeredAmount() != null) {
            detail.put("offeredAmount", tender.offeredAmount());
            detail.put("currency", tender.currency());
        }
        if (tender.responseSource() != null) {
            detail.put("responseSource", tender.responseSource().name());
        }
        if (eventType == ShipmentEventType.TENDER_REJECTED) {
            detail.put("reason", tender.responseNotes());
        }
        if (eventType == ShipmentEventType.TENDER_CANCELLED) {
            detail.put("reason", tender.cancelReason());
        }
        events.publish(scope, trip, eventType, occurredAt, detail);
        announce(scope, trip, tender, eventType, occurredAt);
    }

    /**
     * The two tender outcomes that leave a shipment unplaced, told to the alert bell (V32).
     *
     * <p>Two of the five transitions, and only two. An offer that was sent, accepted or withdrawn
     * is somebody here acting on their own plan and needs no interruption; a rejection and a lapse
     * are the carrier failing to take a load that still has to go, which is work arriving. That is
     * the line the bell is drawn on throughout - see {@code NotificationType}.
     *
     * <p>Placed here, on the one method every tender transition already funnels through, for the
     * reason the method itself exists: five call sites deciding separately which of them alerts is
     * how one of them ends up not doing it.
     */
    private void announce(CompanyScope scope, Trip trip, TripTender tender, ShipmentEventType eventType,
            OffsetDateTime occurredAt) {
        NotificationType alertType = switch (eventType) {
            case TENDER_REJECTED -> NotificationType.TENDER_REJECTED;
            case TENDER_EXPIRED -> NotificationType.TENDER_EXPIRED;
            default -> null;
        };
        if (alertType != null) {
            alerts.tenderRefused(scope, trip, tender, alertType, occurredAt);
        }
    }

    /**
     * The tender a response may still be applied to.
     *
     * <p>The lapse is resolved first so that an answer arriving one second late leaves an
     * {@code EXPIRED} row behind - which it does only when the call goes on to succeed, since the
     * refusal below rolls its own transaction back. The refusal itself never depends on that: it is
     * computed from {@code effectiveStatus}, which needs no write at all.
     */
    private TripTender requireAnswerable(CompanyScope scope, Trip trip, UUID tenderId) {
        TripTender tender = requireTender(scope, trip, tenderId);
        OffsetDateTime now = OffsetDateTime.now();
        resolveLapse(scope, trip, tender, now, auditActorProvider.writerAppUserId());
        if (!tender.awaitsResponseAt(now)) {
            throw new ConflictException("Tender " + tender.attempt() + " on shipment " + trip.shipmentNumber()
                    + " is " + tender.effectiveStatus(now) + " and can no longer be answered.");
        }
        return tender;
    }

    private TripTender requireTender(CompanyScope scope, Trip trip, UUID tenderId) {
        return tenderRepository.findByIdAndCompanyId(tenderId, scope.companyId())
                // Checked against the trip in the path too, not only against the company: a tender
                // id from another of this company's trips must not be actionable through this one's
                // URL, or the audit trail would name the wrong shipment. Same rule as
                // TripExceptionService.resolve.
                .filter(candidate -> candidate.tripId().equals(trip.id()))
                .orElseThrow(() -> new ResourceNotFoundException("Tender not found on this shipment."));
    }

    /** The caller-facing half of {@link TenderStatus}'s transition table. */
    private static void requireTransition(Trip trip, TripTender tender, TenderStatus target) {
        if (!tender.status().canTransitionTo(target)) {
            throw new ConflictException("Tender " + tender.attempt() + " on shipment " + trip.shipmentNumber()
                    + " is " + tender.status() + " and cannot move to " + target + ".");
        }
    }

    private static void requireTenderable(Trip trip) {
        if (!TENDERABLE.contains(trip.status())) {
            throw new ConflictException("Shipment " + trip.shipmentNumber() + " is " + trip.status()
                    + " and cannot be offered to a carrier.");
        }
    }

    /**
     * A shipment with no carrier has nobody to offer it to. Unreachable through the API - migration
     * V25's {@code ck_trip_confirmed_is_complete} makes a committed trip without a vehicle
     * impossible, and the vehicle is where the carrier comes from - so this is the sentence a raw
     * data fix would produce instead of a {@code NullPointerException}, exactly as
     * {@code TripExecutionService.requireOperableVehicle} is.
     */
    private static UUID requireCarrier(Trip trip) {
        if (trip.carrierId() == null) {
            throw new ConflictException(
                    "Shipment " + trip.shipmentNumber() + " has no carrier, so there is nobody to offer it to.");
        }
        return trip.carrierId();
    }

    /**
     * A shipment somebody has already committed to is not offered again. The database says the same
     * thing through {@code uq_trip_tender_accepted}; this is the sentence a planner reads, and it
     * fires before an attempt row is created rather than after.
     */
    private void requireNotPlaced(CompanyScope scope, Trip trip) {
        tenderRepository.findByCompanyIdAndTripIdAndStatus(scope.companyId(), trip.id(), TenderStatus.ACCEPTED)
                .ifPresent(accepted -> {
                    throw new ConflictException("Shipment " + trip.shipmentNumber()
                            + " has already been accepted by its carrier (attempt " + accepted.attempt()
                            + ") and cannot be offered again.");
                });
    }

    private static String requireReason(String notes) {
        String reason = blankToNull(notes);
        if (reason == null) {
            throw new InvalidRequestException("notes are required to reject a tender: the carrier's reason is what "
                    + "the planner needs in order to decide what to do next.");
        }
        return reason;
    }

    private Trip lockedTrip(CompanyScope scope, UUID tripId) {
        return tripRepository.findByIdAndCompanyIdForUpdate(tripId, scope.companyId())
                .orElseThrow(() -> new ResourceNotFoundException("Trip not found."));
    }

    /**
     * Resolves the shipment by its external identity and then takes the same row lock every other
     * mutation takes. Two statements rather than one locking finder by number, so the lock is
     * unambiguously the one {@code TripService} and {@code TripExecutionService} contend on.
     *
     * <p>Answers "no tender was found" rather than "no such shipment" for a shipment number this
     * company does not have: a carrier must not be able to tell the two apart.
     */
    private Trip lockedTripByShipmentNumber(CompanyScope scope, String shipmentNumber) {
        Trip trip = tripRepository.findByShipmentNumberAndCompanyId(shipmentNumber, scope.companyId())
                .orElseThrow(() -> new ResourceNotFoundException("No tender was found for this shipment."));
        return lockedTrip(scope, trip.id());
    }

    /**
     * {@code saveAndFlush} plus a translation for the two partial unique indexes - the backstop for
     * what the trip's row lock cannot cover: a planner and a carrier served by two application
     * instances at the same instant.
     */
    /**
     * Writes the acceptance onto the shipment (migration V42), closing the debt JOB 07 opened.
     *
     * <p>Until V42 an acceptance lived only on the tender, because writing it to
     * {@code trip.carrier_id} would have produced a shipment whose carrier and whose vehicle's
     * owner disagreed, and there was nowhere else to put it. There is now:
     * {@code accepted_carrier_id} says who agreed, {@code carrier_id} goes on being the vehicle's
     * owner, and a shipment where the two differ cannot depart.
     *
     * <p>Nothing here picks a vehicle. When the accepting carrier already owns the one on the
     * shipment - the ordinary case, a carrier accepting its own truck's work - the two fields agree
     * on the spot and nobody has anything to do.
     */
    private void recordAcceptanceOnTrip(Trip trip, TripTender tender, UUID actorId) {
        trip.recordCarrierAcceptance(tender.carrierId(), actorId);
        tripRepository.save(trip);
    }

    private void saveWithUniquenessBackstop(TripTender tender, Trip trip) {
        try {
            tenderRepository.saveAndFlush(tender);
        } catch (DataIntegrityViolationException raced) {
            throw new ConflictException("Shipment " + trip.shipmentNumber()
                    + " was tendered by someone else at the same moment. Reload and try again.");
        }
    }

    private List<TripTenderView> toViews(CompanyScope scope, Trip trip, OffsetDateTime now) {
        List<TripTender> tenders =
                tenderRepository.findByCompanyIdAndTripIdOrderByAttemptDesc(scope.companyId(), trip.id());
        if (tenders.isEmpty()) {
            return List.of();
        }
        // One batched carrier lookup for the whole history, never one per attempt.
        Map<UUID, MasterReference> carriers = carrierLookupPort.findAllInCompany(
                tenders.stream().map(TripTender::carrierId).collect(Collectors.toSet()), scope.companyId());
        return tenders.stream()
                .map(tender -> TripTenderView.from(tender, carriers.get(tender.carrierId()), now))
                .toList();
    }

    /**
     * The depots a set of trips leave from, resolved through their runs in two batched queries.
     *
     * <p>A trip has no origin of its own - it departs from its run's (migration V11) - so this is
     * the same two-hop {@code TripViewAssembler.referencesOf} makes, kept here rather than borrowed
     * from the assembler because the carrier-facing shape must not be able to drift into carrying
     * everything a {@code TripView} carries.
     */
    private Map<UUID, MasterReference> originsOf(Collection<Trip> trips, UUID companyId) {
        if (trips.isEmpty()) {
            return Map.of();
        }
        Map<UUID, PlanningRun> runs = planningRunRepository
                .findByIdInAndCompanyId(trips.stream().map(Trip::planningRunId).collect(Collectors.toSet()), companyId)
                .stream()
                .collect(Collectors.toMap(PlanningRun::id, run -> run));
        Map<UUID, MasterReference> origins = originLookupPort.findAllInCompany(
                runs.values().stream().map(PlanningRun::originId).collect(Collectors.toSet()), companyId);

        // Re-keyed by trip so the caller never has to know a run sits between the two. A plain loop
        // rather than a collector: both hops can legitimately miss - a run outside this company, an
        // origin that no longer resolves - and every null-tolerant Map collector either throws on the
        // null or hides which hop dropped it.
        Map<UUID, MasterReference> byTrip = new LinkedHashMap<>();
        for (Trip trip : trips) {
            PlanningRun run = runs.get(trip.planningRunId());
            MasterReference origin = run == null ? null : origins.get(run.originId());
            if (origin != null) {
                byTrip.put(trip.id(), origin);
            }
        }
        return byTrip;
    }

    /**
     * The carrier-facing shape. Assembled from the trip's own columns only - no vehicle, no driver,
     * no order list - for the reasons {@link CarrierTenderOffer} sets out.
     *
     * <p>{@code stopCount} is read from the trip's own stop collection rather than a grouped count:
     * a carrier's inbox is a handful of rows, not a 300-trip board, and the trip is already loaded.
     */
    private CarrierTenderOffer offerOf(TripTender tender, Trip trip, Map<UUID, MasterReference> origins,
            OffsetDateTime now) {
        MasterReference origin = origins.get(trip.id());
        return new CarrierTenderOffer(
                trip.shipmentNumber(),
                tender.attempt(),
                tender.effectiveStatus(now).name(),
                trip.planningDate(),
                trip.plannedDepartureAt(),
                origin == null ? null : origin.code(),
                origin == null ? null : origin.name(),
                trip.stops().size(),
                tender.offeredAmount(),
                tender.currency(),
                tender.notes(),
                tender.sentAt(),
                tender.expiresAt(),
                tender.respondedAt(),
                tender.responseNotes());
    }

    private static BigDecimal money(BigDecimal value) {
        return value == null ? null : value.setScale(MONEY_SCALE, RoundingMode.HALF_UP);
    }

    private static String normalizeCurrency(String value) {
        return (value == null || value.isBlank()) ? null : value.trim().toUpperCase(Locale.ROOT);
    }

    private static String blankToNull(String value) {
        return (value == null || value.isBlank()) ? null : value.trim();
    }
}
