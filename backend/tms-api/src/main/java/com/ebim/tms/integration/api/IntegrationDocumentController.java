package com.ebim.tms.integration.api;

import com.ebim.tms.integration.application.IntegrationExecution;
import com.ebim.tms.integration.application.IntegrationOutcome;
import com.ebim.tms.integration.application.IntegrationPrincipal;
import com.ebim.tms.integration.application.IntegrationRequestExecutor;
import com.ebim.tms.integration.application.LogisticsDocumentResultV1;
import com.ebim.tms.integration.application.LogisticsDocumentV1;
import com.ebim.tms.integration.domain.IntegrationOperation;
import com.ebim.tms.shared.api.ApiHeaders;
import com.ebim.tms.shared.config.OpenApiConfig;
import com.ebim.tms.shared.reference.LogisticsDocumentPort;
import com.ebim.tms.shared.reference.OrderExternalKey;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** ERP -> TMS: the documents that travel with orders (ADR-015). Informational only. */
@RestController
@RequestMapping(IntegrationApiPaths.V1 + "/logistics-documents")
@Tag(name = "Integration v1 - Logistics documents",
        description = "Invoices, delivery notes and remission guides an ERP issued, linked to orders.")
@SecurityRequirement(name = OpenApiConfig.INTEGRATION_SCHEME)
public class IntegrationDocumentController {

    private final LogisticsDocumentPort documents;
    private final IntegrationRequestExecutor executor;

    public IntegrationDocumentController(LogisticsDocumentPort documents, IntegrationRequestExecutor executor) {
        this.documents = documents;
        this.executor = executor;
    }

    @PostMapping
    @PreAuthorize("hasAuthority('integration.document:write')")
    @Operation(summary = "Create or replace a document by (sourceSystem, documentType, documentNumber)",
            description = "201 when created, 200 when updated or unchanged. Orders are matched exactly by "
                    + "(externalSource, externalReference); an order not yet known is reported and linked by a "
                    + "later re-send. Nothing here moves an order, a trip or a delivery.")
    public ResponseEntity<LogisticsDocumentResultV1> upsert(
            IntegrationPrincipal principal,
            @RequestHeader(value = ApiHeaders.IDEMPOTENCY_KEY, required = false) String idempotencyKey,
            @RequestBody LogisticsDocumentV1 request) {
        IntegrationExecution<LogisticsDocumentResultV1> execution = executor.execute(principal,
                IntegrationOperation.LOGISTICS_DOCUMENT_UPSERT, idempotencyKey, request,
                LogisticsDocumentResultV1.class, () -> {
                    LogisticsDocumentPort.Intake intake = documents.upsert(principal.companyScope(),
                            new LogisticsDocumentPort.Command(principal.id(), request.sourceSystem(),
                                    request.documentType(), request.documentNumber().trim(), request.issueDate(),
                                    request.issuerTaxId(), request.recipientTaxId(), request.recipientName(),
                                    request.amount(), request.currency(), request.status(),
                                    request.orders().stream()
                                            .map(key -> new OrderExternalKey(key.externalSource(),
                                                    key.externalReference().trim()))
                                            .toList()));
                    LogisticsDocumentResultV1 body = new LogisticsDocumentResultV1(intake.id(), intake.outcome(),
                            intake.linkedOrders().size(),
                            intake.unknownOrders().stream()
                                    .map(key -> key.externalSource() + ":" + key.externalReference()).toList());
                    return IntegrationOutcome.single(body, "CREATED".equals(intake.outcome()) ? 201 : 200, intake.id(),
                            request.sourceSystem(), request.documentNumber());
                });
        return IntegrationResponses.of(execution);
    }
}
