package com.ebim.tms.platform.application;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

/**
 * Lower-case hex SHA-256 over the canonical re-serialisation of a provisioning request - the shape
 * {@code ck_platform_provisioning_payload_hash_shape} requires.
 *
 * <p>Hashing the re-serialised record rather than the raw bytes is deliberate: two requests that
 * differ only in whitespace, key order or an unknown field are the same request, and a caller that
 * retries through a proxy which reformats JSON must not be told its payload changed. What the hash
 * covers is exactly what TMS read.
 *
 * <p>The same idea as {@code integration.application.PayloadHash}, written here rather than shared:
 * the platform module does not depend on the integration module, and a helper is not worth breaking
 * that boundary for.
 */
public final class ProvisioningPayloadHash {

    private ProvisioningPayloadHash() {
    }

    public static String of(ObjectMapper mapper, Object payload) {
        try {
            byte[] canonical = mapper.writeValueAsBytes(payload);
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(canonical));
        } catch (JacksonException notSerialisable) {
            throw new IllegalStateException("a provisioning request could not be re-serialised for hashing",
                    notSerialisable);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is not available", impossible);
        }
    }
}
