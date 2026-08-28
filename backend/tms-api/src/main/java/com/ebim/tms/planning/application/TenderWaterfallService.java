package com.ebim.tms.planning.application;

import com.ebim.tms.planning.domain.TenderStatus;
import com.ebim.tms.planning.domain.TenderWaterfall;
import com.ebim.tms.planning.domain.TenderWaterfallCandidate;
import com.ebim.tms.planning.domain.Trip;
import com.ebim.tms.planning.domain.TripTender;
import com.ebim.tms.planning.domain.WaterfallCandidateStatus;
import com.ebim.tms.planning.domain.WaterfallStatus;
import com.ebim.tms.planning.infrastructure.TenderWaterfallRepository;
import com.ebim.tms.planning.infrastructure.TripRepository;
import com.ebim.tms.planning.infrastructure.TripTenderRepository;
import com.ebim.tms.shared.api.ConflictException;
import com.ebim.tms.shared.api.InvalidRequestException;
import com.ebim.tms.shared.api.ResourceNotFoundException;
import com.ebim.tms.shared.audit.AuditActorProvider;
import com.ebim.tms.shared.audit.AuditAggregateType;
import com.ebim.tms.shared.audit.AuditAction;
import com.ebim.tms.shared.audit.AuditRecorder;
import com.ebim.tms.shared.reference.CarrierLookupPort;
import com.ebim.tms.shared.reference.CarrierQuotationPort;
import com.ebim.tms.shared.reference.CarrierQuote;
import com.ebim.tms.shared.reference.CostableTrip;
import com.ebim.tms.shared.reference.MasterReference;
import com.ebim.tms.shared.reference.TripCostingLookupPort;
import com.ebim.tms.shared.security.CompanyScope;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Offers a shipment down a ranked list of carriers until one accepts (migration V40).
 *
 * <pre>
 *   Carrier A → REJECTED
 *   Carrier B → EXPIRED
 *   Carrier C → ACCEPTED
 * </pre>
 *
 * <h2>What this adds over V31</h2>
 *
 * <p>V31 could offer, record and refuse correctly. What it could not do is the thing that fills a
 * truck: when the first carrier says no, offer it to the second. Until now that was a person
 * watching a screen, so every rejection at 19:40 waited until somebody noticed and every deadline
 * that lapsed overnight went unanswered until morning.
 *
 * <h2>What it is not allowed to do</h2>
 *
 * <ul>
 *   <li><b>It never accepts.</b> A carrier accepts, through the same path a manual tender uses.</li>
 *   <li><b>It never dispatches.</b> V31's rule, unchanged.</li>
 *   <li><b>It never reassigns a vehicle.</b> A shipment's carrier is the owner of its vehicle, so
 *       an acceptance by a different carrier is recorded and left for a planner to act on. Doing it
 *       silently would leave a trip whose carrier and whose vehicle's owner disagreed.</li>
 * </ul>
 *
 * <h2>Concurrency</h2>
 *
 * <p>Every mutation takes the <b>trip's</b> row lock first - the same serialisation point
 * {@code TripService}, {@code TripExecutionService} and {@code TripTenderService} use - so a
 * carrier accepting and the scheduler advancing cannot interleave into a shipment with two live
 * offers. {@code uq_tender_waterfall_active} and V31's {@code uq_trip_tender_live} are the backstop
 * for two application instances at the same instant, and the scheduler's own read uses
 * {@code SKIP LOCKED} so a second node moves on instead of duplicating work.
 */
@Service
public class TenderWaterfallService {

    private static final Logger log = LoggerFactory.getLogger(TenderWaterfallService.class);

    private static final String WATERFALL_METRIC = "tms.tender.waterfall";
    private static final String ADVANCE_METRIC = "tms.tender.waterfall.advances";

    /** What a company gets when it does not say. Four carriers is a real waterfall, not a gesture. */
    public static final int DEFAULT_MAX_ATTEMPTS = 4;

    /** Long enough for a carrier to answer within a working day, short enough to still fill the truck. */
    public static final int DEFAULT_RESPONSE_MINUTES = 120;

