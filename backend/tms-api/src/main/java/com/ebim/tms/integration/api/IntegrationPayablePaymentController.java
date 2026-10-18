package com.ebim.tms.integration.api;

import com.ebim.tms.integration.application.IntegrationExecution;
import com.ebim.tms.integration.application.IntegrationPayablePaymentService;
import com.ebim.tms.integration.application.IntegrationPrincipal;
import com.ebim.tms.integration.application.IntegrationRequestExecutor;
import com.ebim.tms.integration.application.PayablePaymentResultV1;
import com.ebim.tms.integration.application.PayablePaymentV1;
import com.ebim.tms.integration.domain.IntegrationOperation;
import com.ebim.tms.shared.api.ApiHeaders;
import com.ebim.tms.shared.config.OpenApiConfig;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.enums.ParameterIn;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * What the ERP paid against an exported carrier invoice (V67, ADR-023 section 2,
 * {@code INBOUND_API_V1.md} section 12). TMS records it and moves no money (D-01). The company is the
 * credential's, never a header's.
 */
@RestController
@RequestMapping(IntegrationApiPaths.V1)
@Tag(name = "Integration v1 - Payables",
        description = "The ERP reports payments of carrier invoices; TMS records the status and pays nothing")
@SecurityRequirement(name = OpenApiConfig.INTEGRATION_SCHEME)
public class IntegrationPayablePaymentController {

    private final IntegrationPayablePaymentService service;
    private final IntegrationRequestExecutor executor;

    public IntegrationPayablePaymentController(IntegrationPayablePaymentService service,
            IntegrationRequestExecutor executor) {
        this.service = service;
        this.executor = executor;
    }

    @PostMapping("/payable-payments")
    @PreAuthorize("hasAuthority('integration.payable-payment:write')")
    @Operation(summary = "Report one payment of a carrier invoice (PAYABLE_PAYMENT)",
            description = "201 RECORDED, 200 DUPLICATE (same paymentReference and figures). 404 unknown invoice; "
                    + "409 not exported yet, a credit/debit note, or the reference already used with other figures; "
                    + "422 payment-exceeds-balance or payment-currency-mismatch.")
    @Parameter(name = ApiHeaders.IDEMPOTENCY_KEY, in = ParameterIn.HEADER,
            description = "Recommended. Repeating it with the same payload replays the first response; with "
                    + "another payload it is 409.")
    public ResponseEntity<PayablePaymentResultV1> record(
            IntegrationPrincipal principal,
            @RequestHeader(value = ApiHeaders.IDEMPOTENCY_KEY, required = false) String idempotencyKey,
            // No @Valid, for the reason IntegrationOrderController documents: validation runs inside
            // the executor so a refusal is recorded in the inbox like every other outcome.
            @RequestBody PayablePaymentV1 payment) {
        IntegrationExecution<PayablePaymentResultV1> execution = executor.execute(principal,
                IntegrationOperation.PAYABLE_PAYMENT, idempotencyKey, payment, PayablePaymentResultV1.class,
                () -> service.record(principal, payment));
        return IntegrationResponses.of(execution);
    }
}
