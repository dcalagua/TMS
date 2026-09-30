package com.ebim.tms.integration.application;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/** A document an ERP issued for one or more orders (ADR-015), contract v1. */
public record LogisticsDocumentV1(
        @NotBlank(message = "is required")
        @Pattern(regexp = "^[A-Z0-9_]{2,40}$", message = "must be 2-40 upper-case letters, digits or '_'")
        String sourceSystem,
        @NotBlank(message = "is required")
        @Pattern(regexp = "^(INVOICE|DELIVERY_NOTE|GRE_REMITENTE|GRE_TRANSPORTISTA|OTHER)$",
                message = "must be INVOICE, DELIVERY_NOTE, GRE_REMITENTE, GRE_TRANSPORTISTA or OTHER")
        String documentType,
        @NotBlank(message = "is required") @Size(max = 60, message = "must be at most 60 characters")
        String documentNumber,
        LocalDate issueDate,
        @Size(max = 20, message = "must be at most 20 characters") String issuerTaxId,
        @Size(max = 20, message = "must be at most 20 characters") String recipientTaxId,
        @Size(max = 200, message = "must be at most 200 characters") String recipientName,
        @DecimalMin(value = "0", message = "cannot be negative") BigDecimal amount,
        @Pattern(regexp = "^[A-Z]{3}$", message = "must be an ISO 4217 code") String currency,
        @Size(max = 40, message = "must be at most 40 characters") String status,
        @NotNull(message = "is required") @Size(min = 1, max = 500, message = "must name 1 to 500 orders")
        List<@Valid @NotNull OrderKey> orders) {

    public record OrderKey(
            @NotBlank(message = "is required")
            @Pattern(regexp = "^[A-Z0-9_]{2,40}$", message = "must be 2-40 upper-case letters, digits or '_'")
            String externalSource,
            @NotBlank(message = "is required") @Size(max = 120, message = "must be at most 120 characters")
            String externalReference) {
    }
}
