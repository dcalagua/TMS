package com.ebim.tms.iam.inventory.application;

import com.ebim.tms.iam.entitlements.application.JtiGuard;
import com.ebim.tms.iam.entitlements.application.PlatformEntitlementsProperties;
import com.ebim.tms.iam.entitlements.application.ReceiverProfile;
import com.ebim.tms.iam.entitlements.domain.EntitlementRejection;
import com.ebim.tms.iam.inventory.application.TenantInventoryStore.Mapping;
import com.ebim.tms.iam.inventory.application.TenantInventoryStore.NewLink;
import com.ebim.tms.iam.inventory.application.TenantInventoryStore.Tenant;
import com.ebim.tms.iam.inventory.domain.InventoryRejection;
import com.ebim.tms.iam.inventory.domain.InventoryRejection.Code;
import com.ebim.tms.iam.provisioning.domain.ProvisioningAuditEntry;
import com.ebim.tms.iam.provisioning.domain.ProvisioningAuditEntry.Operation;
import com.ebim.tms.iam.provisioning.domain.ProvisioningAuditEntry.Result;
import com.ebim.tms.iam.provisioning.domain.ProvisioningMetadata;
import com.ebim.tms.iam.provisioning.security.PlatformCaller;
import com.ebim.tms.iam.provisioning.security.PlatformInventoryProperties;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.core.JacksonException;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * {@code tenant-inventory.v1}: the list of every TMS tenant, and the link that adopts an existing one.
 *
 * <pre>
 *   GET /internal/platform-provisioning/tenants?limit=1..500&amp;cursor=...      list
 *   PUT /internal/platform-provisioning/tenants/{controlPlaneTenantId}/link    link
 * </pre>
 *
 * <p><b>The tenant is the organization</b> (ADR-003): {@code externalTenantId} is the organization id, the
 * same value {@code POST /tenants} returns as {@code externalTenantId} ({@code ProvisionedTenantView}).
 *
 * <p><b>The list</b> reports identity and state only - organization id and code, name, the company a link
 * records, the MasterAdmin tenant mapped to it, and {@code ACTIVE | SUSPENDED | INACTIVE} (organization
 * deactivated wins over a lifecycle.v1 suspension). Every organization of the deployment is listed, demo
 * and internal ones too: MasterAdmin decides what to do with them. Keyset-paginated by organization id.
 *
 * <p><b>The link</b> writes one {@code LINKED} row into V51's {@code platform_provisioning_request} (V76)
 * mapping a MasterAdmin tenant to an organization that already exists, and nothing else - no organization,
 * company, user, membership or role is created or changed. After it, the status GET, entitlements.v1 and
 * lifecycle.v1 resolve that tenant exactly as one created by {@code POST /tenants}. Same pair again: {@code
 * ALREADY_LINKED}; either side already mapped elsewhere: {@code 409 LINK_CONFLICT}. It takes the lock tenant
 * creation takes for the same MasterAdmin tenant, so a create and a link of one tenant cannot interleave.
 *
 * <p>Shared with entitlements and lifecycle: the single-use {@code jti} store, the product code and the
 * environment this deployment answers for ({@code TMS_ENTITLEMENTS_ENVIRONMENT}). A route whose scope or
 * environment is not configured answers {@code 503 ..._NOT_CONFIGURED} before touching anything.
 */
@Service
public class PlatformTenantInventoryService {

    private static final Logger log = LoggerFactory.getLogger(PlatformTenantInventoryService.class);

    public static final String CONTRACT = "tenant-inventory.v1";
    public static final int DEFAULT_LIMIT = 200;
    public static final int MAX_LIMIT = 500;
    /** Generous for a document of two short fields; anything larger is not a link body. */
    public static final int MAX_RAW_BYTES = 4 * 1024;

    static final Set<String> ENVIRONMENTS = Set.of("DEV", "QAS", "DEMO", "PRD");
    private static final int MAX_EXTERNAL_ID = 200;
    private static final int MAX_NAME = 300;

    private static final ObjectMapper STRICT = JsonMapper.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .build();

    private final TenantInventoryStore store;
    private final JtiGuard jti;
    private final PlatformInventoryProperties scopes;
    private final PlatformEntitlementsProperties environment;
    private final ReceiverProfile profile;
    private final TransactionTemplate transaction;
    private final TransactionTemplate ownTransaction;

