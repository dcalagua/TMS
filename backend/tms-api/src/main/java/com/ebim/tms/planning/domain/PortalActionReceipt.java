package com.ebim.tms.planning.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;
import java.util.Objects;
import java.util.UUID;
import org.hibernate.annotations.UuidGenerator;

/**
 * The {@code Idempotency-Key} of one carrier-portal execution action (V70): who sent it, for which
 * action and which target. Written in the same transaction as the action, never updated.
 *
 * <p>A replay is the same key from the same user for the same action and target: it is answered
 * with the current state and changes nothing. The same key for anything else is a client bug and is
 * refused - answering it with the first action's result would tell the carrier their second, different
 * report was recorded when nothing was.
 */
@Entity
@Table(name = "portal_action_receipt")
public class PortalActionReceipt {

    @Id
    @GeneratedValue
    @UuidGenerator
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

    @Column(name = "company_id", updatable = false, nullable = false)
    private UUID companyId;

    @Column(name = "trip_id", updatable = false, nullable = false)
    private UUID tripId;

    @Column(name = "trip_stop_id", updatable = false)
    private UUID tripStopId;

    @Column(name = "order_id", updatable = false)
    private UUID orderId;

    @Enumerated(EnumType.STRING)
    @Column(name = "action", updatable = false, nullable = false)
    private PortalAction action;

    @Column(name = "app_user_id", updatable = false, nullable = false)
    private UUID appUserId;

    @Column(name = "idempotency_key", updatable = false, nullable = false)
    private String idempotencyKey;

    @Column(name = "created_at", updatable = false, nullable = false)
    private OffsetDateTime createdAt;

    protected PortalActionReceipt() {
        // JPA
    }

    public PortalActionReceipt(UUID companyId, UUID tripId, UUID tripStopId, UUID orderId, PortalAction action,
            UUID appUserId, String idempotencyKey) {
        this.companyId = Objects.requireNonNull(companyId, "companyId");
        this.tripId = Objects.requireNonNull(tripId, "tripId");
        this.tripStopId = tripStopId;
        this.orderId = orderId;
        this.action = Objects.requireNonNull(action, "action");
        this.appUserId = Objects.requireNonNull(appUserId, "appUserId");
        this.idempotencyKey = Objects.requireNonNull(idempotencyKey, "idempotencyKey");
        this.createdAt = OffsetDateTime.now();
    }

    /** Whether this receipt answers for exactly this action on exactly this target. */
    public boolean answersFor(PortalAction candidateAction, UUID candidateTripId, UUID candidateStopId,
            UUID candidateOrderId) {
        return action == candidateAction && tripId.equals(candidateTripId)
                && Objects.equals(tripStopId, candidateStopId) && Objects.equals(orderId, candidateOrderId);
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

    public PortalAction action() {
        return action;
    }

    public UUID appUserId() {
        return appUserId;
    }

    public String idempotencyKey() {
        return idempotencyKey;
    }

    public OffsetDateTime createdAt() {
        return createdAt;
    }
}
