package com.ebim.tms.iam.inventory.domain;

import java.io.Serial;

/**
 * A refusal of {@code tenant-inventory.v1} (list and link), with its stable code and HTTP status
 * (MasterAdmin {@code docs/platform-provisioning/TENANT_INVENTORY_CONTRACT.md} sections 5 and 8).
 *
 * <p>The error body is {@code {error, message}}, like entitlements and lifecycle, and never echoes the
 * request.
 */
public class InventoryRejection extends RuntimeException {

    @Serial
    private static final long serialVersionUID = 1L;

    public enum Code {
        INVALID_INVENTORY_QUERY(400),
        INVALID_LINK_REQUEST(400),
        /** The token's {@code jti} was already spent on a MasterAdmin contract route. */
        JTI_REPLAYED(401),
        INSUFFICIENT_SCOPE(403),
        EXTERNAL_TENANT_NOT_FOUND(404),
        LINK_CONFLICT(409),
        INVENTORY_FAILED(500),
        TENANT_LINK_FAILED(500),
        /** The list scope (or the environment) is not configured: the route is closed. */
        INVENTORY_NOT_CONFIGURED(503),
        /** The link scope (or the environment) is not configured: the route is closed. */
        TENANT_LINK_NOT_CONFIGURED(503);

        private final int status;

        Code(int status) {
            this.status = status;
        }

        public int status() {
            return status;
        }
    }

    private final Code code;

    public InventoryRejection(Code code, String message) {
        super(message);
        this.code = code;
    }

    public Code code() {
        return code;
    }
}
