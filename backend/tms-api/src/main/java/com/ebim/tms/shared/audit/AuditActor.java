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
 * <p>An actor is either a person or a machine, and the record says which without ambiguity: a
 * person has an {@code appUserId} and an {@code email} and no {@code machineActorLabel}; a
 * machine has the label and neither of the other two. A machine deliberately gets no invented
 * {@code app_user} row - see {@link com.ebim.tms.shared.security.MachineAuthentication}.
 *
 * @param companyId         the selected company, absent for principal-scoped operations
 * @param machineActorLabel non-null exactly when the caller is a system rather than a person;
 *     safe to log, and never secret material
 */
public record AuditActor(
        UUID appUserId,
        String email,
        UUID companyId,
        UUID organizationId,
        String correlationId,
        String machineActorLabel) {

    /** A person, resolved from a validated Supabase token. */
    public static AuditActor person(UUID appUserId, String email, UUID companyId, UUID organizationId,
            String correlationId) {
        return new AuditActor(appUserId, email, companyId, organizationId, correlationId, null);
    }

    /** A system caller: no {@code app_user}, identified by its label instead. */
    public static AuditActor machine(String machineActorLabel, UUID companyId, UUID organizationId,
            String correlationId) {
        return new AuditActor(null, null, companyId, organizationId, correlationId, machineActorLabel);
    }

    public boolean isMachine() {
        return machineActorLabel != null;
    }

    public Optional<UUID> company() {
        return Optional.ofNullable(companyId);
    }
}
