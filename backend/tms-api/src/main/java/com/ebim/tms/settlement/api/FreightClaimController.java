package com.ebim.tms.settlement.api;

import com.ebim.tms.settlement.application.ClaimEvidenceFile;
import com.ebim.tms.settlement.application.FreightClaimEvidenceService;
import com.ebim.tms.settlement.application.FreightClaimRequests;
import com.ebim.tms.settlement.application.FreightClaimService;
import com.ebim.tms.settlement.application.FreightClaimViews.ClaimDetail;
import com.ebim.tms.settlement.application.FreightClaimViews.ClaimDraft;
import com.ebim.tms.settlement.application.FreightClaimViews.ClaimSummary;
import com.ebim.tms.settlement.application.FreightClaimViews.ClaimsOverview;
import com.ebim.tms.settlement.application.FreightClaimViews.PenaltySuggestion;
import com.ebim.tms.settlement.domain.ClaimStatus;
import com.ebim.tms.settlement.domain.ClaimType;
import com.ebim.tms.shared.api.InvalidRequestException;
import com.ebim.tms.shared.api.PageQuery;
import com.ebim.tms.shared.api.PageResponse;
import com.ebim.tms.shared.security.CompanyScope;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.enums.ParameterIn;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.core.io.InputStreamResource;
import org.springframework.core.io.Resource;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.CacheControl;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * Freight claims (migration V71): damage, shortage, loss and delay claimed from the carrier of a
 * trip, through review and a four-eyes decision, to the carrier document that recovers it.
 *
 * <p>Reading is {@code settlement.claim:read}; opening and working a claim
 * {@code settlement.claim:manage}; deciding it {@code settlement.claim:decide}, and never by whoever
 * opened it. The draft reads the trip, so it also needs {@code planning.trip:read}. The penalty
 * suggestions are for whoever keys a carrier's invoice ({@code settlement.invoice:manage}).
 */
@RestController
@RequestMapping("${tms.api.base-path}/settlement/claims")
@Tag(name = "Freight claims", description = "Claims against carriers for damage, shortage, loss and delay")
public class FreightClaimController {

    private final FreightClaimService claimService;
    private final FreightClaimEvidenceService evidenceService;

    public FreightClaimController(FreightClaimService claimService, FreightClaimEvidenceService evidenceService) {
        this.claimService = claimService;
        this.evidenceService = evidenceService;
    }

