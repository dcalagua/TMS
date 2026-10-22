package com.ebim.tms.fleet.infrastructure;

import com.ebim.tms.fleet.domain.FleetComplianceSettings;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** One row per company, keyed by the company itself (migration V72). */
public interface FleetComplianceSettingsRepository extends JpaRepository<FleetComplianceSettings, UUID> {

    Optional<FleetComplianceSettings> findByCompanyId(UUID companyId);
}
