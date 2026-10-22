package com.ebim.tms.planning.api;

import com.ebim.tms.planning.application.DeliveryEvidenceDownload;
import com.ebim.tms.planning.application.DocumentReportCommand;
import com.ebim.tms.planning.application.DocumentReportService;
import com.ebim.tms.planning.application.EvidenceUpload;
import com.ebim.tms.planning.application.PortalDeliveryCommand;
import com.ebim.tms.planning.application.PortalDocumentView;
import com.ebim.tms.planning.application.PortalExecutionService;
import com.ebim.tms.planning.application.PortalTripDetailView;
import com.ebim.tms.planning.application.PortalTripService;
import com.ebim.tms.planning.application.PortalTripSummaryView;
import com.ebim.tms.planning.application.TripExceptionRequest;
import com.ebim.tms.planning.application.TripStopExecutionRequest;
import com.ebim.tms.planning.application.TripStopFailureRequest;
import com.ebim.tms.planning.domain.DeliveryResult;
import com.ebim.tms.planning.domain.DocumentDeliveryReason;
import com.ebim.tms.planning.domain.DocumentDeliveryStatus;
import com.ebim.tms.shared.api.ApiHeaders;
import com.ebim.tms.shared.api.InvalidRequestException;
import com.ebim.tms.shared.security.CompanyScope;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.enums.ParameterIn;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.core.io.InputStreamResource;
import org.springframework.core.io.Resource;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.CacheControl;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * The carrier portal (ADR-018): a carrier user's dispatched trips and the report of each document,
 * and since V70 the execution of their stops - arrival, service, completion or failure, the delivery
 * result of each order with its evidence, and incidents - through the office's own services
 * ({@link PortalExecutionService}). Only {@code CARRIER_OPERATOR} holds these permissions, and only
 * through a carrier membership. Every write requires an {@code Idempotency-Key}.
 */
@RestController
@RequestMapping("${tms.api.base-path}/portal/trips")
@Tag(name = "Carrier portal", description = "Trips assigned to my carrier and the delivery of their documents")
public class PortalTripController {

    private final PortalTripService portal;
    private final DocumentReportService reports;
    private final PortalExecutionService execution;

    public PortalTripController(PortalTripService portal, DocumentReportService reports,
            PortalExecutionService execution) {
        this.portal = portal;
        this.reports = reports;
        this.execution = execution;
    }

    @GetMapping
    @PreAuthorize("hasAuthority('portal.trip:read')")
    @Operation(summary = "My carrier's dispatched trips, newest first")
    @Parameter(name = "X-Company-Id", in = ParameterIn.HEADER, required = true,
            description = "Id of a company the caller is a member of")
    public List<PortalTripSummaryView> list(CompanyScope scope,
            @RequestParam(name = "days", defaultValue = "14") int days,
            @RequestParam(name = "pendingOnly", defaultValue = "false") boolean pendingOnly) {
        return portal.list(scope, days, pendingOnly);
    }

    @GetMapping("/{tripId}")
    @PreAuthorize("hasAuthority('portal.trip:read')")
    @Operation(summary = "One trip: its stops and the documents to report at each")
    @Parameter(name = "X-Company-Id", in = ParameterIn.HEADER, required = true,
            description = "Id of a company the caller is a member of")
    public PortalTripDetailView detail(CompanyScope scope, @PathVariable UUID tripId) {
        return portal.detail(scope, tripId);
    }

