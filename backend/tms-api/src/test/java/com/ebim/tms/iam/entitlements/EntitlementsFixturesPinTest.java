package com.ebim.tms.iam.entitlements;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The {@code ebim.entitlements/v1} contract TMS implements is EXACTLY the one MasterAdmin published
 * in its phase 08 (CCP plan section 3.2, test {@code entitlements-fixtures-pin}).
 */
class EntitlementsFixturesPinTest {

    /** SHA-256 of {@code CHECKSUMS.sha256}, as published in MasterAdmin's phase 08 evidence. */
    static final String FIX_ENT_V1_SHA256 = "7aab413a145b0e9a165c5f02be4bfda17f886a4b46bc2a557eec3eeaed1f65d5";

    private static String sha256(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }

    @Test
    @DisplayName("CHECKSUMS.sha256 is the one MasterAdmin published (FIX-ENT-v1)")
    void theChecksumListIsThePublishedOne() throws Exception {
        assertThat(sha256(ContractFixtures.bytes("CHECKSUMS.sha256"))).isEqualTo(FIX_ENT_V1_SHA256);
    }

    @Test
    @DisplayName("every vendored file matches CHECKSUMS.sha256")
    void everyVendoredFileMatches() throws Exception {
        List<String> lines = new String(ContractFixtures.bytes("CHECKSUMS.sha256"), StandardCharsets.UTF_8)
                .lines().filter(line -> !line.isBlank()).toList();
        assertThat(lines).hasSize(20);
        for (String line : lines) {
            String[] parts = line.split("\\s+", 2);
            assertThat(sha256(ContractFixtures.bytes(parts[1].trim()))).as(parts[1]).isEqualTo(parts[0]);
        }
    }
}
