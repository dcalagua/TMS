package com.ebim.tms.iam.provisioning.application;

import java.util.List;

/**
 * A contract outcome that is not a success: carries the stable {@link ProvisioningErrorCode} and,
 * for a 400, the fields at fault named as MasterAdmin sent them.
 *
 * <p>Unchecked on purpose: thrown inside the provisioning transaction it rolls the transaction back
 * with no {@code rollbackFor} to remember.
 */
public class PlatformProvisioningException extends RuntimeException {

    private final ProvisioningErrorCode code;
    private final transient List<FieldIssue> details;

    public PlatformProvisioningException(ProvisioningErrorCode code) {
        this(code, List.of());
    }

    public PlatformProvisioningException(ProvisioningErrorCode code, List<FieldIssue> details) {
        super(code.name());
        this.code = code;
        this.details = List.copyOf(details);
    }

    public ProvisioningErrorCode code() {
        return code;
    }

    public List<FieldIssue> details() {
        return details;
    }

    /** One rejected field: {@code field} is the GENERIC path, e.g. {@code organization.countryCode}. */
    public record FieldIssue(String field, String issue) {}
}
