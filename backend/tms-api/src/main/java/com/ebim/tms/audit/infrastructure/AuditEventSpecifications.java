package com.ebim.tms.audit.infrastructure;

import com.ebim.tms.audit.application.AuditFilter;
import com.ebim.tms.audit.domain.AuditEvent;
import java.util.UUID;
import org.springframework.data.jpa.domain.Specification;

/**
 * Composes the audit trail's optional filters. See {@code TransportOrderSpecifications}.
 *
 * <p>The company predicate is applied first and unconditionally, and {@code companyId} is a
 * separate argument from the {@link AuditFilter} rather than a field on it. That is not
 * decoration: it is what makes it impossible to build one of these without a tenant, and the
 * filter object - which is bound straight from the query string - has no way to influence it.
 */
public final class AuditEventSpecifications {

    private AuditEventSpecifications() {}

    public static Specification<AuditEvent> matching(UUID companyId, AuditFilter filter) {
        Specification<AuditEvent> specification = (root, query, cb) -> cb.equal(root.get("companyId"), companyId);

        if (filter.actorAppUserId() != null) {
            specification = specification.and(
                    (root, query, cb) -> cb.equal(root.get("actorAppUserId"), filter.actorAppUserId()));
        }
        if (filter.aggregateType() != null) {
            specification = specification.and(
                    (root, query, cb) -> cb.equal(root.get("aggregateType"), filter.aggregateType()));
        }
        if (filter.aggregateId() != null) {
            specification = specification.and(
                    (root, query, cb) -> cb.equal(root.get("aggregateId"), filter.aggregateId()));
        }
        if (filter.action() != null) {
            specification = specification.and((root, query, cb) -> cb.equal(root.get("action"), filter.action()));
        }
        if (filter.from() != null) {
            specification = specification.and(
                    (root, query, cb) -> cb.greaterThanOrEqualTo(root.get("occurredAt"), filter.from()));
        }
        if (filter.to() != null) {
            specification = specification.and(
                    (root, query, cb) -> cb.lessThanOrEqualTo(root.get("occurredAt"), filter.to()));
        }
        if (filter.correlationId() != null && !filter.correlationId().isBlank()) {
            String correlationId = filter.correlationId().trim();
            specification = specification.and(
                    (root, query, cb) -> cb.equal(root.get("correlationId"), correlationId));
        }
        return specification;
    }
}
