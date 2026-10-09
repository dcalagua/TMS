package com.ebim.tms.iam.entitlements;

import static org.assertj.core.api.Assertions.assertThat;

import com.ebim.tms.iam.entitlements.domain.Jcs;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * RFC 8785 in Java: every vector of FIX-ENT-v1 and the checksum of every fixture snapshot (CCP plan
 * section 3.2; P-04: own implementation, no new dependency).
 */
class JcsTest {

    @Test
    @DisplayName("every JCS vector of the contract: canonical text and SHA-256")
    void vectors() {
        JsonNode vectors = ContractFixtures.json("jcs-vectors.json").get("vectors");
        assertThat(vectors.size()).isGreaterThanOrEqualTo(11);
        for (JsonNode vector : vectors) {
            String id = vector.get("id").stringValue();
            String canonical = Jcs.canonicalize(ContractFixtures.JSON.readTree(vector.get("input").stringValue()));
            assertThat(canonical).as(id).isEqualTo(vector.get("canonical").stringValue());
            assertThat(Jcs.sha256Hex(canonical)).as(id).isEqualTo(vector.get("sha256").stringValue());
        }
    }

    @Test
    @DisplayName("each fixture snapshot's checksum recomputes identically (except 10, wrong on purpose)")
    void fixtureChecksums() {
        Stream.of("01-baseline-only", "02-plan-grants", "03-plan-plus-addon", "04-addon-removed",
                        "05-limit-update", "06-app-inactive", "07-unknown-capability", "08-stale",
                        "09-conflict", "11-wrong-environment", "13-tenant-not-provisioned")
                .forEach(id -> {
                    for (JsonNode step : ContractFixtures.json("fixtures/" + id + ".json").get("steps")) {
                        ObjectNode snapshot = (ObjectNode) step.get("snapshot");
                        assertThat(Jcs.entitlementChecksum(snapshot)).as(id)
                                .isEqualTo(snapshot.get("checksum").stringValue());
                    }
                });
        JsonNode bad = ContractFixtures.json("fixtures/10-bad-checksum.json").get("steps").get(0).get("snapshot");
        assertThat(Jcs.entitlementChecksum((ObjectNode) bad)).isNotEqualTo(bad.get("checksum").stringValue());
    }

    @Test
    @DisplayName("numbers are written as ECMAScript Number.prototype.toString")
    void numbers() {
        assertThat(Jcs.canonicalize(ContractFixtures.JSON.readTree(
                        "[-0, 1e21, 1e-7, 0.000001, 123456789012345680000, 1.5, 100]")))
                .isEqualTo("[0,1e+21,1e-7,0.000001,123456789012345680000,1.5,100]");
    }
}
