package com.ebim.tms.settlement.application;

import com.ebim.tms.settlement.application.FreightClaimViews.ClaimDetail;
import com.ebim.tms.settlement.domain.FreightClaim;
import com.ebim.tms.settlement.domain.FreightClaimEvidence;
import com.ebim.tms.settlement.infrastructure.FreightClaimEvidenceRepository;
import com.ebim.tms.settlement.infrastructure.FreightClaimRepository;
import com.ebim.tms.shared.api.InvalidRequestException;
import com.ebim.tms.shared.api.ResourceNotFoundException;
import com.ebim.tms.shared.audit.AuditAction;
import com.ebim.tms.shared.audit.AuditActorProvider;
import com.ebim.tms.shared.audit.AuditAggregateType;
import com.ebim.tms.shared.audit.AuditRecorder;
import com.ebim.tms.shared.security.CompanyScope;
import com.ebim.tms.shared.storage.EvidenceStoragePort;
import com.ebim.tms.shared.storage.EvidenceStorageProperties;
import com.ebim.tms.shared.storage.StoredObject;
import com.ebim.tms.shared.storage.StoredObjectContent;
import java.io.IOException;
import java.io.InputStream;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The photos and documents behind a freight claim (V71), through the same private store as proof of
 * delivery ({@code EvidenceStoragePort}, ADR-006 / ADR-020) and with the same rules as
 * {@code DeliveryEvidenceService}: bytes first, row second; the media type is the deployment's
 * allow-list; the size is checked by the store while writing; nothing is ever deleted. Where no store
 * is configured both endpoints answer 503 {@code storage-unavailable} and the claim itself is
 * unaffected - the claim is the fact, a photo corroborates it.
 */
@Service
public class FreightClaimEvidenceService {

    private final FreightClaimRepository claimRepository;
    private final FreightClaimEvidenceRepository evidenceRepository;
    private final FreightClaimService claimService;
    private final EvidenceStoragePort storage;
    private final EvidenceStorageProperties properties;
    private final AuditActorProvider auditActorProvider;
    private final AuditRecorder auditRecorder;

    public FreightClaimEvidenceService(FreightClaimRepository claimRepository,
            FreightClaimEvidenceRepository evidenceRepository, FreightClaimService claimService,
            EvidenceStoragePort storage, EvidenceStorageProperties properties, AuditActorProvider auditActorProvider,
            AuditRecorder auditRecorder) {
        this.claimRepository = claimRepository;
        this.evidenceRepository = evidenceRepository;
        this.claimService = claimService;
        this.storage = storage;
        this.properties = properties;
        this.auditActorProvider = auditActorProvider;
        this.auditRecorder = auditRecorder;
    }

    @Transactional
    public ClaimDetail attach(CompanyScope scope, UUID claimId, String description, ClaimEvidenceFile upload) {
        UUID actor = auditActorProvider.requireAppUserId();
        FreightClaim claim = claimRepository.findByIdAndCompanyId(claimId, scope.companyId())
                .orElseThrow(() -> new ResourceNotFoundException("Claim not found."));
        claim.requireAcceptsEvidence();
        String contentType = requireAcceptedType(upload);
        String caption = description == null || description.isBlank() ? null : description.trim();
        if (caption != null && caption.length() > 500) {
            throw new InvalidRequestException("A caption is at most 500 characters.");
        }

        StoredObject stored;
        try (InputStream content = upload.content()) {
            stored = storage.store(scope.companyId(), contentType, upload.fileName(), content, properties.maxBytes());
        } catch (IOException unreadable) {
            throw new InvalidRequestException("The uploaded file could not be read. Try uploading it again.");
        }
        FreightClaimEvidence evidence = evidenceRepository.saveAndFlush(new FreightClaimEvidence(scope.companyId(),
                claim.id(), stored.storageKey(), contentType, stored.sizeBytes(), stored.checksumSha256(),
                upload.fileName(), caption, actor));

        Map<String, Object> details = new LinkedHashMap<>();
        details.put("claimNumber", claim.claimNumber());
        details.put("evidenceId", evidence.id().toString());
        details.put("contentType", contentType);
        details.put("sizeBytes", stored.sizeBytes());
        auditRecorder.record(scope, AuditAggregateType.FREIGHT_CLAIM, claim.id(), AuditAction.CLAIM_EVIDENCE_ADDED,
                details);
        return claimService.get(scope, claim.id());
    }

    /** The claim, then the evidence of that claim, then the store's own check that the key is this company's. */
    @Transactional(readOnly = true)
    public ClaimEvidenceFile download(CompanyScope scope, UUID claimId, UUID evidenceId) {
        FreightClaim claim = claimService.find(scope, claimId);
        FreightClaimEvidence evidence = evidenceRepository.findByIdAndCompanyId(evidenceId, scope.companyId())
                .filter(found -> found.freightClaimId().equals(claim.id()))
                .orElseThrow(() -> new ResourceNotFoundException("Evidence not found on this claim."));
        StoredObjectContent content = storage.open(scope.companyId(), evidence.storageKey());
        int dot = evidence.storageKey().lastIndexOf('.');
        String name = evidence.originalFilename() != null ? evidence.originalFilename()
                : "claim-" + claim.claimNumber() + "-" + evidence.id()
                        + (dot < 0 ? "" : evidence.storageKey().substring(dot));
        return new ClaimEvidenceFile(evidence.contentType(), name, content.sizeBytes(), content.stream());
    }

    private String requireAcceptedType(ClaimEvidenceFile upload) {
        if (upload == null || upload.content() == null) {
            throw new InvalidRequestException("A file is required.");
        }
        if (!properties.allows(upload.contentType())) {
            throw new InvalidRequestException("Claim evidence must be one of "
                    + String.join(", ", properties.allowedContentTypes()) + ".");
        }
        return upload.contentType().trim().toLowerCase(Locale.ROOT);
    }
}