    private final TenderWaterfallRepository waterfallRepository;
    private final TripRepository tripRepository;
    private final TripTenderRepository tenderRepository;
    private final TripTenderService tenderService;
    private final CarrierLookupPort carrierLookupPort;
    private final CarrierQuotationPort carrierQuotationPort;
    private final TripCostingLookupPort tripCostingLookupPort;
    private final AuditActorProvider auditActorProvider;
    private final AuditRecorder auditRecorder;
    private final MeterRegistry meterRegistry;
    private final Clock clock;

    public TenderWaterfallService(TenderWaterfallRepository waterfallRepository, TripRepository tripRepository,
            TripTenderRepository tenderRepository, TripTenderService tenderService,
            CarrierLookupPort carrierLookupPort, CarrierQuotationPort carrierQuotationPort,
            TripCostingLookupPort tripCostingLookupPort, AuditActorProvider auditActorProvider,
            AuditRecorder auditRecorder, MeterRegistry meterRegistry, Clock clock) {
        this.waterfallRepository = waterfallRepository;
        this.tripRepository = tripRepository;
        this.tenderRepository = tenderRepository;
        this.tenderService = tenderService;
        this.carrierLookupPort = carrierLookupPort;
        this.carrierQuotationPort = carrierQuotationPort;
        this.tripCostingLookupPort = tripCostingLookupPort;
        this.auditActorProvider = auditActorProvider;
        this.auditRecorder = auditRecorder;
        this.meterRegistry = meterRegistry;
        this.clock = clock;
    }

    // --- starting ---------------------------------------------------------------------

    /**
     * Ranks every active carrier for the shipment, stores the list, and offers it to the first.
     *
     * @param maxAttempts     how many carriers may be offered before giving up; null for the default
     * @param responseMinutes how long each gets to answer; null for the default
     */
    @Transactional
    public TenderWaterfallView start(CompanyScope scope, UUID tripId, Integer maxAttempts,
            Integer responseMinutes) {
        Trip trip = lockedTrip(scope, tripId);
        waterfallRepository.findByCompanyIdAndTripIdAndStatus(scope.companyId(), tripId, WaterfallStatus.ACTIVE)
                .ifPresent(active -> {
                    throw new ConflictException("Shipment " + trip.shipmentNumber()
                            + " already has a tender waterfall running. Stop it before starting another.");
                });
        tenderRepository.findByCompanyIdAndTripIdAndStatus(scope.companyId(), tripId, TenderStatus.ACCEPTED)
                .ifPresent(accepted -> {
                    throw new ConflictException("Shipment " + trip.shipmentNumber()
                            + " has already been accepted by a carrier.");
                });

        List<CarrierRanking.Candidate> ranked = rankCarriersFor(scope, trip);
        if (ranked.isEmpty()) {
            throw new InvalidRequestException(
                    "There are no active carriers to offer shipment " + trip.shipmentNumber() + " to.");
        }

        OffsetDateTime now = OffsetDateTime.now(clock);
        UUID actorId = auditActorProvider.requireAppUserId();
        TenderWaterfall waterfall = new TenderWaterfall(scope.companyId(), tripId,
                clampAttempts(maxAttempts), clampMinutes(responseMinutes), now, actorId);
        ranked.forEach(candidate -> waterfall.addCandidate(candidate.carrierId(),
                candidate.hasPrice() ? candidate.quote().amount() : null,
                candidate.hasPrice() ? candidate.quote().currency() : null,
                candidate.hasPrice() ? candidate.quote().rateCardId() : null));
        waterfallRepository.saveAndFlush(waterfall);

        auditRecorder.record(scope, AuditAggregateType.SHIPMENT, tripId, AuditAction.WATERFALL_STARTED,
                Map.of("shipmentNumber", trip.shipmentNumber(),
                        "candidates", String.valueOf(ranked.size()),
                        "maxAttempts", String.valueOf(waterfall.maxAttempts())));
        count(WATERFALL_METRIC, "started");

        offerNext(scope, trip, waterfall, now);
        return view(scope, waterfall);
    }

