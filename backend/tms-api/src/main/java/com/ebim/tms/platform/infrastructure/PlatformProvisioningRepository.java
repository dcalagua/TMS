package com.ebim.tms.platform.infrastructure;

import com.ebim.tms.platform.domain.PlatformProvisioning;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** Persistence for the provisioning request log. */
public interface PlatformProvisioningRepository extends JpaRepository<PlatformProvisioning, UUID> {

    /** One row per key ({@code uq_platform_provisioning_idempotency_key}); this is how a replay finds it. */
    Optional<PlatformProvisioning> findByIdempotencyKey(String idempotencyKey);

    /**
     * The successful call that produced a tenant, for the read endpoint. Ordered because a key that
     * failed and was retried leaves earlier rows behind, and the answer is the one that worked.
     */
    Optional<PlatformProvisioning> findFirstByPlatformTenantIdOrderByCompletedAtDesc(UUID platformTenantId);
}
