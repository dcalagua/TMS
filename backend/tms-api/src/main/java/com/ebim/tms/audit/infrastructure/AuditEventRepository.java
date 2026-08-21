package com.ebim.tms.audit.infrastructure;

import com.ebim.tms.audit.domain.AuditEvent;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

/**
 * The audit trail's repository.
 *
 * <p>{@link JpaSpecificationExecutor} is here for the read side ({@code AuditQueryService}) and
 * nothing else. There are deliberately no update or delete methods beyond the ones
 * {@link JpaRepository} cannot avoid inheriting: {@code tms.audit_event} is append-only, and the
 * database says so too - migration V22 revokes UPDATE and DELETE from {@code tms_app}, so a call
 * to {@code delete} fails at the connection rather than quietly succeeding.
 */
public interface AuditEventRepository extends JpaRepository<AuditEvent, UUID>, JpaSpecificationExecutor<AuditEvent> {
}
