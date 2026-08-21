package com.ebim.tms.integration.domain;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;
import org.hibernate.annotations.UuidGenerator;

/**
 * A partner machine credential (migration V18): the thing an ERP, a WMS or a store system
 * authenticates as when it posts locations or orders, with no browser session and no user
 * password anywhere in the picture.
 *
 * <h2>The tenancy invariant</h2>
 *
 * <p>{@code companyId} is set once, by {@link #IntegrationClient constructor}, and there is no
 * method that changes it - which is the whole point. The company an integration request operates
 * in is a property of the credential, so a partner cannot address another tenant by changing a
 * header, and re-pointing a credential at a different company is not an operation that exists.
 * A partner that needs to write into a second company gets a second credential.
 *
 * <h2>Secrets</h2>
 *
 * <p>The entity never sees a plaintext secret except to verify one: {@link #acceptsSecret} takes
 * the presented value and answers yes or no. Nothing here stores it, returns it or renders it,
 * and {@link #toString} is not overridden to print state for the same reason.
 */
@Entity
@Table(name = "integration_client")
public class IntegrationClient {

    @Id
    @GeneratedValue
    @UuidGenerator
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

    @Column(name = "company_id", updatable = false, nullable = false)
    private UUID companyId;

    @Column(name = "client_id", updatable = false, nullable = false)
    private String clientId;

    @Column(name = "name", nullable = false)
    private String name;

    @Column(name = "description")
    private String description;

    /**
     * The carrier this credential answers for, or null for an ordinary partner credential
     * (migration V31).
     *
     * <p>Mutable, unlike {@link #companyId}, and the asymmetry is the point. The company is what
     * makes a credential a tenant's and must never move; the carrier is an attribute of what the
     * credential is <em>for</em>, and an administrator who bound a key to the wrong haulier has to
     * be able to fix it without re-issuing a secret their partner has already deployed. Every
     * change goes through {@link #assignCarrier}, which is audited by
     * {@code IntegrationClientService}.
     */
    @Column(name = "carrier_id")
    private UUID carrierId;

    @Column(name = "secret_hash", nullable = false)
    private String secretHash;

    @Column(name = "secret_algorithm", nullable = false)
    private String secretAlgorithm;

    @Column(name = "previous_secret_hash")
    private String previousSecretHash;

    @Column(name = "previous_secret_expires_at")
    private OffsetDateTime previousSecretExpiresAt;

    @Column(name = "active", nullable = false)
    private boolean active = true;

    @Column(name = "last_used_at")
    private OffsetDateTime lastUsedAt;

    @Column(name = "secret_rotated_at")
    private OffsetDateTime secretRotatedAt;

    @Column(name = "revoked_at")
    private OffsetDateTime revokedAt;

    @Column(name = "revoked_by")
    private UUID revokedBy;

    @OneToMany(mappedBy = "client", cascade = CascadeType.ALL, orphanRemoval = true)
    private Set<IntegrationClientScope> scopes = new LinkedHashSet<>();

    @CreationTimestamp
    @Column(name = "created_at", updatable = false, nullable = false)
    private OffsetDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    @Column(name = "created_by", updatable = false)
    private UUID createdBy;

    @Column(name = "updated_by")
    private UUID updatedBy;

    protected IntegrationClient() {
        // JPA
    }

    public IntegrationClient(UUID companyId, String clientId, String name, String description, String secretHash,
            UUID actorId) {
        this.companyId = companyId;
        this.clientId = clientId;
        this.name = name;
        this.description = description;
        this.secretHash = secretHash;
        this.secretAlgorithm = IntegrationSecrets.ALGORITHM;
        this.createdBy = actorId;
        this.updatedBy = actorId;
    }

    public UUID id() {
        return id;
    }

    public UUID companyId() {
        return companyId;
    }

    public String clientId() {
        return clientId;
    }

    public String name() {
        return name;
    }

    public String description() {
        return description;
    }

    public UUID carrierId() {
        return carrierId;
    }

    /**
     * Binds this credential to one carrier, or unbinds it when {@code carrierId} is null.
     *
     * <p>Whether that carrier exists, is active and belongs to this company is
     * {@code IntegrationClientService}'s check, made before this is called - the entity cannot see
     * the fleet master, which {@code integration} reaches only through {@code CarrierLookupPort}.
     * {@code fk_integration_client_carrier_company} is the database's own copy of the tenant half.
     */
    public void assignCarrier(UUID carrierId, UUID actorId) {
        this.carrierId = carrierId;
        this.updatedBy = actorId;
    }

    public String secretAlgorithm() {
        return secretAlgorithm;
    }

    public boolean active() {
        return active;
    }

    public OffsetDateTime lastUsedAt() {
        return lastUsedAt;
    }

    public OffsetDateTime secretRotatedAt() {
        return secretRotatedAt;
    }

    public OffsetDateTime revokedAt() {
        return revokedAt;
    }

    public UUID revokedBy() {
        return revokedBy;
    }

    public OffsetDateTime previousSecretExpiresAt() {
        return previousSecretExpiresAt;
    }

    public OffsetDateTime createdAt() {
        return createdAt;
    }

    public OffsetDateTime updatedAt() {
        return updatedAt;
    }

    public UUID createdBy() {
        return createdBy;
    }

