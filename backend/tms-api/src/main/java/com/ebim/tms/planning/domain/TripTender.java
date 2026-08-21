package com.ebim.tms.planning.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;
import org.hibernate.annotations.UuidGenerator;

/**
 * One attempt to place a shipment with a carrier: what was offered, when it went out, and what came
 * back (migration V31).
 *
 * <p>An aggregate of its own, reached through {@code TripTenderRepository} rather than hung off
 * {@link Trip} - the same relationship {@link TripOrderAssignment} and {@link TripException} have
 * with it, and for the same two reasons. Loading a trip must not drag in its whole tender history,
 * and that history has to outlive the trip's own edits: a rejection from attempt 1 is still a fact
 * after attempt 2 was accepted.
 *
 * <p><b>What this entity does not decide.</b> Whether the trip may be tendered at all (it must be
 * committed, not departed, not cancelled), whether the carrier is the trip's own, and whether a
 * live attempt already exists are all {@code TripTenderService}'s checks - they are questions about
 * <em>other</em> rows, which an entity holding one row cannot answer. What lives here is the
 * transition table ({@link TenderStatus}), the shape of a legal row, and the one rule a status enum
 * cannot express: {@link #effectiveStatus}.
 *
 * <p><b>Expiry is asked, not stored.</b> {@link #effectiveStatus} reports a {@link TenderStatus#SENT}
 * tender past its deadline as {@link TenderStatus#EXPIRED}, whatever the column says. Every read
 * goes through it, so no screen shows a lapsed offer as live and no response is accepted after the
 * deadline; the column catches up when the next write touches the tender
 * ({@code TripTenderService.resolveLapse}). Migration V31, section 1b, says why there is no job and
 * what the lag costs.
 */
@Entity
@Table(name = "trip_tender")
public class TripTender {

    @Id
    @GeneratedValue
    @UuidGenerator
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

    @Column(name = "company_id", updatable = false, nullable = false)
    private UUID companyId;

    @Column(name = "trip_id", updatable = false, nullable = false)
    private UUID tripId;

    /**
     * Snapshotted at creation and never updated - always the trip's own carrier at the moment the
     * offer was prepared. See migration V31 for why a tender cannot name a different one.
     */
    @Column(name = "carrier_id", updatable = false, nullable = false)
    private UUID carrierId;

    @Column(name = "attempt", updatable = false, nullable = false)
    private int attempt;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private TenderStatus status;

    @Column(name = "offered_amount", precision = 14, scale = 2)
    private BigDecimal offeredAmount;

    @Column(name = "currency")
    private String currency;

    @Column(name = "notes")
    private String notes;

    @Column(name = "expires_at")
    private OffsetDateTime expiresAt;

    @Column(name = "created_by", updatable = false, nullable = false)
    private UUID createdBy;

    @Column(name = "sent_at")
    private OffsetDateTime sentAt;

    @Column(name = "sent_by")
    private UUID sentBy;

