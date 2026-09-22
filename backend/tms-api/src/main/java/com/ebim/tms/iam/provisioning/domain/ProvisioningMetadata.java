package com.ebim.tms.iam.provisioning.domain;

/**
 * The transport facts of one create call that are recorded but never hashed: which key, which
 * correlation id, and who signed the token.
 */
public record ProvisioningMetadata(
        String idempotencyKey,
        String correlationId,
        String m2mSubject,
        String m2mJti,
        String actorId,
        String actorRole) {

    /** The same facts without the key - used when the key itself is what was rejected. */
    public ProvisioningMetadata withoutKey() {
        return new ProvisioningMetadata(null, correlationId, m2mSubject, m2mJti, actorId, actorRole);
    }
}
