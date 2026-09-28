package com.ebim.tms.iam.entitlements;

import static org.assertj.core.api.Assertions.assertThat;

import com.ebim.tms.iam.entitlements.application.EntitlementQueryService;
import com.ebim.tms.iam.entitlements.domain.TmsCapabilityRegistry;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/**
 * The capability manifest TMS publishes to MasterAdmin ({@code ebim.capabilities/v1}).
 *
 * <p>Held to three rules: the served copy is the documented one byte for byte; it names exactly what
 * the Java registry knows; and it declares no sellable at all - TMS's RBAC permissions are not paid
 * capabilities, and the manifest is where that would silently change.
 */
class EntitlementsManifestTest {

    static Path repositoryRoot() {
        Path candidate = Path.of("").toAbsolutePath();
        while (candidate != null && !Files.exists(candidate.resolve("CLAUDE.md"))) {
            candidate = candidate.getParent();
        }
        if (candidate == null) {
            throw new IllegalStateException("repository root (the directory holding CLAUDE.md) was not found");
        }
        return candidate;
    }

    private static byte[] served() throws IOException {
        try (InputStream in = EntitlementQueryService.class.getResourceAsStream(EntitlementQueryService.MANIFEST_RESOURCE)) {
            assertThat(in).as("classpath manifest").isNotNull();
            return in.readAllBytes();
        }
    }

    private static byte[] documented() throws IOException {
        return Files.readAllBytes(repositoryRoot().resolve("docs/platform-provisioning/ENTITLEMENTS_MANIFEST.json"));
    }

    @Test
    @DisplayName("the served manifest is docs/platform-provisioning/ENTITLEMENTS_MANIFEST.json byte for byte")
    void servedIsDocumented() throws IOException {
        assertThat(served()).isEqualTo(documented());
    }

    @Test
    @DisplayName("it is an ebim.capabilities/v1 document for tms naming exactly the registry's codes")
    void matchesTheRegistry() throws IOException {
        JsonNode manifest = ContractFixtures.JSON.readTree(served());
        assertThat(manifest.get("schema").stringValue()).isEqualTo("ebim.capabilities/v1");
        assertThat(manifest.get("productCode").stringValue()).isEqualTo(TmsCapabilityRegistry.PRODUCT_CODE);
        assertThat(new ArrayList<>(manifest.propertyNames()))
                .containsExactlyInAnyOrder("schema", "productCode", "manifestVersion", "capabilities");
        List<String> codes = new ArrayList<>();
        manifest.get("capabilities").forEach(c -> codes.add(c.get("code").stringValue()));
        assertThat(codes).containsExactlyInAnyOrderElementsOf(TmsCapabilityRegistry.known());
    }

    @Test
    @DisplayName("no sellable, limit or allowance is declared: only the tms.core baseline")
    void declaresNoSellable() throws IOException {
        JsonNode manifest = ContractFixtures.JSON.readTree(served());
        for (JsonNode capability : manifest.get("capabilities")) {
            assertThat(capability.get("isBaseline").booleanValue()).as(capability.get("code").stringValue()).isTrue();
            assertThat(capability.get("kind").stringValue()).isEqualTo("FEATURE");
        }
        assertThat(TmsCapabilityRegistry.SELLABLE).isEmpty();
        assertThat(TmsCapabilityRegistry.LIMITS).isEmpty();
    }

    @Test
    @DisplayName("the manifest carries no price, amount or currency")
    void noCommercialValues() throws IOException {
        String text = new String(served(), java.nio.charset.StandardCharsets.UTF_8).toLowerCase(Locale.ROOT);
        for (String forbidden : List.of("price", "amount", "currency", "cost", "\"secret", "token")) {
            assertThat(text).as(forbidden).doesNotContain(forbidden);
        }
    }
}
