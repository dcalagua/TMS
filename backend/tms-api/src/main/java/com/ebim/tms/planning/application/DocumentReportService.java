package com.ebim.tms.planning.application;

import com.ebim.tms.planning.domain.DocumentDelivery;
import com.ebim.tms.planning.domain.DocumentDeliveryEvent;
import com.ebim.tms.planning.domain.DocumentDeliveryEvidence;
import com.ebim.tms.planning.domain.DocumentDeliveryReason;
import com.ebim.tms.planning.domain.DocumentDeliveryStatus;
import com.ebim.tms.planning.domain.DocumentEvidenceType;
import com.ebim.tms.planning.infrastructure.DocumentDeliveryEventRepository;
import com.ebim.tms.planning.infrastructure.DocumentDeliveryEvidenceRepository;
import com.ebim.tms.planning.infrastructure.DocumentDeliveryQueries.PortalTripRow;
import com.ebim.tms.planning.infrastructure.DocumentDeliveryRepository;
import com.ebim.tms.shared.api.ConflictException;
import com.ebim.tms.shared.api.IdempotencyConflictException;
import com.ebim.tms.shared.api.InvalidRequestException;
import com.ebim.tms.shared.api.ResourceNotFoundException;
import com.ebim.tms.shared.audit.AuditAction;
import com.ebim.tms.shared.audit.AuditActor;
import com.ebim.tms.shared.audit.AuditActorProvider;
import com.ebim.tms.shared.audit.AuditAggregateType;
import com.ebim.tms.shared.audit.AuditRecorder;
import com.ebim.tms.shared.security.CompanyScope;
import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * A carrier reports what happened to one document on one of its trips (ADR-018).
 *
 * <p><b>The rules.</b> {@code DELIVERED} needs at least one photo and carries no reason;
 * {@code REJECTED} and {@code NOT_DELIVERED} need a reason, and {@code OTHER} needs a remark. A
 * report may be corrected until the office confirms the return. Nothing here moves an order, a
 * stop or a trip.
 *
 * <p><b>Idempotency (ADR-018 section 5).</b> A phone on a bad network retries. The key is stored on
 * the event, unique per (company, user, key): the same key for the same document answers with the
 * current state and records nothing; for another document it is a 409.
 *
 * <p><b>Bytes first, row second</b>, as {@link DeliveryEvidenceService}: every file is checked
 * before any is written, then all are written, then the rows. A rollback after the writes leaves
 * orphans a sweep can find, never a row pointing at nothing.
 */
@Service
public class DocumentReportService {

    static final int MAX_PHOTOS = 5;
    static final int MAX_REMARK = 500;
    static final int MAX_KEY = 128;
    private static final Duration CLOCK_SKEW_TOLERANCE = Duration.ofMinutes(5);

    private final PortalTripService portal;
    private final DocumentDeliveryRepository deliveries;
    private final DocumentDeliveryEventRepository events;
    private final DocumentDeliveryEvidenceRepository evidence;
    private final DocumentEvidenceStore evidenceStore;
    private final DocumentDeliveryViews views;
    private final AuditActorProvider actors;
    private final AuditRecorder audit;
    private final Clock clock;

    @SuppressWarnings("java:S107")
    public DocumentReportService(PortalTripService portal, DocumentDeliveryRepository deliveries,
            DocumentDeliveryEventRepository events, DocumentDeliveryEvidenceRepository evidence,
            DocumentEvidenceStore evidenceStore, DocumentDeliveryViews views, AuditActorProvider actors,
            AuditRecorder audit, Clock clock) {
        this.portal = portal;
        this.deliveries = deliveries;
        this.events = events;
        this.evidence = evidence;
        this.evidenceStore = evidenceStore;
        this.views = views;
        this.actors = actors;
        this.audit = audit;
        this.clock = clock;
    }

