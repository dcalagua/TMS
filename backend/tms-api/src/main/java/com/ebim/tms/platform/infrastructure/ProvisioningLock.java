package com.ebim.tms.platform.infrastructure;

import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * Serialises provisioning of one control-plane tenant, so two callers arriving at the same instant
 * become two calls in a row instead of two half-built tenants.
 *
 * <p>A transaction-scoped PostgreSQL advisory lock, which suits this exactly: there is no row to
 * lock yet - that is the whole problem - and the lock is released by commit or rollback, so a
 * crashed request cannot leave the tenant locked out. The second caller waits, then finds the first
 * caller's committed work and replays it.
 *
 * <p>Unique constraints still do the real enforcing: {@code uq_platform_tenant_control_plane} would
 * refuse the duplicate even without this lock, but it would refuse it as a constraint violation
 * after the work had been done. The lock turns that into an ordinary replay, which is what the
 * caller expects and what keeps the error log free of noise that is not a problem.
 *
 * <p>The two-argument form namespaces the key, so a hash collision with some other feature's
 * advisory lock is not possible - only a collision between two control-plane tenant ids, which
 * would cost one of them a short wait and nothing else.
 */
@Component
public class ProvisioningLock {

    /** 'PL' - the platform-provisioning namespace for advisory locks. */
    private static final int NAMESPACE = 0x504C;

    private final JdbcClient jdbcClient;

    public ProvisioningLock(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    /** Must be called inside a transaction; the lock is held until that transaction ends. */
    public void onControlPlaneTenant(UUID controlPlaneTenantId) {
        jdbcClient.sql("SELECT pg_advisory_xact_lock(:namespace, :key)")
                .param("namespace", NAMESPACE)
                .param("key", controlPlaneTenantId.hashCode())
                .query(Object.class)
                .optional();
    }
}