    public PlatformTenantInventoryService(TenantInventoryStore store, JtiGuard jti, PlatformInventoryProperties scopes,
            PlatformEntitlementsProperties environment, ReceiverProfile profile,
            PlatformTransactionManager transactionManager) {
        this.store = store;
        this.jti = jti;
        this.scopes = scopes;
        this.environment = environment;
        this.profile = profile;
        this.transaction = new TransactionTemplate(transactionManager);
        this.ownTransaction = new TransactionTemplate(transactionManager);
        this.ownTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    /** Whether {@code GET /tenants} is open: its scope and the environment are configured. */
    public boolean listConfigured() {
        return scopes.listScope().isPresent() && ENVIRONMENTS.contains(environment.environment());
    }

    /** Whether {@code PUT /tenants/{id}/link} is open: its scope and the environment are configured. */
    public boolean linkConfigured() {
        return scopes.adoptScope().isPresent() && ENVIRONMENTS.contains(environment.environment());
    }

    // ------------------------------------------------------------------------------------------------
    // List
    // ------------------------------------------------------------------------------------------------

    public Map<String, Object> list(String limitParameter, String cursorParameter, String contractHeader,
            String correlationId, PlatformCaller caller, JtiGuard.Call call) {
        ProvisioningMetadata metadata = metadata(null, correlationId, caller);
        if (!listConfigured()) {
            throw new InventoryRejection(Code.INVENTORY_NOT_CONFIGURED, "The tenant inventory is not configured");
        }
        consume(call);
        try {
            if (contractHeader != null && !contractHeader.isBlank() && !CONTRACT.equals(contractHeader.trim())) {
                throw new InventoryRejection(Code.INVALID_INVENTORY_QUERY, "This receiver speaks " + CONTRACT);
            }
            int limit = limit(limitParameter);
            UUID after = null;
            if (cursorParameter != null) {
                after = InventoryCursor.decode(cursorParameter).orElseThrow(() ->
                        new InventoryRejection(Code.INVALID_INVENTORY_QUERY, "cursor is not a cursor of this list"));
            }

            List<Tenant> page;
            try {
                page = store.page(after, limit + 1);
            } catch (RuntimeException failure) {
                log.error("Tenant inventory read failed (correlation {})", correlationId, failure);
                throw new InventoryRejection(Code.INVENTORY_FAILED, "The inventory could not be read");
            }
            boolean more = page.size() > limit;
            List<Tenant> shown = more ? page.subList(0, limit) : page;
            List<Map<String, Object>> items = new ArrayList<>(shown.size());
            shown.forEach(tenant -> items.add(item(tenant)));

            Map<String, Object> body = envelope();
            body.put("items", items);
            body.put("nextCursor", more ? InventoryCursor.encode(shown.getLast().organizationId()) : null);
            audit(Operation.LIST_TENANTS, Result.FOUND, 200, null, null, null, metadata);
            return body;
        } catch (InventoryRejection rejection) {
            audit(Operation.LIST_TENANTS, result(rejection.code()), rejection.code().status(), rejection.code().name(),
                    null, null, metadata);
            throw rejection;
        }
    }

    private static int limit(String parameter) {
        if (parameter == null) {
            return DEFAULT_LIMIT;
        }
        if (!parameter.matches("[0-9]{1,3}")) {
            throw new InventoryRejection(Code.INVALID_INVENTORY_QUERY, "limit is an integer from 1 to " + MAX_LIMIT);
        }
        int limit = Integer.parseInt(parameter);
        if (limit < 1 || limit > MAX_LIMIT) {
            throw new InventoryRejection(Code.INVALID_INVENTORY_QUERY, "limit is an integer from 1 to " + MAX_LIMIT);
        }
        return limit;
    }

    private static Map<String, Object> item(Tenant tenant) {
        Map<String, Object> item = new LinkedHashMap<>();
        String organization = tenant.organizationId().toString();
        item.put("externalTenantId", organization);
        item.put("externalOrganizationId", organization);
        item.put("externalCompanyId", tenant.companyId() == null ? null : tenant.companyId().toString());
        item.put("controlPlaneTenantId",
                tenant.controlPlaneTenantId() == null ? null : tenant.controlPlaneTenantId().toString());
        item.put("code", tenant.code());
        item.put("name", tenant.name() == null || tenant.name().length() <= MAX_NAME
                ? tenant.name() : tenant.name().substring(0, MAX_NAME));
        item.put("status", status(tenant));
        return item;
    }

    /** INACTIVE (organization deactivated) wins over SUSPENDED (lifecycle.v1), which wins over ACTIVE. */
    static String status(Tenant tenant) {
        if (!tenant.active()) {
            return "INACTIVE";
        }
        return tenant.suspended() ? "SUSPENDED" : "ACTIVE";
    }

    // ------------------------------------------------------------------------------------------------
    // Link
    // ------------------------------------------------------------------------------------------------

    /**
     * {@code PUT /tenants/{controlPlaneTenantId}/link}.
     *
     * @param json whether the request's Content-Type is JSON (or absent); a non-JSON body is refused after
     *             the {@code jti} is spent, like every other MasterAdmin contract route
     */
    public Map<String, Object> link(String tenantPath, byte[] body, boolean json, String contractHeader,
            String correlationId, PlatformCaller caller, JtiGuard.Call call) {
        if (!linkConfigured()) {
            throw new InventoryRejection(Code.TENANT_LINK_NOT_CONFIGURED, "The tenant link is not configured");
        }
        consume(call);
        UUID controlPlaneTenantId = uuid(tenantPath).orElse(null);
        ProvisioningMetadata metadata = metadata(null, correlationId, caller);
        try {
            if (contractHeader != null && !contractHeader.isBlank() && !CONTRACT.equals(contractHeader.trim())) {
                throw new InventoryRejection(Code.INVALID_LINK_REQUEST, "This receiver speaks " + CONTRACT);
            }
            if (!json) {
                throw new InventoryRejection(Code.INVALID_LINK_REQUEST, "The body is application/json");
            }
            if (controlPlaneTenantId == null) {
                throw new InventoryRejection(Code.INVALID_LINK_REQUEST, "controlPlaneTenantId must be a UUID");
            }
            LinkRequest request = parse(body, controlPlaneTenantId);
            metadata = metadata(request.requestId().toString(), correlationId, caller);

            // A TMS tenant id is an organization UUID; anything else names no tenant here.
            UUID organizationId = uuid(request.externalTenantId()).orElseThrow(() ->
                    new InventoryRejection(Code.EXTERNAL_TENANT_NOT_FOUND, "No TMS tenant has this externalTenantId"));

            Linked linked = linkInTransaction(controlPlaneTenantId, organizationId, metadata);
            audit(Operation.LINK_TENANT, linked.already() ? Result.ALREADY_LINKED : Result.LINKED, 200, null,
                    controlPlaneTenantId, linked.provisioningId(), metadata);
            log.info("MasterAdmin tenant {} {} to organization {} (correlation {})", controlPlaneTenantId,
                    linked.already() ? "was already linked" : "linked", organizationId, correlationId);

            Map<String, Object> response = envelope();
            response.put("controlPlaneTenantId", controlPlaneTenantId.toString());
            response.put("externalTenantId", organizationId.toString());
            response.put("status", linked.already() ? "ALREADY_LINKED" : "LINKED");
            return response;
        } catch (InventoryRejection rejection) {
            audit(Operation.LINK_TENANT, result(rejection.code()), rejection.code().status(), rejection.code().name(),
                    controlPlaneTenantId, null, metadata);
            throw rejection;
        }
    }

    private Linked linkInTransaction(UUID controlPlaneTenantId, UUID organizationId, ProvisioningMetadata metadata) {
        try {
            return transaction.execute(status -> {
                // Tenant first, organization second, always: the fixed order keeps two links from each
                // holding the lock the other needs. "tenant:" is the lock tenant creation takes.
                store.lock("tenant:" + controlPlaneTenantId);
                store.lock("organization:" + organizationId);

                Optional<Mapping> byTenant = store.mappingOfControlPlaneTenant(controlPlaneTenantId);
                if (byTenant.isPresent()) {
                    if (byTenant.get().organizationId().equals(organizationId)) {
                        return new Linked(byTenant.get().provisioningId(), true);
                    }
                    throw new InventoryRejection(Code.LINK_CONFLICT,
                            "This controlPlaneTenantId is already mapped to another TMS tenant");
                }
                Tenant tenant = store.tenant(organizationId).orElseThrow(() ->
                        new InventoryRejection(Code.EXTERNAL_TENANT_NOT_FOUND, "No TMS tenant has this externalTenantId"));
                if (store.mappingOfOrganization(organizationId).isPresent()) {
                    throw new InventoryRejection(Code.LINK_CONFLICT,
                            "This TMS tenant is already mapped to another controlPlaneTenantId");
                }
                UUID id = store.insertLink(new NewLink(controlPlaneTenantId, organizationId, tenant.companyId(),
                        tenant.code(), requestHash(controlPlaneTenantId, organizationId), profile.productCode(),
                        CONTRACT, environment.environment()), metadata);
                return new Linked(id, false);
            });
        } catch (InventoryRejection rejection) {
            throw rejection;
        } catch (DataIntegrityViolationException raced) {
            // A unique key lost to a concurrent create or link the locks did not cover. Nothing was kept.
            throw new InventoryRejection(Code.LINK_CONFLICT, "The tenant was mapped concurrently");
        } catch (RuntimeException failure) {
            log.error("Tenant link failed for control-plane tenant {} (correlation {}); rolled back",
                    controlPlaneTenantId, metadata.correlationId(), failure);
            throw new InventoryRejection(Code.TENANT_LINK_FAILED, "The link failed; nothing was changed");
        }
    }

    private static LinkRequest parse(byte[] body, UUID path) {
        if (body == null || body.length == 0 || body.length > MAX_RAW_BYTES) {
            throw new InventoryRejection(Code.INVALID_LINK_REQUEST, "The body is missing or too large");
        }
        JsonNode document;
        try {
            document = STRICT.readTree(new String(body, StandardCharsets.UTF_8));
        } catch (JacksonException notJson) {
            throw new InventoryRejection(Code.INVALID_LINK_REQUEST, "The body is not valid JSON");
        }
        if (document == null || !document.isObject()) {
            throw new InventoryRejection(Code.INVALID_LINK_REQUEST, "The body is a JSON object");
        }
        String external = text(document, "externalTenantId");
        if (external == null || external.isBlank() || external.length() > MAX_EXTERNAL_ID) {
            throw new InventoryRejection(Code.INVALID_LINK_REQUEST, "externalTenantId is text of 1 to 200 characters");
        }
        UUID requestId = uuid(text(document, "requestId")).orElseThrow(() ->
                new InventoryRejection(Code.INVALID_LINK_REQUEST, "requestId must be a UUID"));
        // Optional echoes of the path and the product: when sent, they must agree.
        if (document.has("controlPlaneTenantId")
                && !uuid(text(document, "controlPlaneTenantId")).map(path::equals).orElse(false)) {
            throw new InventoryRejection(Code.INVALID_LINK_REQUEST, "The tenant of the body differs from the path");
        }
        if (document.has("productCode") && !"tms".equals(text(document, "productCode"))) {
            throw new InventoryRejection(Code.INVALID_LINK_REQUEST, "This receiver is tms");
        }
        return new LinkRequest(external.trim(), requestId);
    }

    private static String text(JsonNode document, String field) {
        JsonNode node = document.get(field);
        return node != null && node.isString() ? node.stringValue() : null;
    }

    /** Canonical UUIDs only: {@code UUID.fromString} also accepts short forms such as {@code 1-1-1-1-1}. */
    static Optional<UUID> uuid(String value) {
        if (value == null || value.length() != 36) {
            return Optional.empty();
        }
        try {
            UUID uuid = UUID.fromString(value);
            return uuid.toString().equalsIgnoreCase(value) ? Optional.of(uuid) : Optional.empty();
        } catch (IllegalArgumentException malformed) {
            return Optional.empty();
        }
    }

    /** What the link asserts, for the {@code request_hash} V51 requires; no transport fact is part of it. */
    private static String requestHash(UUID controlPlaneTenantId, UUID organizationId) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(
                    (CONTRACT + "|link|" + controlPlaneTenantId + "|" + organizationId).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is required by the JVM specification", impossible);
        }
    }

