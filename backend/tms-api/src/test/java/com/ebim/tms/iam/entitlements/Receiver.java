package com.ebim.tms.iam.entitlements;

import com.ebim.tms.iam.entitlements.application.ApplyEntitlementSnapshotService;
import com.ebim.tms.iam.entitlements.application.CommercialEntitlementService;
import com.ebim.tms.iam.entitlements.application.EntitlementApplyWriter;
import com.ebim.tms.iam.entitlements.application.EntitlementQueryService;
import com.ebim.tms.iam.entitlements.application.JtiGuard;
import com.ebim.tms.iam.entitlements.application.PlatformEntitlementsProperties;
import com.ebim.tms.iam.entitlements.application.ReceiverProfile;
import com.ebim.tms.iam.entitlements.domain.EnforcementMode;
import com.ebim.tms.iam.entitlements.domain.EntitlementRejection;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import tools.jackson.databind.JsonNode;

/**
 * The REAL TMS receiver (production services) over an in-memory store. No database, no network and
 * no Spring: what is left is the semantics of the contract.
 */
public final class Receiver {

    public static final Instant NOW = Instant.parse("2026-09-28T12:00:00Z");

    public final InMemoryEntitlementStore store;
    public final ApplyEntitlementSnapshotService put;
    public final EntitlementQueryService queries;
    public final CommercialEntitlementService commercial;
    private final AtomicInteger jtis = new AtomicInteger();

    public record Response(int status, Map<String, Object> body) {}

    public Receiver(ReceiverProfile profile, String environment, EnforcementMode productMode) {
        store = new InMemoryEntitlementStore(productMode);
        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
        JtiGuard jti = new JtiGuard(store);
        put = new ApplyEntitlementSnapshotService(jti, new EntitlementApplyWriter(store, profile, clock),
                new PlatformEntitlementsProperties(environment), profile);
        queries = new EntitlementQueryService(jti, store, profile);
        commercial = new CommercialEntitlementService(store, profile);
    }

    public static Receiver tms(EnforcementMode productMode) {
        return new Receiver(ReceiverProfile.tms(), "DEV", productMode);
    }

    public JtiGuard.Call token() {
        return new JtiGuard.Call("masteradmin.ebim", "masteradmin-provisioning", "jti-" + jtis.incrementAndGet(),
                NOW.plusSeconds(300));
    }

    public Response put(String tenantPath, JsonNode snapshot) {
        return put(tenantPath, snapshot.toString().getBytes(StandardCharsets.UTF_8), token());
    }

    public Response put(String tenantPath, byte[] body, JtiGuard.Call call) {
        try {
            return new Response(200, put.apply(tenantPath, body, "entitlements.v1", "corr-test", call));
        } catch (EntitlementRejection e) {
            return error(e);
        }
    }

    public Response get(String tenantPath) {
        try {
            return new Response(200, queries.applied(tenantPath, token()));
        } catch (EntitlementRejection e) {
            return error(e);
        }
    }

    private static Response error(EntitlementRejection e) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("error", e.code().name());
        body.put("message", e.getMessage());
        if (e.appliedVersion() != null) {
            body.put("appliedVersion", e.appliedVersion());
        }
        return new Response(e.code().status(), body);
    }
}
