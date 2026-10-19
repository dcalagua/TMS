package com.ebim.tms.iam.application;

import com.ebim.tms.iam.lifecycle.application.TenantLifecycleService;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/** Projects the lifecycle states of a caller's organizations into {@link OperationalStatusView}s. */
final class OperationalStatuses {

    private OperationalStatuses() {}

    /** An organization absent from the result is ACTIVE; so is every one when lifecycle is not wired. */
    static Map<UUID, OperationalStatusView> of(TenantLifecycleService lifecycle, Set<UUID> organizationIds) {
        if (lifecycle == null) {
            return Map.of();
        }
        return lifecycle.statusOf(organizationIds).entrySet().stream()
                .collect(Collectors.toMap(Map.Entry::getKey, entry -> new OperationalStatusView(
                        entry.getValue().state().name(),
                        entry.getValue().reason() == null ? null : entry.getValue().reason().name(),
                        entry.getValue().appliedAt())));
    }
}
