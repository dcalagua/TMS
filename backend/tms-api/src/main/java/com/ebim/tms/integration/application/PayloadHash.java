package com.ebim.tms.integration.application;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

/**
 * The fingerprint of an inbound payload, used to tell a genuine retry from a client that reused
 * an {@code Idempotency-Key} for a different object.
 *
 * <h2>Why the parsed object and not the raw bytes</h2>
 *
 * <p>Hashing the request body as it arrived would make the fingerprint depend on whitespace, key
 * order, and fields TMS ignores. A partner that pretty-prints a retry, or upgrades a client
 * library that reorders JSON keys, would then be told their key was reused with a different
 * payload - which is both false and infuriating.
 *
 * <p>So the hash is taken over the canonical re-serialisation of the deserialised request record.
 * Java records serialise in component declaration order, so the output is deterministic for a
 * given build, and it captures exactly what TMS understood the caller to have said. The trade is
 * explicit: two bodies that differ only in a field TMS does not read hash identically, and that
 * is the correct answer - they ask for the same thing.
 */
public final class PayloadHash {

    private PayloadHash() {}

    /** Lower-case hex SHA-256, the shape {@code ck_integration_request_payload_hash_shape} requires. */
    public static String of(ObjectMapper mapper, Object payload) {
        try {
            byte[] canonical = mapper.writeValueAsBytes(payload);
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(canonical));
        } catch (JacksonException notSerialisable) {
            // Every payload reaching this point was produced by Jackson from the request body, so
            // it can be written back. A failure here is a wiring bug, not a caller error.
            throw new IllegalStateException("an inbound payload could not be re-serialised for hashing",
                    notSerialisable);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is not available", impossible);
        }
    }
}
