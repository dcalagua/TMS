package com.ebim.tms.settlement.infrastructure;

import com.ebim.tms.settlement.domain.AccrualEntryType;
import com.ebim.tms.settlement.domain.AccrualSourceType;
import com.ebim.tms.settlement.domain.FreightAccrualEntry;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** The movements (V65). Insert and read; the entity is {@code @Immutable} and the table append-only. */
public interface FreightAccrualEntryRepository extends JpaRepository<FreightAccrualEntry, UUID> {

    List<FreightAccrualEntry> findByCompanyIdAndAccrualIdOrderByCreatedAtAscIdAsc(UUID companyId, UUID accrualId);

    boolean existsByCompanyIdAndAccrualIdAndEntryTypeAndSourceTypeAndSourceKey(UUID companyId, UUID accrualId,
            AccrualEntryType entryType, AccrualSourceType sourceType, String sourceKey);

    List<FreightAccrualEntry> findByCompanyIdAndPeriodOrderByCreatedAtAscIdAsc(UUID companyId, LocalDate period);
}