    @Column(name = "responded_at")
    private OffsetDateTime respondedAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "response_source")
    private TenderResponseSource responseSource;

    @Column(name = "responded_by")
    private UUID respondedBy;

    @Column(name = "responded_by_client")
    private UUID respondedByClient;

    @Column(name = "response_notes")
    private String responseNotes;

    @Column(name = "expired_at")
    private OffsetDateTime expiredAt;

    @Column(name = "cancelled_at")
    private OffsetDateTime cancelledAt;

    @Column(name = "cancelled_by")
    private UUID cancelledBy;

    @Column(name = "cancel_reason")
    private String cancelReason;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false, nullable = false)
    private OffsetDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    @Column(name = "updated_by")
    private UUID updatedBy;

    protected TripTender() {
        // JPA
    }

    /**
     * Prepares an offer. Always {@link TenderStatus#DRAFT}: nothing has left this company yet, which
     * is why creating one is not audited and not published.
     *
     * @param attempt the nth attempt on this trip, from 1 - {@code TripTenderService} counts it
     * @param offeredAmount and {@code currency}: both or neither
     */
    public TripTender(UUID companyId, UUID tripId, UUID carrierId, int attempt, BigDecimal offeredAmount,
            String currency, String notes, OffsetDateTime expiresAt, UUID createdBy) {
        requireOfferPair(offeredAmount, currency);
        this.companyId = companyId;
        this.tripId = tripId;
        this.carrierId = carrierId;
        this.attempt = attempt;
        this.status = TenderStatus.DRAFT;
        this.offeredAmount = offeredAmount;
        this.currency = currency;
        this.notes = notes;
        this.expiresAt = expiresAt;
        this.createdBy = createdBy;
        this.updatedBy = createdBy;
    }

    /**
     * Rewrites the terms of an offer nobody has seen yet.
     *
     * <p>Refused after {@link TenderStatus#DRAFT}, and that refusal is the entire justification for
     * DRAFT existing: an offer whose amount can change under the party considering it is not an
     * offer. Correcting a sent tender means cancelling it and sending another, which is a second
     * attempt and is recorded as one.
     */
    public void updateTerms(BigDecimal offeredAmount, String currency, String notes, OffsetDateTime expiresAt,
            UUID actorId) {
        if (!status.allowsTermEdits()) {
            throw new IllegalStateException("tender " + attempt + " is " + status + " and its terms are frozen");
        }
        requireOfferPair(offeredAmount, currency);
        this.offeredAmount = offeredAmount;
        this.currency = currency;
        this.notes = notes;
        this.expiresAt = expiresAt;
        this.updatedBy = actorId;
    }

    /**
     * Sends the offer out. From here the terms are frozen, an outbox row exists and the carrier can
     * answer.
     *
     * @param sentAt when the offer left, so a deadline can be checked against it
     * @throws IllegalArgumentException if the deadline is not after the moment of sending - an
     *     offer that expires before it arrives is not an offer, and
     *     {@code ck_trip_tender_deadline_after_sent} would refuse the row anyway. Thrown here so
     *     the failure names the problem instead of surfacing a constraint name
     */
    public void send(OffsetDateTime sentAt, UUID actorId) {
        requireTransitionTo(TenderStatus.SENT);
        if (expiresAt != null && !expiresAt.isAfter(sentAt)) {
            throw new IllegalArgumentException("a tender's deadline must be after the moment it is sent");
        }
        this.status = TenderStatus.SENT;
        this.sentAt = sentAt;
        this.sentBy = actorId;
        this.updatedBy = actorId;
    }

    /**
     * The carrier said yes.
     *
     * <p>That there is only ever one accepted tender per trip is not this method's guarantee - it
     * is {@code uq_trip_tender_accepted}'s, backing {@code TripTenderService}'s own check. An entity
     * holding one row cannot see the others.
     */
    public void accept(OffsetDateTime respondedAt, TenderResponseSource source, UUID appUserId, UUID clientId,
            String notes) {
        requireTransitionTo(TenderStatus.ACCEPTED);
        applyResponse(TenderStatus.ACCEPTED, respondedAt, source, appUserId, clientId, notes);
    }

    /**
     * The carrier said no, and said why.
     *
     * @param reason required - {@code ck_trip_tender_rejection_has_reason} refuses a blank one, and
     *     so does this: "they declined" with no reason is the answer that helps a planner least,
     *     precisely when they have to decide what to do next
     */
    public void reject(OffsetDateTime respondedAt, TenderResponseSource source, UUID appUserId, UUID clientId,
            String reason) {
        requireTransitionTo(TenderStatus.REJECTED);
        if (reason == null || reason.isBlank()) {
            throw new IllegalArgumentException("a rejected tender must carry the carrier's reason");
        }
        applyResponse(TenderStatus.REJECTED, respondedAt, source, appUserId, clientId, reason);
    }

    /**
     * Materialises a lapse the reads have been reporting all along.
     *
     * <p>Takes no actor: nobody expired this tender, a deadline did. The person whose action
     * happened to trigger the materialisation is recorded in {@code updated_by} and in the audit
     * event, which is the honest split - they did not decide this, they were merely present.
     *
     * @param expiredAt when TMS resolved the lapse. Not {@code expiresAt}: the two are different
     *     facts and V31 keeps both
     */
    public void expire(OffsetDateTime expiredAt, UUID actorId) {
        requireTransitionTo(TenderStatus.EXPIRED);
        if (expiresAt == null) {
            throw new IllegalStateException("tender " + attempt + " has no deadline and cannot expire");
        }
        this.status = TenderStatus.EXPIRED;
        this.expiredAt = expiredAt;
        this.updatedBy = actorId;
    }

    /**
     * Withdraws the offer, from either live state.
     *
     * <p>Also what TMS writes when the shipment stops being offerable underneath a live tender - it
     * was cancelled, or it left without an answer. Those are withdrawals with a reason, not a state
     * of their own: from the carrier's side the offer is off, and why is a sentence.
     */
    public void cancel(String reason, UUID actorId) {
        requireTransitionTo(TenderStatus.CANCELLED);
        if (reason == null || reason.isBlank()) {
            throw new IllegalArgumentException("a withdrawn tender must carry a reason");
        }
        this.status = TenderStatus.CANCELLED;
        this.cancelledAt = OffsetDateTime.now();
        this.cancelledBy = actorId;
        this.cancelReason = reason;
        this.updatedBy = actorId;
    }

    /**
     * What this tender <em>is</em>, as opposed to what the column says: {@link TenderStatus#EXPIRED}
     * for a sent offer whose deadline has passed, and {@link #status} for everything else.
     *
     * <p>Every read and every guard goes through this rather than through {@link #status}, which is
     * what makes "a lapsed offer is never actionable and never displayed as live" true without a
     * scheduler. See the class comment and migration V31, section 1b.
     */
    public TenderStatus effectiveStatus(OffsetDateTime now) {
        return hasLapsedAt(now) ? TenderStatus.EXPIRED : status;
    }

    /** Whether the deadline has passed on an offer still recorded as sent. */
    public boolean hasLapsedAt(OffsetDateTime now) {
        return status == TenderStatus.SENT && expiresAt != null && !expiresAt.isAfter(now);
    }

    /** Whether the carrier may still answer, deadline included. */
    public boolean awaitsResponseAt(OffsetDateTime now) {
        return effectiveStatus(now).awaitsResponse();
    }

    /** Whether this attempt still occupies the trip's live slot, deadline <em>not</em> included. */
    public boolean isLive() {
        return status.isLive();
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

    public UUID carrierId() {
        return carrierId;
    }

    public int attempt() {
        return attempt;
    }

    /** The stored value. Callers that render or guard want {@link #effectiveStatus} instead. */
    public TenderStatus status() {
        return status;
    }

    public BigDecimal offeredAmount() {
        return offeredAmount;
    }

    public String currency() {
        return currency;
    }

    public String notes() {
        return notes;
    }

    public OffsetDateTime expiresAt() {
        return expiresAt;
    }

    public OffsetDateTime sentAt() {
        return sentAt;
    }

    public UUID sentBy() {
        return sentBy;
    }

    public OffsetDateTime respondedAt() {
        return respondedAt;
    }

    public TenderResponseSource responseSource() {
        return responseSource;
    }

    public UUID respondedBy() {
        return respondedBy;
    }

    public UUID respondedByClient() {
        return respondedByClient;
    }

    public String responseNotes() {
        return responseNotes;
    }

    public OffsetDateTime expiredAt() {
        return expiredAt;
    }

    public OffsetDateTime cancelledAt() {
        return cancelledAt;
    }

    public String cancelReason() {
        return cancelReason;
    }

    public OffsetDateTime createdAt() {
        return createdAt;
    }

    public OffsetDateTime updatedAt() {
        return updatedAt;
    }

    public UUID createdBy() {
        return createdBy;
    }

    /**
     * The shared half of {@link #accept} and {@link #reject}, including the source/actor pairing
     * that {@code ck_trip_tender_response_actor} enforces in the database. Asserted here too so a
     * caller that passed the wrong pair is told what it did wrong rather than being handed a
     * constraint violation from three layers down.
     */
    private void applyResponse(TenderStatus target, OffsetDateTime respondedAt, TenderResponseSource source,
            UUID appUserId, UUID clientId, String notes) {
        if (source == TenderResponseSource.OPERATOR && (appUserId == null || clientId != null)) {
            throw new IllegalArgumentException("an OPERATOR response names an app user and no credential");
        }
        if (source == TenderResponseSource.INTEGRATION && (clientId == null || appUserId != null)) {
            throw new IllegalArgumentException("an INTEGRATION response names a credential and no app user");
        }
        if (sentAt != null && respondedAt.isBefore(sentAt)) {
            throw new IllegalArgumentException("a tender cannot be answered before it was sent");
        }
        this.status = target;
        this.respondedAt = respondedAt;
        this.responseSource = source;
        this.respondedBy = appUserId;
        this.respondedByClient = clientId;
        this.responseNotes = notes;
        this.updatedBy = appUserId;
    }

    /**
     * The transition table's last line of defense, in the transaction that broke it.
     *
     * <p>An {@link IllegalStateException} and not a caller-facing 4xx, exactly as
     * {@code Trip.requireTransitionTo} is: every service path consults
     * {@link TenderStatus#canTransitionTo} first and refuses with a message naming both states, so
     * reaching this is a defect in a caller that skipped that check.
     */
    private void requireTransitionTo(TenderStatus target) {
        if (!status.canTransitionTo(target)) {
            throw new IllegalStateException(
                    "tender " + attempt + " cannot move from " + status + " to " + target);
        }
    }

    /** An amount with no currency is not a price, and a currency with no amount is not an offer. */
    private static void requireOfferPair(BigDecimal offeredAmount, String currency) {
        if ((offeredAmount == null) != (currency == null)) {
            throw new IllegalArgumentException("an offered amount and its currency must be given together");
        }
    }
}
