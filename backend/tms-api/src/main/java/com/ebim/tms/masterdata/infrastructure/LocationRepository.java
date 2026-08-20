package com.ebim.tms.masterdata.infrastructure;

import com.ebim.tms.masterdata.domain.Location;
import com.ebim.tms.masterdata.domain.LocationRole;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Company-scoped persistence for {@link Location}. Every finder carries the company predicate in
 * the query, never as a filter applied to the result in Java: a row of another tenant must never
 * be loaded at all, not merely discarded after loading.
 *
 * <p>The role-aware finders exist because since V23 this is the only place table: a caller that
 * needs "a location this company may dispatch from" must not be able to receive one that only
 * receives. Expressing the role as a join predicate rather than as a filter over the loaded
 * entity keeps that guarantee in the query, where the company predicate already lives, and
 * keeps a page of results a page.
 */
public interface LocationRepository extends JpaRepository<Location, UUID>, JpaSpecificationExecutor<Location> {

    Optional<Location> findByIdAndCompanyId(UUID id, UUID companyId);

    boolean existsByCompanyIdAndCode(UUID companyId, String code);

    boolean existsByCompanyIdAndCodeAndIdNot(UUID companyId, String code, UUID id);

    /**
     * The external identity guard behind {@code uq_location_external}. Checked in the service so
     * a duplicate is a readable 409 rather than a constraint violation translated into a generic
     * conflict, with the unique index as the race backstop.
     */
    boolean existsByCompanyIdAndExternalSystemAndExternalReference(
            UUID companyId, String externalSystem, String externalReference);

    boolean existsByCompanyIdAndExternalSystemAndExternalReferenceAndIdNot(
            UUID companyId, String externalSystem, String externalReference, UUID id);

    /**
     * Resolves the row a sending system's own key names, for the inbound integration upsert. The
     * pair is unique per company ({@code uq_location_external}), so at most one row can match.
     */
    Optional<Location> findByCompanyIdAndExternalSystemAndExternalReference(
            UUID companyId, String externalSystem, String externalReference);

    /** The fallback identity for a payload that carries no external reference. */
    Optional<Location> findByCompanyIdAndCode(UUID companyId, String code);

    /**
     * Which of a bulk import file's codes this company already holds, resolved once for the whole
     * file rather than once per row - see {@code LocationImportService}.
     */
    List<Location> findByCompanyIdAndCodeIn(UUID companyId, Collection<String> codes);

    /**
     * Batched resolution of already-persisted references for display, active or not and
     * <em>role or not</em>. An order whose destination later lost the DESTINATION role must keep
     * rendering the place it was actually sent to; hiding it would be rewriting history to match
     * today's master data. Validation of a <em>new</em> assignment is the role-aware finder
     * below, never this one.
     */
    List<Location> findByIdInAndCompanyId(Collection<UUID> ids, UUID companyId);

    /**
     * The guard behind every new origin/destination assignment: the location exists, belongs to
     * this company, is active, and holds the role the caller needs it for.
     */
    @Query("""
            select l from Location l join l.roles r
            where l.id = :id and l.companyId = :companyId and l.active = true and r.role = :role
            """)
    Optional<Location> findUsableAs(@Param("id") UUID id, @Param("companyId") UUID companyId,
            @Param("role") LocationRole role);

    /**
     * The same guard for a caller that has codes rather than ids - the bulk order import, where a
     * spreadsheet names a place the only way a human can. {@code distinct} because a location
     * holding both roles would otherwise be returned once per joined row.
     */
    @Query("""
            select distinct l from Location l join l.roles r
            where l.companyId = :companyId and l.code in :codes and l.active = true and r.role = :role
            """)
    List<Location> findUsableAsByCodes(@Param("codes") Collection<String> codes,
            @Param("companyId") UUID companyId, @Param("role") LocationRole role);

    /**
     * The same guard for a whole set of ids at once - a route's stop list, validated in one
     * query rather than one per stop. Ids that fail any of the four conditions are simply
     * absent, which is how the caller names the offending one back to the operator.
     */
    @Query("""
            select distinct l from Location l join l.roles r
            where l.companyId = :companyId and l.id in :ids and l.active = true and r.role = :role
            """)
    List<Location> findUsableAsByIds(@Param("ids") Collection<UUID> ids,
            @Param("companyId") UUID companyId, @Param("role") LocationRole role);
}
