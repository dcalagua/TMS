package com.ebim.tms.planning.api;

import com.ebim.tms.planning.application.DeliveryEvidenceDownload;
import com.ebim.tms.planning.application.DeliveryEvidenceService;
import com.ebim.tms.planning.application.DeliveryResultRequest;
import com.ebim.tms.planning.application.EvidenceUpload;
import com.ebim.tms.planning.application.TripDeliveryService;
import com.ebim.tms.planning.application.TripDetailView;
import com.ebim.tms.planning.domain.EvidenceType;
import com.ebim.tms.shared.api.InvalidRequestException;
import com.ebim.tms.shared.security.CompanyScope;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.enums.ParameterIn;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
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
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * Proof of delivery (migration V28): what was handed over at a stop, and the evidence for it.
 *
 * <p><b>Its own controller</b> rather than five more methods on {@link TripController}: that one is
 * about the shipment - what it carries, who runs it, where it is in its day - and this is about the
 * goods. The URLs stay under {@code /planning/trips/{id}} because a delivery has no life outside
 * the shipment that made it.
 *
 * <p><b>No new permission.</b> Recording a delivery is {@code planning.trip:execute}, the same
 * authority as working the stop it happened at - the argument migration V27 made for stop execution
 * and this does not weaken. Reading one is {@code planning.trip:read} <em>and</em>
 * {@code orders.order:read}, matching {@code TripController.get}: a delivery names an order, and
 * the extra authority on that endpoint exists precisely because it returns order fields.
 *
 * <p><b>Evidence needs a configured store.</b> Both file endpoints answer 503
 * ({@code storage-unavailable}) where {@code tms.storage.evidence.mode} is not set. Recording the
 * result itself never does: the result is the fact, the artefact is corroboration.
 */
@RestController
@RequestMapping("${tms.api.base-path}/planning/trips/{tripId}")
@Tag(name = "Deliveries", description = "Delivery results and proof-of-delivery evidence per order and stop")
public class TripDeliveryController {

    private final TripDeliveryService deliveryService;
    private final DeliveryEvidenceService evidenceService;

    public TripDeliveryController(TripDeliveryService deliveryService, DeliveryEvidenceService evidenceService) {
        this.deliveryService = deliveryService;
        this.evidenceService = evidenceService;
    }

    /**
     * A {@code PUT} and not a {@code POST}: the body is the whole state of one delivery, addressed
     * by the stop and the order it is about, and sending it twice leaves the same row. Recording and
     * correcting are the same operation, because a correction is not a different fact - it is the
     * same fact, told properly.
     */
    @PutMapping("/stops/{stopId}/orders/{orderId}/delivery")
    @PreAuthorize("hasAuthority('planning.trip:execute')")
    @Operation(summary = "Record or correct what happened to one order at one stop")
    @Parameter(name = "X-Company-Id", in = ParameterIn.HEADER, required = true,
            description = "Id of a company the caller is a member of")
    public TripDetailView recordDelivery(CompanyScope scope, @PathVariable UUID tripId, @PathVariable UUID stopId,
            @PathVariable UUID orderId, @Valid @RequestBody DeliveryResultRequest request) {
        return deliveryService.record(scope, tripId, stopId, orderId, request);
    }

    /**
     * Attaches one artefact. {@code multipart/form-data}, like the import endpoints, with the type
     * and the optional capture time as form fields beside the file.
     */
    @PostMapping(path = "/deliveries/{deliveryId}/evidence", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("hasAuthority('planning.trip:execute')")
    @Operation(summary = "Attach a signature, photo or document to a recorded delivery")
    @Parameter(name = "X-Company-Id", in = ParameterIn.HEADER, required = true,
            description = "Id of a company the caller is a member of")
    public TripDetailView attachEvidence(CompanyScope scope, @PathVariable UUID tripId,
            @PathVariable UUID deliveryId,
            @RequestParam("evidenceType") EvidenceType evidenceType,
            // ISO-8601, like every other instant on the wire. A form field rather than a JSON body
            // because the file makes this multipart, and a second JSON part would be a shape no
            // browser form produces.
            @RequestParam(name = "capturedAt", required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime capturedAt,
            @RequestParam("file") MultipartFile file) {
        return evidenceService.attach(scope, tripId, deliveryId, evidenceType, capturedAt, toUpload(file));
    }

    /**
     * The part, reduced to what the use case takes - multipart stops here, so the service stays
     * callable from a job or an integration ({@code LayeringTest}).
     *
     * <p>The file name is cut down to its last path segment, the same treatment
     * {@code OrderImportController} gives an import file: a browser sends a bare name, a hand-made
     * request may send a path, and this value is stored and echoed back in a
     * {@code Content-Disposition} header. It never reaches the storage key, which the store
     * generates itself.
     */
    private static EvidenceUpload toUpload(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new InvalidRequestException("A file is required.");
        }
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

    /**
     * The bytes of one artefact.
     *
     * <p>Every safeguard a file endpoint needs and a static host cannot give: the caller is
     * authenticated, scoped to a company, holds the permission, and the object is resolved through
     * two owning rows before the store is asked. {@code Content-Disposition: attachment} so a photo
     * or a PDF is saved rather than rendered inside the app's own origin, and {@code no-store} so no
     * proxy keeps a copy of a customer's signed delivery note.
     */
    @GetMapping("/deliveries/{deliveryId}/evidence/{evidenceId}/content")
    @PreAuthorize("hasAuthority('planning.trip:read') and hasAuthority('orders.order:read')")
    @Operation(summary = "Download one piece of delivery evidence")
    @Parameter(name = "X-Company-Id", in = ParameterIn.HEADER, required = true,
            description = "Id of a company the caller is a member of")
    public ResponseEntity<Resource> downloadEvidence(CompanyScope scope, @PathVariable UUID tripId,
            @PathVariable UUID deliveryId, @PathVariable UUID evidenceId) {
        DeliveryEvidenceDownload download = evidenceService.download(scope, tripId, deliveryId, evidenceId);
        return ResponseEntity.ok()
                // UTF-8 encoded: an operator's phone names files in their own language, and a
                // header that cannot carry the name would either mangle it or fail to be built.
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
