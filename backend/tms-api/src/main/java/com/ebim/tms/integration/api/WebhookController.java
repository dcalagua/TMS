package com.ebim.tms.integration.api;

import com.ebim.tms.integration.application.WebhookDeliveryDetailView;
import com.ebim.tms.integration.application.IntegrationHealthService;
import com.ebim.tms.integration.application.IntegrationHealthView;
import com.ebim.tms.integration.application.WebhookDeliveryView;
import com.ebim.tms.integration.application.WebhookSubscriptionRequest;
import com.ebim.tms.integration.application.WebhookSubscriptionSecretView;
import com.ebim.tms.integration.application.WebhookSubscriptionService;
import com.ebim.tms.integration.application.WebhookSubscriptionView;
import com.ebim.tms.integration.domain.WebhookDeliveryStatus;
import com.ebim.tms.shared.api.PageQuery;
import com.ebim.tms.shared.api.PageResponse;
import com.ebim.tms.shared.security.CompanyScope;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.enums.ParameterIn;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Administration of outbound webhooks, on the <em>user-facing</em> API (migration V35).
 *
 * <p>Note the path: {@code /api/v1/webhooks}, not {@code /integration/v1}. Configuring where this
 * company's events are pushed is an administrator's job done from the browser with a Supabase token,
 * and an integration credential cannot reach these endpoints at all - it is on a different security
 * chain and its {@code CompanyScope} carries no permissions, so
 * {@code hasAuthority('integration.webhook:manage')} can never be satisfied by one. A partner able
 * to add a second destination for the events it already receives would make this feature a data
 * exfiltration tool.
 *
 * <p>There is no delete. A subscription is deactivated, which keeps its delivery history intact and
 * answerable - the same "deactivate, never delete" rule every master in TMS follows, and here it is
 * also what preserves the record of what was sent where.
 */
@RestController
@RequestMapping("${tms.api.base-path}/webhooks")
@Tag(name = "Webhooks",
        description = "Subscribe an endpoint to this company's published events, and audit what was delivered")
public class WebhookController {

    private final WebhookSubscriptionService subscriptionService;
    private final IntegrationHealthService healthService;

    public WebhookController(WebhookSubscriptionService subscriptionService,
            IntegrationHealthService healthService) {
        this.subscriptionService = subscriptionService;
        this.healthService = healthService;
    }

    /**
     * The vocabulary of the form above.
     *
     * <p>The {@link CompanyScope} parameter is unused by the service and is not decoration - it is
     * what makes this endpoint company-scoped at all, the same reason
     * {@code UserAdministrationController.roles} keeps one. Without it the argument resolver never
     * runs, {@code X-Company-Id} stops being required in fact while still being documented as
     * required, and a caller who omits it is refused by {@code @PreAuthorize} with
     * {@code access-denied} - because an unscoped token carries no authorities - rather than being
     * told {@code company-scope-required}. Fail-closed either way; misleading either way, and the
     * three-part contract {@code CompanyContextController} sets out is not something to satisfy by
     * accident.
     */
    @GetMapping("/event-types")
    @PreAuthorize("hasAuthority('integration.webhook:read')")
    @Operation(summary = "The event types a subscription may select",
            description = "Served from the backend so a type added by a migration appears in the form without "
                    + "a frontend release. The same vocabulary as the polling change feed.")
    @Parameter(name = "X-Company-Id", in = ParameterIn.HEADER, required = true,
            description = "Id of a company the caller is a member of")
    public List<String> eventTypes(CompanyScope scope) {
        return subscriptionService.availableEventTypes();
    }

