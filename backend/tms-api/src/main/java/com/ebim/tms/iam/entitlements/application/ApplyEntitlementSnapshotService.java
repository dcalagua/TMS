package com.ebim.tms.iam.entitlements.application;

import com.ebim.tms.iam.entitlements.application.EntitlementStore.CallContext;
import com.ebim.tms.iam.entitlements.domain.EntitlementRejection;
import com.ebim.tms.iam.entitlements.domain.EntitlementRejection.Code;
import com.ebim.tms.iam.entitlements.domain.EntitlementSnapshot;
import com.ebim.tms.iam.entitlements.domain.Jcs;
import com.ebim.tms.iam.entitlements.domain.SnapshotValidator;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.springframework.stereotype.Service;
import tools.jackson.core.JacksonException;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * {@code PUT /internal/platform-provisioning/tenants/{controlPlaneTenantId}/entitlements} (CCP spec
 * section 8.2).
 *
 * <p>Not transactional, like {@code PlatformProvisioningService}: first everything that can be refused
 * without touching the database - jti, contract, size, shape, environment, checksum - and only then
 * the transaction of {@link EntitlementApplyWriter}. The order is the contract's ({@code README.md}
 * section 3).
 */
@Service
public class ApplyEntitlementSnapshotService {

    public static final String CONTRACT = "entitlements.v1";
    /** Cap on the RAW body, before parsing; the canonical form has its own 64 KB cap. */
    public static final int MAX_RAW_BYTES = 1 << 20;
    public static final int MAX_CANONICAL_BYTES = 65_536;

    /** A duplicated key makes the document ambiguous: two readers could see two different snapshots. */
    private static final ObjectMapper STRICT = JsonMapper.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .build();

    private final JtiGuard jti;
    private final EntitlementApplyWriter writer;
    private final PlatformEntitlementsProperties properties;
    private final ReceiverProfile profile;

    public ApplyEntitlementSnapshotService(JtiGuard jti, EntitlementApplyWriter writer,
            PlatformEntitlementsProperties properties, ReceiverProfile profile) {
        this.jti = jti;
        this.writer = writer;
        this.properties = properties;
        this.profile = profile;
    }

    public Map<String, Object> apply(String tenantPath, byte[] body, String contract, String correlationId,
            JtiGuard.Call call) {
        jti.consume(call);

        if (contract != null && !contract.isBlank() && !CONTRACT.equals(contract.trim())) {
            throw new EntitlementRejection(Code.UNSUPPORTED_CONTRACT_VERSION, "This receiver speaks " + CONTRACT);
        }
        if (body == null || body.length == 0) {
            throw new EntitlementRejection(Code.SNAPSHOT_INVALID, "The snapshot is missing");
        }
        if (body.length > MAX_RAW_BYTES) {
            throw new EntitlementRejection(Code.SNAPSHOT_TOO_LARGE, "The snapshot exceeds 64 KB");
        }
        JsonNode document;
        try {
            document = STRICT.readTree(new String(body, StandardCharsets.UTF_8));
        } catch (JacksonException notJson) {
            throw new EntitlementRejection(Code.SNAPSHOT_INVALID, "The body is not valid JSON");
        }

        EntitlementSnapshot snapshot = SnapshotValidator.validate(document);
        if (Jcs.canonicalize(document).getBytes(StandardCharsets.UTF_8).length > MAX_CANONICAL_BYTES) {
            throw new EntitlementRejection(Code.SNAPSHOT_TOO_LARGE, "The snapshot exceeds 64 KB");
        }
        if (!snapshot.controlPlaneTenantId().toString().equals(tenantPath)
                || !profile.productCode().equals(snapshot.productCode())) {
            throw new EntitlementRejection(Code.SNAPSHOT_INVALID,
                    "The tenant or product of the body differs from the path or the receiver");
        }
        if (!snapshot.environment().equals(properties.environment())) {
            throw new EntitlementRejection(Code.ENVIRONMENT_MISMATCH, properties.environment().isEmpty()
                    ? "This receiver has no environment configured"
                    : "This receiver is " + properties.environment());
        }
        if (!Jcs.entitlementChecksum((ObjectNode) document).equals(snapshot.checksum())) {
            throw new EntitlementRejection(Code.CHECKSUM_MISMATCH, "The checksum does not match the content");
        }

        EntitlementApplyWriter.Outcome outcome =
                writer.apply(snapshot, new CallContext(correlationId, call.subject(), call.jti()));
        if (outcome instanceof EntitlementApplyWriter.Refused refused) {
            throw refused.rejection();
        }
        return ((EntitlementApplyWriter.Accepted) outcome).body();
    }
}
