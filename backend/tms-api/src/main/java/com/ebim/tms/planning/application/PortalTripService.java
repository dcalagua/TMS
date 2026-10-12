package com.ebim.tms.planning.application;

import com.ebim.tms.planning.domain.DocumentDelivery;
import com.ebim.tms.planning.domain.DocumentDeliveryEvidence;
import com.ebim.tms.planning.domain.PlanningRun;
import com.ebim.tms.planning.domain.Trip;
import com.ebim.tms.planning.infrastructure.DocumentDeliveryEvidenceRepository;
import com.ebim.tms.planning.infrastructure.DocumentDeliveryQueries;
import com.ebim.tms.planning.infrastructure.DocumentDeliveryQueries.PortalTripRow;
import com.ebim.tms.planning.infrastructure.DocumentDeliveryRepository;
import com.ebim.tms.planning.infrastructure.PlanningRunRepository;
import com.ebim.tms.planning.infrastructure.TripRepository;
import com.ebim.tms.shared.api.InvalidRequestException;
import com.ebim.tms.shared.api.ResourceNotFoundException;
import com.ebim.tms.shared.reference.MasterReference;
import com.ebim.tms.shared.reference.OriginLookupPort;
import com.ebim.tms.shared.security.CompanyScope;
import com.ebim.tms.shared.storage.StoredObjectContent;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * What a carrier user reads in the portal (ADR-018): the dispatched trips assigned to their carrier
 * and the documents to report on each.
 *
 * <p>The carrier comes from the resolved {@link CompanyScope}, never from the request, and every
 * statement names it. The V57 policies narrow the same statements again in the database.
 */
@Service
public class PortalTripService {

    static final int MAX_DAYS = 90;
    static final int MAX_TRIPS = 200;

    private final DocumentDeliveryQueries queries;
    private final DocumentDeliveryRepository deliveries;
    private final DocumentDeliveryEvidenceRepository evidence;
    private final DocumentDeliveryViews views;
    private final DocumentEvidenceStore evidenceStore;
    private final TripRepository trips;
    private final PlanningRunRepository planningRuns;
    private final OriginLookupPort origins;
    private final TripViewAssembler assembler;
    private final Clock clock;

    @SuppressWarnings("java:S107")
    public PortalTripService(DocumentDeliveryQueries queries, DocumentDeliveryRepository deliveries,
            DocumentDeliveryEvidenceRepository evidence, DocumentDeliveryViews views,
            DocumentEvidenceStore evidenceStore, TripRepository trips, PlanningRunRepository planningRuns,
            OriginLookupPort origins, TripViewAssembler assembler, Clock clock) {
        this.queries = queries;
        this.deliveries = deliveries;
        this.evidence = evidence;
        this.views = views;
        this.evidenceStore = evidenceStore;
        this.trips = trips;
        this.planningRuns = planningRuns;
        this.origins = origins;
        this.assembler = assembler;
        this.clock = clock;
    }

    /** The carrier this caller acts for, or a refusal: the portal is for carrier users only. */
    static UUID requireCarrier(CompanyScope scope) {
        if (!scope.isCarrierScoped()) {
            throw new AccessDeniedException("The carrier portal is for carrier users.");
        }
        return scope.carrierId();
    }

    @Transactional(readOnly = true)
    public List<PortalTripSummaryView> list(CompanyScope scope, int days, boolean pendingOnly) {
        UUID carrierId = requireCarrier(scope);
        if (days < 1 || days > MAX_DAYS) {
            throw new InvalidRequestException("days must be between 1 and " + MAX_DAYS + ".");
        }
        OffsetDateTime since = OffsetDateTime.now(clock).minusDays(days);
        List<PortalTripRow> rows = queries.carrierTrips(scope.companyId(), carrierId, since, pendingOnly, MAX_TRIPS);
        return summaries(scope, rows);
    }

