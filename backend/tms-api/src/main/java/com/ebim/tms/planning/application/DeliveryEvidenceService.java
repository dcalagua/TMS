package com.ebim.tms.planning.application;

import com.ebim.tms.planning.domain.DeliveryEvidence;
import com.ebim.tms.planning.domain.EvidenceType;
import com.ebim.tms.planning.domain.OrderDelivery;
import com.ebim.tms.planning.domain.Trip;
import com.ebim.tms.planning.infrastructure.DeliveryEvidenceRepository;
import com.ebim.tms.planning.infrastructure.TripRepository;
import com.ebim.tms.shared.api.InvalidRequestException;
import com.ebim.tms.shared.api.ResourceNotFoundException;
import com.ebim.tms.shared.audit.AuditAction;
import com.ebim.tms.shared.audit.AuditAggregateType;
import com.ebim.tms.shared.audit.AuditActorProvider;
import com.ebim.tms.shared.audit.AuditRecorder;
import com.ebim.tms.shared.security.CompanyScope;
import com.ebim.tms.shared.storage.EvidenceStoragePort;
import com.ebim.tms.shared.storage.EvidenceStorageProperties;
import com.ebim.tms.shared.storage.StoredObject;
import com.ebim.tms.shared.storage.StoredObjectContent;
import java.io.IOException;
import java.io.InputStream;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The proof behind a delivery result (migration V28): attaching a signature, a photo or a signed
 * note, and serving one back.
 *
 * <p><b>Bytes first, row second.</b> The object is written to the store and only then is the
 * metadata row inserted. The failure modes are not symmetric: a row pointing at an object that was
 * never stored is a piece of evidence that cannot be produced - worse than useless in the dispute
 * it exists for - while an object with no row is an orphan a sweep can find. If the transaction
 * rolls back after the write, the object is orphaned and nothing points at it.
 *
 * <p><b>What is checked before anything is stored.</b> The delivery exists in this company and on
 * this trip; the media type is on the deployment's allow-list; there is a file at all. The size is
 * checked by the store <em>while writing</em>, because a chunked upload need not declare its length
 * and the only number that cannot be lied about is the count of bytes that actually arrived.
 *
 * <p><b>There is no delete.</b> V1 never removes evidence, and the database withholds the grant -
 * see migration V28. A wrong photo is answered by uploading the right one beside it and saying so
 * in the delivery's notes.
 */
@Service
public class DeliveryEvidenceService {

    private final TripRepository tripRepository;
    private final TripDeliveryService deliveryService;
    private final DeliveryEvidenceRepository evidenceRepository;
    private final EvidenceStoragePort storage;
    private final EvidenceStorageProperties properties;
    private final AuditRecorder auditRecorder;
    private final AuditActorProvider auditActorProvider;
    private final TripViewAssembler assembler;

    public DeliveryEvidenceService(TripRepository tripRepository, TripDeliveryService deliveryService,
            DeliveryEvidenceRepository evidenceRepository, EvidenceStoragePort storage,
            EvidenceStorageProperties properties, AuditRecorder auditRecorder,
            AuditActorProvider auditActorProvider, TripViewAssembler assembler) {
        this.tripRepository = tripRepository;
        this.deliveryService = deliveryService;
        this.evidenceRepository = evidenceRepository;
        this.storage = storage;
        this.properties = properties;
        this.auditRecorder = auditRecorder;
        this.auditActorProvider = auditActorProvider;
        this.assembler = assembler;
    }

