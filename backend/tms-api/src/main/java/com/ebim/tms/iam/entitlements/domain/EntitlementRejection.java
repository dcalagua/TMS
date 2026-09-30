package com.ebim.tms.iam.entitlements.domain;

import java.io.Serial;

/**
 * A refusal of the receiver, with the code and HTTP status of the contract
 * ({@code contracts/entitlements/v1/README.md} section 3).
 *
 * <p>The error body is {@code {error, message, appliedVersion?}} and <b>never</b> echoes the
 * snapshot that was sent.
 */
public class EntitlementRejection extends RuntimeException {

    @Serial
    private static final long serialVersionUID = 1L;

    public enum Code {
        UNSUPPORTED_MEDIA_TYPE(415),
        UNSUPPORTED_CONTRACT_VERSION(422),
        SNAPSHOT_INVALID(422),
        SNAPSHOT_TOO_LARGE(413),
        ENVIRONMENT_MISMATCH(422),
        CHECKSUM_MISMATCH(422),
        TENANT_NOT_PROVISIONED(404),
        STALE_SNAPSHOT(409),
        VERSION_CONFLICT(409),
        JTI_REPLAYED(401);

        private final int status;

        Code(int status) {
            this.status = status;
        }

        public int status() {
            return status;
        }
    }

    private final Code code;
    private final Long appliedVersion;

    public EntitlementRejection(Code code, String message) {
        this(code, message, null);
    }

    public EntitlementRejection(Code code, String message, Long appliedVersion) {
        super(message);
        this.code = code;
        this.appliedVersion = appliedVersion;
    }

    public Code code() {
        return code;
    }

    public Long appliedVersion() {
        return appliedVersion;
    }
}
