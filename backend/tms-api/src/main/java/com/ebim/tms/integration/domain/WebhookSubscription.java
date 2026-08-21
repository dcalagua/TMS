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
 * Where one company wants its published events pushed (migration V35).
 *
 * <h2>The tenancy invariant</h2>
 *
 * <p>{@code companyId} is set once by the constructor and no method changes it, exactly as on
 * {@link IntegrationClient} and for the same reason: it is what makes "this endpoint receives this
 * company's events and no other company's" a property of the row rather than of the query that
 * happened to load it. A second company wanting the same URL creates its own subscription.
 *
 * <h2>Secrets</h2>
 *
 * <p>The entity holds the signing secret as ciphertext and never as plaintext. It cannot decrypt it
 * - {@link WebhookSecretCipher} is held by the services, not by the entity - so nothing here can
 * accidentally render, log or compare a usable secret. {@link #secretHint} is the four characters a
 * screen may show.
 *
 * <h2>Suspension</h2>
 *
 * <p>{@link #active} is one flag with two authors. A person sets it through
 * {@link #setActive}; the dispatcher clears it through {@link #suspend} after too many consecutive
 * failures, and only that path leaves a {@link #suspendedReason}. One flag rather than two states
 * because everything downstream asks the same question - "should this endpoint be sent anything
 * right now" - and a second column would only create a pair that can disagree.
 */
@Entity
@Table(name = "webhook_subscription")
public class WebhookSubscription {

    @Id
    @GeneratedValue
    @UuidGenerator
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

    @Column(name = "company_id", updatable = false, nullable = false)
    private UUID companyId;

    @Column(name = "name", nullable = false)
    private String name;

    @Column(name = "description")
    private String description;

    @Column(name = "target_url", nullable = false)
    private String targetUrl;

    @Column(name = "secret_ciphertext", nullable = false)
    private String secretCiphertext;

    @Column(name = "secret_algorithm", nullable = false)
    private String secretAlgorithm;

    @Column(name = "secret_hint", nullable = false)
    private String secretHint;

    @Column(name = "secret_rotated_at")
    private OffsetDateTime secretRotatedAt;

    @Column(name = "active", nullable = false)
    private boolean active = true;

    @Column(name = "suspended_reason")
    private String suspendedReason;

    @Column(name = "consecutive_failures", nullable = false)
    private int consecutiveFailures;

    @Column(name = "last_success_at")
    private OffsetDateTime lastSuccessAt;

    @Column(name = "last_failure_at")
    private OffsetDateTime lastFailureAt;

    @OneToMany(mappedBy = "subscription", cascade = CascadeType.ALL, orphanRemoval = true)
    private Set<WebhookSubscriptionEvent> eventTypes = new LinkedHashSet<>();

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

    protected WebhookSubscription() {
        // JPA
    }

    public WebhookSubscription(UUID companyId, String name, String description, String targetUrl,
            String secretCiphertext, String secretHint, UUID actorId) {
        this.companyId = companyId;
        this.name = name;
        this.description = description;
        this.targetUrl = targetUrl;
        this.secretCiphertext = secretCiphertext;
        this.secretAlgorithm = WebhookSecretCipher.ALGORITHM;
        this.secretHint = secretHint;
        this.createdBy = actorId;
        this.updatedBy = actorId;
    }

    public UUID id() {
        return id;
    }

    public UUID companyId() {
        return companyId;
    }

    public String name() {
        return name;
    }

    public String description() {
        return description;
    }

    public String targetUrl() {
        return targetUrl;
    }

    /** The ciphertext, for the one caller that holds the key. Never rendered by any view. */
    public String secretCiphertext() {
        return secretCiphertext;
    }

    public String secretAlgorithm() {
        return secretAlgorithm;
    }

    public String secretHint() {
        return secretHint;
    }

    public OffsetDateTime secretRotatedAt() {
        return secretRotatedAt;
    }

    public boolean active() {
        return active;
    }

    public String suspendedReason() {
        return suspendedReason;
    }

    public int consecutiveFailures() {
        return consecutiveFailures;
    }

    public OffsetDateTime lastSuccessAt() {
        return lastSuccessAt;
    }

    public OffsetDateTime lastFailureAt() {
        return lastFailureAt;
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

    /** The selected types as values, in the enum's declaration order for a stable API response. */
    public Set<WebhookEventType> eventTypeValues() {
        Set<WebhookEventType> selected = EnumSet.noneOf(WebhookEventType.class);
        eventTypes.forEach(row -> row.value().ifPresent(selected::add));
        return selected;
    }

    public boolean wants(WebhookEventType eventType) {
        return eventTypes.stream().anyMatch(row -> row.value().filter(eventType::equals).isPresent());
    }

    /**
     * Diffs the requested types against the persisted ones, so a type the subscription already had
     * keeps its own row and its {@code created_at} - the same shape as
     * {@link IntegrationClient#replaceScopes}, and for the same reason: "since when has this
     * endpoint been receiving delivery results" is a question somebody asks.
     */
    public void replaceEventTypes(Collection<WebhookEventType> requested, UUID actorId) {
        Set<WebhookEventType> target = EnumSet.noneOf(WebhookEventType.class);
        target.addAll(requested);
        eventTypes.removeIf(row -> row.value().map(value -> !target.contains(value)).orElse(true));
        Set<WebhookEventType> held = eventTypeValues();
        for (WebhookEventType eventType : target) {
            if (!held.contains(eventType)) {
                eventTypes.add(new WebhookSubscriptionEvent(this, eventType));
            }
        }
        this.updatedBy = actorId;
    }

    /**
     * The rows rather than their values. Package-private and used by {@code WebhookSubscriptionTest}
     * to pin {@link #replaceEventTypes} as a diff rather than a delete-and-recreate.
     */
    Set<WebhookSubscriptionEvent> eventTypeRows() {
        return Set.copyOf(eventTypes);
    }

    public void edit(String name, String description, String targetUrl, UUID actorId) {
        this.name = name;
        this.description = description;
        this.targetUrl = targetUrl;
        this.updatedBy = actorId;
    }

    /**
     * Replaces the signing secret. Immediate and without a grace window, unlike
     * {@link IntegrationClient#rotateSecret}, and the asymmetry is not an oversight: an inbound
     * credential is verified by TMS, which can afford to accept two values at once, while a webhook
     * signature is produced by TMS and verified by somebody else. Sending two signatures would mean
     * the receiver had to accept either, which is precisely the property a rotation is meant to
     * remove. The receiver's own migration path is to accept both secrets for a window on their
     * side, which is theirs to schedule.
     */
    public void rotateSecret(String ciphertext, String hint, OffsetDateTime now, UUID actorId) {
        this.secretCiphertext = ciphertext;
        this.secretAlgorithm = WebhookSecretCipher.ALGORITHM;
        this.secretHint = hint;
        this.secretRotatedAt = now;
        this.updatedBy = actorId;
    }

    /** A person turning the endpoint on or off. Always clears any dispatcher-written reason. */
    public void setActive(boolean active, UUID actorId) {
        this.active = active;
        this.suspendedReason = null;
        if (active) {
            // Reactivating with the counter still at its ceiling would suspend the subscription
            // again on its very next failure, which reads as "reactivation did not work".
            this.consecutiveFailures = 0;
        }
        this.updatedBy = actorId;
    }

    /** The dispatcher giving up on an endpoint that has failed too many times in a row. */
    public void suspend(String reason) {
        this.active = false;
        this.suspendedReason = reason;
    }

    /** Records a delivered attempt: the failure streak is over by definition. */
    public void recordSuccess(OffsetDateTime now) {
        this.consecutiveFailures = 0;
        this.lastSuccessAt = now;
    }

    /** @return the new consecutive-failure count, which is what decides a suspension */
    public int recordFailure(OffsetDateTime now) {
        this.consecutiveFailures++;
        this.lastFailureAt = now;
        return this.consecutiveFailures;
    }
}
