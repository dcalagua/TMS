package com.ebim.tms.planning.infrastructure;

import com.ebim.tms.planning.domain.DocumentDelivery;
import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** The tracked documents of trips (ADR-018, V57). Every query names the company. */
public interface DocumentDeliveryRepository extends JpaRepository<DocumentDelivery, UUID> {

    Optional<DocumentDelivery> findByIdAndCompanyId(UUID id, UUID companyId);

    List<DocumentDelivery> findByCompanyIdAndTripIdOrderByDocumentTypeAscDocumentNumberAsc(UUID companyId,
            UUID tripId);

    /** Under a row lock: a report and the office's return confirmation must not interleave. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT d FROM DocumentDelivery d WHERE d.id = :id AND d.companyId = :companyId")
    Optional<DocumentDelivery> findForUpdate(@Param("id") UUID id, @Param("companyId") UUID companyId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT d FROM DocumentDelivery d WHERE d.companyId = :companyId AND d.tripId = :tripId "
            + "AND d.documentId = :documentId")
    Optional<DocumentDelivery> findForUpdate(@Param("companyId") UUID companyId, @Param("tripId") UUID tripId,
            @Param("documentId") UUID documentId);
}
