package com.ebim.tms.settlement.application;

import com.ebim.tms.settlement.domain.GlConcept;
import com.ebim.tms.settlement.domain.GlScopeType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.UUID;

/**
 * A GL account mapping (V65). On update only {@code accountCode} and {@code costCenterCode} change:
 * the concept and scope are what the row is, so a different one is a new mapping.
 */
public record GlAccountMappingRequest(
        @NotNull(message = "is required") GlConcept concept,
        GlScopeType scopeType,
        UUID scopeId,
        @NotBlank(message = "is required") @Size(max = 40, message = "must be at most 40 characters")
        String accountCode,
        @Size(max = 40, message = "must be at most 40 characters")
        String costCenterCode) {
}
