package com.ebim.tms.iam.entitlements;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * FIX-ENT-v1 as published by EBIM MasterAdmin ({@code contracts/entitlements/v1/}), vendored under
 * {@code src/test/resources/contracts/entitlements-v1/}.
 *
 * <p><b>Never edited here.</b> A correction is a v1.1 published by MasterAdmin; the pin in
 * {@link EntitlementsFixturesPinTest} fails if a vendored byte changes.
 */
public final class ContractFixtures {

    public static final ObjectMapper JSON = JsonMapper.builder().build();

    private ContractFixtures() {}

    public static Path dir() {
        try {
            return Path.of(Objects.requireNonNull(
                    ContractFixtures.class.getResource("/contracts/entitlements-v1/CHECKSUMS.sha256"),
                    "the vendored contract is missing").toURI()).getParent();
        } catch (URISyntaxException e) {
            throw new IllegalStateException(e);
        }
    }

    public static byte[] bytes(String relative) {
        try {
            return Files.readAllBytes(dir().resolve(relative));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public static JsonNode json(String relative) {
        return JSON.readTree(bytes(relative));
    }
}
