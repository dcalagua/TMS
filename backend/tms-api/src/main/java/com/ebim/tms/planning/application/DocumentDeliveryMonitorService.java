package com.ebim.tms.planning.application;

import com.ebim.tms.planning.domain.DocumentDelivery;
import com.ebim.tms.planning.domain.DocumentDeliveryEvent;
import com.ebim.tms.planning.domain.DocumentDeliveryEvidence;
import com.ebim.tms.planning.domain.DocumentDeliveryStatus;
import com.ebim.tms.planning.infrastructure.DocumentDeliveryEventRepository;
import com.ebim.tms.planning.infrastructure.DocumentDeliveryEvidenceRepository;
import com.ebim.tms.planning.infrastructure.DocumentDeliveryQueries;
import com.ebim.tms.planning.infrastructure.DocumentDeliveryQueries.MonitorFilter;
import com.ebim.tms.planning.infrastructure.DocumentDeliveryQueries.MonitorRow;
import com.ebim.tms.planning.infrastructure.DocumentDeliveryQueries.StatusTotals;
import com.ebim.tms.planning.infrastructure.DocumentDeliveryRepository;
import com.ebim.tms.shared.api.ConflictException;
import com.ebim.tms.shared.api.InvalidRequestException;
import com.ebim.tms.shared.api.PageQuery;
import com.ebim.tms.shared.api.PageResponse;
import com.ebim.tms.shared.api.ResourceNotFoundException;
import com.ebim.tms.shared.audit.AuditAction;
import com.ebim.tms.shared.audit.AuditActor;
import com.ebim.tms.shared.audit.AuditActorProvider;
import com.ebim.tms.shared.audit.AuditAggregateType;
import com.ebim.tms.shared.audit.AuditRecorder;
import com.ebim.tms.shared.security.CompanyScope;
import java.time.Clock;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The office's view of document delivery (ADR-018): what every carrier reported, what is still
 * pending, what disagrees with the goods' delivery, and the confirmation that the signed copy is back.
 */
@Service
public class DocumentDeliveryMonitorService {

    /** A monitor over a quarter is a report, not a screen; the dispatch-date filter says so. */
    static final long MAX_RANGE_DAYS = 92;

    private final DocumentDeliveryQueries queries;
    private final DocumentDeliveryRepository deliveries;
    private final DocumentDeliveryEventRepository events;
    private final DocumentDeliveryEvidenceRepository evidence;
    private final DocumentEvidenceStore evidenceStore;
    private final DocumentDeliveryViews views;
    private final AuditActorProvider actors;
    private final AuditRecorder audit;
    private final Clock clock;

