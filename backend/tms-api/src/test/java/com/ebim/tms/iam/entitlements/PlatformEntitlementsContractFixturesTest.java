package com.ebim.tms.iam.entitlements;

import static org.assertj.core.api.Assertions.assertThat;

import com.ebim.tms.iam.entitlements.application.ReceiverProfile;
import com.ebim.tms.iam.entitlements.domain.EnforcementMode;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;
import tools.jackson.databind.JsonNode;

/**
 * FIX-ENT-v1: the 13 golden fixtures against the REAL TMS services (CCP plan section 10.2.4). Each
 * fixture runs on an empty receiver with a fresh {@code jti} per request; the configuration comes
 * from the fixture's {@code receiver} block (synthetic product {@code fixture}).
 */
class PlatformEntitlementsContractFixturesTest {

    private static final JsonNode PUTS = ContractFixtures.json("expected/put-responses.json").get("responses");
    private static final JsonNode GETS = ContractFixtures.json("expected/get-applied.json").get("responses");

    @TestFactory
    @DisplayName("the 13 FIX-ENT-v1 fixtures produce exactly the expected responses")
    Stream<DynamicTest> fixtures() {
        List<String> ids = new ArrayList<>(PUTS.propertyNames());
        assertThat(ids).hasSize(13);
        return ids.stream().map(id -> DynamicTest.dynamicTest(id, () -> run(id)));
    }

    private static void run(String id) {
        JsonNode fixture = ContractFixtures.json("fixtures/" + id + ".json");
        JsonNode config = fixture.get("receiver");
        Set<String> known = new HashSet<>();
        config.get("knownCapabilities").forEach(code -> known.add(code.stringValue()));
        Receiver receiver = new Receiver(new ReceiverProfile(config.get("productCode").stringValue(), known),
                config.get("environment").stringValue(),
                EnforcementMode.valueOf(config.get("enforcementMode").stringValue()));
        config.get("provisionedTenants").forEach(t -> receiver.store.provision(UUID.fromString(t.stringValue())));

        String last = null;
        int i = 0;
        for (JsonNode step : fixture.get("steps")) {
            last = step.get("tenantPath").stringValue();
            Receiver.Response response = receiver.put(last, step.get("snapshot"));
            compare(id + " step " + step.get("step").asInt(), PUTS.get(id).get(i++), response);
        }
        compare(id + " GET", GETS.get(id), receiver.get(last));
    }

    static void compare(String what, JsonNode expected, Receiver.Response actual) {
        assertThat(actual.status()).as(what + " status " + actual.body()).isEqualTo(expected.get("status").asInt());
        JsonNode body = expected.get("body");
        JsonNode got = ContractFixtures.JSON.valueToTree(actual.body());
        if (body.has("error")) {
            assertThat(got.get("error").stringValue()).as(what).isEqualTo(body.get("error").stringValue());
            if (body.has("appliedVersion")) {
                assertThat(got.get("appliedVersion").toString()).as(what)
                        .isEqualTo(body.get("appliedVersion").toString());
            }
            assertThat(actual.body()).as(what + ": an error never echoes the snapshot")
                    .containsOnlyKeys(body.has("appliedVersion")
                            ? List.of("error", "message", "appliedVersion") : List.of("error", "message"));
            return;
        }
        List<String> keys = new ArrayList<>(body.propertyNames());
        assertThat(actual.body()).as(what).containsOnlyKeys(keys);
        for (String key : keys) {
            if (body.get(key).isString() && "$iso8601".equals(body.get(key).stringValue())) {
                assertThat(got.get(key).stringValue()).as(what + "." + key)
                        .matches("^\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}(\\.\\d+)?Z$");
            } else {
                assertThat(got.get(key).toString()).as(what + "." + key).isEqualTo(body.get(key).toString());
            }
        }
    }
}
