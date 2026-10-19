package com.ebim.tms.integration.infrastructure;

import com.ebim.tms.integration.domain.WarehouseOrderEvent;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** Append-only persistence for {@link WarehouseOrderEvent} (V61: {@code tms_app} holds SELECT, INSERT). */
public interface WarehouseOrderEventRepository extends JpaRepository<WarehouseOrderEvent, UUID> {

    boolean existsByCompanyIdAndSourceSystemAndEventId(UUID companyId, String sourceSystem, String eventId);
}
