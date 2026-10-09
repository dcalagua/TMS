package com.ebim.tms.iam.entitlements.application;

import com.ebim.tms.iam.entitlements.application.EntitlementStore.Applied;
import com.ebim.tms.iam.entitlements.domain.AppliedStatus;
import com.ebim.tms.iam.entitlements.domain.EntitlementRejection;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * {@code GET /tenants/{id}/entitlements} (CCP spec section 8.3) and {@code GET /entitlements/manifest}.
 *
 * <p>The applied GET is MasterAdmin's <b>only</b> valid proof of synchronisation (spec section 19): it
 * returns what is stored, not what was asked for.
 */
@Service
public class EntitlementQueryService {

    public static final String MANIFEST_RESOURCE = "/platform/entitlements/ENTITLEMENTS_MANIFEST.json";

    private final JtiGuard jti;
    private final EntitlementStore store;
    private final ReceiverProfile profile;
    private final byte[] manifest;

    public EntitlementQueryService(JtiGuard jti, EntitlementStore store, ReceiverProfile profile) {
        this.jti = jti;
        this.store = store;
        this.profile = profile;
        this.manifest = readManifest();
    }

    @Transactional(readOnly = true)
    public Map<String, Object> applied(String tenantPath, JtiGuard.Call call) {
        jti.consume(call);
        UUID tenant = uuid(tenantPath).filter(id -> store.provisioned(id).isPresent())
                .orElseThrow(() -> new EntitlementRejection(EntitlementRejection.Code.TENANT_NOT_PROVISIONED,
                        "No local provisioning for this tenant"));
        Optional<Applied> applied = store.applied(tenant);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("controlPlaneTenantId", tenant.toString());
        body.put("productCode", profile.productCode());
        body.put("appliedVersion", applied.map(Applied::version).orElse(null));
        body.put("appliedChecksum", applied.map(Applied::checksum).orElse(null));
        body.put("appliedAt", applied.map(a -> a.appliedAt().toString()).orElse(null));
        body.put("status", applied.map(a -> a.status().name()).orElse(AppliedStatus.NONE.name()));
        body.put("unknownCapabilities", applied.map(Applied::unknownCapabilities).orElse(List.of()));
        body.put("enforcementMode", store.mode(tenant).name());
        return body;
    }

    /** The manifest, byte for byte {@code docs/platform-provisioning/ENTITLEMENTS_MANIFEST.json}. */
    public byte[] manifest(JtiGuard.Call call) {
        jti.consume(call);
        return manifest();
    }

    public byte[] manifest() {
        return manifest.clone();
    }

    private static Optional<UUID> uuid(String text) {
        try {
            return Optional.of(UUID.fromString(text)).filter(id -> id.toString().equals(text));
        } catch (IllegalArgumentException notAUuid) {
            return Optional.empty();
        }
    }

    private static byte[] readManifest() {
        try (InputStream in = Objects.requireNonNull(
                EntitlementQueryService.class.getResourceAsStream(MANIFEST_RESOURCE), "the manifest is missing")) {
            return in.readAllBytes();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
