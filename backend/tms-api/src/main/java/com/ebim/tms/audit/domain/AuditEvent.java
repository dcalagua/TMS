package com.ebim.tms.audit.domain;

import com.ebim.tms.shared.audit.AuditAction;
import com.ebim.tms.shared.audit.AuditAggregateType;
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
import org.hibernate.annotations.UuidGenerator;

/**
 * One append-only business audit record (migration V22): who did what to which aggregate, when,
 * under which correlation id.
 *
 * <p>Written once by {@code AuditEventRecorder}, in the same transaction as the change it
 * describes. Immutable after insert - there is no setter, no {@code updated_at}, and {@code
 * tms_app} holds no UPDATE/DELETE grant on the table (V22): an audit record that can be edited
 * answers a different question from the one it was written for.
 */
@Entity
@Table(name = "audit_event")
public class AuditEvent {

    @Id
    @GeneratedValue
    @UuidGenerator
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

    @Column(name = "company_id", updatable = false, nullable = false)
    private UUID companyId;

    @Column(name = "actor_app_user_id", updatable = false)
    private UUID actorAppUserId;

    @Column(name = "actor_email", updatable = false)
    private String actorEmail;

    @Column(name = "actor_machine_label", updatable = false)
    private String actorMachineLabel;

    @Enumerated(EnumType.STRING)
    @Column(name = "aggregate_type", updatable = false, nullable = false)
    private AuditAggregateType aggregateType;

    @Column(name = "aggregate_id", updatable = false, nullable = false)
    private UUID aggregateId;

    @Enumerated(EnumType.STRING)
    @Column(name = "action", updatable = false, nullable = false)
    private AuditAction action;

    @CreationTimestamp
    @Column(name = "occurred_at", updatable = false, nullable = false)
    private OffsetDateTime occurredAt;

    @Column(name = "correlation_id", updatable = false)
    private String correlationId;

    @Column(name = "metadata", updatable = false)
    private String metadata;

    protected AuditEvent() {
        // JPA
    }

    public AuditEvent(UUID companyId, UUID actorAppUserId, String actorEmail, String actorMachineLabel,
            AuditAggregateType aggregateType, UUID aggregateId, AuditAction action, String correlationId,
            String metadata) {
        this.companyId = companyId;
        this.actorAppUserId = actorAppUserId;
        this.actorEmail = actorEmail;
        this.actorMachineLabel = actorMachineLabel;
        this.aggregateType = aggregateType;
        this.aggregateId = aggregateId;
        this.action = action;
        this.correlationId = correlationId;
        this.metadata = metadata;
    }

    public UUID id() {
        return id;
    }

    public UUID companyId() {
        return companyId;
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

    public AuditAggregateType aggregateType() {
        return aggregateType;
    }

    public UUID aggregateId() {
        return aggregateId;
    }

    public AuditAction action() {
        return action;
    }

    public OffsetDateTime occurredAt() {
        return occurredAt;
    }

    public String correlationId() {
        return correlationId;
    }

    public String metadata() {
        return metadata;
    }
}
