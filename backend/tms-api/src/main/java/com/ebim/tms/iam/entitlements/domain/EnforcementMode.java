package com.ebim.tms.iam.entitlements.domain;

/**
 * The entitlements axis of CCP spec section 15.1, on the TMS side.
 *
 * <ul>
 *   <li>{@code LEGACY}: TMS behaves as before the control plane - every organization operates.</li>
 *   <li>{@code SHADOW}: the snapshot is stored and compared; it <b>decides nothing</b>.</li>
 *   <li>{@code DUAL_READ}: the snapshot decides; with none applied TMS falls back to legacy and warns.</li>
 *   <li>{@code PRIMARY}: only the snapshot decides; with none applied nothing commercial is granted.</li>
 * </ul>
 *
 * <p>Moved one step at a time, forwards or back; the database enforces it (V52).
 */
public enum EnforcementMode {
    LEGACY, SHADOW, DUAL_READ, PRIMARY;

    /** Whether the local snapshot is what decides. */
    public boolean snapshotDecides() {
        return this == DUAL_READ || this == PRIMARY;
    }
}
