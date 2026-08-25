package com.ebim.tms.audit.application;

import com.ebim.tms.shared.audit.AuditAction;
import com.ebim.tms.shared.audit.AuditAggregateType;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * The optional filters for {@code GET /audit-events}.
 *
 * <p>Every one of them is served by an index that already exists (migration V22):
 * {@code ix_audit_event_company_occurred} for the date window, which is the filter a compliance
 * question always starts from, and {@code ix_audit_event_company_aggregate} for "everything that
 * happened to this trip". Nothing here can ask a question the table would have to scan to answer -
 * an audit trail grows without bound and is read rarely, which is exactly the shape where one
 * unindexed filter becomes a production incident months after it shipped.
 *
 * <p>There is deliberately no free-text search over {@code metadata}. It is a JSON blob whose keys
 * differ per action; searching it would mean either a scan or a GIN index on a column nobody has
 * agreed the contents of.
 *
 * <p>There is no {@code companyId} either, here or anywhere else: the tenant comes from
 * {@code X-Company-Id} and is checked against the caller's memberships. A filter that accepted one
 * would put the tenant back in the client's hands.
 *
 * @param actorAppUserId one person's actions - the "what did they touch" question an
 *     investigation asks after it has a name
 * @param aggregateType  narrow to one kind of thing (trips, orders, users)
 * @param aggregateId    one specific record's whole history; usually paired with the type
 * @param action         one kind of change
 * @param from           inclusive lower bound on {@code occurredAt}
 * @param to             inclusive upper bound on {@code occurredAt}
 * @param correlationId  every entry written while serving one HTTP request
 */
public record AuditFilter(
        UUID actorAppUserId,
        AuditAggregateType aggregateType,
        UUID aggregateId,
        AuditAction action,
        OffsetDateTime from,
        OffsetDateTime to,
        String correlationId) {

    /** No filter at all: the company's most recent history. */
    public static AuditFilter none() {
        return new AuditFilter(null, null, null, null, null, null, null);
    }
}
