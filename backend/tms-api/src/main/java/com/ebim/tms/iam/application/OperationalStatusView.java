package com.ebim.tms.iam.application;

import java.time.Instant;

/**
 * The operational state of a company's organization as EBIM MasterAdmin last set it
 * ({@code lifecycle.v1}, ADR-021), for the UI's "Cuenta suspendida" banner.
 *
 * <p>UX input only: the server refuses the writes of a suspended organization whatever the screen
 * shows ({@code TenantSuspensionFilter}).
 *
 * @param state     {@code ACTIVE} or {@code SUSPENDED}
 * @param reason    {@code NON_PAYMENT}, {@code MANUAL} or {@code TERMINATED} when suspended, else null
 * @param appliedAt when TMS applied the state; null when MasterAdmin never sent one
 */
public record OperationalStatusView(String state, String reason, Instant appliedAt) {

    public static final OperationalStatusView ACTIVE = new OperationalStatusView("ACTIVE", null, null);
}
