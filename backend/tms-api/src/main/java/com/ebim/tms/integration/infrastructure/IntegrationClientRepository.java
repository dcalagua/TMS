package com.ebim.tms.integration.infrastructure;

import com.ebim.tms.integration.domain.IntegrationClient;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Persistence for {@link IntegrationClient}.
 *
 * <p>Two kinds of finder live here and the difference is the whole security story of this table:
 *
 * <ul>
 *   <li>{@link #findByClientId} is the <em>authentication</em> lookup. It carries no company
 *       predicate, and cannot: it runs before any tenant is known, in order to decide which one
 *       the request is in. It is the exact analogue of {@code JdbcIdentityRepository}'s
 *       auth-user lookup, and like that one it runs on the owner connection because
 *       {@code TenantScopedDataSource} has no scope to apply yet.</li>
 *   <li>every other finder is an <em>administration</em> lookup, made by a signed-in user
 *       through the company-scoped admin API, and every one carries the company predicate in the
 *       query - the rule {@code LocationRepository} states for the whole codebase.</li>
 * </ul>
 */
public interface IntegrationClientRepository extends JpaRepository<IntegrationClient, UUID> {

    /**
     * The authentication lookup. Returns the credential whatever its state; whether it is active,
     * revoked or holds the presented secret is decided in
     * {@code IntegrationAuthenticationService}, so that every rejection answers identically and a
     * caller cannot tell "no such client" from "revoked client" by the response.
     */
    Optional<IntegrationClient> findByClientId(String clientId);

    Page<IntegrationClient> findByCompanyId(UUID companyId, Pageable pageable);

    Optional<IntegrationClient> findByIdAndCompanyId(UUID id, UUID companyId);

    boolean existsByCompanyIdAndName(UUID companyId, String name);

    boolean existsByCompanyIdAndNameAndIdNot(UUID companyId, String name, UUID id);
}