    public UUID updatedBy() {
        return updatedBy;
    }

    /** The scopes as values, in the enum's declaration order for a stable API response. */
    public Set<IntegrationScope> scopeValues() {
        Set<IntegrationScope> held = EnumSet.noneOf(IntegrationScope.class);
        scopes.forEach(assignment -> assignment.value().ifPresent(held::add));
        return held;
    }

    /**
     * The assignment rows rather than their values. Package-private and used only by
     * {@code IntegrationClientTest}, which pins {@link #replaceScopes} as a diff: an unchanged
     * scope has to keep its own row so its {@code created_at} keeps saying when the credential
     * first gained the capability. Same contract as {@code Location.roleAssignments}.
     */
    Set<IntegrationClientScope> scopeRows() {
        return Set.copyOf(scopes);
    }

    public boolean hasScope(IntegrationScope scope) {
        return scopes.stream().anyMatch(assignment -> assignment.value().filter(scope::equals).isPresent());
    }

    /**
     * Diffs the requested scopes against the persisted ones, so a scope the credential already
     * held keeps its own row and its {@code created_at} - the same shape as
     * {@code Location.replaceRoles}.
     */
    public void replaceScopes(Collection<IntegrationScope> requested, UUID actorId) {
        Set<IntegrationScope> target = EnumSet.noneOf(IntegrationScope.class);
        target.addAll(requested);
        scopes.removeIf(assignment -> assignment.value().map(value -> !target.contains(value)).orElse(true));
        Set<IntegrationScope> held = scopeValues();
        for (IntegrationScope scope : target) {
            if (!held.contains(scope)) {
                scopes.add(new IntegrationClientScope(this, scope));
            }
        }
        this.updatedBy = actorId;
    }

    public void rename(String name, String description, UUID actorId) {
        this.name = name;
        this.description = description;
        this.updatedBy = actorId;
    }

    /**
     * Whether this credential opens with the presented secret, right now.
     *
     * <p>Accepts the superseded secret while the rotation window is open, which is what makes a
     * rotation deployable: the partner receives the new value, redeploys at their own pace, and
     * the old one stops working when the window closes rather than the instant the button was
     * pressed. Both comparisons are constant-time; which of the two matched is not observable
     * from the outside because the answer is a single boolean either way.
     */
    public boolean acceptsSecret(String presentedSecret, OffsetDateTime now) {
        if (IntegrationSecrets.matches(presentedSecret, secretHash)) {
            return true;
        }
        boolean windowOpen = previousSecretHash != null
                && previousSecretExpiresAt != null
                && previousSecretExpiresAt.isAfter(now);
        return windowOpen && IntegrationSecrets.matches(presentedSecret, previousSecretHash);
    }

    /** True when the presented secret matched the superseded one - worth telling the partner. */
    public boolean isRotationPending(OffsetDateTime now) {
        return previousSecretHash != null && previousSecretExpiresAt != null
                && previousSecretExpiresAt.isAfter(now);
    }

    /**
     * Issues a new secret and keeps the old one usable until {@code graceUntil}.
     *
     * @param graceUntil when the superseded secret stops being accepted; {@code null} revokes it
     *     immediately, which is the right choice when the old secret is believed to have leaked
     */
    public void rotateSecret(String newSecretHash, OffsetDateTime graceUntil, OffsetDateTime now, UUID actorId) {
        if (newSecretHash.equals(secretHash)) {
            // ck_integration_client_previous_hash_differs would refuse this anyway; failing here
            // names the actual problem instead of surfacing a constraint name.
            throw new IllegalArgumentException("the rotated secret must differ from the current one");
        }
        if (graceUntil != null && graceUntil.isAfter(now)) {
            this.previousSecretHash = this.secretHash;
            this.previousSecretExpiresAt = graceUntil;
        } else {
            this.previousSecretHash = null;
            this.previousSecretExpiresAt = null;
        }
        this.secretHash = newSecretHash;
        this.secretAlgorithm = IntegrationSecrets.ALGORITHM;
        this.secretRotatedAt = now;
        this.updatedBy = actorId;
    }

    /** Drops the superseded secret once its window has closed, so no stale hash lingers. */
    public boolean expireRotationWindow(OffsetDateTime now) {
        if (previousSecretHash == null || previousSecretExpiresAt == null || previousSecretExpiresAt.isAfter(now)) {
            return false;
        }
        this.previousSecretHash = null;
        this.previousSecretExpiresAt = null;
        return true;
    }

    /**
     * Terminal. A revoked credential is also inactive - {@code ck_integration_client_revoked_is_inactive}
     * makes the pair impossible to break - and both superseded and current secrets stop being
     * accepted because the authenticator refuses a revoked row before it ever compares a hash.
     */
    public void revoke(OffsetDateTime now, UUID actorId) {
        this.active = false;
        this.revokedAt = now;
        this.revokedBy = actorId;
        this.previousSecretHash = null;
        this.previousSecretExpiresAt = null;
        this.updatedBy = actorId;
    }

    public boolean isRevoked() {
        return revokedAt != null;
    }

    /** Best-effort "this credential is in use" marker; see the column comment in V18. */
    public void markUsed(OffsetDateTime now) {
        this.lastUsedAt = now;
    }
}
