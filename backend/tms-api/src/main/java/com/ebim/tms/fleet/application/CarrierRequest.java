package com.ebim.tms.fleet.application;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Create and update share one shape; see {@code OriginRequest} (masterdata module) for the
 * reasoning behind the lower-case-accepting code pattern and the company/id coming from
 * context rather than the body.
 *
 * <p>{@code taxIdType}/{@code taxIdValue} are deliberately plain {@code @NotBlank} text, not a
 * restricted enum - see the V9 migration comment on {@code tms.carrier.tax_id_type}.
 */
public record CarrierRequest(
        @NotBlank @Size(max = 32) @Pattern(regexp = "^[A-Za-z0-9][A-Za-z0-9_-]{0,31}$",
                message = "must be 1-32 characters: letters, digits, underscore or hyphen") String code,
        @NotBlank @Size(max = 200) String businessName,
        @NotBlank @Size(max = 32) String taxIdType,
        @NotBlank @Size(max = 64) String taxIdValue,
        @Size(max = 200) String contactName,
        @Size(max = 32) String phone,
        @Size(max = 200) String email,
        @Size(max = 200) String externalReference) {
}
