package com.ebim.tms.integration.application;

import com.ebim.tms.integration.domain.WebhookDelivery;
import com.ebim.tms.integration.domain.WebhookDeliveryStatus;
import com.ebim.tms.integration.domain.WebhookEventType;
import com.ebim.tms.integration.domain.WebhookSubscription;
import com.ebim.tms.integration.infrastructure.WebhookDeliveryAttemptRepository;
import com.ebim.tms.integration.infrastructure.WebhookDeliveryRepository;
import com.ebim.tms.integration.infrastructure.WebhookSubscriptionRepository;
import com.ebim.tms.shared.api.ConflictException;
import com.ebim.tms.shared.api.InvalidRequestException;
import com.ebim.tms.shared.api.PageQuery;
import com.ebim.tms.shared.api.PageResponse;
import com.ebim.tms.shared.api.ResourceNotFoundException;
import com.ebim.tms.shared.audit.AuditAction;
import com.ebim.tms.shared.audit.AuditActorProvider;
import com.ebim.tms.shared.audit.AuditAggregateType;
import com.ebim.tms.shared.audit.AuditRecorder;
import com.ebim.tms.shared.security.CompanyScope;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Administration of webhook subscriptions: the screen behind "tell our WMS when a shipment is
 * confirmed" (migration V35).
 *
 * <p>Takes a {@link CompanyScope} and never a company id, exactly like every other use case, which
 * is what binds a new endpoint to the administrator's own tenant and makes creating one for another
 * company impossible rather than merely unsupported.
 *
 * <p>The actor is {@code requireAppUserId}, not {@code writerAppUserId}, for the reason
 * {@code IntegrationClientService} states: deciding where this company's operational data is sent is
 * a decision a <em>person</em> takes. An integration credential cannot reach this API at all - it is
 * on a different security chain and its scope carries no permissions - so a partner cannot quietly
 * add a second address for the events it already receives.
 */
@Service
public class WebhookSubscriptionService {

    private static final Set<String> SORTABLE_PROPERTIES =
            Set.of("name", "active", "lastSuccessAt", "lastFailureAt", "createdAt", "updatedAt");

    private static final Set<String> DELIVERY_SORTABLE_PROPERTIES =
            Set.of("createdAt", "occurredAt", "status", "attemptCount", "nextAttemptAt");

    private final WebhookSubscriptionRepository subscriptionRepository;
    private final WebhookDeliveryRepository deliveryRepository;
    private final WebhookDeliveryAttemptRepository attemptRepository;
    private final WebhookSecretVault secretVault;
    private final WebhookTargetPolicy targetPolicy;
    private final AuditActorProvider auditActorProvider;
    private final AuditRecorder auditRecorder;
    private final Clock clock;

    public WebhookSubscriptionService(WebhookSubscriptionRepository subscriptionRepository,
            WebhookDeliveryRepository deliveryRepository, WebhookDeliveryAttemptRepository attemptRepository,
            WebhookSecretVault secretVault, WebhookTargetPolicy targetPolicy,
            AuditActorProvider auditActorProvider, AuditRecorder auditRecorder, Clock clock) {
        this.subscriptionRepository = subscriptionRepository;
        this.deliveryRepository = deliveryRepository;
        this.attemptRepository = attemptRepository;
        this.secretVault = secretVault;
        this.targetPolicy = targetPolicy;
        this.auditActorProvider = auditActorProvider;
        this.auditRecorder = auditRecorder;
        this.clock = clock;
    }

    /**
     * The vocabulary a subscription may select from, for the picker on the screen.
     *
     * <p>Served from the enum rather than hard-coded in the frontend so that a type added by a
     * migration appears in the form on the next deploy, without a matching frontend release - the
     * same reason the role catalogue is an endpoint.
     */
    public List<String> availableEventTypes() {
        return List.copyOf(WebhookEventType.names());
    }

    @Transactional(readOnly = true)
    public PageResponse<WebhookSubscriptionView> list(CompanyScope scope, PageQuery pageQuery) {
        Page<WebhookSubscription> page = subscriptionRepository.findByCompanyId(scope.companyId(),
                toPageable(pageQuery, SORTABLE_PROPERTIES, "name", Sort.Direction.ASC));
        List<WebhookSubscriptionView> content = page.getContent().stream().map(WebhookSubscriptionView::from).toList();
        return new PageResponse<>(content, pageQuery.pageNumber(), pageQuery.pageSize(), page.getTotalElements());
    }

    @Transactional(readOnly = true)
    public WebhookSubscriptionView get(CompanyScope scope, UUID id) {
        return WebhookSubscriptionView.from(find(scope, id));
    }

