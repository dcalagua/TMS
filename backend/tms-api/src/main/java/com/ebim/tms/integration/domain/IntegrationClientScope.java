package com.ebim.tms.integration.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UuidGenerator;

/**
 * One {@link IntegrationScope} held by one {@link IntegrationClient} (migration V18). A pure
 * child of its credential, following {@code LocationRoleAssignment}: created and removed only
 * through {@link IntegrationClient#replaceScopes}, never persisted on its own, and carrying no
 * {@code company_id} because the tenant is the parent's.
 *
 * <p>The scope is stored as its {@code code} rather than as the enum name, because the code is
 * what {@code ck_integration_client_scope_value} checks and what a {@code @PreAuthorize}
 * expression names - one string, three places, no translation table.
 */
@Entity
@Table(name = "integration_client_scope")
public class IntegrationClientScope {

    @Id
    @GeneratedValue
    @UuidGenerator
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "integration_client_id", nullable = false, updatable = false)
    private IntegrationClient client;

    @Column(name = "scope", nullable = false, updatable = false)
    private String scope;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false, nullable = false)
    private OffsetDateTime createdAt;

    protected IntegrationClientScope() {
        // JPA
    }

    IntegrationClientScope(IntegrationClient client, IntegrationScope scope) {
        this.client = client;
        this.scope = scope.code();
    }

    public UUID id() {
        return id;
    }

    public IntegrationClient client() {
        return client;
    }

    /**
     * The scope as a value, or empty when the row holds a code this build no longer knows. That
     * cannot happen while the CHECK constraint and the enum agree; handling it rather than
     * throwing means that removing a scope from the enum would degrade to "that credential loses
     * the capability" instead of "every request from that credential fails to load".
     */
    public Optional<IntegrationScope> value() {
        return IntegrationScope.byCode(scope);
    }

    public String code() {
        return scope;
    }

    public OffsetDateTime createdAt() {
        return createdAt;
    }
}
