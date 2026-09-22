package com.ebim.tms.iam.provisioning.application;

import com.ebim.tms.iam.provisioning.domain.ProvisioningAuditEntry;
import com.ebim.tms.iam.provisioning.domain.ProvisioningAuditEntry.Operation;
import com.ebim.tms.iam.provisioning.domain.ProvisioningAuditEntry.Result;
import com.ebim.tms.iam.provisioning.domain.ProvisioningMetadata;
import com.ebim.tms.iam.provisioning.domain.ProvisioningRecord;
import com.ebim.tms.iam.provisioning.infrastructure.PlatformProvisioningRepository;
import com.ebim.tms.iam.provisioning.infrastructure.PlatformProvisioningRepository.ExistingProfile;
import com.ebim.tms.iam.provisioning.infrastructure.PlatformProvisioningRepository.NewProvisioning;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Creates a TMS tenant for a MasterAdmin tenant, in one transaction, or does nothing at all.
 *
 * <p>Order of a call, and why:
 *
 * <ol>
 *   <li>Advisory locks on the Idempotency-Key and on the MasterAdmin tenant. Two concurrent retries
 *       of the same call, or two calls for the same tenant, queue here instead of racing to the
 *       unique indexes.</li>
 *   <li>Idempotency: the same key with the same hash is a replay and returns what was created;
 *       the same key with another hash is {@code IDEMPOTENCY_CONFLICT}.</li>
 *   <li>One tenant per MasterAdmin tenant: another key for an already-provisioned tenant is
 *       {@code TENANT_ALREADY_PROVISIONED} (same content) or {@code TENANT_CONFLICT}.</li>
 *   <li>An organization code already used outside provisioning is {@code TENANT_CONFLICT}: TMS never
 *       adopts a tenant it did not create.</li>
 *   <li>The writes, then the provisioning row and its {@code CREATED} audit entry.</li>
 * </ol>
 *
 * <p>Any exception - a contract conflict or a database failure - propagates and rolls back every
 * row written above, including the audit entry. The caller ({@link PlatformProvisioningService})
 * records rejections and failures in a transaction of their own.
 */
@Service
public class PlatformTenantProvisioner {

    static final String ADMIN_ROLE = "ORGANIZATION_ADMIN";

    private final PlatformProvisioningRepository repository;

    public PlatformTenantProvisioner(PlatformProvisioningRepository repository) {
        this.repository = repository;
    }

    @Transactional
    public Outcome provision(ProvisionTenantCommand command, ProvisioningMetadata metadata) {
        String requestHash = command.requestHash();
        UUID controlPlaneTenantId = command.controlPlaneTenantId();

        // Always key first, tenant second: a fixed order is what keeps two callers from each
        // holding the lock the other one needs.
        repository.lock("key:" + metadata.idempotencyKey());
        repository.lock("tenant:" + controlPlaneTenantId);

        Optional<ProvisioningRecord> sameKey = repository.findByIdempotencyKey(metadata.idempotencyKey());
        if (sameKey.isPresent()) {
            if (!sameKey.get().requestHash().equals(requestHash)) {
                throw new PlatformProvisioningException(ProvisioningErrorCode.IDEMPOTENCY_CONFLICT);
            }
            audit(Result.REPLAYED, 200, sameKey.get(), metadata, controlPlaneTenantId);
            return new Outcome(sameKey.get(), true);
        }

        Optional<ProvisioningRecord> sameTenant = repository.findByControlPlaneTenantId(controlPlaneTenantId);
        if (sameTenant.isPresent()) {
            throw new PlatformProvisioningException(sameTenant.get().requestHash().equals(requestHash)
                    ? ProvisioningErrorCode.TENANT_ALREADY_PROVISIONED
                    : ProvisioningErrorCode.TENANT_CONFLICT);
        }
        if (repository.organizationCodeTaken(command.tenantCode())) {
            throw new PlatformProvisioningException(ProvisioningErrorCode.TENANT_CONFLICT);
        }

        Optional<ExistingProfile> profile = repository.findProfileByEmail(command.adminEmail());
        if (profile.isPresent() && !profile.get().active()) {
            // A deactivated person is deactivated for a reason somebody in TMS decided. Being named
            // by another system is not a reason to reverse it silently.
            throw new PlatformProvisioningException(ProvisioningErrorCode.ADMIN_EMAIL_CONFLICT);
        }

        UUID organizationId = repository.insertOrganization(command.tenantCode(), command.organizationName());
        UUID companyId = repository.insertCompany(organizationId, command.effectiveCompanyCode(),
                command.effectiveCompanyName(), command.companyTaxId(), command.effectiveTimeZone());
        if (command.effectiveCountryCode() != null) {
            repository.insertCompanySettings(companyId, command.effectiveCountryCode());
        }

        // The same person may work for several organizations (tms.app_user is global, V2), and the
        // user administration screen already reuses a profile by email. Provisioning does the same.
        boolean reused = profile.isPresent();
        UUID adminId = reused
                ? profile.get().id()
                : repository.insertPreprovisionedProfile(command.adminEmail(), command.adminDisplayName());
        UUID membershipId = repository.insertOrganizationWideMembership(adminId, organizationId);
        if (repository.grantRole(membershipId, ADMIN_ROLE) != 1) {
            throw new IllegalStateException("role " + ADMIN_ROLE + " is missing or inactive in tms.role");
        }

        repository.insertProvisioning(new NewProvisioning(controlPlaneTenantId, requestHash, organizationId,
                companyId, adminId, membershipId, reused, command.productCode(), command.contractVersion(),
                command.tenantCode(), command.environment(), command.tenantType(), command.deploymentMode(),
                command.planCode()), metadata);

        ProvisioningRecord created = repository.findByControlPlaneTenantId(controlPlaneTenantId)
                .orElseThrow(() -> new IllegalStateException("the provisioning row just written is not readable"));
        audit(Result.CREATED, 201, created, metadata, controlPlaneTenantId);
        return new Outcome(created, false);
    }

    private void audit(Result result, int status, ProvisioningRecord record, ProvisioningMetadata metadata,
            UUID controlPlaneTenantId) {
        repository.recordAudit(new ProvisioningAuditEntry(Operation.CREATE_TENANT, result, status, null,
                controlPlaneTenantId, metadata.idempotencyKey(), record.provisioningId(), metadata.correlationId(),
                metadata.m2mSubject(), metadata.m2mJti(), metadata.actorId(), metadata.actorRole()));
    }

    /** What a successful call produced, and whether it was produced by an earlier call. */
    public record Outcome(ProvisioningRecord record, boolean replayed) {}
}
