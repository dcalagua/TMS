package com.ebim.tms.iam.entitlements;

import static org.assertj.core.api.Assertions.assertThat;

import com.ebim.tms.iam.entitlements.domain.CommercialAccess;
import com.ebim.tms.iam.entitlements.domain.CommercialAccess.Reason;
import com.ebim.tms.iam.entitlements.domain.EnforcementMode;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** The whole decision table of commercial access, as a pure function of three local facts. */
class CommercialAccessTest {

    @ParameterizedTest(name = "under CP={0}, {1}, appActive={2} -> allowed={3} ({4})")
    @CsvSource(nullValues = "none", value = {
        // Organizations MasterAdmin never provisioned are outside its authority, whatever the mode.
        "false, PRIMARY,   none,  true,  NOT_UNDER_CONTROL_PLANE",
        "false, DUAL_READ, false, true,  NOT_UNDER_CONTROL_PLANE",
        // LEGACY and SHADOW decide nothing: even appActive=false only becomes a recorded difference.
        "true,  LEGACY,    false, true,  NOT_ENFORCED",
        "true,  SHADOW,    false, true,  NOT_ENFORCED",
        "true,  SHADOW,    none,  true,  NOT_ENFORCED",
        // DUAL_READ: the snapshot decides; without one legacy does (and it is logged).
        "true,  DUAL_READ, true,  true,  APP_ACTIVE",
        "true,  DUAL_READ, false, false, APP_INACTIVE",
        "true,  DUAL_READ, none,  true,  NO_SNAPSHOT_LEGACY_FALLBACK",
        // PRIMARY: only the snapshot; without one nothing is granted.
        "true,  PRIMARY,   true,  true,  APP_ACTIVE",
        "true,  PRIMARY,   false, false, APP_INACTIVE",
        "true,  PRIMARY,   none,  false, NO_SNAPSHOT",
    })
    void decisionTable(boolean underControlPlane, EnforcementMode mode, Boolean appActive, boolean allowed,
            Reason reason) {
        CommercialAccess access = CommercialAccess.decide(underControlPlane, mode, appActive);

        assertThat(access.allowed()).isEqualTo(allowed);
        assertThat(access.reason()).isEqualTo(reason);
    }
}
