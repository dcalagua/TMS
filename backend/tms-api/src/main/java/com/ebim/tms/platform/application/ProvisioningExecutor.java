package com.ebim.tms.platform.application;

import com.ebim.tms.platform.domain.AdminProvisioningStatus;
import com.ebim.tms.platform.domain.DeploymentMode;
import com.ebim.tms.platform.domain.PlatformProvisioning;
import com.ebim.tms.platform.domain.PlatformTenant;
import com.ebim.tms.platform.infrastructure.PlatformProvisioningRepository;
import com.ebim.tms.platform.infrastructure.PlatformTenantRepository;
import com.ebim.tms.platform.infrastructure.ProvisioningLock;
import com.ebim.tms.shared.api.ProblemType;
import com.ebim.tms.shared.api.ProblemTypedException;
import com.ebim.tms.shared.reference.TenantIdentity;
import com.ebim.tms.shared.reference.TenantIdentityLookupPort;
import com.ebim.tms.shared.reference.TenantOnboarding;
import com.ebim.tms.shared.reference.TenantOnboardingCommand;
import com.ebim.tms.shared.reference.TenantOnboardingPort;
import com.ebim.tms.shared.reference.TenantOnboardingRefusedException;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.Locale;
import java.util.Optional;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

/**
 * The one transaction that either produces a tenant or produces nothing.
 *
 * <p>Everything a provisioning call writes - the organization, the company, the administrator, the
 * membership, the mapping and the record of the call itself - happens here, inside a single
 * transaction. A half-provisioned tenant is worse than no tenant: the control plane would believe a
 * customer exists, and the customer would find an organization nobody can administer. Rolling back
 * is therefore the only acceptable outcome of a partial failure, which is why the failure record is
 * written by a different bean afterwards, in its own transaction - a record written in <em>this</em>
 * one would roll back with the failure it exists to explain.
 *
 * <p>Order matters and is not accidental:
 *
 * <ol>
 *   <li>take the advisory lock, so concurrent calls for one tenant queue instead of racing;</li>
 *   <li>answer from the idempotency key if this exact call already succeeded;</li>
 *   <li>refuse if the tenant exists under a different key - that is a conflict, not a replay;</li>
 *   <li>only then write anything.</li>
 * </ol>
 */
@Service
public class ProvisioningExecutor {

    private final PlatformTenantRepository tenantRepository;
    private final PlatformProvisioningRepository provisioningRepository;
    private final ProvisioningLock lock;
    private final TenantOnboardingPort onboardingPort;
    private final TenantIdentityLookupPort identityLookup;
    private final ProvisioningProperties properties;
    private final ObjectMapper mapper;
    private final Clock clock;

    public ProvisioningExecutor(PlatformTenantRepository tenantRepository,
            PlatformProvisioningRepository provisioningRepository, ProvisioningLock lock,
            TenantOnboardingPort onboardingPort, TenantIdentityLookupPort identityLookup,
            ProvisioningProperties properties, ObjectMapper mapper, Clock clock) {
        this.tenantRepository = tenantRepository;
        this.provisioningRepository = provisioningRepository;
        this.lock = lock;
        this.onboardingPort = onboardingPort;
        this.identityLookup = identityLookup;
        this.properties = properties;
        this.mapper = mapper;
        this.clock = clock;
    }