    @GetMapping
    @PreAuthorize("hasAuthority('integration.webhook:read')")
    @Operation(summary = "List the webhook subscriptions of the selected company")
    @Parameter(name = "X-Company-Id", in = ParameterIn.HEADER, required = true,
            description = "Id of a company the caller is a member of")
    public PageResponse<WebhookSubscriptionView> list(CompanyScope scope, @ModelAttribute PageQuery pageQuery) {
        return subscriptionService.list(scope, pageQuery);
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('integration.webhook:read')")
    @Operation(summary = "Get one webhook subscription")
    @Parameter(name = "X-Company-Id", in = ParameterIn.HEADER, required = true,
            description = "Id of a company the caller is a member of")
    public WebhookSubscriptionView get(CompanyScope scope, @PathVariable UUID id) {
        return subscriptionService.get(scope, id);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('integration.webhook:manage')")
    @Operation(summary = "Subscribe an endpoint to selected events",
            description = "The only response that ever contains the signing secret. It is not recoverable "
                    + "afterwards; a lost secret is replaced by rotating. Answers 503 "
                    + "(feature-not-configured) when the deployment has no webhook signing key.")
    @Parameter(name = "X-Company-Id", in = ParameterIn.HEADER, required = true,
            description = "Id of a company the caller is a member of")
    public WebhookSubscriptionSecretView create(CompanyScope scope,
            @Valid @RequestBody WebhookSubscriptionRequest request) {
        return subscriptionService.create(scope, request);
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasAuthority('integration.webhook:manage')")
    @Operation(summary = "Rename a subscription, re-point it, or change which events it receives",
            description = "Never changes the secret and never switches the endpoint on or off; those are "
                    + "their own operations, so a save from a stale screen cannot silently undo a suspension.")
    @Parameter(name = "X-Company-Id", in = ParameterIn.HEADER, required = true,
            description = "Id of a company the caller is a member of")
    public WebhookSubscriptionView update(CompanyScope scope, @PathVariable UUID id,
            @Valid @RequestBody WebhookSubscriptionRequest request) {
        return subscriptionService.update(scope, id, request);
    }

    @PostMapping("/{id}/rotate-secret")
    @PreAuthorize("hasAuthority('integration.webhook:manage')")
    @Operation(summary = "Issue a new signing secret",
            description = "Effective immediately and without a grace window: TMS produces these signatures "
                    + "rather than verifying them, so sending two would mean the receiver had to accept "
                    + "either. Accept both secrets on your side while you redeploy.")
    @Parameter(name = "X-Company-Id", in = ParameterIn.HEADER, required = true,
            description = "Id of a company the caller is a member of")
    public WebhookSubscriptionSecretView rotateSecret(CompanyScope scope, @PathVariable UUID id) {
        return subscriptionService.rotateSecret(scope, id);
    }

    @PostMapping("/{id}/activate")
    @PreAuthorize("hasAuthority('integration.webhook:manage')")
    @Operation(summary = "Switch a subscription back on",
            description = "Clears the failure streak and any automatic suspension, and releases whatever "
                    + "queued deliveries built up while it was off.")
    @Parameter(name = "X-Company-Id", in = ParameterIn.HEADER, required = true,
            description = "Id of a company the caller is a member of")
    public WebhookSubscriptionView activate(CompanyScope scope, @PathVariable UUID id) {
        return subscriptionService.setActive(scope, id, true);
    }

    @PostMapping("/{id}/deactivate")
    @PreAuthorize("hasAuthority('integration.webhook:manage')")
    @Operation(summary = "Pause a subscription",
            description = "Stops deliveries being sent. Nothing is discarded: events keep queueing and are "
                    + "released when the subscription is switched back on.")
    @Parameter(name = "X-Company-Id", in = ParameterIn.HEADER, required = true,
            description = "Id of a company the caller is a member of")
    public WebhookSubscriptionView deactivate(CompanyScope scope, @PathVariable UUID id) {
        return subscriptionService.setActive(scope, id, false);
    }

    /**
     * Whether this company's integrations are working right now (JOB 13).
     *
     * <p>{@code integration.webhook:read}, the same authority the delivery list carries: this is
     * that list summarised, and a caller who may not see deliveries must not learn how many are
     * failing from a count instead.
     *
     * <p>Deliberately on the webhook controller although it also reports the inbound side. The two
     * halves of an integration fail together in practice - a partner whose credentials expired
     * stops sending and stops receiving - and making an operator open two screens to notice that
     * is how it goes unnoticed.
     */
    @GetMapping("/health")
    @PreAuthorize("hasAuthority('integration.webhook:read')")
    @Operation(summary = "Whether integrations are working: the outbound queue, and inbound requests over 24h")
    @Parameter(name = "X-Company-Id", in = ParameterIn.HEADER, required = true,
            description = "Id of a company the caller is a member of")
    public IntegrationHealthView health(CompanyScope scope) {
        return healthService.health(scope);
    }

    @GetMapping("/deliveries")
    @PreAuthorize("hasAuthority('integration.webhook:read')")
    @Operation(summary = "The delivery log: what was pushed out of this company, and what came back",
            description = "Newest first. Filter by subscriptionId or by status. Carries no payload; read one "
                    + "delivery for that.")
    @Parameter(name = "X-Company-Id", in = ParameterIn.HEADER, required = true,
            description = "Id of a company the caller is a member of")
    public PageResponse<WebhookDeliveryView> deliveries(CompanyScope scope,
            @RequestParam(required = false) UUID subscriptionId,
            @RequestParam(required = false) WebhookDeliveryStatus status,
            @ModelAttribute PageQuery pageQuery) {
        return subscriptionService.deliveries(scope, subscriptionId, status, pageQuery);
    }

    @GetMapping("/deliveries/{id}")
    @PreAuthorize("hasAuthority('integration.webhook:read')")
    @Operation(summary = "One delivery, with the exact bytes sent and every attempt made",
            description = "The screen a \"you never sent us that\" conversation is settled from.")
    @Parameter(name = "X-Company-Id", in = ParameterIn.HEADER, required = true,
            description = "Id of a company the caller is a member of")
    public WebhookDeliveryDetailView delivery(CompanyScope scope, @PathVariable UUID id) {
        return subscriptionService.delivery(scope, id);
    }

    @PostMapping("/deliveries/{id}/retry")
    @PreAuthorize("hasAuthority('integration.webhook:manage')")
    @Operation(summary = "Queue a finished delivery to be attempted again",
            description = "For a delivery that was given up on and whose receiver has since been fixed. A "
                    + "delivery that is still queued is refused - it is already going to be retried.")
    @Parameter(name = "X-Company-Id", in = ParameterIn.HEADER, required = true,
            description = "Id of a company the caller is a member of")
    public WebhookDeliveryView retry(CompanyScope scope, @PathVariable UUID id) {
        return subscriptionService.retry(scope, id);
    }
}