    @GetMapping
    @PreAuthorize("hasAuthority('settlement.claim:read')")
    @Operation(summary = "The company's freight claims, newest first, filtered by status, carrier, type, trip and period")
    @Parameter(name = "X-Company-Id", in = ParameterIn.HEADER, required = true,
            description = "Id of a company the caller is a member of")
    public PageResponse<ClaimSummary> list(CompanyScope scope,
            @RequestParam(required = false) List<ClaimStatus> status,
            @RequestParam(required = false) UUID carrierId,
            @RequestParam(required = false) ClaimType claimType,
            @RequestParam(required = false) UUID tripId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) String search,
            @ModelAttribute PageQuery pageQuery) {
        return claimService.list(scope,
                new FreightClaimService.ClaimFilter(status, carrierId, claimType, tripId, from, to, search), pageQuery);
    }

    @GetMapping("/overview")
    @PreAuthorize("hasAuthority('settlement.claim:read')")
    @Operation(summary = "Active claims by status and currency, and how old they are")
    @Parameter(name = "X-Company-Id", in = ParameterIn.HEADER, required = true,
            description = "Id of a company the caller is a member of")
    public ClaimsOverview overview(CompanyScope scope) {
        return claimService.overview(scope);
    }

    @GetMapping("/draft")
    @PreAuthorize("hasAuthority('settlement.claim:read') and hasAuthority('planning.trip:read')")
    @Operation(summary = "What a new claim would say, from a trip, one of its deliveries or one of its exceptions."
            + " Writes nothing")
    @Parameter(name = "X-Company-Id", in = ParameterIn.HEADER, required = true,
            description = "Id of a company the caller is a member of")
    public ClaimDraft draft(CompanyScope scope, @RequestParam(required = false) UUID tripId,
            @RequestParam(required = false) UUID orderDeliveryId,
            @RequestParam(required = false) UUID tripExceptionId) {
        return claimService.draft(scope, tripId, orderDeliveryId, tripExceptionId);
    }

    @GetMapping("/penalty-suggestions")
    @PreAuthorize("hasAuthority('settlement.invoice:manage')")
    @Operation(summary = "Accepted claims an invoice of this carrier could deduct as PENALTY lines."
            + " A proposal: nothing is applied until the invoice is saved with the line")
    @Parameter(name = "X-Company-Id", in = ParameterIn.HEADER, required = true,
            description = "Id of a company the caller is a member of")
    public List<PenaltySuggestion> penaltySuggestions(CompanyScope scope, @RequestParam UUID carrierId,
            @RequestParam String currency, @RequestParam(required = false) List<UUID> tripId) {
        return claimService.penaltySuggestions(scope, carrierId, currency, tripId);
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('settlement.claim:read')")
    @Operation(summary = "One claim with its lines, evidence, the penalties that recover it and what can be done next")
    @Parameter(name = "X-Company-Id", in = ParameterIn.HEADER, required = true,
            description = "Id of a company the caller is a member of")
    public ClaimDetail get(CompanyScope scope, @PathVariable UUID id) {
        return claimService.get(scope, id);
    }

    @PostMapping
    @PreAuthorize("hasAuthority('settlement.claim:manage')")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Open a claim against the carrier of a trip. The carrier is the trip's, never sent")
    @Parameter(name = "X-Company-Id", in = ParameterIn.HEADER, required = true,
            description = "Id of a company the caller is a member of")
    public ClaimDetail open(CompanyScope scope, @Valid @RequestBody FreightClaimRequests.OpenClaim request) {
        return claimService.open(scope, request);
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasAuthority('settlement.claim:manage')")
    @Operation(summary = "Correct an OPEN claim: its type, date, description, amount and lines")
    @Parameter(name = "X-Company-Id", in = ParameterIn.HEADER, required = true,
            description = "Id of a company the caller is a member of")
    public ClaimDetail update(CompanyScope scope, @PathVariable UUID id,
            @Valid @RequestBody FreightClaimRequests.UpdateClaim request) {
        return claimService.update(scope, id, request);
    }

    @PostMapping("/{id}/submit")
    @PreAuthorize("hasAuthority('settlement.claim:manage')")
    @Operation(summary = "Send an OPEN claim for review. From here on it is frozen until decided")
    @Parameter(name = "X-Company-Id", in = ParameterIn.HEADER, required = true,
            description = "Id of a company the caller is a member of")
    public ClaimDetail submit(CompanyScope scope, @PathVariable UUID id) {
        return claimService.submit(scope, id);
    }

    @PostMapping("/{id}/decision")
    @PreAuthorize("hasAuthority('settlement.claim:decide')")
    @Operation(summary = "Accept, partially accept or reject a claim in review. Whoever opened it cannot accept it")
    @Parameter(name = "X-Company-Id", in = ParameterIn.HEADER, required = true,
            description = "Id of a company the caller is a member of")
    public ClaimDetail decide(CompanyScope scope, @PathVariable UUID id,
            @Valid @RequestBody FreightClaimRequests.Decide request) {
        return claimService.decide(scope, id, request);
    }

    @PostMapping("/{id}/close")
    @PreAuthorize("hasAuthority('settlement.claim:manage')")
    @Operation(summary = "File a decided claim; an accepted one names the invoice or credit note that recovered it")
    @Parameter(name = "X-Company-Id", in = ParameterIn.HEADER, required = true,
            description = "Id of a company the caller is a member of")
    public ClaimDetail close(CompanyScope scope, @PathVariable UUID id,
            @Valid @RequestBody FreightClaimRequests.Close request) {
        return claimService.close(scope, id, request);
    }

    @PostMapping("/{id}/cancel")
    @PreAuthorize("hasAuthority('settlement.claim:manage')")
    @Operation(summary = "Withdraw a claim before a decision, saying why")
    @Parameter(name = "X-Company-Id", in = ParameterIn.HEADER, required = true,
            description = "Id of a company the caller is a member of")
    public ClaimDetail cancel(CompanyScope scope, @PathVariable UUID id,
            @Valid @RequestBody FreightClaimRequests.Cancel request) {
        return claimService.cancel(scope, id, request);
    }

    @PostMapping(path = "/{id}/evidence", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("hasAuthority('settlement.claim:manage')")
    @Operation(summary = "Attach a photo or document to a claim. Needs a configured evidence store (503 otherwise)")
    @Parameter(name = "X-Company-Id", in = ParameterIn.HEADER, required = true,
            description = "Id of a company the caller is a member of")
    public ClaimDetail attachEvidence(CompanyScope scope, @PathVariable UUID id,
            @RequestParam(name = "description", required = false) String description,
            @RequestParam("file") MultipartFile file) {
        return evidenceService.attach(scope, id, description, toFile(file));
    }

    /**
     * The bytes of one piece of evidence: saved rather than rendered inside the app's own origin, and
     * kept by no proxy - the same headers as delivery evidence.
     */
    @GetMapping("/{id}/evidence/{evidenceId}/content")
    @PreAuthorize("hasAuthority('settlement.claim:read')")
    @Operation(summary = "Download one piece of claim evidence")
    @Parameter(name = "X-Company-Id", in = ParameterIn.HEADER, required = true,
            description = "Id of a company the caller is a member of")
    public ResponseEntity<Resource> downloadEvidence(CompanyScope scope, @PathVariable UUID id,
            @PathVariable UUID evidenceId) {
        ClaimEvidenceFile download = evidenceService.download(scope, id, evidenceId);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
                        .filename(download.fileName(), StandardCharsets.UTF_8)
                        .build()
                        .toString())
                .contentType(MediaType.parseMediaType(download.contentType()))
                .contentLength(download.sizeBytes())
                .cacheControl(CacheControl.noStore())
                .body(new InputStreamResource(download.content()));
    }

    /** The part, reduced to what the use case takes; the name cut to its last path segment. */
    private static ClaimEvidenceFile toFile(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new InvalidRequestException("A file is required.");
        }
        try {
            return new ClaimEvidenceFile(file.getContentType(), bareNameOf(file.getOriginalFilename()), file.getSize(),
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
}
