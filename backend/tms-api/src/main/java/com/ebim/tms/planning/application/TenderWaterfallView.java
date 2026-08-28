package com.ebim.tms.planning.application;

import com.ebim.tms.planning.domain.TenderWaterfall;
import com.ebim.tms.planning.domain.TenderWaterfallCandidate;
import com.ebim.tms.planning.domain.WaterfallCandidateStatus;
import com.ebim.tms.planning.domain.WaterfallStatus;
import com.ebim.tms.shared.reference.MasterReference;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * A tender waterfall as a screen reads it (migration V40).
 *
 * <p>The candidate list is the point. "Offered to three carriers" is a number; the list, in rank
 * order, with what each was quoted and what each said, is the answer to the question a dispatcher
 * actually asks - *who has already turned this down, and who is left*.
 *
 * @param attemptsUsed how many carriers have actually been offered the shipment
 * @param currentOfferLapsed whether the offer that is out has passed its deadline. Computed here
 *     rather than stored: a deadline is a moment, and a row that had to be updated to become true
 *     would be wrong for as long as nobody updated it
 */
public record TenderWaterfallView(
        UUID id,
        UUID tripId,
        WaterfallStatus status,
        int maxAttempts,
        int responseMinutes,
        int attemptsUsed,
        boolean currentOfferLapsed,
        String outcomeNote,
        OffsetDateTime startedAt,
        OffsetDateTime completedAt,
        List<CandidateView> candidates) {

    /**
     * One carrier's place on the list.
     *
     * @param quotedAmount what it was ranked on, or null when it had no applicable agreement. Null
     *     and zero are different: "no tariff entered" is not "free", which is why the ranking puts
     *     an unpriced carrier last rather than first
     */
    public record CandidateView(
            int rank,
            UUID carrierId,
            String carrierCode,
            String carrierName,
            WaterfallCandidateStatus status,
            BigDecimal quotedAmount,
            String quotedCurrency,
            UUID rateCardId,
            UUID tenderId,
            OffsetDateTime decidedAt) {
    }

    static TenderWaterfallView from(TenderWaterfall waterfall, Map<UUID, MasterReference> carriers) {
        List<CandidateView> candidates = waterfall.candidates().stream()
                .map(candidate -> toCandidateView(candidate, carriers.get(candidate.carrierId())))
                .toList();
        return new TenderWaterfallView(
                waterfall.id(),
                waterfall.tripId(),
                waterfall.status(),
                waterfall.maxAttempts(),
                waterfall.responseMinutes(),
                waterfall.attemptsUsed(),
                false,
                waterfall.outcomeNote(),
                waterfall.startedAt(),
                waterfall.completedAt(),
                candidates);
    }

    /** The same view, told whether the offer currently out has passed its deadline. */
    TenderWaterfallView withLapsed(boolean lapsed) {
        return new TenderWaterfallView(id, tripId, status, maxAttempts, responseMinutes, attemptsUsed,
                lapsed, outcomeNote, startedAt, completedAt, candidates);
    }

    private static CandidateView toCandidateView(TenderWaterfallCandidate candidate, MasterReference carrier) {
        return new CandidateView(
                candidate.rank(),
                candidate.carrierId(),
                carrier == null ? null : carrier.code(),
                carrier == null ? null : carrier.name(),
                candidate.status(),
                candidate.quotedAmount(),
                candidate.quotedCurrency(),
                candidate.rateCardId(),
                candidate.tenderId(),
                candidate.decidedAt());
    }
}
