package com.ebim.tms.settlement.application;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** Reverse an open accrual or close the remainder of a partially settled one (V65). */
public record AccrualReverseRequest(
        @NotBlank(message = "is required: a reversal says why")
        @Size(max = 500, message = "must be at most 500 characters")
        String reason) {
}
