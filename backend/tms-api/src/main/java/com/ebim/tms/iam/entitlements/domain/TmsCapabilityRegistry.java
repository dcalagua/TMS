package com.ebim.tms.iam.entitlements.domain;

import java.util.Set;

/**
 * The commercial capabilities TMS enforces - the Java side of
 * {@code docs/platform-provisioning/ENTITLEMENTS_MANIFEST.json}.
 *
 * <p><b>No sellable capability, limit or allowance is registered.</b> TMS has no licensing today
 * ({@code MASTERADMIN_GENERIC_CONTRACT.md}, "plan.code UNSUPPORTED"), and none of the
 * {@link com.ebim.tms.shared.security.Permission}s is a paid capability: they are RBAC and stay RBAC.
 * The only commercial fact TMS acts on is {@code appActive}. A sellable is added here, in the
 * manifest and in MasterAdmin's registry together - never by turning a permission into one.
 *
 * <p>{@code tms.core} is the baseline: every provisioned TMS tenant has it, MasterAdmin never lists
 * it in a snapshot, and it exists so the manifest names what the product is.
 */
public final class TmsCapabilityRegistry {

    public static final String PRODUCT_CODE = "tms";
    public static final String CORE = "tms.core";

    /** Sellable FEATURE/AI_FEATURE codes. Empty on purpose; see the class comment. */
    public static final Set<String> SELLABLE = Set.of();

    /** LIMIT codes TMS enforces. Empty: no commercial resource dimension is decided (D-05). */
    public static final Set<String> LIMITS = Set.of();

    private TmsCapabilityRegistry() {}

    /** Every code the receiver recognises; anything else is stored, reported and never granted. */
    public static Set<String> known() {
        return Set.of(CORE);
    }
}