    /**
     * Attaches one artefact to a delivery and returns the trip, so the screen that uploaded it
     * re-renders from one answer.
     *
     * @param capturedAt when the photo was taken or the signature captured, when the operator knows
     *     it differs from the upload; null otherwise. Not validated against the delivery's own time:
     *     a photo of a damaged pallet taken in the yard the next morning is legitimate evidence
     */
    @Transactional
    public TripDetailView attach(CompanyScope scope, UUID tripId, UUID deliveryId, EvidenceType evidenceType,
            OffsetDateTime capturedAt, EvidenceUpload upload) {
        Trip trip = tripRepository.findByIdAndCompanyId(tripId, scope.companyId())
                .orElseThrow(() -> new ResourceNotFoundException("Trip not found."));
        OrderDelivery delivery = deliveryService.requireDelivery(scope, tripId, deliveryId);

        String contentType = requireAcceptedType(upload);
        String fileName = upload.fileName();

        StoredObject stored;
        try (InputStream content = upload.content()) {
            stored = storage.store(scope.companyId(), contentType, fileName, content, properties.maxBytes());
        } catch (IOException unreadable) {
            throw new InvalidRequestException("The uploaded file could not be read. Try uploading it again.");
        }

        DeliveryEvidence evidence = evidenceRepository.saveAndFlush(new DeliveryEvidence(
                scope.companyId(), delivery.id(), evidenceType, stored.storageKey(), contentType,
                stored.sizeBytes(), stored.checksumSha256(), fileName, capturedAt,
                auditActorProvider.requireAppUserId()));

        // A plain UPDATE on the shipment rather than an action of its own, and no timeline entry.
        // The fact worth recording twice is the delivery *result* - that is what a claim is argued
        // from, and it has DELIVERY_RESULT_RECORDED. An attachment is corroboration of a fact
        // already logged, and the row it produces is itself append-only and stamped with who
        // uploaded it and when; a second vocabulary for it would be a state nobody reads.
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("shipmentNumber", trip.shipmentNumber());
        detail.put("deliveryId", delivery.id().toString());
        detail.put("evidenceId", evidence.id().toString());
        detail.put("evidenceType", evidenceType.name());
        detail.put("sizeBytes", stored.sizeBytes());
        auditRecorder.record(scope, AuditAggregateType.SHIPMENT, trip.id(), AuditAction.UPDATE, detail);

        return assembler.toDetail(trip, scope.companyId());
    }

    /**
     * Opens one artefact for download.
     *
     * <p>Three checks stand between a caller and the bytes, and the first two are why this is not a
     * static file server: the delivery belongs to this company and this trip, the evidence belongs
     * to that delivery, and only then is the key handed to the store - which checks a third time
     * that the key names this company's subtree.
     */
    @Transactional(readOnly = true)
    public DeliveryEvidenceDownload download(CompanyScope scope, UUID tripId, UUID deliveryId, UUID evidenceId) {
        OrderDelivery delivery = deliveryService.requireDelivery(scope, tripId, deliveryId);
        DeliveryEvidence evidence = evidenceRepository.findByIdAndCompanyId(evidenceId, scope.companyId())
                .orElseThrow(() -> new ResourceNotFoundException("Evidence not found."));
        if (!evidence.orderDeliveryId().equals(delivery.id())) {
            throw new ResourceNotFoundException("Evidence not found on this delivery.");
        }

        StoredObjectContent content = storage.open(scope.companyId(), evidence.storageKey());
        return new DeliveryEvidenceDownload(evidence.contentType(), downloadNameOf(evidence),
                content.sizeBytes(), content.stream());
    }

    /**
     * The media type, checked against the deployment's allow-list before a byte is read.
     *
     * <p>Taken from the part's own {@code Content-Type} and never from the file extension: the
     * extension is the caller's suggestion, and the store picks its own from this value anyway. A
     * refusal names the accepted types, because "unsupported file" without a list is a support
     * ticket.
     */
    private String requireAcceptedType(EvidenceUpload upload) {
        if (upload == null || upload.content() == null) {
            throw new InvalidRequestException("A file is required.");
        }
        String contentType = upload.contentType();
        if (!properties.allows(contentType)) {
            throw new InvalidRequestException("Delivery evidence must be one of "
                    + String.join(", ", properties.allowedContentTypes()) + ".");
        }
        return contentType.trim().toLowerCase(Locale.ROOT);
    }

    /** What the browser saves it as: the uploader's name, or one built from the artefact's identity. */
    private static String downloadNameOf(DeliveryEvidence evidence) {
        if (evidence.originalFilename() != null) {
            return evidence.originalFilename();
        }
        String extension = evidence.storageKey().substring(evidence.storageKey().lastIndexOf('.') + 1);
        return evidence.evidenceType().name().toLowerCase(Locale.ROOT) + "-" + evidence.id() + "."
                + extension;
    }
}