    /**
     * Creates an endpoint and issues its signing secret. The secret exists in memory for the length
     * of this method and in the response it produces; only its ciphertext is persisted.
     */
    @Transactional
    public WebhookSubscriptionSecretView create(CompanyScope scope, WebhookSubscriptionRequest request) {
        secretVault.requireConfigured();
        String name = request.name().trim();
        Set<WebhookEventType> eventTypes = parseEventTypes(request.eventTypes());
        String targetUrl = targetPolicy.requireAllowed(request.targetUrl());
        if (subscriptionRepository.existsByCompanyIdAndName(scope.companyId(), name)) {
            throw new ConflictException("A webhook subscription named '" + name + "' already exists in this company.");
        }

        UUID actorId = auditActorProvider.requireAppUserId();
        WebhookSecretVault.NewSecret secret = secretVault.issue();
        WebhookSubscription subscription = new WebhookSubscription(scope.companyId(), name,
                blankToNull(request.description()), targetUrl, secret.ciphertext(), secret.hint(), actorId);
        subscription.replaceEventTypes(eventTypes, actorId);

        WebhookSubscription saved = subscriptionRepository.saveAndFlush(subscription);
        auditRecorder.record(scope, AuditAggregateType.WEBHOOK_SUBSCRIPTION, saved.id(), AuditAction.CREATE,
                Map.of("name", saved.name(), "targetUrl", saved.targetUrl(),
                        "eventTypes", String.join(",", viewEventTypes(saved))));
        return WebhookSubscriptionSecretView.of(WebhookSubscriptionView.from(saved), secret.secret());
    }

    /**
     * Renames an endpoint, re-points it and re-selects its events. Not a way to change its secret -
     * {@link #rotateSecret} is - and not a way to switch it on or off, which is {@link #setActive}.
     *
     * <p>The target URL is audited on every change, including when it is unchanged. Where this
     * company's shipment data is sent is exactly the field somebody will later need a history of.
     */
    @Transactional
    public WebhookSubscriptionView update(CompanyScope scope, UUID id, WebhookSubscriptionRequest request) {
        WebhookSubscription subscription = find(scope, id);
        String name = request.name().trim();
        Set<WebhookEventType> eventTypes = parseEventTypes(request.eventTypes());
        String targetUrl = targetPolicy.requireAllowed(request.targetUrl());
        if (subscriptionRepository.existsByCompanyIdAndNameAndIdNot(scope.companyId(), name, id)) {
            throw new ConflictException("A webhook subscription named '" + name + "' already exists in this company.");
        }

        UUID actorId = auditActorProvider.requireAppUserId();
        String previousUrl = subscription.targetUrl();
        subscription.edit(name, blankToNull(request.description()), targetUrl, actorId);
        subscription.replaceEventTypes(eventTypes, actorId);

        WebhookSubscription saved = subscriptionRepository.saveAndFlush(subscription);
        auditRecorder.record(scope, AuditAggregateType.WEBHOOK_SUBSCRIPTION, saved.id(), AuditAction.UPDATE,
                Map.of("name", saved.name(), "targetUrl", saved.targetUrl(), "previousTargetUrl", previousUrl,
                        "eventTypes", String.join(",", viewEventTypes(saved))));
        return WebhookSubscriptionView.from(saved);
    }

    /**
     * Issues a new signing secret, effective immediately.
     *
     * <p>No grace window, unlike an inbound credential's rotation, and {@code WebhookSubscription.rotateSecret}
     * explains why: TMS produces these signatures rather than verifying them, so sending two would
     * mean the receiver had to accept either - which is the property a rotation exists to remove.
     * The receiver accepts both secrets on their own side for as long as their deployment needs.
     */
    @Transactional
    public WebhookSubscriptionSecretView rotateSecret(CompanyScope scope, UUID id) {
        secretVault.requireConfigured();
        WebhookSubscription subscription = find(scope, id);
        UUID actorId = auditActorProvider.requireAppUserId();
        WebhookSecretVault.NewSecret secret = secretVault.issue();
        subscription.rotateSecret(secret.ciphertext(), secret.hint(), OffsetDateTime.now(clock), actorId);

        WebhookSubscription saved = subscriptionRepository.saveAndFlush(subscription);
        auditRecorder.record(scope, AuditAggregateType.WEBHOOK_SUBSCRIPTION, saved.id(),
                AuditAction.CREDENTIAL_ROTATE, Map.of("name", saved.name()));
        return WebhookSubscriptionSecretView.of(WebhookSubscriptionView.from(saved), secret.secret());
    }

    /**
     * Switches an endpoint on or off.
     *
     * <p>Reactivating also clears the failure streak and the suspension reason, so an endpoint TMS
     * suspended is genuinely given another chance rather than suspended again by its next failure.
     * Queued deliveries are not discarded by either direction: switching off stops them being sent
     * and switching on releases the backlog, which is what an operator expects from a pause.
     */
    @Transactional
    public WebhookSubscriptionView setActive(CompanyScope scope, UUID id, boolean active) {
        WebhookSubscription subscription = find(scope, id);
        if (subscription.active() == active) {
            // A suspended subscription is inactive, so "reactivate" is the transition that clears
            // the streak and the reason. Asking to deactivate one that TMS already switched off
            // changes nothing and is refused rather than audited as if it had.
            throw new ConflictException(
                    active ? "This subscription is already active." : "This subscription is already inactive.");
        }
        UUID actorId = auditActorProvider.requireAppUserId();
        subscription.setActive(active, actorId);
        WebhookSubscription saved = subscriptionRepository.saveAndFlush(subscription);
        auditRecorder.record(scope, AuditAggregateType.WEBHOOK_SUBSCRIPTION, saved.id(),
                active ? AuditAction.ACTIVATE : AuditAction.DEACTIVATE, Map.of("name", saved.name()));
        return WebhookSubscriptionView.from(saved);
    }

