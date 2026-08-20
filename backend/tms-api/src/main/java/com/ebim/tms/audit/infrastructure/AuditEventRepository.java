package com.ebim.tms.audit.infrastructure;

import com.ebim.tms.audit.domain.AuditEvent;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AuditEventRepository extends JpaRepository<AuditEvent, UUID> {
}
