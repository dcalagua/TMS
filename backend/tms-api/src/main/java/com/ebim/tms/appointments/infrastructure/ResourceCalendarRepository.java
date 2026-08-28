package com.ebim.tms.appointments.infrastructure;

import com.ebim.tms.appointments.domain.ResourceCalendarEntry;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** The opening hours of one door, by weekday. */
public interface ResourceCalendarRepository extends JpaRepository<ResourceCalendarEntry, UUID> {

    List<ResourceCalendarEntry> findByCompanyIdAndResourceIdOrderByDayOfWeekAsc(UUID companyId, UUID resourceId);

    Optional<ResourceCalendarEntry> findByCompanyIdAndResourceIdAndDayOfWeek(
            UUID companyId, UUID resourceId, int dayOfWeek);

    void deleteByResourceId(UUID resourceId);
}
