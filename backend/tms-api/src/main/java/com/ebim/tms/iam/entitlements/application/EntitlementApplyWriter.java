package com.ebim.tms.iam.entitlements.application;

import com.ebim.tms.iam.entitlements.application.EntitlementStore.Applied;
import com.ebim.tms.iam.entitlements.application.EntitlementStore.CallContext;
import com.ebim.tms.iam.entitlements.application.EntitlementStore.ProvisionedTenant;
import com.ebim.tms.iam.entitlements.application.EntitlementStore.ShadowDiff;
import com.ebim.tms.iam.entitlements.domain.AppliedStatus;
import com.ebim.tms.iam.entitlements.domain.EnforcementMode;
import com.ebim.tms.iam.entitlements.domain.EntitlementRejection;
import com.ebim.tms.iam.entitlements.domain.EntitlementSnapshot;
import java.time.Clock;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeSet;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Steps 3-7 of CCP spec section 8.2, in ONE transaction.
 *
 * <p>Version refusals ({@code STALE_SNAPSHOT}, {@code VERSION_CONFLICT}) are <b>returned</b>, not
 * thrown: the audit row of the attempt must be written, and an exception would roll it back with
 * everything else.
 *
 * <p>There is nothing to materialise. TMS registers no sellable capability, so the durable snapshot
 * IS the local state the enforcement reads; no legacy table is rewritten and there is nothing to
 * restore when a mode steps back.
 */
@Service
public class EntitlementApplyWriter {

    private final EntitlementStore store;
    private final ReceiverProfile profile;
    private final Clock clock;

    public EntitlementApplyWriter(EntitlementStore store, ReceiverProfile profile, Clock clock) {
        this.store = store;
        this.profile = profile;
        this.clock = clock;
    }

    public sealed interface Outcome permits Accepted, Refused {}

    public record Accepted(Map<String, Object> body) implements Outcome {}

    public record Refused(EntitlementRejection rejection) implements Outcome {}

    @Transactional
    public Outcome apply(EntitlementSnapshot snapshot, CallContext caller) {
        ProvisionedTenant tenant = store.provisioned(snapshot.controlPlaneTenantId())
                .orElseThrow(() -> new EntitlementRejection(EntitlementRejection.Code.TENANT_NOT_PROVISIONED,
                        "No local provisioning for this tenant"));
        store.lock(tenant.controlPlaneTenantId());

        Optional<Applied> current = store.applied(tenant.controlPlaneTenantId());
        if (current.isPresent()) {
            Applied applied = current.get();
            if (snapshot.snapshotVersion() < applied.version()) {
                return refuse(snapshot, applied, EntitlementRejection.Code.STALE_SNAPSHOT,
                        "Older than the applied version", caller);
            }
            if (snapshot.snapshotVersion() == applied.version()) {
                if (!snapshot.checksum().equals(applied.checksum())) {
                    return refuse(snapshot, applied, EntitlementRejection.Code.VERSION_CONFLICT,
                            "Same version with different content", caller);
                }
                store.audit(tenant.controlPlaneTenantId(), applied.version(), applied.checksum(), "REPLAYED",
                        caller, Map.of());
                return new Accepted(body(applied, true));
            }
        }

        List<String> unknown = List.copyOf(new TreeSet<>(snapshot.allCodes().stream()
                .filter(code -> !profile.knownCodes().contains(code)).toList()));
        AppliedStatus status = unknown.isEmpty() ? AppliedStatus.APPLIED : AppliedStatus.APPLIED_WITH_WARNINGS;
        Applied applied = new Applied(tenant.controlPlaneTenantId(), tenant.organizationId(), snapshot.productCode(),
                snapshot.snapshotVersion(), snapshot.checksum(), status, snapshot.appActive(), unknown,
                clock.instant().truncatedTo(ChronoUnit.MICROS), snapshot.raw());
        store.save(applied, caller);
        store.audit(tenant.controlPlaneTenantId(), applied.version(), applied.checksum(), status.name(), caller,
                Map.of("unknownCapabilities", unknown, "appActive", snapshot.appActive()));

        EnforcementMode mode = store.mode(tenant.controlPlaneTenantId());
        if (mode != EnforcementMode.PRIMARY && mode != EnforcementMode.LEGACY && !snapshot.appActive()) {
            // Legacy TMS lets every organization operate; the snapshot says this one must not. While
            // the snapshot does not decide alone, the disagreement is recorded for the cut-over review.
            store.shadowDiff(new ShadowDiff(tenant.controlPlaneTenantId(), tenant.organizationId(), "APP_ACTIVE",
                    mode, true, false, snapshot.snapshotVersion()));
        }
        return new Accepted(body(applied, false));
    }

    private Refused refuse(EntitlementSnapshot snapshot, Applied applied, EntitlementRejection.Code code,
            String message, CallContext caller) {
        store.audit(applied.controlPlaneTenantId(), snapshot.snapshotVersion(), snapshot.checksum(), code.name(),
                caller, Map.of("appliedVersion", applied.version()));
        return new Refused(new EntitlementRejection(code, message, applied.version()));
    }

    static Map<String, Object> body(Applied applied, boolean replayed) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("appliedVersion", applied.version());
        body.put("appliedChecksum", applied.checksum());
        body.put("appliedAt", applied.appliedAt().toString());
        body.put("status", applied.status().name());
        body.put("unknownCapabilities", applied.unknownCapabilities());
        body.put("replayed", replayed);
        return body;
    }
}