    @SuppressWarnings("java:S107")
    public DocumentDeliveryMonitorService(DocumentDeliveryQueries queries, DocumentDeliveryRepository deliveries,
            DocumentDeliveryEventRepository events, DocumentDeliveryEvidenceRepository evidence,
            DocumentEvidenceStore evidenceStore, DocumentDeliveryViews views, AuditActorProvider actors,
            AuditRecorder audit, Clock clock) {
        this.queries = queries;
        this.deliveries = deliveries;
        this.events = events;
        this.evidence = evidence;
        this.evidenceStore = evidenceStore;
        this.views = views;
        this.actors = actors;
        this.audit = audit;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public PageResponse<DocumentDeliveryRowView> list(CompanyScope scope, DocumentDeliveryFilter filter,
            PageQuery pageQuery) {
        MonitorFilter resolved = resolve(scope, filter, null);
        List<MonitorRow> page = queries.monitorPage(resolved, pageQuery.pageSize(), pageQuery.offset());
        long total = queries.monitorCount(resolved);
        return PageResponse.of(views.rows(scope.companyId(), page), pageQuery, total);
    }

    @Transactional(readOnly = true)
    public DocumentDeliverySummaryView summary(CompanyScope scope, DocumentDeliveryFilter filter) {
        List<StatusTotals> byCarrier = queries.totalsByCarrier(resolve(scope, filter, null));
        Map<UUID, String> names = views.carrierNames(scope.companyId(), byCarrier.stream()
                .map(StatusTotals::carrierId).filter(Objects::nonNull).collect(Collectors.toSet()));
        return new DocumentDeliverySummaryView(
                byCarrier.stream().mapToLong(StatusTotals::total).sum(),
                byCarrier.stream().mapToLong(StatusTotals::pending).sum(),
                byCarrier.stream().mapToLong(StatusTotals::delivered).sum(),
                byCarrier.stream().mapToLong(StatusTotals::rejected).sum(),
                byCarrier.stream().mapToLong(StatusTotals::notDelivered).sum(),
                byCarrier.stream().mapToLong(StatusTotals::returned).sum(),
                byCarrier.stream().mapToLong(StatusTotals::awaitingReturn).sum(),
                byCarrier.stream().mapToLong(StatusTotals::inconsistent).sum(),
                byCarrier.stream().map(totals -> new CarrierDocumentTotalsView(totals.carrierId(),
                        totals.carrierId() == null ? null : names.get(totals.carrierId()), totals.total(),
                        totals.pending(), totals.delivered(), totals.rejected(), totals.notDelivered(),
                        totals.awaitingReturn())).toList());
    }

    @Transactional(readOnly = true)
    public DocumentDeliveryDetailView detail(CompanyScope scope, UUID id) {
        DocumentDelivery delivery = deliveries.findByIdAndCompanyId(id, scope.companyId())
                .orElseThrow(() -> new ResourceNotFoundException("Document delivery not found."));
        return detailOf(scope, delivery);
    }

    /**
     * The office has the signed copy - or the undelivered original - back. Closes the document: the
     * carrier can no longer correct the report.
     */
    @Transactional
    public DocumentDeliveryDetailView confirmReturn(CompanyScope scope, UUID id, DocumentReturnRequest request) {
        DocumentDelivery delivery = deliveries.findForUpdate(id, scope.companyId())
                .orElseThrow(() -> new ResourceNotFoundException("Document delivery not found."));
        if (delivery.status() == DocumentDeliveryStatus.PENDING) {
            throw new ConflictException("The carrier has not reported this document yet.");
        }
        if (delivery.isReturned()) {
            throw new ConflictException("The return of this document is already confirmed.");
        }
        UUID actorId = actors.requireAppUserId();
        String actorLabel = actors.current().map(AuditActor::email).orElse(null);
        String remark = request == null || request.remark() == null || request.remark().isBlank()
                ? null : request.remark().trim();

        delivery.confirmReturn(OffsetDateTime.now(clock), actorId, actorLabel, remark);
        deliveries.saveAndFlush(delivery);
        events.saveAndFlush(DocumentDeliveryEvent.returned(delivery, actorId, actorLabel));

        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("documentType", delivery.documentType());
        detail.put("documentNumber", delivery.documentNumber());
        detail.put("returned", true);
        audit.record(scope, AuditAggregateType.SHIPMENT, delivery.tripId(), AuditAction.UPDATE, detail);
        return detailOf(scope, delivery);
    }

    @Transactional(readOnly = true)
    public DeliveryEvidenceDownload download(CompanyScope scope, UUID id, UUID evidenceId) {
        DocumentDelivery delivery = deliveries.findByIdAndCompanyId(id, scope.companyId())
                .orElseThrow(() -> new ResourceNotFoundException("Document delivery not found."));
        DocumentDeliveryEvidence artefact = evidence
                .findByIdAndCompanyIdAndDocumentDeliveryId(evidenceId, scope.companyId(), delivery.id())
                .orElseThrow(() -> new ResourceNotFoundException("Evidence not found on this document."));
        return PortalTripService.downloadOf(evidenceStore, scope.companyId(), artefact);
    }

    private DocumentDeliveryDetailView detailOf(CompanyScope scope, DocumentDelivery delivery) {
        MonitorRow row = queries.monitorPage(new MonitorFilter(scope.companyId(), null, null, null, null, null,
                null, delivery.id()), 1, 0).stream().findFirst()
                .orElseThrow(() -> new ResourceNotFoundException("Document delivery not found."));
        DocumentDeliveryRowView view = views.rows(scope.companyId(), List.of(row)).get(0);

        List<DocumentDeliveryEvent> history =
                events.findByCompanyIdAndDocumentDeliveryIdOrderByRecordedAtAsc(scope.companyId(), delivery.id());
        Map<UUID, String> actorByEvent = history.stream().filter(event -> event.actorLabel() != null)
                .collect(Collectors.toMap(DocumentDeliveryEvent::id, DocumentDeliveryEvent::actorLabel));
        List<DocumentEvidenceView> artefacts = evidence
                .findByCompanyIdAndDocumentDeliveryIdInOrderByUploadedAtAsc(scope.companyId(), List.of(delivery.id()))
                .stream()
                .map(item -> new DocumentEvidenceView(item.id(), item.evidenceType(), item.contentType(),
                        item.sizeBytes(), item.originalFilename(), item.uploadedAt(), actorByEvent.get(item.eventId())))
                .toList();
        return new DocumentDeliveryDetailView(view, history.stream()
                .map(event -> new DocumentDeliveryEventView(event.id(), event.eventType(), event.status(),
                        event.reasonCode(), event.remark(), event.reportedAt(), event.actorLabel(),
                        event.recordedAt()))
                .toList(), artefacts);
    }

    private static MonitorFilter resolve(CompanyScope scope, DocumentDeliveryFilter filter, UUID id) {
        ZoneId zone = scope.zoneId();
        LocalDate from = filter == null ? null : filter.dateFrom();
        LocalDate to = filter == null ? null : filter.dateTo();
        if (from != null && to != null) {
            if (to.isBefore(from)) {
                throw new InvalidRequestException("dateTo must not be before dateFrom.");
            }
            if (ChronoUnit.DAYS.between(from, to) > MAX_RANGE_DAYS) {
                throw new InvalidRequestException("The date range may span at most " + MAX_RANGE_DAYS + " days.");
            }
        }
        return new MonitorFilter(scope.companyId(),
                from == null ? null : from.atStartOfDay(zone).toOffsetDateTime(),
                to == null ? null : to.plusDays(1).atStartOfDay(zone).toOffsetDateTime(),
                filter == null ? null : filter.carrierId(),
                filter == null ? null : filter.status(),
                filter == null ? null : filter.returned(),
                filter == null ? null : searchPattern(filter.q()),
                id);
    }

    /** A contains-match, with the LIKE wildcards in the user's text taken literally. */
    static String searchPattern(String q) {
        if (q == null || q.isBlank()) {
            return null;
        }
        String escaped = q.trim().replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
        return "%" + escaped + "%";
    }
}
