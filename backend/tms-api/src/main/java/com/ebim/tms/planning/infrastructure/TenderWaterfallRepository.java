package com.ebim.tms.planning.infrastructure;

import com.ebim.tms.planning.domain.TenderWaterfall;
import com.ebim.tms.planning.domain.WaterfallStatus;
import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.QueryHints;
import org.springframework.data.repository.query.Param;

/** Company-scoped persistence for the tender waterfall (migration V40). */
public interface TenderWaterfallRepository extends JpaRepository<TenderWaterfall, UUID> {

    Optional<TenderWaterfall> findByIdAndCompanyId(UUID id, UUID companyId);

    Optional<TenderWaterfall> findByCompanyIdAndTripIdAndStatus(UUID companyId, UUID tripId,
            WaterfallStatus status);

    List<TenderWaterfall> findByCompanyIdAndTripIdOrderByStartedAtDesc(UUID companyId, UUID tripId);

    /** One waterfall under a write lock, for the paths that already know which one they mean. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @QueryHints(@jakarta.persistence.QueryHint(name = "jakarta.persistence.lock.timeout", value = "5000"))
    @Query("select w from TenderWaterfall w where w.id = :id")
    Optional<TenderWaterfall> findByIdForUpdate(@Param("id") UUID id);
}