    // ------------------------------------------------------------------------------------------------
    // Shared
    // ------------------------------------------------------------------------------------------------

    private Map<String, Object> envelope() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("contract", CONTRACT);
        body.put("productCode", profile.productCode());
        body.put("environment", environment.environment());
        return body;
    }

    /** Spends the token's {@code jti}; a second use is {@code 401 JTI_REPLAYED}. */
    private void consume(JtiGuard.Call call) {
        try {
            jti.consume(call);
        } catch (EntitlementRejection replayed) {
            throw new InventoryRejection(Code.JTI_REPLAYED, "jti already used");
        }
    }

    private static ProvisioningMetadata metadata(String requestId, String correlationId, PlatformCaller caller) {
        return new ProvisioningMetadata(requestId, correlationId, caller.subject(), caller.jti(), caller.actorId(),
                caller.actorRole());
    }

    private static Result result(Code code) {
        return switch (code.status()) {
            case 409 -> Result.CONFLICT;
            case 500 -> Result.ERROR;
            default -> Result.REJECTED;
        };
    }

    /**
     * Written in a transaction of its own: a refused link has just been rolled back. An audit failure never
     * changes the answer - the outcome is decided - but it is logged, so a broken trail is visible.
     * {@code idempotency_key} carries MasterAdmin's {@code requestId} of a link.
     */
    private void audit(Operation operation, Result result, int httpStatus, String errorCode, UUID controlPlaneTenantId,
            UUID provisioningId, ProvisioningMetadata metadata) {
        ProvisioningAuditEntry entry = new ProvisioningAuditEntry(operation, result, httpStatus, errorCode,
                controlPlaneTenantId, metadata.idempotencyKey(), provisioningId, metadata.correlationId(),
                metadata.m2mSubject(), metadata.m2mJti(), metadata.actorId(), metadata.actorRole());
        try {
            ownTransaction.executeWithoutResult(status -> store.audit(entry));
        } catch (RuntimeException auditFailure) {
            log.error("Could not write the tenant-inventory audit entry (correlation {})", metadata.correlationId(),
                    auditFailure);
        }
    }

    private record LinkRequest(String externalTenantId, UUID requestId) {}

    private record Linked(UUID provisioningId, boolean already) {}
}
