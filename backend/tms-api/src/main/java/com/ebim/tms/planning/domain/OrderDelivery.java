package com.ebim.tms.planning.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;
import org.hibernate.annotations.UuidGenerator;

/**
 * The commercial outcome of one order at one {@link TripStop} (migration V28): delivered, partial,
 * rejected, failed, or never attempted - with who received it, when, and why it fell short.
 *
 * <p><b>Beside the stop's status, never inside it.</b> {@link TripStop#executionStatus()} is what
 * the vehicle did at a destination; this is what the goods did there. A stop serving three orders
 * can be {@link StopExecutionStatus#COMPLETED} with one of them {@link DeliveryResult#REJECTED},
 * and both statements are true - see {@link DeliveryResult}.
 *
 * <p><b>Not part of the {@link Trip} aggregate.</b> Reached through its own repository, exactly as
 * {@link TripOrderAssignment} is, and for the same two reasons: loading a trip must not drag in
 * every delivery recorded against it, and a delivery must be readable ("has order 4711 ever been
 * delivered?") without materialising the shipment that carried it.
 *
 * <p><b>Corrected in place, not appended to.</b> One row per {@code (tripStop, order)} pair, and
 * {@link #record} overwrites it. Nothing is lost by that: every recording also appends a
 * {@link TransportEventType#DELIVERY_RECORDED} entry to the append-only timeline, so what was
 * claimed and when lives there. Two rows here would instead make "was this order delivered" a
 * question with two answers and an ordering rule to pick between them.
 *
 * <p>The field rules ({@code deliveredAt} required or forbidden, receiver only where somebody was
 * present, an explanation for anything short) are {@link DeliveryResult}'s, asserted here as the
 * last line of defense and refused first - with a sentence a dispatcher can read - by
 * {@code TripDeliveryService}. Migration V28's CHECK constraints are the backstop under both.
 */
@Entity
@Table(name = "order_delivery")
public class OrderDelivery {

    @Id
    @GeneratedValue
    @UuidGenerator
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

    @Column(name = "company_id", updatable = false, nullable = false)
    private UUID companyId;

    @Column(name = "trip_id", updatable = false, nullable = false)
    private UUID tripId;

    @Column(name = "trip_stop_id", updatable = false, nullable = false)
    private UUID tripStopId;

    @Column(name = "order_id", updatable = false, nullable = false)
    private UUID orderId;

    @Enumerated(EnumType.STRING)
    @Column(name = "result", nullable = false)
    private DeliveryResult result;

    /** When the goods changed hands - the operator's own time, not when the row was typed. */
    @Column(name = "delivered_at")
    private OffsetDateTime deliveredAt;

    @Column(name = "receiver_name")
    private String receiverName;

    @Column(name = "receiver_document")
    private String receiverDocument;

    @Column(name = "notes")
    private String notes;

    /**
     * Where this recording came from. Reuses {@link TransportEventSource} rather than declaring a
     * parallel enum with the same three values: the vocabulary is the same question - a person at a
     * desk, a TMS rule, or a partner over the M2M API - and two copies of it would drift the day a
     * fourth source appears.
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "source", nullable = false)
    private TransportEventSource source;

    /** When it was typed, against {@link #deliveredAt}'s when-it-happened. */
    @CreationTimestamp
    @Column(name = "recorded_at", updatable = false, nullable = false)
    private OffsetDateTime recordedAt;

    @Column(name = "actor_app_user_id")
    private UUID actorAppUserId;

    /** A snapshot, never re-resolved: a later address change must not rewrite who signed off. */
    @Column(name = "actor_email")
    private String actorEmail;

