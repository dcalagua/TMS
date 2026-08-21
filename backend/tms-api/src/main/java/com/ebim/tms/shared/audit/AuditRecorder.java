package com.ebim.tms.shared.audit;

import com.ebim.tms.shared.security.CompanyScope;
import java.util.Map;
import java.util.UUID;

/**
 * The one way a business module other than {@code audit} may record a business audit event,
 * without depending on {@code com.ebim.tms.audit} directly.
 *
 * <p>{@code audit} owns {@code tms.audit_event}; {@code ModuleBoundaryTest} forbids any other
 * business module from importing it. This port is the "explicit API"
 * {@code ModuleBoundaryTest.business_modules_must_not_depend_on_each_other}'s message points to
 * - the same shape {@code com.ebim.tms.shared.reference.OriginLookupPort} already established for
 * {@code masterdata}. {@code com.ebim.tms.audit.application.AuditEventRecorder} is the only
 * implementation.
 *
 * <p>Every call happens inside the caller's own transaction (no {@code REQUIRES_NEW}): a
 * business audit event describes something that happened, so if the change it describes rolls
 * back, the event recording it must roll back too. That is deliberately different from
 * {@code IntegrationInboxService}, whose delivery record is written independently of the
 * business outcome it reports on.
 */
public interface AuditRecorder {

    /**
     * Records one audit event for the current actor (resolved server-side, never accepted from a
     * caller) and the given aggregate.
     *
     * @param scope the tenant the change belongs to - stamped onto the event, not derived from
     *     the actor, so the event is scoped exactly like the write it describes
     * @param aggregateType what kind of thing changed
     * @param aggregateId the changed row's id
     * @param action what happened to it
     * @param metadata compact, business-only detail (a code, a reason, a count) - never a secret
     *     and never the row-level detail already in the aggregate's own table. May be empty or
     *     {@code null}.
     * @throws IllegalStateException when there is no authenticated actor, so an audit event can
     *     never silently record with no actor because nobody was authenticated
     */
    void record(CompanyScope scope, AuditAggregateType aggregateType, UUID aggregateId, AuditAction action,
            Map<String, Object> metadata);
}
