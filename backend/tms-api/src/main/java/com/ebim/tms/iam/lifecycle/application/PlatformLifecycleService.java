package com.ebim.tms.iam.lifecycle.application;

import com.ebim.tms.iam.entitlements.application.JtiGuard;
import com.ebim.tms.iam.entitlements.application.PlatformEntitlementsProperties;
import com.ebim.tms.iam.entitlements.application.ReceiverProfile;
import com.ebim.tms.iam.entitlements.domain.EntitlementRejection;
import com.ebim.tms.iam.lifecycle.application.LifecycleStore.Applied;
import com.ebim.tms.iam.lifecycle.application.LifecycleStore.CallContext;
import com.ebim.tms.iam.lifecycle.domain.LifecycleCommand;
import com.ebim.tms.iam.lifecycle.domain.LifecycleRejection;
import com.ebim.tms.iam.lifecycle.domain.LifecycleRejection.Code;
import com.ebim.tms.iam.lifecycle.domain.LifecycleState;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.JacksonException;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * {@code PUT|GET /internal/platform-provisioning/tenants/{controlPlaneTenantId}/lifecycle}
 * ({@code lifecycle.v1}, ADR-021).
 *
 * <p>The PUT is not transactional here, like {@code ApplyEntitlementSnapshotService}: first everything
 * that can be refused without touching the tenant - jti, contract, shape, path, product, environment -
 * and only then the transaction of {@link LifecycleApplyWriter}.
 *
 * <p>Shared with the entitlements receiver, on purpose: the {@code jti} store (a token spent on one
 * MasterAdmin contract route cannot be replayed on another), the product code, and the environment
 * this deployment answers for ({@code TMS_ENTITLEMENTS_ENVIRONMENT}) - one deployment is one
 * environment for every MasterAdmin channel.
 */
@Service
public class PlatformLifecycleService {

    /** Generous for a document of ten short fields; anything larger is not a lifecycle body. */
    public static final int MAX_RAW_BYTES = 16 * 1024;

    private static final ObjectMapper STRICT = JsonMapper.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .build();

    private final JtiGuard jti;
    private final LifecycleApplyWriter writer;
    private final LifecycleStore store;
    private final PlatformEntitlementsProperties environment;
    private final ReceiverProfile profile;

    public PlatformLifecycleService(JtiGuard jti, LifecycleApplyWriter writer, LifecycleStore store,
            PlatformEntitlementsProperties environment, ReceiverProfile profile) {
        this.jti = jti;
        this.writer = writer;
        this.store = store;
        this.environment = environment;
        this.profile = profile;
    }

    public Map<String, Object> apply(String tenantPath, byte[] body, String contractHeader, String correlationId,
            JtiGuard.Call call) {
        consume(call);

        if (contractHeader != null && !contractHeader.isBlank()
                && !LifecycleCommand.CONTRACT.equals(contractHeader.trim())) {
            throw new LifecycleRejection(Code.INVALID_LIFECYCLE, "This receiver speaks " + LifecycleCommand.CONTRACT);
        }
        if (body == null || body.length == 0 || body.length > MAX_RAW_BYTES) {
            throw new LifecycleRejection(Code.INVALID_LIFECYCLE, "The body is missing or too large");
        }
        JsonNode document;
        try {
            document = STRICT.readTree(new String(body, StandardCharsets.UTF_8));
        } catch (JacksonException notJson) {
            throw new LifecycleRejection(Code.INVALID_LIFECYCLE, "The body is not valid JSON");
        }

        LifecycleCommand command = LifecycleCommand.parse(document);
        Optional<UUID> path = LifecycleCommand.uuid(tenantPath);
        if (path.isEmpty() || !path.get().equals(command.controlPlaneTenantId())) {
            throw new LifecycleRejection(Code.INVALID_LIFECYCLE, "The tenant of the body differs from the path");
        }
        if (!profile.productCode().equals(command.productCode())) {
            throw new LifecycleRejection(Code.PRODUCT_MISMATCH, "This receiver is " + profile.productCode());
        }
        if (!command.environment().equals(environment.environment())) {
            throw new LifecycleRejection(Code.WRONG_ENVIRONMENT, environment.environment().isEmpty()
                    ? "This receiver has no environment configured"
                    : "This receiver is " + environment.environment());
        }

        LifecycleApplyWriter.Outcome outcome = writer.apply(command, profile.productCode(),
                new CallContext(correlationId, call.subject(), call.jti()));
        if (outcome instanceof LifecycleApplyWriter.Refused refused) {
            throw refused.rejection();
        }
        return ((LifecycleApplyWriter.Accepted) outcome).body();
    }

    /** What is applied here, never what was asked: MasterAdmin's proof that the state took effect. */
    @Transactional(readOnly = true)
    public Map<String, Object> applied(String tenantPath, JtiGuard.Call call) {
        consume(call);
        UUID tenant = LifecycleCommand.uuid(tenantPath).filter(id -> store.provisioned(id).isPresent())
                .orElseThrow(() -> new LifecycleRejection(Code.TENANT_NOT_PROVISIONED,
                        "No local provisioning for this tenant"));
        Optional<Applied> applied = store.applied(tenant);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("controlPlaneTenantId", tenant.toString());
        body.put("productCode", profile.productCode());
        body.put("appliedVersion", applied.map(Applied::version).orElse(null));
        body.put("state", applied.map(a -> a.state().name()).orElse(LifecycleState.ACTIVE.name()));
        body.put("reason", applied.map(Applied::reason).map(Enum::name).orElse(null));
        body.put("appliedAt", applied.map(a -> a.appliedAt().toString()).orElse(null));
        return body;
    }

    /** Spends the token's {@code jti}; a second use is {@code 401 JTI_REPLAYED}. */
    public void consume(JtiGuard.Call call) {
        try {
            jti.consume(call);
        } catch (EntitlementRejection replayed) {
            throw new LifecycleRejection(Code.JTI_REPLAYED, "jti already used");
        }
    }
}