    @Transactional
    public ProvisioningOutcome execute(TenantProvisioningRequest request, ProvisioningCaller caller,
            String idempotencyKey, String correlationId) {

        lock.onControlPlaneTenant(request.controlPlaneTenantId());

        String payloadHash = ProvisioningPayloadHash.of(mapper, request);
        Optional<PlatformProvisioning> recorded = provisioningRepository.findByIdempotencyKey(idempotencyKey);

        if (recorded.isPresent()) {
            PlatformProvisioning previous = recorded.get();
            if (!previous.payloadHash().equals(payloadHash)) {
                throw new ProblemTypedException(ProblemType.IDEMPOTENCY_CONFLICT,
                        "This Idempotency-Key was already used for a different request.");
            }
            if (previous.succeeded()) {
                return new ProvisioningOutcome(replayOf(previous), true);
            }
        }

        if (tenantRepository.findByControlPlaneTenantId(request.controlPlaneTenantId()).isPresent()) {
            throw new ProblemTypedException(ProblemType.TENANT_ALREADY_PROVISIONED,
                    "This control-plane tenant is already provisioned in TMS.");
        }

        DeploymentMode mode = deploymentMode(request.deploymentMode());
        String organizationCode = codeFrom(request.organization().slug(), "organization.slug");
        String timeZone = timeZone(request.organization().timezone());
        TenantOnboardingCommand command = new TenantOnboardingCommand(
                request.organization().id(),
                organizationCode,
                request.organization().name().trim(),
                request.company().id(),
                // One company per tenant at provisioning time, so it takes the organization's code.
                // Company codes are unique per organization, never globally, so this cannot clash
                // with another customer's.
                organizationCode,
                request.company().name().trim(),
                taxIdentifier(request),
                timeZone,
                request.admin() == null ? null : request.admin().email(),
                request.admin() == null ? null : request.admin().fullName().trim());

        TenantOnboarding onboarded = onboard(command);

        OffsetDateTime now = OffsetDateTime.now(clock);
        PlatformTenant tenant = tenantRepository.save(new PlatformTenant(
                request.controlPlaneTenantId(), onboarded.organizationId(), onboarded.companyId(),
                onboarded.adminAppUserId(), mode, now));

        PlatformProvisioning record = recorded
                .map(previous -> {
                    previous.retrying(request.controlPlaneTenantId(), payloadHash, snapshot(request),
                            correlationId, caller.subject(), caller.jti(), caller.actorId(), caller.actorRole());
                    return previous;
                })
                .orElseGet(() -> PlatformProvisioning.attempt(request.controlPlaneTenantId(), idempotencyKey,
                        payloadHash, snapshot(request), correlationId, caller.subject(), caller.jti(),
                        caller.actorId(), caller.actorRole(), now));

        AdminProvisioningStatus adminStatus = adminStatusOf(onboarded);
        // The id is assigned in Java, so the body stored on the row and the body returned to the
        // caller are the same bytes - which is what makes a replay answer identically.
        TenantProvisioningView view = TenantProvisioningView.of(record.id(), tenant, adminStatus,
                organizationCode, organizationCode, timeZone, false);
        record.succeeded(tenant, adminStatus, mapper.writeValueAsString(view), OffsetDateTime.now(clock));
        save(record);

        return new ProvisioningOutcome(view, false);
    }

    /** The read endpoint: state and mapping, never business data. */
    @Transactional(readOnly = true)
    public Optional<TenantProvisioningView> find(java.util.UUID controlPlaneTenantId) {
        return tenantRepository.findByControlPlaneTenantId(controlPlaneTenantId)
                .map(tenant -> {
                    TenantIdentity identity = identityLookup.find(tenant.organizationId(), tenant.companyId())
                            .orElseThrow(() -> new ProblemTypedException(ProblemType.PROVISIONING_FAILED,
                                    "The tenant mapping exists but its organization or company does not."));
                    PlatformProvisioning record = provisioningRepository
                            .findFirstByPlatformTenantIdOrderByCompletedAtDesc(tenant.id())
                            .orElseThrow(() -> new ProblemTypedException(ProblemType.PROVISIONING_FAILED,
                                    "The tenant mapping exists but its provisioning record does not."));
                    return TenantProvisioningView.of(record.id(), tenant, record.adminStatus(),
                            identity.organizationCode(), identity.companyCode(), identity.timeZone(), false);
                });
    }

    private TenantOnboarding onboard(TenantOnboardingCommand command) {
        try {
            return onboardingPort.onboard(command);
        } catch (TenantOnboardingRefusedException refused) {
            throw new ProblemTypedException(problemFor(refused), refused.getMessage(), refused);
        }
    }

