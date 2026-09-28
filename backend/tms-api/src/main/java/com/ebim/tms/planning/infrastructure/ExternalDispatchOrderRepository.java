package com.ebim.tms.planning.infrastructure;

import com.ebim.tms.planning.domain.ExternalDispatchOrder;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** Persistence for {@link ExternalDispatchOrder}. */
public interface ExternalDispatchOrderRepository extends JpaRepository<ExternalDispatchOrder, UUID> {

    List<ExternalDispatchOrder> findByCompanyIdAndExternalDispatchIdIn(UUID companyId, Collection<UUID> dispatchIds);
}