    /**
     * Ranks the company's active carriers for this shipment.
     *
     * <p>Every active carrier is a candidate, priced or not: a dispatcher may well want to offer to
     * somebody they have no tariff for, and excluding them would make the waterfall unable to reach
     * a carrier the company demonstrably works with. {@link CarrierRanking} puts the priced ones
     * first - see its rule.
     */
    private List<CarrierRanking.Candidate> rankCarriersFor(CompanyScope scope, Trip trip) {
        List<MasterReference> carriers = carrierLookupPort.findAllActiveInCompany(scope.companyId());
        if (carriers.isEmpty()) {
            return List.of();
        }
        List<CarrierRanking.CarrierReference> references = carriers.stream()
                .map(carrier -> new CarrierRanking.CarrierReference(carrier.id(), carrier.code(), carrier.name()))
                .toList();

        Map<UUID, CarrierQuote> quotes = tripCostingLookupPort
                .findCostableTrip(trip.id(), scope.companyId())
                .map(costable -> carrierQuotationPort.quote(scope.companyId(), costable,
                        references.stream().map(CarrierRanking.CarrierReference::id).toList()))
                .orElseGet(Map::of);

        return CarrierRanking.rank(references, quotes);
    }

    // --- advancing --------------------------------------------------------------------

    /**
     * Offers the shipment to the next carrier on the list, or ends the waterfall when there is
     * none.
     *
     * <p>Called after a rejection, after a lapse, and once when the waterfall starts. Idempotent
     * in the way that matters: a waterfall that already has an offer out is left alone, so a
     * duplicate trigger cannot produce two live tenders for one shipment.
     */
    private void offerNext(CompanyScope scope, Trip trip, TenderWaterfall waterfall, OffsetDateTime now) {
        if (!waterfall.status().isActive() || waterfall.offered().isPresent()) {
            return;
        }
        Optional<TenderWaterfallCandidate> next = waterfall.nextToOffer();
        if (next.isEmpty()) {
            String note = waterfall.hasReachedAttemptCeiling()
                    ? "Reached the limit of " + waterfall.maxAttempts() + " offers."
                    : "Every carrier on the list has been offered the shipment.";
            end(scope, trip, waterfall, WaterfallStatus.EXHAUSTED, note, now);
            return;
        }

        TenderWaterfallCandidate candidate = next.get();
        OffsetDateTime deadline = now.plusMinutes(waterfall.responseMinutes());
        // The offer goes out through the ordinary tender path, so a waterfall-driven offer and a
        // hand-made one are the same kind of record - same history, same uniqueness invariants,
        // same audit action. A parallel path would be a second way to tender that could drift.
        TenderRequest request = new TenderRequest(
                candidate.quotedAmount(), candidate.quotedCurrency(),
                "Automatic tender waterfall, rank " + candidate.rank() + ".", deadline);
        tenderService.createFor(scope, trip.id(), candidate.carrierId(), request);

        TripTender opened = tenderRepository.findLive(scope.companyId(), trip.id())
                .orElseThrow(() -> new IllegalStateException(
                        "the tender just created for shipment " + trip.shipmentNumber() + " is not live"));
        tenderService.send(scope, trip.id(), opened.id());

        candidate.offered(opened.id());
        waterfallRepository.saveAndFlush(waterfall);
        count(ADVANCE_METRIC, "offered");
    }