    /** Every delivery this company owes or has owed, newest first - the outbound half of the hub. */
    @Transactional(readOnly = true)
    public PageResponse<WebhookDeliveryView> deliveries(CompanyScope scope, UUID subscriptionId,
            WebhookDeliveryStatus status, PageQuery pageQuery) {
        Pageable pageable = toPageable(pageQuery, DELIVERY_SORTABLE_PROPERTIES, "createdAt", Sort.Direction.DESC);
        // find() first when a subscription is named, so asking for another company's endpoint
        // answers 404 rather than an empty page that might read as "it was never sent anything".
        UUID resolved = subscriptionId == null ? null : find(scope, subscriptionId).id();
        Page<WebhookDelivery> page;
        if (resolved == null && status == null) {
            page = deliveryRepository.findByCompanyId(scope.companyId(), pageable);
        } else if (resolved == null) {
            page = deliveryRepository.findByCompanyIdAndStatus(scope.companyId(), status, pageable);
        } else if (status == null) {
            page = deliveryRepository.findByCompanyIdAndSubscriptionId(scope.companyId(), resolved, pageable);
        } else {
            page = deliveryRepository.findByCompanyIdAndSubscriptionIdAndStatus(
                    scope.companyId(), resolved, status, pageable);
        }
        List<WebhookDeliveryView> content = page.getContent().stream().map(WebhookDeliveryView::from).toList();
        return new PageResponse<>(content, pageQuery.pageNumber(), pageQuery.pageSize(), page.getTotalElements());
    }

    /** One delivery with the exact bytes that were sent and every attempt that was made. */
    @Transactional(readOnly = true)
    public WebhookDeliveryDetailView delivery(CompanyScope scope, UUID id) {
        WebhookDelivery delivery = findDelivery(scope, id);
        return WebhookDeliveryDetailView.of(delivery,
                attemptRepository.findByWebhookDeliveryIdOrderByAttemptNumberAsc(delivery.id()));
    }

    /**
     * Puts a finished delivery back in the queue - what an operator presses once the receiving side
     * has been fixed.
     *
     * <p>Only a finished one: re-queueing a delivery that is already pending would move its due time
     * forward, which is at best a no-op and at worst a way to hammer an endpoint that is already
     * being retried on a schedule designed not to.
     */
    @Transactional
    public WebhookDeliveryView retry(CompanyScope scope, UUID id) {
        WebhookDelivery delivery = findDelivery(scope, id);
        if (delivery.isPending()) {
            throw new ConflictException("This delivery is still queued and will be retried automatically.");
        }
        if (!delivery.subscription().active()) {
            throw new ConflictException(
                    "The subscription is inactive. Reactivate it first, or the delivery will simply wait.");
        }
        delivery.requeue(OffsetDateTime.now(clock));
        return WebhookDeliveryView.from(deliveryRepository.saveAndFlush(delivery));
    }

    private WebhookSubscription find(CompanyScope scope, UUID id) {
        return subscriptionRepository.findByIdAndCompanyId(id, scope.companyId())
                .orElseThrow(() -> new ResourceNotFoundException("Webhook subscription not found."));
    }

    private WebhookDelivery findDelivery(CompanyScope scope, UUID id) {
        return deliveryRepository.findByIdAndCompanyId(id, scope.companyId())
                .orElseThrow(() -> new ResourceNotFoundException("Webhook delivery not found."));
    }

    private static List<String> viewEventTypes(WebhookSubscription subscription) {
        return subscription.eventTypeValues().stream().map(WebhookEventType::name).toList();
    }

    private static Set<WebhookEventType> parseEventTypes(Set<String> names) {
        return names.stream()
                .map(name -> WebhookEventType.byName(name)
                        .orElseThrow(() -> new InvalidRequestException("eventTypes must be chosen from "
                                + String.join(", ", WebhookEventType.names())
                                + " (received: " + name + ").")))
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    private static Pageable toPageable(PageQuery pageQuery, Set<String> sortable, String defaultProperty,
            Sort.Direction defaultDirection) {
        List<PageQuery.SortTerm> terms = pageQuery.sortTerms(sortable);
        Sort sort = terms.isEmpty()
                ? Sort.by(defaultDirection, defaultProperty)
                : Sort.by(terms.stream()
                        .map(term -> new Sort.Order(
                                term.descending() ? Sort.Direction.DESC : Sort.Direction.ASC, term.property()))
                        .toList());
        return PageRequest.of(pageQuery.pageNumber(), pageQuery.pageSize(), sort);
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
