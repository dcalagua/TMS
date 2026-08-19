package com.ebim.tms.shared.audit;

import java.util.Optional;
import java.util.UUID;

/**
 * Who did it, where, and under which trace - the actor context every write records.
 *
 * <p>{@code appUserId} is what lands in the {@code created_by} / {@code updated_by} columns
 * defined in migration V2, and {@code companyId} is the tenant the change belongs to. Both are
 * resolved server-side from the authenticated request; neither is ever accepted from a payload,
 * because an audit trail a client can write is not an audit trail.
 *
 * @param companyId the selected company, absent for principal-scoped operations
 */
public record AuditActor(UUID appUserId, String email, UUID companyId, UUID organizationId, String correlationId) {

    public Optional<UUID> company() {
        return Optional.ofNullable(companyId);
    }
}