    /**
     * Records what a carrier answered and moves the waterfall on.
     *
     * <p>Called by {@code TripTenderService} after every response so that the two records - the
     * tender's own status and the candidate's - cannot disagree. A shipment with no waterfall is
     * simply not affected, which is what keeps hand-made tenders working exactly as they did.
     */
    @Transactional
    public void tenderAnswered(CompanyScope scope, Trip trip, TripTender tender, TenderStatus outcome) {
        Optional<TenderWaterfall> found = waterfallRepository
                .findByCompanyIdAndTripIdAndStatus(scope.companyId(), trip.id(), WaterfallStatus.ACTIVE);
        if (found.isEmpty()) {
            return;
        }
        TenderWaterfall waterfall = found.get();
        Optional<TenderWaterfallCandidate> candidate = waterfall.candidates().stream()
                .filter(entry -> tender.id().equals(entry.tenderId()))
                .findFirst();
        if (candidate.isEmpty()) {
            // A hand-made tender on a shipment that also has a waterfall running. It is not part of
            // the list, so it does not advance it - but if it was accepted, the waterfall is over.
            if (outcome == TenderStatus.ACCEPTED) {
                end(scope, trip, waterfall, WaterfallStatus.ACCEPTED,
                        "Accepted through an offer made outside the waterfall.", OffsetDateTime.now(clock));
            }
            return;
        }

        OffsetDateTime now = OffsetDateTime.now(clock);
        switch (outcome) {
            case ACCEPTED -> {
                candidate.get().decided(WaterfallCandidateStatus.ACCEPTED, now);
                end(scope, trip, waterfall, WaterfallStatus.ACCEPTED, null, now);
                count(ADVANCE_METRIC, "accepted");
            }
            case REJECTED -> {
                candidate.get().decided(WaterfallCandidateStatus.REJECTED, now);
                count(ADVANCE_METRIC, "rejected");
                waterfallRepository.saveAndFlush(waterfall);
                offerNext(scope, trip, waterfall, now);
            }
            case EXPIRED -> {
                candidate.get().decided(WaterfallCandidateStatus.EXPIRED, now);
                count(ADVANCE_METRIC, "expired");
                waterfallRepository.saveAndFlush(waterfall);
                offerNext(scope, trip, waterfall, now);
            }
            case CANCELLED -> {
                // Somebody withdrew the offer by hand. That is a decision to stop, not a refusal to
                // route around: continuing down the list would re-offer a shipment a person just
                // pulled back.
                candidate.get().decided(WaterfallCandidateStatus.EXPIRED, now);
                end(scope, trip, waterfall, WaterfallStatus.CANCELLED,
                        "The offer to rank " + candidate.get().rank() + " was withdrawn by hand.", now);
                count(ADVANCE_METRIC, "withdrawn");
            }
            case DRAFT, SENT -> {
                // Not an answer. Nothing to record.
            }
        }
    }

    // --- ending -----------------------------------------------------------------------

    /** The manual override: a person stops the waterfall. */
    @Transactional
    public TenderWaterfallView stop(CompanyScope scope, UUID tripId, String reason) {
        Trip trip = lockedTrip(scope, tripId);
        TenderWaterfall waterfall = waterfallRepository
                .findByCompanyIdAndTripIdAndStatus(scope.companyId(), tripId, WaterfallStatus.ACTIVE)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Shipment " + trip.shipmentNumber() + " has no tender waterfall running."));

        OffsetDateTime now = OffsetDateTime.now(clock);
        // The offer that is out is withdrawn too: leaving it live would let a carrier accept a
        // shipment whose waterfall a planner has just stopped.
        waterfall.offered().ifPresent(candidate -> {
            candidate.decided(WaterfallCandidateStatus.EXPIRED, now);
            tenderRepository.findLive(scope.companyId(), tripId).ifPresent(live ->
                    tenderService.withdraw(scope, tripId, live.id(),
                            new TenderWithdrawRequest("The tender waterfall was stopped.")));
        });
        end(scope, trip, waterfall, WaterfallStatus.CANCELLED, blankToNull(reason), now);
        count(WATERFALL_METRIC, "stopped");
        return view(scope, waterfall);
    }

    private void end(CompanyScope scope, Trip trip, TenderWaterfall waterfall, WaterfallStatus outcome,
            String note, OffsetDateTime now) {
        if (!waterfall.finish(outcome, note, now)) {
            return;
        }
        waterfallRepository.saveAndFlush(waterfall);
        auditRecorder.record(scope, AuditAggregateType.SHIPMENT, trip.id(), AuditAction.WATERFALL_ENDED,
                Map.of("shipmentNumber", trip.shipmentNumber(),
                        "outcome", outcome.name(),
                        "attempts", String.valueOf(waterfall.attemptsUsed())));
        count(WATERFALL_METRIC, outcome.name().toLowerCase(java.util.Locale.ROOT));
    }

    // --- reading ----------------------------------------------------------------------

    @Transactional(readOnly = true)
    public Optional<TenderWaterfallView> current(CompanyScope scope, UUID tripId) {
        return waterfallRepository
                .findByCompanyIdAndTripIdOrderByStartedAtDesc(scope.companyId(), tripId)
                .stream()
                .findFirst()
                .map(waterfall -> view(scope, waterfall));
    }

