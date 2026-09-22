package com.ebim.tms.iam.provisioning.api;

import com.ebim.tms.iam.provisioning.application.PlatformProvisioningException.FieldIssue;
import com.ebim.tms.iam.provisioning.application.ProvisioningErrorCode;
import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;

/**
 * The error body of the provisioning surface.
 *
 * <p>Not the RFC 9457 document the rest of the API returns, on purpose: MasterAdmin reads
 * {@code code} at the top level (and {@code error.code} as a fallback), which is the shape every
 * product of the suite answers it with. {@code details} names fields in the GENERIC vocabulary.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ProvisioningErrorResponse(String code, String message, Error error, String correlationId) {

    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    public record Error(String code, String message, List<FieldIssue> details) {}

    public static ProvisioningErrorResponse of(ProvisioningErrorCode code, List<FieldIssue> details,
            String correlationId) {
        return new ProvisioningErrorResponse(code.name(), code.message(),
                new Error(code.name(), code.message(), details == null ? List.of() : details), correlationId);
    }
}
