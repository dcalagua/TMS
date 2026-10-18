package com.ebim.tms.integration.application;

import com.fasterxml.jackson.annotation.JsonInclude;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * A document an ERP issued for one or more orders (ADR-015), contract v1.
 *
 * <p>The fiscal fields (ADR-028, V69) are optional and additive: a body without them is the same v1
 * document it always was. They are marked {@code NON_NULL} because {@code PayloadHash} fingerprints
 * this record's re-serialisation, so a body sent before they existed must serialise exactly as it
 * did - otherwise an {@code Idempotency-Key} replay across the upgrade would be refused as different
 * content. TMS receives these documents; it never issues, signs or validates them with the SRI or
 * SUNAT.
 *
 * @param fiscalCountry ISO 3166-1 alpha-2 of the issuing tax authority (EC, PE, ...)
 * @param fiscalDocumentCode the authority's code for the class of document: '06' SRI guia de
 *     remision, '09' SUNAT GRE remitente, '31' GRE transportista
 * @param authorizationNumber the SRI clave de acceso (exactly 49 digits when {@code fiscalCountry} is
 *     EC) or the SUNAT reference
 */
public record LogisticsDocumentV1(
        @NotBlank(message = "is required")
        @Pattern(regexp = "^[A-Z0-9_]{2,40}$", message = "must be 2-40 upper-case letters, digits or '_'")
        String sourceSystem,
        @NotBlank(message = "is required")
        @Pattern(regexp = "^(INVOICE|DELIVERY_NOTE|REMISSION_GUIDE|GRE_REMITENTE|GRE_TRANSPORTISTA|OTHER)$",
                message = "must be INVOICE, DELIVERY_NOTE, REMISSION_GUIDE, GRE_REMITENTE, GRE_TRANSPORTISTA or OTHER")
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
        List<@Valid @NotNull OrderKey> orders,
        @JsonInclude(JsonInclude.Include.NON_NULL)
        @Pattern(regexp = "^[A-Z]{2}$", message = "must be an ISO 3166-1 alpha-2 code in upper case")
        String fiscalCountry,
        @JsonInclude(JsonInclude.Include.NON_NULL)
        @Pattern(regexp = "^[0-9A-Z]{1,4}$", message = "must be 1-4 digits or upper-case letters")
        String fiscalDocumentCode,
        @JsonInclude(JsonInclude.Include.NON_NULL)
        @Pattern(regexp = "^\\S{1,64}$", message = "must be 1-64 characters without spaces")
        String authorizationNumber,
        @JsonInclude(JsonInclude.Include.NON_NULL) LocalDate transportStartDate,
        @JsonInclude(JsonInclude.Include.NON_NULL) LocalDate transportEndDate,
        @JsonInclude(JsonInclude.Include.NON_NULL)
        @Size(max = 20, message = "must be at most 20 characters") String carrierTaxId,
        @JsonInclude(JsonInclude.Include.NON_NULL)
        @Size(max = 300, message = "must be at most 300 characters") String carrierName,
        @JsonInclude(JsonInclude.Include.NON_NULL)
        @Size(max = 20, message = "must be at most 20 characters") String vehiclePlate,
        @JsonInclude(JsonInclude.Include.NON_NULL)
        @Size(max = 300, message = "must be at most 300 characters") String originAddress,
        @JsonInclude(JsonInclude.Include.NON_NULL)
        @Size(max = 300, message = "must be at most 300 characters") String transferReason,
        @JsonInclude(JsonInclude.Include.NON_NULL)
        @Size(max = 500, message = "must have at most 500 recipients")
        List<@Valid @NotNull Recipient> recipients) {

    public record OrderKey(
            @NotBlank(message = "is required")
            @Pattern(regexp = "^[A-Z0-9_]{2,40}$", message = "must be 2-40 upper-case letters, digits or '_'")
            String externalSource,
            @NotBlank(message = "is required") @Size(max = 120, message = "must be at most 120 characters")
            String externalReference) {
    }

    /** A destinatario of a guide: who receives the goods, where, why and under which sales document. */
    public record Recipient(
            @Size(max = 20, message = "must be at most 20 characters") String taxId,
            @Size(max = 300, message = "must be at most 300 characters") String name,
            @Size(max = 300, message = "must be at most 300 characters") String destinationAddress,
            @Size(max = 300, message = "must be at most 300 characters") String transferReason,
            @Size(max = 10, message = "must be at most 10 characters") String destinationEstablishmentCode,
            @Size(max = 300, message = "must be at most 300 characters") String route,
            @Pattern(regexp = "^[0-9A-Z]{1,4}$", message = "must be 1-4 digits or upper-case letters")
            String relatedDocumentCode,
            @Size(max = 60, message = "must be at most 60 characters") String relatedDocumentNumber,
            @Size(max = 64, message = "must be at most 64 characters") String relatedDocumentAuthorizationNumber,
            LocalDate relatedDocumentDate,
            @Size(max = 500, message = "must have at most 500 items") List<@Valid @NotNull Item> items) {
    }

    /** A line of the guide for one recipient, as issued. Shown; never reconciled with the order. */
    public record Item(
            @Size(max = 40, message = "must be at most 40 characters") String code,
            @Size(max = 40, message = "must be at most 40 characters") String additionalCode,
            @NotBlank(message = "is required") @Size(max = 300, message = "must be at most 300 characters")
            String description,
            @DecimalMin(value = "0", message = "cannot be negative") BigDecimal quantity,
            @Size(max = 20, message = "must be at most 20 characters") String unit) {
    }
}
