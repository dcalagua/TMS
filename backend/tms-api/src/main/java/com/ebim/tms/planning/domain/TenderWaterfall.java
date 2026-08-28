package com.ebim.tms.planning.domain;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;
import org.hibernate.annotations.UuidGenerator;

/**
 * A shipment being offered down a ranked list of carriers (migration V40).
 *
 * <p><b>The list is walked, not recomputed.</b> Candidates are ranked once, at the moment somebody
 * starts the waterfall, and the prices they were ranked on are snapshotted with them. Re-ranking at
 * each step would let a rate card edited on Tuesday change the order of a list approved on Monday,
 * and "why did this go to the third carrier" would stop being answerable from the data.
 *
 * <p><b>What it never does.</b> It offers; it does not accept. A carrier accepts, through the same
 * {@code TripTenderService} path a manually created tender uses, and the waterfall learns about it
 * afterwards. Nothing here dispatches anything.
 */
@Entity
@Table(name = "tender_waterfall")
public class TenderWaterfall {

    @Id
    @GeneratedValue
    @UuidGenerator
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

    @Column(name = "company_id", updatable = false, nullable = false)
    private UUID companyId;

    @Column(name = "trip_id", updatable = false, nullable = false)
    private UUID tripId;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private WaterfallStatus status = WaterfallStatus.ACTIVE;

    @Column(name = "max_attempts", nullable = false)
    private int maxAttempts;

    @Column(name = "response_minutes", nullable = false)
    private int responseMinutes;

    @Column(name = "outcome_note")
    private String outcomeNote;

    @Column(name = "started_at", updatable = false, nullable = false)
    private OffsetDateTime startedAt;

    @Column(name = "started_by", updatable = false)
    private UUID startedBy;

    @Column(name = "completed_at")
    private OffsetDateTime completedAt;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false, nullable = false)
    private OffsetDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    /**
     * The ranked list, loaded with the waterfall because every operation on it needs the whole
     * list: advancing reads the next pending one and the one just decided, and rendering shows all
     * of them. A candidate is meaningless without its waterfall, which is what makes this a real
     * aggregate rather than two tables that happen to reference each other.
     */
    @OneToMany(mappedBy = "waterfall", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("rank ASC")
    private final List<TenderWaterfallCandidate> candidates = new ArrayList<>();

    protected TenderWaterfall() {}

    public TenderWaterfall(UUID companyId, UUID tripId, int maxAttempts, int responseMinutes,
            OffsetDateTime startedAt, UUID startedBy) {
        this.companyId = companyId;
        this.tripId = tripId;
        this.maxAttempts = maxAttempts;
        this.responseMinutes = responseMinutes;
        this.startedAt = startedAt;
        this.startedBy = startedBy;
    }

    /** Appends a candidate at the next rank. Ranks are dense and one-based. */
    public TenderWaterfallCandidate addCandidate(UUID carrierId, java.math.BigDecimal quotedAmount,
            String quotedCurrency, UUID rateCardId) {
        TenderWaterfallCandidate candidate = new TenderWaterfallCandidate(this, companyId,
                candidates.size() + 1, carrierId, quotedAmount, quotedCurrency, rateCardId);
        candidates.add(candidate);
        return candidate;
    }

    /**
     * The next carrier to offer to, or empty when there is none left <em>or</em> the attempt
     * ceiling has been reached.
     *
     * <p>The ceiling is counted here rather than by the caller so that the two questions - "is
     * there another carrier" and "are we allowed another attempt" - cannot be asked separately and
     * answered inconsistently.
     */
    public Optional<TenderWaterfallCandidate> nextToOffer() {
        if (attemptsUsed() >= maxAttempts) {
            return Optional.empty();
        }
        return candidates.stream()
                .filter(candidate -> candidate.status().isPending())
                .min(Comparator.comparingInt(TenderWaterfallCandidate::rank));
    }

    /** The candidate with an offer out right now, if any. At most one, by construction. */
    public Optional<TenderWaterfallCandidate> offered() {
        return candidates.stream().filter(candidate -> candidate.status().isOffered()).findFirst();
    }

    /** How many carriers have actually been offered the shipment. */
    public int attemptsUsed() {
        return (int) candidates.stream()
                .filter(candidate -> candidate.status().consumedAnAttempt())
                .count();
    }

    /** Whether the ceiling has been reached, regardless of how many carriers remain on the list. */
    public boolean hasReachedAttemptCeiling() {
        return attemptsUsed() >= maxAttempts;
    }

    /**
     * Ends the waterfall.
     *
     * <p>Every candidate that was never reached becomes {@link WaterfallCandidateStatus#SKIPPED},
     * which is the honest record: they were on the list and were not offered. Leaving them
     * {@code PENDING} would make a finished waterfall look like one still waiting to continue.
     *
     * <p>Idempotent - a waterfall already finished stays exactly as it finished, so a race between
     * an acceptance and the scheduler cannot rewrite an outcome.
     */
    public boolean finish(WaterfallStatus outcome, String note, OffsetDateTime at) {
        if (!outcome.isFinished()) {
            throw new IllegalArgumentException(outcome + " is not an outcome");
        }
        if (status.isFinished()) {
            return false;
        }
        status = outcome;
        outcomeNote = note;
        completedAt = at;
        candidates.stream()
                .filter(candidate -> candidate.status().isPending())
                .forEach(candidate -> candidate.skip(at));
        return true;
    }

    public UUID id() {
        return id;
    }

    public UUID companyId() {
        return companyId;
    }

    public UUID tripId() {
        return tripId;
    }

    public WaterfallStatus status() {
        return status;
    }

    public int maxAttempts() {
        return maxAttempts;
    }

    public int responseMinutes() {
        return responseMinutes;
    }

    public String outcomeNote() {
        return outcomeNote;
    }

    public OffsetDateTime startedAt() {
        return startedAt;
    }

    public OffsetDateTime completedAt() {
        return completedAt;
    }

    public List<TenderWaterfallCandidate> candidates() {
        return List.copyOf(candidates);
    }
}
