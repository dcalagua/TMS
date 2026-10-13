package com.ebim.tms.iam.lifecycle.domain;

import java.io.Serial;

/**
 * A refusal of the lifecycle receiver, with the code and HTTP status of {@code lifecycle.v1}
 * (MasterAdmin {@code docs/platform-provisioning/LIFECYCLE_CONTRACT.md} section 4).
 *
 * <p>The error body is {@code {error, message, appliedVersion?}} and never echoes the request.
 */
public class LifecycleRejection extends RuntimeException {

    @Serial
    private static final long serialVersionUID = 1L;

    public enum Code {
        INVALID_LIFECYCLE(400),
        PRODUCT_MISMATCH(400),
        WRONG_ENVIRONMENT(400),
        TENANT_NOT_PROVISIONED(404),
        STALE_LIFECYCLE(409),
        VERSION_CONFLICT(409),
        /** The token's {@code jti} was already spent on a MasterAdmin contract route. */
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

    public LifecycleRejection(Code code, String message) {
        this(code, message, null);
    }

    public LifecycleRejection(Code code, String message, Long appliedVersion) {
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
