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
import org.hibernate.annotations.UuidGenerator;

/**
 * One append-only entry in a shipment's operational timeline (migration V27): a trip transition, a
 * stop transition, or an exception being reported or resolved.
 *
 * <p>Immutable once written - no setter beyond the constructors, and the database withholds UPDATE
 * and DELETE from {@code tms_app} so that "append-only" is a fact rather than a convention. The
 * same shape and the same reasoning as {@code audit.domain.AuditEvent} and
 * {@link ShipmentOutboxEvent}.
 *
 * <p>Written in the <em>same</em> transaction as the transition it describes. A rolled-back
 * dispatch must not leave a timeline entry saying the truck left, which is exactly the guarantee
 * {@link ShipmentOutboxEvent} needs for the opposite audience.
 *
 * <p><b>Not event sourcing.</b> The trip and its stops stay the source of truth and are never
 * rebuilt by replaying this table; it is a read/report trail beside them, the relationship
 * {@code tms.audit_event} has already established in this schema.
 */
@Entity
@Table(name = "transport_event")
public class TransportEvent {

    @Id
    @GeneratedValue
    @UuidGenerator
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

    @Column(name = "company_id", updatable = false, nullable = false)
    private UUID companyId;

    @Column(name = "trip_id", updatable = false, nullable = false)
    private UUID tripId;

    /** Null for a trip-level event; set for one that happened at a stop. */
    @Column(name = "trip_stop_id", updatable = false)
    private UUID tripStopId;

    @Enumerated(EnumType.STRING)
    @Column(name = "event_type", updatable = false, nullable = false)
    private TransportEventType eventType;

    @Column(name = "event_time", updatable = false, nullable = false)
    private OffsetDateTime eventTime;

    @CreationTimestamp
    @Column(name = "recorded_at", updatable = false, nullable = false)
    private OffsetDateTime recordedAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "source", updatable = false, nullable = false)
    private TransportEventSource source;

    @Column(name = "actor_app_user_id", updatable = false)
    private UUID actorAppUserId;

    /** Denormalized so the timeline can name who acted; a snapshot, never re-resolved. */
    @Column(name = "actor_email", updatable = false)
    private String actorEmail;

    @Column(name = "actor_machine_label", updatable = false)
    private String actorMachineLabel;

    @Column(name = "latitude", updatable = false)
    private BigDecimal latitude;

    @Column(name = "longitude", updatable = false)
    private BigDecimal longitude;

    @Column(name = "notes", updatable = false)
    private String notes;

    @Column(name = "metadata", updatable = false)
    private String metadata;

    protected TransportEvent() {
        // JPA
    }

    /**
     * The full constructor. Never called directly by a use case: {@code TransportEventRecorder} is
     * the only writer, so the actor, the source and the scope rules are decided in one place
     * rather than at every transition that produces an event.
     *
     * @param eventTime when the fact happened - the operator's own time, not the moment this ran
     * @param actorAppUserId the person who acted, or null for a machine
     * @param actorEmail that person's address at the time they acted, null for a machine
     * @param actorMachineLabel the machine that acted, or null for a person. Exactly one of it and
     *     {@code actorAppUserId} is non-null, which {@code ck_transport_event_actor_xor} enforces
     */
    public TransportEvent(UUID companyId, UUID tripId, UUID tripStopId, TransportEventType eventType,
            OffsetDateTime eventTime, TransportEventSource source, UUID actorAppUserId, String actorEmail,
            String actorMachineLabel, BigDecimal latitude, BigDecimal longitude, String notes, String metadata) {
        requireScope(eventType, tripStopId);
        this.companyId = companyId;
        this.tripId = tripId;
        this.tripStopId = tripStopId;
        this.eventType = eventType;
        this.eventTime = eventTime;
        this.source = source;
        this.actorAppUserId = actorAppUserId;
        this.actorEmail = actorEmail;
        this.actorMachineLabel = actorMachineLabel;
        this.latitude = latitude;
        this.longitude = longitude;
        this.notes = notes;
        this.metadata = metadata;
    }

    /**
     * The scope rule, checked before the row exists rather than after the database refuses it.
     *
     * <p>An {@link IllegalArgumentException} and not a caller-facing 4xx: no request body chooses
     * an event type, so reaching this means a recorder paired a type with the wrong scope, which
     * is a defect. Same reasoning as {@code Trip.requireTransitionTo}.
     */
    private static void requireScope(TransportEventType eventType, UUID tripStopId) {
        if (eventType.requiresStop() && tripStopId == null) {
            throw new IllegalArgumentException(eventType + " is a stop event and must name a stop");
        }
        if (eventType.forbidsStop() && tripStopId != null) {
            throw new IllegalArgumentException(eventType + " is a trip event and cannot name a stop");
        }
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

    public TransportEventType eventType() {
        return eventType;
    }

    public OffsetDateTime eventTime() {
        return eventTime;
    }

    public OffsetDateTime recordedAt() {
        return recordedAt;
    }

    public TransportEventSource source() {
        return source;
    }

    public UUID actorAppUserId() {
        return actorAppUserId;
    }

    public String actorEmail() {
        return actorEmail;
    }

    public String actorMachineLabel() {
        return actorMachineLabel;
    }

    /** Who acted, in one string a screen can print: the person's address or the machine's label. */
    public String actorDisplayName() {
        return actorEmail != null ? actorEmail : actorMachineLabel;
    }

    public BigDecimal latitude() {
        return latitude;
    }

    public BigDecimal longitude() {
        return longitude;
    }

    public String notes() {
        return notes;
    }

    public String metadata() {
        return metadata;
    }
}