    @Column(name = "actor_machine_label")
    private String actorMachineLabel;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false, nullable = false)
    private OffsetDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    @Column(name = "created_by", updatable = false)
    private UUID createdBy;

    @Column(name = "updated_by")
    private UUID updatedBy;

    protected OrderDelivery() {
        // JPA
    }

    /**
     * Records a delivery for the first time.
     *
     * @param deliveredAt when the goods changed hands; required by some results and forbidden by
     *     one - see {@link DeliveryResult}
     * @param actorAppUserId the person who recorded it, or null for a machine
     * @param actorMachineLabel the machine that recorded it, or null for a person. Exactly one of
     *     the two is non-null, which {@code ck_order_delivery_actor_xor} enforces
     */
    public OrderDelivery(UUID companyId, UUID tripId, UUID tripStopId, UUID orderId, DeliveryResult result,
            OffsetDateTime deliveredAt, String receiverName, String receiverDocument, String notes,
            TransportEventSource source, UUID actorAppUserId, String actorEmail, String actorMachineLabel) {
        this.companyId = companyId;
        this.tripId = tripId;
        this.tripStopId = tripStopId;
        this.orderId = orderId;
        this.createdBy = actorAppUserId;
        apply(result, deliveredAt, receiverName, receiverDocument, notes, source, actorAppUserId, actorEmail,
                actorMachineLabel);
    }

    /**
     * Replaces what this delivery says - a correction, or the second half of a delivery that was
     * recorded before the paperwork came back.
     *
     * <p>Overwrites every field including the actor, because the row is a statement of what is
     * believed <em>now</em> and by whom: leaving the original recorder on a row somebody else
     * corrected would attribute a claim to the wrong person. Who said what before is in the
     * timeline, which is append-only.
     *
     * <p>When it may still be called - the shipment has not been closed out - is
     * {@code TripDeliveryService}'s answer, not this method's: the entity does not know the trip's
     * status, and asking it to would put a lifecycle rule in the row it applies to.
     */
    public void record(DeliveryResult result, OffsetDateTime deliveredAt, String receiverName,
            String receiverDocument, String notes, TransportEventSource source, UUID actorAppUserId,
            String actorEmail, String actorMachineLabel) {
        apply(result, deliveredAt, receiverName, receiverDocument, notes, source, actorAppUserId, actorEmail,
                actorMachineLabel);
    }

    /**
     * The three field rules, asserted rather than assumed.
     *
     * <p>{@link IllegalArgumentException} and not a caller-facing 4xx on purpose: every service path
     * checks the same rules first and refuses with a message naming the result, so reaching this
     * means a caller skipped that check - and the honest answer to a defect is a rolled-back
     * transaction, not a delivery recorded as handed over at no particular time. Same reasoning as
     * {@code Trip.requireTransitionTo}.
     */
    private void apply(DeliveryResult result, OffsetDateTime deliveredAt, String receiverName,
            String receiverDocument, String notes, TransportEventSource source, UUID actorAppUserId,
            String actorEmail, String actorMachineLabel) {
        if (result.requiresDeliveredAt() && deliveredAt == null) {
            throw new IllegalArgumentException(result + " must say when the goods changed hands");
        }
        if (result.forbidsDeliveredAt() && deliveredAt != null) {
            throw new IllegalArgumentException(result + " cannot carry a delivery time");
        }
        if (!result.allowsReceiver() && (receiverName != null || receiverDocument != null)) {
            throw new IllegalArgumentException(result + " cannot name a receiver: nobody received anything");
        }
        if (result.requiresNotes() && (notes == null || notes.isBlank())) {
            throw new IllegalArgumentException(result + " must be explained");
        }
        this.result = result;
        this.deliveredAt = deliveredAt;
        this.receiverName = receiverName;
        this.receiverDocument = receiverDocument;
        this.notes = notes;
        this.source = source;
        this.actorAppUserId = actorAppUserId;
        this.actorEmail = actorEmail;
        this.actorMachineLabel = actorMachineLabel;
        this.updatedBy = actorAppUserId;
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

    public UUID tripStopId() {
        return tripStopId;
    }

    public UUID orderId() {
        return orderId;
    }

    public DeliveryResult result() {
        return result;
    }

    public OffsetDateTime deliveredAt() {
        return deliveredAt;
    }

    public String receiverName() {
        return receiverName;
    }

    public String receiverDocument() {
        return receiverDocument;
    }

    public String notes() {
        return notes;
    }

    public TransportEventSource source() {
        return source;
    }

    public OffsetDateTime recordedAt() {
        return recordedAt;
    }

    public UUID actorAppUserId() {
        return actorAppUserId;
    }

    /** Who recorded it, in one string a screen can print: the person's address or the machine's label. */
    public String actorDisplayName() {
        return actorEmail != null ? actorEmail : actorMachineLabel;
    }

    public OffsetDateTime createdAt() {
        return createdAt;
    }

    public OffsetDateTime updatedAt() {
        return updatedAt;
    }
}
