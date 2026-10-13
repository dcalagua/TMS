package com.ebim.tms.shared.security;

import java.io.Serial;

/**
 * EBIM MasterAdmin has suspended the operation of this organization ({@code lifecycle.v1}, ADR-021):
 * writes are refused with {@code 423 tenant-suspended}, reads go on. Nothing is deleted, and operation
 * resumes without intervention as soon as MasterAdmin sends {@code ACTIVE}.
 */
public class TenantSuspendedException extends RuntimeException {

    @Serial
    private static final long serialVersionUID = 1L;

    /** The stable contract code, carried in the problem document as {@code error}. */
    public static final String ERROR_CODE = "TENANT_SUSPENDED";

    private final String reason;

    public TenantSuspendedException(String reason, String message) {
        super(message);
        this.reason = reason;
    }

    /** {@code NON_PAYMENT}, {@code MANUAL} or {@code TERMINATED}. */
    public String reason() {
        return reason;
    }
}