    @Transactional(readOnly = true)
    public PortalTripDetailView detail(CompanyScope scope, UUID tripId) {
        PortalTripRow row = visibleTrip(scope, tripId);
        Trip trip = trips.findByIdAndCompanyId(tripId, scope.companyId())
                .orElseThrow(() -> new ResourceNotFoundException("Trip not found."));
        TripDetailView plan = assembler.toDetail(trip, scope.companyId());

        List<DocumentDelivery> tracked =
                deliveries.findByCompanyIdAndTripIdOrderByDocumentTypeAscDocumentNumberAsc(scope.companyId(), tripId);
        List<PortalDocumentView> documents = views.portalDocuments(scope.companyId(), tracked);
        DocumentDeliveryViews.OrderLinks links =
                views.orderLinks(scope.companyId(), tracked.stream().map(DocumentDelivery::id).toList());

        // A document is delivered where its orders go: the stop of the first of its orders' destinations.
        Map<UUID, UUID> destinationByOrder = plan.assignments().stream()
                .filter(assignment -> assignment.destinationId() != null)
                .collect(Collectors.toMap(TripAssignmentView::orderId, TripAssignmentView::destinationId,
                        (left, right) -> left));
        Map<UUID, TripStopView> stopByDestination = plan.stops().stream()
                .collect(Collectors.toMap(TripStopView::destinationId, stop -> stop, (left, right) -> left));

        Map<UUID, List<PortalDocumentView>> byStop = new LinkedHashMap<>();
        List<PortalDocumentView> unplaced = new ArrayList<>();
        for (PortalDocumentView document : documents) {
            TripStopView stop = links.orderIdsOf(document.id()).stream()
                    .map(destinationByOrder::get).filter(Objects::nonNull)
                    .map(stopByDestination::get).filter(Objects::nonNull)
                    .min(Comparator.comparingInt(TripStopView::sequence))
                    .orElse(null);
            if (stop == null) {
                unplaced.add(document);
            } else {
                byStop.computeIfAbsent(stop.id(), key -> new ArrayList<>()).add(document);
            }
        }

        List<PortalStopView> stops = new ArrayList<>();
        plan.stops().stream().sorted(Comparator.comparingInt(TripStopView::sequence)).forEach(stop -> stops.add(
                new PortalStopView(stop.id(), stop.sequence(), stop.destinationName(), stop.address(),
                        stop.latitude(), stop.longitude(), byStop.getOrDefault(stop.id(), List.of()))));
        if (!unplaced.isEmpty()) {
            stops.add(new PortalStopView(null, null, null, null, null, null, unplaced));
        }
        return new PortalTripDetailView(summaries(scope, List.of(row)).get(0), evidenceStore.isEnabled(), stops);
    }

    /** The bytes of one artefact of a document on a trip this carrier may see. */
    @Transactional(readOnly = true)
    public DeliveryEvidenceDownload download(CompanyScope scope, UUID tripId, UUID documentId, UUID evidenceId) {
        visibleTrip(scope, tripId);
        DocumentDelivery delivery = deliveries
                .findByCompanyIdAndTripIdOrderByDocumentTypeAscDocumentNumberAsc(scope.companyId(), tripId).stream()
                .filter(item -> item.documentId().equals(documentId))
                .findFirst()
                .orElseThrow(() -> new ResourceNotFoundException("Document not found on this trip."));
        DocumentDeliveryEvidence artefact = evidence
                .findByIdAndCompanyIdAndDocumentDeliveryId(evidenceId, scope.companyId(), delivery.id())
                .orElseThrow(() -> new ResourceNotFoundException("Evidence not found on this document."));
        return downloadOf(evidenceStore, scope.companyId(), artefact);
    }

    PortalTripRow visibleTrip(CompanyScope scope, UUID tripId) {
        UUID carrierId = requireCarrier(scope);
        // One answer for "not yours", "not dispatched yet" and "does not exist": a carrier must not
        // learn which shipment numbers belong to somebody else.
        return queries.carrierTrip(scope.companyId(), carrierId, tripId)
                .orElseThrow(() -> new ResourceNotFoundException("Trip not found."));
    }

    private List<PortalTripSummaryView> summaries(CompanyScope scope, List<PortalTripRow> rows) {
        if (rows.isEmpty()) {
            return List.of();
        }
        Map<UUID, DocumentDeliveryViews.Crew> crews = views.crews(scope.companyId(), rows);
        Set<UUID> runIds = rows.stream().map(PortalTripRow::planningRunId).collect(Collectors.toSet());
        Map<UUID, UUID> originByRun = planningRuns.findAllById(runIds).stream()
                .filter(run -> run.companyId().equals(scope.companyId()))
                .filter(run -> run.originId() != null)
                .collect(Collectors.toMap(PlanningRun::id, PlanningRun::originId, (left, right) -> left));
        Set<UUID> originIds = Set.copyOf(originByRun.values());
        Map<UUID, MasterReference> originNames = originIds.isEmpty() ? Map.of()
                : origins.findAllInCompany(originIds, scope.companyId());

        return rows.stream().map(row -> {
            DocumentDeliveryViews.Crew crew = crews.get(row.tripId());
            UUID originId = originByRun.get(row.planningRunId());
            MasterReference origin = originId == null ? null : originNames.get(originId);
            return new PortalTripSummaryView(row.tripId(), row.shipmentNumber(), row.status(), row.planningDate(),
                    row.dispatchedAt(), origin == null ? null : origin.name(),
                    crew == null ? null : crew.plate(), crew == null ? null : crew.driverName(), row.stopCount(),
                    row.documentCount(), row.pendingCount(), row.deliveredCount(), row.rejectedCount(),
                    row.notDeliveredCount(), row.returnedCount());
        }).toList();
    }

    static DeliveryEvidenceDownload downloadOf(DocumentEvidenceStore store, UUID companyId,
            DocumentDeliveryEvidence artefact) {
        StoredObjectContent content = store.open(companyId, artefact.storageKey());
        String name = artefact.originalFilename();
        if (name == null) {
            String extension = artefact.storageKey().substring(artefact.storageKey().lastIndexOf('.') + 1);
            name = artefact.evidenceType().name().toLowerCase(Locale.ROOT) + "-" + artefact.id() + "." + extension;
        }
        return new DeliveryEvidenceDownload(artefact.contentType(), name, content.sizeBytes(), content.stream());
    }
}
