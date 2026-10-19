package com.ebim.tms.iam.lifecycle.application;

import com.ebim.tms.iam.lifecycle.application.LifecycleStore.Applied;
import com.ebim.tms.iam.lifecycle.application.LifecycleStore.CallContext;
import com.ebim.tms.iam.lifecycle.application.LifecycleStore.ProvisionedTenant;
import com.ebim.tms.iam.lifecycle.domain.LifecycleCommand;
import com.ebim.tms.iam.lifecycle.domain.LifecycleRejection;
import com.ebim.tms.iam.lifecycle.domain.LifecycleRejection.Code;
import java.time.Clock;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The version rules of {@code lifecycle.v1} section 4, in ONE transaction under a per-tenant lock.
 *
 * <pre>
 *   no row, or version &gt; applied            apply           200 replayed=false
 *   version == applied, same state + reason   nothing         200 replayed=true
 *   version == applied, other state/reason    refuse          409 VERSION_CONFLICT
 *   version &lt; applied                        refuse          409 STALE_LIFECYCLE
 * </pre>
 *
 * <p>Refusals are <b>returned</b>, not thrown, like {@code EntitlementApplyWriter}: the audit row of
 * the attempt must be committed, and an exception would roll it back. The state is applied on receipt;
 * {@code effectiveAt} is stored for the record and decides nothing.
 */
@Service
public class LifecycleApplyWriter {

    private final LifecycleStore store;
    private final Clock clock;

    public LifecycleApplyWriter(LifecycleStore store, Clock clock) {
        this.store = store;
        this.clock = clock;
    }

    public sealed interface Outcome permits Accepted, Refused {}

    public record Accepted(Map<String, Object> body) implements Outcome {}

    public record Refused(LifecycleRejection rejection) implements Outcome {}

    @Transactional
    public Outcome apply(LifecycleCommand command, String productCode, CallContext caller) {
        ProvisionedTenant tenant = store.provisioned(command.controlPlaneTenantId())
                .orElseThrow(() -> new LifecycleRejection(Code.TENANT_NOT_PROVISIONED,
                        "No local provisioning for this tenant"));
        store.lock(tenant.controlPlaneTenantId());

        Optional<Applied> current = store.applied(tenant.controlPlaneTenantId());
        if (current.isPresent()) {
            Applied applied = current.get();
            if (command.version() < applied.version()) {
                return refuse(command, applied, Code.STALE_LIFECYCLE, "Older than the applied version", caller);
            }
            if (command.version() == applied.version()) {
                if (command.state() != applied.state() || !Objects.equals(command.reason(), applied.reason())) {
                    return refuse(command, applied, Code.VERSION_CONFLICT, "Same version with another state",
                            caller);
                }
                store.audit(tenant.controlPlaneTenantId(), command.version(), command.state(), command.reason(),
                        "REPLAYED", applied.version(), command.idempotencyKey(), caller);
                return new Accepted(body(applied, productCode, true));
            }
        }

        Applied applied = new Applied(tenant.controlPlaneTenantId(), tenant.organizationId(), command.state(),
                command.reason(), command.version(), clock.instant().truncatedTo(ChronoUnit.MICROS),
                command.effectiveAt(), command.idempotencyKey());
        store.save(applied, caller);
        store.audit(tenant.controlPlaneTenantId(), command.version(), command.state(), command.reason(), "APPLIED",
                applied.version(), command.idempotencyKey(), caller);
        return new Accepted(body(applied, productCode, false));
    }

    private Refused refuse(LifecycleCommand command, Applied applied, Code code, String message, CallContext caller) {
        store.audit(applied.controlPlaneTenantId(), command.version(), command.state(), command.reason(), code.name(),
                applied.version(), command.idempotencyKey(), caller);
        return new Refused(new LifecycleRejection(code, message, applied.version()));
    }

    static Map<String, Object> body(Applied applied, String productCode, boolean replayed) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("controlPlaneTenantId", applied.controlPlaneTenantId().toString());
        body.put("productCode", productCode);
        body.put("appliedVersion", applied.version());
        body.put("state", applied.state().name());
        body.put("reason", applied.reason() == null ? null : applied.reason().name());
        body.put("appliedAt", applied.appliedAt().toString());
        body.put("replayed", replayed);
        return body;
    }
}
