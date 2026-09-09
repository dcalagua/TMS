package com.ebim.tms.shared.security;

import static org.assertj.core.api.Assertions.assertThat;

import com.ebim.tms.shared.security.TenantRuntimeRoleCheck.Outcome;
import com.ebim.tms.shared.security.TenantRuntimeRoleCheck.Roles;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * What the startup check concludes from the two facts it reads.
 *
 * <p>Unit tests on purpose, and for the same reason {@code SupabaseJwtDecodersTest} is one: the
 * interesting case - a deployment whose credential cannot enter {@code tms_app} - is precisely
 * the one no local run reproduces, because a Testcontainers superuser may set any role. If the
 * conclusion were only reachable by booting against a misconfigured project, it would be a
 * conclusion nobody ever checks.
 */
class TenantRuntimeRoleCheckTest {

    private static final Roles OWNER = new Roles("tms_owner", "tms_owner");

    @Test
    @DisplayName("an owner connection that can enter the runtime role is the expected posture")
    void ownerThatCanEnterTheRuntimeRole() {
        assertThat(TenantRuntimeRoleCheck.diagnose(OWNER, true)).isEqualTo(Outcome.RLS_IN_FORCE);
    }

    @Test
    @DisplayName("an owner connection that cannot enter the runtime role is reported, not assumed away")
    void ownerThatCannotEnterTheRuntimeRole() {
        assertThat(TenantRuntimeRoleCheck.diagnose(OWNER, false))
                .isEqualTo(Outcome.RUNTIME_ROLE_UNREACHABLE);
    }

    @Test
    @DisplayName("connecting as the runtime role itself is a distinct finding, not a success")
    void connectedAsTheRuntimeRole() {
        // SET ROLE trivially succeeds when you are already that role, so the probe alone would
        // read as healthy. It is not: Flyway would not be running as the schema owner and the
        // per-request downgrade ADR-005 relies on would have become a no-op.
        assertThat(TenantRuntimeRoleCheck.diagnose(new Roles("tms_app", "tms_app"), true))
                .isEqualTo(Outcome.CONNECTED_AS_RUNTIME_ROLE);
    }

    @Test
    @DisplayName("PostgreSQL folds unquoted identifiers, so the role name is matched case-insensitively")
    void runtimeRoleIsRecognisedWhateverTheCase() {
        assertThat(new Roles("TMS_APP", "TMS_APP").isRuntimeRole()).isTrue();
        assertThat(new Roles("tms_owner", "tms_app").isRuntimeRole()).isFalse();
    }

    @Test
    @DisplayName("an unreadable session_user does not throw and does not read as the runtime role")
    void missingSessionUserIsNotTheRuntimeRole() {
        assertThat(new Roles(null, null).isRuntimeRole()).isFalse();
        assertThat(TenantRuntimeRoleCheck.diagnose(new Roles(null, null), false))
                .isEqualTo(Outcome.RUNTIME_ROLE_UNREACHABLE);
    }
}