    /**
     * Reports one document: delivered (with one to five photos), rejected or not delivered (with a
     * reason). Requires {@code Idempotency-Key}: a retry with the same key answers from the first.
     */
    @PostMapping(path = "/{tripId}/documents/{documentId}/report", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("hasAuthority('portal.document:report')")
    @Operation(summary = "Report the delivery of a document")
    @Parameter(name = "X-Company-Id", in = ParameterIn.HEADER, required = true,
            description = "Id of a company the caller is a member of")
    public PortalDocumentView report(CompanyScope scope, @PathVariable UUID tripId, @PathVariable UUID documentId,
            @RequestHeader(name = ApiHeaders.IDEMPOTENCY_KEY, required = false) String idempotencyKey,
            @RequestParam("status") DocumentDeliveryStatus status,
            @RequestParam(name = "reasonCode", required = false) DocumentDeliveryReason reasonCode,
            @RequestParam(name = "remark", required = false) String remark,
            @RequestParam(name = "reportedAt", required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime reportedAt,
            @RequestParam(name = "photos", required = false) List<MultipartFile> photos,
            @RequestParam(name = "signature", required = false) MultipartFile signature) {
        List<EvidenceUpload> uploads = new ArrayList<>();
        if (photos != null) {
            photos.stream().filter(photo -> photo != null && !photo.isEmpty()).map(PortalTripController::toUpload)
                    .forEach(uploads::add);
        }
        EvidenceUpload signatureUpload = signature == null || signature.isEmpty() ? null : toUpload(signature);
        return reports.report(scope, tripId, documentId, idempotencyKey,
                new DocumentReportCommand(status, reasonCode, remark, reportedAt, uploads, signatureUpload));
    }

    @GetMapping("/{tripId}/documents/{documentId}/evidence/{evidenceId}/content")
    @PreAuthorize("hasAuthority('portal.trip:read')")
    @Operation(summary = "Download a photo or signature of a document on my trip")
    @Parameter(name = "X-Company-Id", in = ParameterIn.HEADER, required = true,
            description = "Id of a company the caller is a member of")
    public ResponseEntity<Resource> evidence(CompanyScope scope, @PathVariable UUID tripId,
            @PathVariable UUID documentId, @PathVariable UUID evidenceId) {
        return asAttachment(portal.download(scope, tripId, documentId, evidenceId));
    }

    // --- stop execution and proof of delivery (V70) -------------------------------------------

    @PostMapping("/{tripId}/stops/{stopId}/arrive")
    @PreAuthorize("hasAuthority('portal.stop:execute')")
    @Operation(summary = "I arrived at a stop of my trip")
    @Parameter(name = "X-Company-Id", in = ParameterIn.HEADER, required = true,
            description = "Id of a company the caller is a member of")
    public PortalTripDetailView arrive(CompanyScope scope, @PathVariable UUID tripId, @PathVariable UUID stopId,
            @RequestHeader(name = ApiHeaders.IDEMPOTENCY_KEY, required = false) String idempotencyKey,
            @Valid @RequestBody TripStopExecutionRequest request) {
        return execution.arrive(scope, tripId, stopId, idempotencyKey, request);
    }

    @PostMapping("/{tripId}/stops/{stopId}/service")
    @PreAuthorize("hasAuthority('portal.stop:execute')")
    @Operation(summary = "I started unloading at a stop of my trip")
    @Parameter(name = "X-Company-Id", in = ParameterIn.HEADER, required = true,
            description = "Id of a company the caller is a member of")
    public PortalTripDetailView startService(CompanyScope scope, @PathVariable UUID tripId,
            @PathVariable UUID stopId,
            @RequestHeader(name = ApiHeaders.IDEMPOTENCY_KEY, required = false) String idempotencyKey,
            @Valid @RequestBody TripStopExecutionRequest request) {
        return execution.startService(scope, tripId, stopId, idempotencyKey, request);
    }

    @PostMapping("/{tripId}/stops/{stopId}/complete")
    @PreAuthorize("hasAuthority('portal.stop:execute')")
    @Operation(summary = "I finished a stop of my trip and left")
    @Parameter(name = "X-Company-Id", in = ParameterIn.HEADER, required = true,
            description = "Id of a company the caller is a member of")
    public PortalTripDetailView complete(CompanyScope scope, @PathVariable UUID tripId, @PathVariable UUID stopId,
            @RequestHeader(name = ApiHeaders.IDEMPOTENCY_KEY, required = false) String idempotencyKey,
            @Valid @RequestBody TripStopExecutionRequest request) {
        return execution.complete(scope, tripId, stopId, idempotencyKey, request);
    }

    @PostMapping("/{tripId}/stops/{stopId}/fail")
    @PreAuthorize("hasAuthority('portal.stop:execute')")
    @Operation(summary = "I could not serve a stop of my trip, with the reason")
    @Parameter(name = "X-Company-Id", in = ParameterIn.HEADER, required = true,
            description = "Id of a company the caller is a member of")
    public PortalTripDetailView fail(CompanyScope scope, @PathVariable UUID tripId, @PathVariable UUID stopId,
            @RequestHeader(name = ApiHeaders.IDEMPOTENCY_KEY, required = false) String idempotencyKey,
            @Valid @RequestBody TripStopFailureRequest request) {
        return execution.fail(scope, tripId, stopId, idempotencyKey, request);
    }

    @PostMapping("/{tripId}/incidents")
    @PreAuthorize("hasAuthority('portal.stop:execute')")
    @Operation(summary = "Report an incident on my trip or one of its stops")
    @Parameter(name = "X-Company-Id", in = ParameterIn.HEADER, required = true,
            description = "Id of a company the caller is a member of")
    public PortalTripDetailView reportIncident(CompanyScope scope, @PathVariable UUID tripId,
            @RequestHeader(name = ApiHeaders.IDEMPOTENCY_KEY, required = false) String idempotencyKey,
            @Valid @RequestBody TripExceptionRequest request) {
        return execution.reportIncident(scope, tripId, idempotencyKey, request);
    }

    /**
     * The delivery result of one order at one stop, with its photos and signature, in one request
     * so the offline queue holds one entry. {@code multipart/form-data} like the document report.
     */
    @PostMapping(path = "/{tripId}/stops/{stopId}/orders/{orderId}/delivery",
            consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("hasAuthority('portal.delivery:report')")
    @Operation(summary = "Record what happened to one order at a stop of my trip")
    @Parameter(name = "X-Company-Id", in = ParameterIn.HEADER, required = true,
            description = "Id of a company the caller is a member of")
    @SuppressWarnings("java:S107")
    public PortalTripDetailView recordDelivery(CompanyScope scope, @PathVariable UUID tripId,
            @PathVariable UUID stopId, @PathVariable UUID orderId,
            @RequestHeader(name = ApiHeaders.IDEMPOTENCY_KEY, required = false) String idempotencyKey,
            @RequestParam("result") DeliveryResult result,
            @RequestParam(name = "deliveredAt", required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime deliveredAt,
            @RequestParam(name = "receiverName", required = false) String receiverName,
            @RequestParam(name = "receiverDocument", required = false) String receiverDocument,
            @RequestParam(name = "notes", required = false) String notes,
            @RequestParam(name = "refusedWeightKg", required = false) BigDecimal refusedWeightKg,
            @RequestParam(name = "refusedVolumeM3", required = false) BigDecimal refusedVolumeM3,
            @RequestParam(name = "refusedPallets", required = false) BigDecimal refusedPallets,
            @RequestParam(name = "photos", required = false) List<MultipartFile> photos,
            @RequestParam(name = "signature", required = false) MultipartFile signature) {
        requireLength("receiverName", receiverName, 120);
        requireLength("receiverDocument", receiverDocument, 60);
        requireLength("notes", notes, 1000);
        List<EvidenceUpload> uploads = new ArrayList<>();
        if (photos != null) {
            photos.stream().filter(photo -> photo != null && !photo.isEmpty()).map(PortalTripController::toUpload)
                    .forEach(uploads::add);
        }
        EvidenceUpload signatureUpload = signature == null || signature.isEmpty() ? null : toUpload(signature);
        return execution.recordDelivery(scope, tripId, stopId, orderId, idempotencyKey,
                new PortalDeliveryCommand(result, deliveredAt, receiverName, receiverDocument, notes,
                        refusedWeightKg, refusedVolumeM3, refusedPallets, uploads, signatureUpload));
    }

    /** The bounds {@code DeliveryResultRequest} declares, for the form fields that bypass Bean Validation. */
    private static void requireLength(String field, String value, int max) {
        if (value != null && value.length() > max) {
            throw new InvalidRequestException(field + " must be at most " + max + " characters.");
        }
    }

    /** As {@code TripDeliveryController.toUpload}: the bare file name, never a path. */
    static EvidenceUpload toUpload(MultipartFile file) {
        try {
            return new EvidenceUpload(file.getContentType(), bareNameOf(file.getOriginalFilename()),
                    file.getInputStream());
        } catch (IOException unreadable) {
            throw new InvalidRequestException("The uploaded file could not be read. Try uploading it again.");
        }
    }

    private static String bareNameOf(String name) {
        if (name == null || name.isBlank()) {
            return null;
        }
        String trimmed = name.trim();
        int lastSeparator = Math.max(trimmed.lastIndexOf('/'), trimmed.lastIndexOf('\\'));
        String bare = lastSeparator < 0 ? trimmed : trimmed.substring(lastSeparator + 1);
        return bare.isBlank() ? null : bare.substring(0, Math.min(bare.length(), 255));
    }

    /** {@code attachment} and {@code no-store}, for the reasons {@code TripDeliveryController} gives. */
    static ResponseEntity<Resource> asAttachment(DeliveryEvidenceDownload download) {
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
                        .filename(download.fileName(), StandardCharsets.UTF_8)
                        .build()
                        .toString())
                .contentType(MediaType.parseMediaType(download.contentType()))
                .contentLength(download.sizeBytes())
                .cacheControl(CacheControl.noStore())
                .body(new InputStreamResource(download.stream()));
    }
}
