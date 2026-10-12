package com.ebim.tms.planning.api;

import com.ebim.tms.planning.application.DeliveryEvidenceDownload;
import com.ebim.tms.planning.application.DocumentReportCommand;
import com.ebim.tms.planning.application.DocumentReportService;
import com.ebim.tms.planning.application.EvidenceUpload;
import com.ebim.tms.planning.application.PortalDocumentView;
import com.ebim.tms.planning.application.PortalTripDetailView;
import com.ebim.tms.planning.application.PortalTripService;
import com.ebim.tms.planning.application.PortalTripSummaryView;
import com.ebim.tms.planning.domain.DocumentDeliveryReason;
import com.ebim.tms.planning.domain.DocumentDeliveryStatus;
import com.ebim.tms.shared.api.ApiHeaders;
import com.ebim.tms.shared.api.InvalidRequestException;
import com.ebim.tms.shared.security.CompanyScope;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.enums.ParameterIn;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.io.IOException;
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
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * The carrier portal (ADR-018): a carrier user's dispatched trips and the report of each document.
 * Only {@code CARRIER_OPERATOR} holds these permissions, and only through a carrier membership.
 */
@RestController
@RequestMapping("${tms.api.base-path}/portal/trips")
@Tag(name = "Carrier portal", description = "Trips assigned to my carrier and the delivery of their documents")
public class PortalTripController {

    private final PortalTripService portal;
    private final DocumentReportService reports;

    public PortalTripController(PortalTripService portal, DocumentReportService reports) {
        this.portal = portal;
        this.reports = reports;
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
