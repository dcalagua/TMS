package com.ebim.tms.masterdata.infrastructure;

import com.ebim.tms.masterdata.domain.FrequencyException;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Persistence for {@link FrequencyException}. Scoped by {@code frequencyId} rather than
 * {@code companyId} directly - {@code FrequencyService} resolves and authorizes the parent
 * {@link com.ebim.tms.masterdata.domain.Frequency} first (company-scoped), then every exception
 * finder here takes that already-authorized id, exactly like {@code FrequencyWeeklyRule} is
 * only ever reached through its parent.
 */
public interface FrequencyExceptionRepository extends JpaRepository<FrequencyException, UUID> {

    List<FrequencyException> findByFrequencyIdOrderByExceptionDateAsc(UUID frequencyId);

    Optional<FrequencyException> findByIdAndFrequencyId(UUID id, UUID frequencyId);

    boolean existsByFrequencyIdAndExceptionDate(UUID frequencyId, LocalDate exceptionDate);
}
