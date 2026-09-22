package com.ebim.tms.iam.provisioning.security;

/**
 * Who called the provisioning surface, as far as the verified token says.
 *
 * <p>{@code subject} and {@code jti} were validated. {@code actorId} and {@code actorRole} are
 * copied from the signed token <b>for the audit trail only</b>: MasterAdmin authorized its operator
 * before signing, and TMS must not take a second decision with a different rule set. Nothing in TMS
 * reads them to decide anything; authorization is the scope, and only the scope.
 */
public record PlatformCaller(String subject, String jti, String actorId, String actorRole) {

    /** Longest value copied from a claim into a log or audit column. */
    static final int MAX_CLAIM_LENGTH = 200;

    static String bounded(Object claim) {
        if (!(claim instanceof String text) || text.isBlank()) {
            return null;
        }
        String trimmed = text.trim();
        return trimmed.length() > MAX_CLAIM_LENGTH ? trimmed.substring(0, MAX_CLAIM_LENGTH) : trimmed;
    }
}
