package com.ebim.tms.platform.domain;

/**
 * The outcome of one provisioning call, as {@code tms.platform_provisioning.status} records it.
 *
 * <p>There is deliberately no {@code IN_PROGRESS}. The whole provisioning runs in one transaction,
 * so a row exists only once its outcome is known: a crash mid-flight leaves no row at all rather
 * than one that claims to be still running and never stops. A failed attempt keeps its row so the
 * same idempotency key can be retried - the row is updated, never duplicated.
 *
 * <p>Mirrored by {@code ck_platform_provisioning_status} (V51).
 */
public enum ProvisioningStatus {
    SUCCEEDED,
    FAILED
}
