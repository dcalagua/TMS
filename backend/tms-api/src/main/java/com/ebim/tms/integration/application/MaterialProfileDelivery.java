package com.ebim.tms.integration.application;

import java.util.List;

/**
 * A PUT of one material profile: the code from the path, the body, and the optional fields the
 * body named. All three are fingerprinted, so the same key with another code, other content or
 * another set of named fields is a different delivery (409 {@code idempotency-key-reused}).
 *
 * @param fieldsSent sorted, so the fingerprint does not depend on the JSON's key order
 */
public record MaterialProfileDelivery(String materialCode, MaterialProfileV1 profile, List<String> fieldsSent) {

    public MaterialProfileDelivery {
        fieldsSent = List.copyOf(fieldsSent);
    }
}
