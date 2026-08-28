package com.ebim.tms.fleet.infrastructure;

import com.ebim.tms.fleet.domain.DriverShift;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** Company-scoped persistence for {@link DriverShift}. */
public interface DriverShiftRepository extends JpaRepository<DriverShift, UUID> {

    Optional<DriverShift> findByIdAndCompanyId(UUID id, UUID companyId);

    List<DriverShift> findByCompanyIdAndDriverIdOrderByDayOfWeekAsc(UUID companyId, UUID driverId);

    Optional<DriverShift> findByCompanyIdAndDriverIdAndDayOfWeek(UUID companyId, UUID driverId, int dayOfWeek);
}
