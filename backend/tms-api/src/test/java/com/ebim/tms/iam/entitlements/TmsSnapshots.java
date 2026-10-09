package com.ebim.tms.iam.entitlements;

import com.ebim.tms.iam.entitlements.domain.Jcs;
import java.util.UUID;
import java.util.function.Consumer;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Builds {@code ebim.entitlements/v1} snapshots for product {@code tms} exactly as MasterAdmin's
 * {@code buildSnapshot} does: every field present, idempotency key and checksum computed. Values are
 * illustrative; no price, amount or currency exists anywhere in the contract.
 */
public final class TmsSnapshots {

    private TmsSnapshots() {}

    public static ObjectNode snapshot(UUID tenant, long version, boolean appActive) {
        return snapshot(tenant, version, appActive, s -> { });
    }

    public static ObjectNode snapshot(UUID tenant, long version, boolean appActive, Consumer<ObjectNode> change) {
        ObjectNode s = ContractFixtures.JSON.createObjectNode();
        s.put("schema", "ebim.entitlements/v1");
        s.put("environment", "DEV");
        s.put("controlPlaneTenantId", tenant.toString());
        s.put("productCode", "tms");
        ObjectNode external = s.putObject("external");
        external.put("tenantId", "tms-ext");
        external.putNull("organizationId");
        external.putArray("companyIds");
        s.put("snapshotVersion", version);
        if (version == 1) {
            s.putNull("previousVersion");
        } else {
            s.put("previousVersion", version - 1);
        }
        s.put("effectiveAt", "2026-09-28T11:00:00Z");
        s.put("issuedAt", "2026-09-28T11:00:05Z");
        s.put("appActive", appActive);
        s.put("planCode", "tms-standard");
        s.putArray("capabilities");
        s.putArray("limits");
        s.putArray("allowances");
        ObjectNode credits = s.putObject("aiCredits");
        credits.putArray("weights");
        credits.put("weightsVersion", 0);
        s.put("correlationId", "00000000-0000-4ccc-8000-0000000c1201");
        s.put("idempotencyKey", "ma-ent-v1-" + Jcs.sha256Hex(tenant + ":tms:" + version));
        change.accept(s);
        s.put("checksum", Jcs.entitlementChecksum(s));
        return s;
    }

    /** A capability line, as MasterAdmin writes it. */
    public static void capability(ObjectNode snapshot, String code, boolean enabled) {
        ObjectNode line = ((ArrayNode) snapshot.get("capabilities")).addObject();
        line.put("code", code);
        line.put("enabled", enabled);
        line.putObject("scope").put("level", "TENANT");
        line.putArray("sources").add("PLAN");
    }

    /** A limit line, as MasterAdmin writes it. */
    public static void limit(ObjectNode snapshot, String code, long value, String enforcement) {
        ObjectNode line = ((ArrayNode) snapshot.get("limits")).addObject();
        line.put("code", code);
        line.put("value", value);
        line.put("unit", "vehicle");
        line.put("enforcement", enforcement);
        line.putObject("scope").put("level", "TENANT");
        line.putArray("sources").add("PLAN");
    }
}
