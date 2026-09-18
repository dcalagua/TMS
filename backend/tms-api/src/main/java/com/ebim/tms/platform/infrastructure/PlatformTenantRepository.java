package com.ebim.tms.platform.infrastructure;

import com.ebim.tms.platform.domain.PlatformTenant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** Persistence for the control-plane tenant mapping. */
public interface PlatformTenantRepository extends JpaRepository<PlatformTenant, UUID> {

    /**
     * The only lookup that matters: the control plane knows its own id and nothing else about TMS.
     * Backed by {@code uq_platform_tenant_control_plane}, which is also what makes provisioning the
     * same tenant twice impossible rather than merely unlikely.
     */
    Optional<PlatformTenant> findByControlPlaneTenantId(UUID controlPlaneTenantId);
}
