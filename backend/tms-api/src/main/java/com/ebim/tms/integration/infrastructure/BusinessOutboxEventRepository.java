package com.ebim.tms.integration.infrastructure;

import com.ebim.tms.integration.domain.BusinessOutboxEvent;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** Append-only persistence for {@link BusinessOutboxEvent} (V61: {@code tms_app} holds SELECT, INSERT). */
public interface BusinessOutboxEventRepository extends JpaRepository<BusinessOutboxEvent, UUID> {
}