    private static ProblemType problemFor(TenantOnboardingRefusedException refused) {
        return switch (refused.reason()) {
            case ADMIN_EMAIL_TAKEN -> ProblemType.ADMIN_EMAIL_CONFLICT;
            case ORGANIZATION_TAKEN, COMPANY_TAKEN -> ProblemType.TENANT_CONFLICT;
            case ADMIN_ROLE_MISSING -> ProblemType.PROVISIONING_FAILED;
        };
    }

    private PlatformProvisioning save(PlatformProvisioning record) {
        try {
            return provisioningRepository.saveAndFlush(record);
        } catch (DataIntegrityViolationException raced) {
            // The advisory lock makes this unreachable for one tenant; it stays as the backstop for
            // the same key arriving for two different tenants, which the lock does not serialise.
            throw new ProblemTypedException(ProblemType.IDEMPOTENCY_CONFLICT,
                    "This Idempotency-Key is already recorded for another request.", raced);
        }
    }

    private TenantProvisioningView replayOf(PlatformProvisioning previous) {
        TenantProvisioningView stored = mapper.readValue(previous.responseBody(), TenantProvisioningView.class);
        return new TenantProvisioningView(stored.provisioningId(), stored.status(), true,
                stored.controlPlaneTenantId(), stored.externalTenantId(), stored.externalOrganizationId(),
                stored.externalCompanyId(), stored.resources(), stored.adminProvisioningStatus(),
                stored.deploymentMode(), stored.createdAt());
    }

    private String snapshot(TenantProvisioningRequest request) {
        return properties.retainRequestSnapshot() ? mapper.writeValueAsString(request) : null;
    }

    private static AdminProvisioningStatus adminStatusOf(TenantOnboarding onboarded) {
        if (onboarded.adminAppUserId() == null) {
            return AdminProvisioningStatus.NOT_REQUESTED;
        }
        return onboarded.adminLinkedToExistingUser()
                ? AdminProvisioningStatus.LINKED
                : AdminProvisioningStatus.PREPROVISIONED;
    }

    private static DeploymentMode deploymentMode(String requested) {
        if ("SHARED".equalsIgnoreCase(requested.trim())) {
            return DeploymentMode.SHARED;
        }
        throw new ProblemTypedException(ProblemType.VALIDATION_FAILED,
                "deploymentMode must be SHARED; this deployment provisions no other mode.");
    }

    private static String taxIdentifier(TenantProvisioningRequest request) {
        String company = request.company().taxId();
        if (company != null && !company.isBlank()) {
            return company.trim();
        }
        // The organization's tax id is the same registration in practice, and TMS keeps it on the
        // company because that is where the column is. Neither is required.
        String organization = request.organization().taxId();
        return organization == null || organization.isBlank() ? null : organization.trim();
    }

    private static String timeZone(String requested) {
        if (requested == null || requested.isBlank()) {
            return "UTC";
        }
        try {
            return ZoneId.of(requested.trim()).getId();
        } catch (java.time.DateTimeException notAZone) {
            throw new ProblemTypedException(ProblemType.VALIDATION_FAILED,
                    "organization.timezone is not an IANA time zone identifier.");
        }
    }

    /**
     * A control-plane slug becomes a TMS code. Normalised rather than rejected outright, because
     * "cliente-norte" is a perfectly good slug and an equally good code once it is upper-cased -
     * but a slug that cannot survive normalisation is refused instead of truncated into something
     * that merely resembles what was asked for.
     */
    private static String codeFrom(String slug, String field) {
        String candidate = slug.trim().toUpperCase(Locale.ROOT)
                .replaceAll("[^A-Z0-9_-]+", "-")
                .replaceAll("^-+", "")
                .replaceAll("-+$", "");
        if (!candidate.matches("^[A-Z0-9][A-Z0-9_-]{1,31}$")) {
            throw new ProblemTypedException(ProblemType.VALIDATION_FAILED,
                    field + " cannot be used as a TMS code: it must normalise to 2-32 characters of "
                            + "A-Z, 0-9, hyphen or underscore, starting with a letter or digit.");
        }
        return candidate;
    }

    /** What the caller gets back, plus whether it was produced now or replayed. */
    public record ProvisioningOutcome(TenantProvisioningView view, boolean replayed) {
    }
}