    @Transactional
    public PortalDocumentView report(CompanyScope scope, UUID tripId, UUID documentId, String idempotencyKey,
            DocumentReportCommand command) {
        PortalTripService.requireCarrier(scope);
        String key = requireKey(idempotencyKey);
        UUID actorId = actors.requireAppUserId();
        String actorLabel = actors.current().map(AuditActor::email).orElse(null);

        Optional<PortalDocumentView> replay = replay(scope, actorId, key, tripId, documentId);
        if (replay.isPresent()) {
            return replay.get();
        }

        PortalTripRow trip = portal.visibleTrip(scope, tripId);
        DocumentDelivery delivery = deliveries.findForUpdate(scope.companyId(), tripId, documentId)
                .orElseThrow(() -> new ResourceNotFoundException("Document not found on this trip."));
        // Again under the row lock: the same request sent twice at once waits here for the first, and
        // must then answer from it rather than record a second report.
        replay = replay(scope, actorId, key, tripId, documentId);
        if (replay.isPresent()) {
            return replay.get();
        }
        if (!delivery.isOpenForReport()) {
            throw new ConflictException("The office already confirmed this document's return. It can no longer be "
                    + "changed.");
        }

        OffsetDateTime now = OffsetDateTime.now(clock);
        OffsetDateTime reportedAt = validate(command, now);
        command.photos().forEach(photo -> evidenceStore.requireAcceptable(DocumentEvidenceType.PHOTO, photo));
        if (command.signature() != null) {
            evidenceStore.requireAcceptable(DocumentEvidenceType.SIGNATURE, command.signature());
        }

        List<DocumentEvidenceStore.Stored> stored = new ArrayList<>();
        command.photos().forEach(photo ->
                stored.add(evidenceStore.store(scope.companyId(), DocumentEvidenceType.PHOTO, photo)));
        if (command.signature() != null) {
            stored.add(evidenceStore.store(scope.companyId(), DocumentEvidenceType.SIGNATURE, command.signature()));
        }

        delivery.report(command.status(), command.reasonCode(), blankToNull(command.remark()), reportedAt, now,
                actorId, actorLabel);
        deliveries.saveAndFlush(delivery);
        DocumentDeliveryEvent event;
        try {
            event = events.saveAndFlush(DocumentDeliveryEvent.reported(delivery, actorId, actorLabel, key));
        } catch (DataIntegrityViolationException raced) {
            // uq_document_delivery_event_idempotency, lost to the same request arriving twice at once.
            throw new IdempotencyConflictException("This report is already being recorded. Retry it.");
        }
        for (DocumentEvidenceStore.Stored item : stored) {
            evidence.save(new DocumentDeliveryEvidence(scope.companyId(), delivery.id(), event.id(), item.type(),
                    item.object().storageKey(), item.contentType(), item.object().sizeBytes(),
                    item.object().checksumSha256(), item.fileName(), null, actorId));
        }
        evidence.flush();

        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("shipmentNumber", trip.shipmentNumber());
        detail.put("documentType", delivery.documentType());
        detail.put("documentNumber", delivery.documentNumber());
        detail.put("status", delivery.status().name());
        if (delivery.reasonCode() != null) {
            detail.put("reasonCode", delivery.reasonCode().name());
        }
        detail.put("evidence", stored.size());
        audit.record(scope, AuditAggregateType.SHIPMENT, tripId, AuditAction.UPDATE, detail);

        return views.portalDocuments(scope.companyId(), List.of(delivery)).get(0);
    }

    private Optional<PortalDocumentView> replay(CompanyScope scope, UUID actorId, String key, UUID tripId,
            UUID documentId) {
        Optional<DocumentDeliveryEvent> previous = events.findByCompanyIdAndActorIdAndIdempotencyKey(
                scope.companyId(), actorId, key);
        if (previous.isEmpty()) {
            return Optional.empty();
        }
        DocumentDelivery delivery = deliveries.findByIdAndCompanyId(previous.get().documentDeliveryId(),
                scope.companyId()).orElseThrow(() -> new ResourceNotFoundException("Document not found."));
        if (!delivery.tripId().equals(tripId) || !delivery.documentId().equals(documentId)) {
            throw new IdempotencyConflictException("This Idempotency-Key was already used for another document.");
        }
        return Optional.of(views.portalDocuments(scope.companyId(), List.of(delivery)).get(0));
    }

    private static String requireKey(String key) {
        if (key == null || key.isBlank()) {
            throw new InvalidRequestException("The Idempotency-Key header is required.");
        }
        String trimmed = key.trim();
        if (trimmed.length() > MAX_KEY) {
            throw new InvalidRequestException("The Idempotency-Key header must be at most " + MAX_KEY + " characters.");
        }
        return trimmed;
    }

    /** The report's own rules, before any file is touched. Returns when it happened. */
    private OffsetDateTime validate(DocumentReportCommand command, OffsetDateTime now) {
        DocumentDeliveryStatus status = command.status();
        if (status == null || status == DocumentDeliveryStatus.PENDING) {
            throw new InvalidRequestException("status must be DELIVERED, REJECTED or NOT_DELIVERED.");
        }
        DocumentDeliveryReason reason = command.reasonCode();
        if (status.requiresReason() && reason == null) {
            throw new InvalidRequestException("A reason is required when the document was not delivered.");
        }
        if (!status.requiresReason() && reason != null) {
            throw new InvalidRequestException("A delivered document carries no reason.");
        }
        String remark = blankToNull(command.remark());
        if (reason == DocumentDeliveryReason.OTHER && remark == null) {
            throw new InvalidRequestException("Describe the reason in the remark when it is OTHER.");
        }
        if (remark != null && remark.length() > MAX_REMARK) {
            throw new InvalidRequestException("The remark must be at most " + MAX_REMARK + " characters.");
        }
        if (status == DocumentDeliveryStatus.DELIVERED && command.photos().isEmpty()) {
            throw new InvalidRequestException("A photo is required to report a document as delivered.");
        }
        if (command.photos().size() > MAX_PHOTOS) {
            throw new InvalidRequestException("At most " + MAX_PHOTOS + " photos per report.");
        }
        OffsetDateTime reportedAt = command.reportedAt() == null ? now : command.reportedAt();
        if (reportedAt.isAfter(now.plus(CLOCK_SKEW_TOLERANCE))) {
            throw new InvalidRequestException("reportedAt cannot be in the future.");
        }
        return reportedAt;
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
