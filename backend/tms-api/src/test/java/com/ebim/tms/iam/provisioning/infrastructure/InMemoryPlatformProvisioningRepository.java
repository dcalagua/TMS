package com.ebim.tms.iam.provisioning.infrastructure;

import com.ebim.tms.iam.provisioning.domain.ProvisioningAuditEntry;
import com.ebim.tms.iam.provisioning.domain.ProvisioningMetadata;
import com.ebim.tms.iam.provisioning.domain.ProvisioningRecord;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The provisioning repository over maps, so the web tests exercise real idempotency across calls
 * without a database. Mirrors the unique keys of V2 and V51 that the use case relies on.
 */
public class InMemoryPlatformProvisioningRepository extends PlatformProvisioningRepository {

    public record Organization(UUID id, String code, String name) {}

    public record Company(UUID id, UUID organizationId, String code, String name, String taxId, String timeZone) {}

    public record Profile(UUID id, String email, String fullName, boolean active, UUID authUserId) {}

    public record Membership(UUID id, UUID appUserId, UUID organizationId, UUID companyId, List<String> roles) {}

    private record Stored(String idempotencyKey, NewProvisioning row, UUID id, OffsetDateTime createdAt) {}

    public final Map<UUID, Organization> organizations = new ConcurrentHashMap<>();
    public final Map<UUID, Company> companies = new ConcurrentHashMap<>();
    public final Map<UUID, String> companySettings = new ConcurrentHashMap<>();
    public final Map<UUID, Profile> profiles = new ConcurrentHashMap<>();
    public final Map<UUID, Membership> memberships = new ConcurrentHashMap<>();
    public final List<ProvisioningAuditEntry> audit = new ArrayList<>();
    private final Map<UUID, Stored> provisionings = new ConcurrentHashMap<>();

    public InMemoryPlatformProvisioningRepository() {
        super(null);
    }

    public void reset() {
        organizations.clear();
        companies.clear();
        companySettings.clear();
        profiles.clear();
        memberships.clear();
        audit.clear();
        provisionings.clear();
    }

    public UUID addProfile(String email, boolean active, UUID authUserId) {
        UUID id = UUID.randomUUID();
        profiles.put(id, new Profile(id, email, email, active, authUserId));
        return id;
    }

    public void addOrganization(String code) {
        UUID id = UUID.randomUUID();
        organizations.put(id, new Organization(id, code, code));
    }

    @Override
    public void lock(String lockName) {
        // A single-threaded test has nothing to serialize.
    }

    @Override
    public Optional<ProvisioningRecord> findByIdempotencyKey(String idempotencyKey) {
        return provisionings.values().stream().filter(s -> s.idempotencyKey().equals(idempotencyKey))
                .findFirst().map(this::toRecord);
    }

    @Override
    public Optional<ProvisioningRecord> findByControlPlaneTenantId(UUID controlPlaneTenantId) {
        return Optional.ofNullable(provisionings.get(controlPlaneTenantId)).map(this::toRecord);
    }

    @Override
    public boolean organizationCodeTaken(String code) {
        return organizations.values().stream().anyMatch(o -> o.code().equals(code));
    }

    @Override
    public Optional<ExistingProfile> findProfileByEmail(String email) {
        return profiles.values().stream().filter(p -> p.email().equals(email)).findFirst()
                .map(p -> new ExistingProfile(p.id(), p.active()));
    }

    @Override
    public UUID insertOrganization(String code, String name) {
        UUID id = UUID.randomUUID();
        organizations.put(id, new Organization(id, code, name));
        return id;
    }

    @Override
    public UUID insertCompany(UUID organizationId, String code, String name, String taxIdentifier, String timeZone) {
        UUID id = UUID.randomUUID();
        companies.put(id, new Company(id, organizationId, code, name, taxIdentifier, timeZone));
        return id;
    }

    @Override
    public void insertCompanySettings(UUID companyId, String defaultCountry) {
        companySettings.put(companyId, defaultCountry);
    }

    @Override
    public UUID insertPreprovisionedProfile(String email, String fullName) {
        UUID id = UUID.randomUUID();
        profiles.put(id, new Profile(id, email, fullName, true, null));
        return id;
    }

    @Override
    public UUID insertOrganizationWideMembership(UUID appUserId, UUID organizationId) {
        UUID id = UUID.randomUUID();
        memberships.put(id, new Membership(id, appUserId, organizationId, null, new ArrayList<>()));
        return id;
    }

    @Override
    public int grantRole(UUID membershipId, String roleCode) {
        memberships.get(membershipId).roles().add(roleCode);
        return 1;
    }

    @Override
    public UUID insertProvisioning(NewProvisioning row, ProvisioningMetadata metadata) {
        UUID id = UUID.randomUUID();
        provisionings.put(row.controlPlaneTenantId(),
                new Stored(metadata.idempotencyKey(), row, id, OffsetDateTime.now()));
        return id;
    }

    @Override
    public synchronized void recordAudit(ProvisioningAuditEntry entry) {
        audit.add(entry);
    }

    private ProvisioningRecord toRecord(Stored stored) {
        NewProvisioning row = stored.row();
        Organization organization = organizations.get(row.organizationId());
        Company company = companies.get(row.companyId());
        Profile admin = profiles.get(row.adminAppUserId());
        return new ProvisioningRecord(stored.id(), row.controlPlaneTenantId(), row.requestHash(),
                organization.id(), organization.code(), true, company.id(), company.code(), company.timeZone(),
                true, row.adminProfileReused(), admin.authUserId() != null, stored.createdAt());
    }
}