    private TenderWaterfallView view(CompanyScope scope, TenderWaterfall waterfall) {
        Map<UUID, MasterReference> carriers = carrierLookupPort.findAllInCompany(
                waterfall.candidates().stream().map(TenderWaterfallCandidate::carrierId)
                        .collect(java.util.stream.Collectors.toSet()),
                scope.companyId());
        Map<UUID, MasterReference> byId = new LinkedHashMap<>(carriers);

        // Whether the offer currently out has passed its deadline, computed rather than stored:
        // a deadline is a moment, and a flag that had to be written to become true would be wrong
        // for exactly as long as nobody wrote it. Same reasoning as TripTender.effectiveStatus.
        OffsetDateTime now = OffsetDateTime.now(clock);
        boolean lapsed = waterfall.status().isActive()
                && waterfall.offered().isPresent()
                && tenderRepository.findLive(waterfall.companyId(), waterfall.tripId())
                        .map(live -> live.effectiveStatus(now) == TenderStatus.EXPIRED)
                        .orElse(false);
        return TenderWaterfallView.from(waterfall, byId).withLapsed(lapsed);
    }

    // --- helpers ----------------------------------------------------------------------

    /**
     * Advances past an offer whose deadline has passed, and offers the shipment to the next carrier.
     *
     * <p><b>Why a dispatcher triggers this and a timer does not.</b> Creating a tender goes through
     * {@code AuditActorProvider.requireAppUserId}, which refuses a machine by design - "this
     * operation is restricted to an interactive user". That rule predates this feature and it is
     * the right rule: an offer sent to a carrier is a commitment, and the trail has to name the
     * person who made it. A background sweep offering on a company's behalf would need a
     * system-actor concept this product does not have, and inventing one to save a click would put
     * an unattributable commercial commitment into the history. So the waterfall surfaces
     * {@code currentOfferLapsed} and a dispatcher advances it - see the JOB 07 result for the
     * design this leaves open.
     *
     * <p>Refuses while the offer is still live: advancing early would withdraw an offer a carrier
     * may be about to accept.
     */
    @Transactional
    public TenderWaterfallView advance(CompanyScope scope, UUID tripId) {
        Trip trip = lockedTrip(scope, tripId);
        TenderWaterfall waterfall = waterfallRepository
                .findByCompanyIdAndTripIdAndStatus(scope.companyId(), tripId, WaterfallStatus.ACTIVE)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Shipment " + trip.shipmentNumber() + " has no tender waterfall running."));

        OffsetDateTime now = OffsetDateTime.now(clock);
        Optional<TenderWaterfallCandidate> offered = waterfall.offered();
        if (offered.isPresent()) {
            TripTender live = tenderRepository.findLive(scope.companyId(), tripId).orElse(null);
            if (live != null && live.effectiveStatus(now) != TenderStatus.EXPIRED) {
                throw new ConflictException("The offer to rank " + offered.get().rank()
                        + " has not expired yet. Withdraw it if you want to move on now.");
            }
            offered.get().decided(WaterfallCandidateStatus.EXPIRED, now);
            if (live != null) {
                // Materialises the lapse on the tender too, so the two records agree. V31's
                // resolveLapse does this on the next write that touches the trip's tenders; this is
                // that write.
                tenderService.list(scope, tripId);
            }
            waterfallRepository.saveAndFlush(waterfall);
            count(ADVANCE_METRIC, "expired");
        }

        offerNext(scope, trip, waterfall, now);
        return view(scope, waterfall);
    }

    private Trip lockedTrip(CompanyScope scope, UUID tripId) {
        return tripRepository.findByIdAndCompanyIdForUpdate(tripId, scope.companyId())
                .orElseThrow(() -> new ResourceNotFoundException("Trip not found."));
    }

    private static int clampAttempts(Integer requested) {
        if (requested == null) {
            return DEFAULT_MAX_ATTEMPTS;
        }
        return Math.max(1, Math.min(20, requested));
    }

    private static int clampMinutes(Integer requested) {
        if (requested == null) {
            return DEFAULT_RESPONSE_MINUTES;
        }
        return Math.max(1, Math.min(10_080, requested));
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private void count(String metric, String outcome) {
        Counter.builder(metric).tag("outcome", outcome).register(meterRegistry).increment();
    }
}
