package com.ebim.tms.shared.imports.infrastructure;

import com.ebim.tms.shared.imports.ImportBatch;
import com.ebim.tms.shared.imports.ImportEntityType;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Company-scoped persistence for {@link ImportBatch}. See any entity repository (e.g.
 * {@code CarrierRepository}) for the isolation rule every finder here follows.
 *
 * <p>Lives in its own {@code infrastructure} sub-package, separate from the rest of
 * {@code shared.imports}, because {@code LayeringTest.repositories_live_in_infrastructure_packages}
 * requires every {@code *Repository} to reside under an {@code infrastructure} package - the same
 * rule every business module's own repositories follow.
 */
public interface ImportBatchRepository extends JpaRepository<ImportBatch, UUID> {

    /** The import history of one company and entity type, most recent first. */
    List<ImportBatch> findByCompanyIdAndEntityTypeOrderByCreatedAtDesc(
            UUID companyId, ImportEntityType entityType, Pageable pageable);
}
