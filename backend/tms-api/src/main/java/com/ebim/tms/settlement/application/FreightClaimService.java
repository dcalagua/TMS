package com.ebim.tms.settlement.application;

import com.ebim.tms.settlement.application.FreightClaimViews.AgeingBuckets;
import com.ebim.tms.settlement.application.FreightClaimViews.ClaimDetail;
import com.ebim.tms.settlement.application.FreightClaimViews.ClaimDraft;
import com.ebim.tms.settlement.application.FreightClaimViews.ClaimEvidenceView;
import com.ebim.tms.settlement.application.FreightClaimViews.ClaimLineView;
import com.ebim.tms.settlement.application.FreightClaimViews.ClaimSummary;
import com.ebim.tms.settlement.application.FreightClaimViews.ClaimsOverview;
import com.ebim.tms.settlement.application.FreightClaimViews.CurrencyTotal;
import com.ebim.tms.settlement.application.FreightClaimViews.DraftLine;
import com.ebim.tms.settlement.application.FreightClaimViews.ExistingClaim;
import com.ebim.tms.settlement.application.FreightClaimViews.PenaltyLineView;
import com.ebim.tms.settlement.application.FreightClaimViews.PenaltySuggestion;
import com.ebim.tms.settlement.domain.CarrierInvoice;
import com.ebim.tms.settlement.domain.ClaimStatus;
import com.ebim.tms.settlement.domain.ClaimType;
import com.ebim.tms.settlement.domain.FreightClaim;
import com.ebim.tms.settlement.domain.FreightClaimLine;
import com.ebim.tms.settlement.domain.InvoiceStatus;
import com.ebim.tms.settlement.domain.RecoveryMethod;
import com.ebim.tms.settlement.domain.tax.DocumentKind;
import com.ebim.tms.settlement.infrastructure.CarrierInvoiceRepository;
import com.ebim.tms.settlement.infrastructure.FreightClaimEvidenceRepository;
import com.ebim.tms.settlement.infrastructure.FreightClaimLineRepository;
import com.ebim.tms.settlement.infrastructure.FreightClaimQueries;
import com.ebim.tms.settlement.infrastructure.FreightClaimQueries.PenaltyApplication;
import com.ebim.tms.settlement.infrastructure.FreightClaimRepository;
import com.ebim.tms.shared.api.ConflictException;
import com.ebim.tms.shared.api.InvalidRequestException;
import com.ebim.tms.shared.api.PageQuery;
import com.ebim.tms.shared.api.PageResponse;
import com.ebim.tms.shared.api.ResourceNotFoundException;
import com.ebim.tms.shared.audit.AuditAction;
import com.ebim.tms.shared.audit.AuditActorProvider;
import com.ebim.tms.shared.audit.AuditAggregateType;
import com.ebim.tms.shared.audit.AuditRecorder;
import com.ebim.tms.shared.reference.CarrierLookupPort;
import com.ebim.tms.shared.reference.OrderLineSnapshot;
import com.ebim.tms.shared.reference.OrderReferencePort;
import com.ebim.tms.shared.reference.TripIncidentLookupPort;
import com.ebim.tms.shared.reference.TripIncidentLookupPort.ClaimableTrip;
import com.ebim.tms.shared.reference.TripIncidentLookupPort.DeliveryIncident;
import com.ebim.tms.shared.reference.TripIncidentLookupPort.ExceptionIncident;
import com.ebim.tms.shared.security.CompanyScope;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Freight claims (migration V71): opening a claim against the carrier of a trip, working it through
 * review and a decision, and filing it once the carrier's document that recovers it is in TMS.
 *
 * <h2>The boundary</h2>
 *
 * <p><b>TMS moves no money.</b> An accepted claim is recovered through what settlement already has
 * (V66): a PENALTY line on the carrier's invoice - proposed by {@link #penaltySuggestions} to the
 * person keying the invoice and applied only when they save it, checked by
 * {@link #requireRecoverablePenalties} - or a CREDIT_NOTE the carrier sends. Nothing here writes an
 * invoice.
 *
 * <p><b>What a claim is about is read, never written.</b> The trip, its delivery and its exception
 * belong to planning and are reached through {@link TripIncidentLookupPort}; the order and its
 * lines through {@link OrderReferencePort}. The carrier is always the trip's.
 *
 * <p><b>Four eyes on money.</b> Whoever opened a claim cannot accept it - {@link FreightClaim} and
 * {@code ck_freight_claim_resolution_terms} both say so.
 */
@Service
public class FreightClaimService {

    /** A decided claim the carrier owes on: what an invoice can deduct, and what is awaiting recovery. */
    static final Set<ClaimStatus> RECOVERABLE = EnumSet.of(ClaimStatus.ACCEPTED, ClaimStatus.PARTIALLY_ACCEPTED);

    private static final Set<String> SORTABLE = Set.of("openedAt", "claimNumber", "claimedAmount", "incidentDate");

    private final FreightClaimRepository claimRepository;
    private final FreightClaimLineRepository lineRepository;
    private final FreightClaimEvidenceRepository evidenceRepository;
    private final FreightClaimQueries queries;
    private final CarrierInvoiceRepository invoiceRepository;
    private final TripIncidentLookupPort incidents;
    private final OrderReferencePort orders;
    private final CarrierLookupPort carriers;
    private final AuditActorProvider auditActorProvider;
    private final AuditRecorder auditRecorder;
    private final Clock clock;

    public FreightClaimService(FreightClaimRepository claimRepository, FreightClaimLineRepository lineRepository,
            FreightClaimEvidenceRepository evidenceRepository, FreightClaimQueries queries,
            CarrierInvoiceRepository invoiceRepository, TripIncidentLookupPort incidents, OrderReferencePort orders,
            CarrierLookupPort carriers, AuditActorProvider auditActorProvider, AuditRecorder auditRecorder,
            Clock clock) {
        this.claimRepository = claimRepository;
        this.lineRepository = lineRepository;
        this.evidenceRepository = evidenceRepository;
        this.queries = queries;
        this.invoiceRepository = invoiceRepository;
        this.incidents = incidents;
        this.orders = orders;
        this.carriers = carriers;
        this.auditActorProvider = auditActorProvider;
        this.auditRecorder = auditRecorder;
        this.clock = clock;
    }

    /** The list's filters; every one optional. {@code from}/{@code to} are opening days in the company's zone. */
    public record ClaimFilter(List<ClaimStatus> statuses, UUID carrierId, ClaimType claimType, UUID tripId,
            LocalDate from, LocalDate to, String search) {
    }

    // ------------------------------------------------------------------ reading

    @Transactional(readOnly = true)
    public PageResponse<ClaimSummary> list(CompanyScope scope, ClaimFilter filter, PageQuery pageQuery) {
        Specification<FreightClaim> spec = specificationOf(scope, filter);
        Sort sort = Sort.by(Sort.Order.desc("openedAt"), Sort.Order.desc("claimNumber"));
        List<PageQuery.SortTerm> terms = pageQuery.sortTerms(SORTABLE);
        if (!terms.isEmpty()) {
            sort = Sort.by(terms.stream().map(term -> term.descending()
                    ? Sort.Order.desc(term.property()) : Sort.Order.asc(term.property())).toList());
        }
        Page<FreightClaim> page = claimRepository.findAll(spec,
                PageRequest.of(pageQuery.pageNumber(), pageQuery.pageSize(), sort));
        return new PageResponse<>(summaries(scope, page.getContent()), pageQuery.pageNumber(), pageQuery.pageSize(),
                page.getTotalElements());
    }

    /** The company's active claims: how many, how much per currency, and how old. */
    @Transactional(readOnly = true)
    public ClaimsOverview overview(CompanyScope scope) {
        long active = 0;
        long open = 0;
        long review = 0;
        long awaiting = 0;
        Map<String, BigDecimal[]> byCurrency = new LinkedHashMap<>();
        Map<String, Long> countByCurrency = new LinkedHashMap<>();
        for (FreightClaimQueries.StatusTotals row : queries.activeTotals(scope.companyId())) {
            active += row.count();
            ClaimStatus status = ClaimStatus.valueOf(row.status());
            if (status == ClaimStatus.OPEN) {
                open += row.count();
            } else if (status == ClaimStatus.UNDER_REVIEW) {
                review += row.count();
            } else if (RECOVERABLE.contains(status)) {
                awaiting += row.count();
            }
            BigDecimal[] totals = byCurrency.computeIfAbsent(row.currency(),
                    key -> new BigDecimal[] {BigDecimal.ZERO, BigDecimal.ZERO});
            totals[0] = totals[0].add(row.claimedAmount());
            if (RECOVERABLE.contains(status) && row.acceptedAmount() != null) {
                totals[1] = totals[1].add(row.acceptedAmount());
            }
            countByCurrency.merge(row.currency(), row.count(), Long::sum);
        }
        List<CurrencyTotal> currencies = byCurrency.entrySet().stream()
                .map(entry -> new CurrencyTotal(entry.getKey(), countByCurrency.get(entry.getKey()),
                        entry.getValue()[0], entry.getValue()[1]))
                .toList();
        OffsetDateTime now = OffsetDateTime.now(clock);
        List<Long> ages = queries.activeOpenedAt(scope.companyId()).stream()
                .map(openedAt -> ageDays(openedAt, now, scope))
                .toList();
        return new ClaimsOverview(active, open, review, awaiting, currencies, AgeingBuckets.of(ages));
    }

    @Transactional(readOnly = true)
    public ClaimDetail get(CompanyScope scope, UUID claimId) {
        return detailOf(scope, find(scope, claimId));
    }

    /**
     * What a new claim would say, from what was recorded about the trip. Reads only.
     *
     * <p>From a delivery, the trip and order are the delivery's, the suggested type follows its
     * result and the refused order lines become the claim's lines - with no amount, because TMS does
     * not know what a chair is worth. From an exception, the trip is the exception's.
     */
    @Transactional(readOnly = true)
    public ClaimDraft draft(CompanyScope scope, UUID tripId, UUID orderDeliveryId, UUID tripExceptionId) {
        DeliveryIncident delivery = orderDeliveryId == null ? null
                : incidents.findDelivery(orderDeliveryId, scope.companyId())
                        .orElseThrow(() -> new ResourceNotFoundException("Delivery not found."));
        ExceptionIncident exception = tripExceptionId == null ? null
                : incidents.findException(tripExceptionId, scope.companyId())
                        .orElseThrow(() -> new ResourceNotFoundException("Exception not found."));
        UUID resolvedTripId = tripId != null ? tripId
                : delivery != null ? delivery.tripId()
                : exception != null ? exception.tripId() : null;
        if (resolvedTripId == null) {
            throw new InvalidRequestException("A claim draft starts from a trip, a delivery or an exception.");
        }
        requireSameTrip(resolvedTripId, delivery, exception);
        ClaimableTrip trip = incidents.findTrip(resolvedTripId, scope.companyId())
                .orElseThrow(() -> new ResourceNotFoundException("Trip not found."));

        String refusal = refusalOf(trip);
        String carrierName = trip.carrierId() == null ? null : carrierNames(scope, Set.of(trip.carrierId()))
                .get(trip.carrierId());
        UUID orderId = delivery == null ? null : delivery.orderId();
        String orderNumber = orderId == null ? null : orderNumbers(scope, Set.of(orderId)).get(orderId);

        ClaimType suggested = delivery != null ? typeForDelivery(delivery.result())
                : exception != null ? typeForException(exception.type()) : null;
        LocalDate incidentDate = delivery != null && delivery.deliveredAt() != null
                ? delivery.deliveredAt().atZoneSameInstant(scope.zoneId()).toLocalDate()
                : exception != null ? exception.reportedAt().atZoneSameInstant(scope.zoneId()).toLocalDate()
                : trip.planningDate();

        List<DraftLine> refused = new ArrayList<>();
        if (delivery != null) {
            Set<UUID> lineIds = delivery.lines().stream()
                    .filter(line -> line.quantityRefused() != null && line.quantityRefused().signum() > 0)
                    .map(TripIncidentLookupPort.DeliveryIncidentLine::orderLineId)
                    .collect(Collectors.toSet());
            Map<UUID, OrderLineSnapshot> snapshots = lineIds.isEmpty() ? Map.of()
                    : orders.linesByIds(lineIds, scope.companyId());
            for (TripIncidentLookupPort.DeliveryIncidentLine line : delivery.lines()) {
                if (!lineIds.contains(line.orderLineId())) {
                    continue;
                }
                OrderLineSnapshot snapshot = snapshots.get(line.orderLineId());
                refused.add(new DraftLine(line.orderLineId(), snapshot == null ? null : snapshot.lineNumber(),
                        snapshot == null ? null : snapshot.materialCode(), line.quantityRefused(), line.uom(),
                        line.notes()));
            }
        }
        List<ExistingClaim> existing = claimRepository
                .findByCompanyIdAndTripIdOrderByOpenedAtDesc(scope.companyId(), trip.tripId()).stream()
                .filter(claim -> claim.status() != ClaimStatus.CANCELLED)
                .map(claim -> new ExistingClaim(claim.id(), claim.claimNumber(), claim.status(), claim.claimType()))
                .toList();

        return new ClaimDraft(trip.tripId(), trip.shipmentNumber(), trip.carrierId(), carrierName, refusal == null,
                refusal, orderId, orderNumber, delivery == null ? null : delivery.orderDeliveryId(),
                delivery == null ? null : delivery.result(), delivery == null ? null : delivery.notes(),
                delivery == null ? null : delivery.refusedWeightKg(), delivery == null ? null : delivery.refusedPallets(),
                exception == null ? null : exception.exceptionId(), exception == null ? null : exception.type(),
                exception == null ? null : exception.notes(), suggested, incidentDate, refused, existing);
    }

    // ------------------------------------------------------------------ writing

    @Transactional
    public ClaimDetail open(CompanyScope scope, FreightClaimRequests.OpenClaim request) {
        UUID actor = auditActorProvider.requireAppUserId();
        ClaimableTrip trip = incidents.findTrip(request.tripId(), scope.companyId())
                .orElseThrow(() -> new InvalidRequestException("The trip does not exist in this company."));
        String refusal = refusalOf(trip);
        if (refusal != null) {
            throw new InvalidRequestException(refusal);
        }
        UUID orderId = request.orderId();
        if (request.orderDeliveryId() != null) {
            DeliveryIncident delivery = incidents.findDelivery(request.orderDeliveryId(), scope.companyId())
                    .filter(found -> found.tripId().equals(trip.tripId()))
                    .orElseThrow(() -> new InvalidRequestException("The delivery is not one of this trip's."));
            if (orderId != null && !orderId.equals(delivery.orderId())) {
                throw new InvalidRequestException("The delivery is of another order than the one the claim names.");
            }
            orderId = delivery.orderId();
        }
        if (request.tripExceptionId() != null) {
            incidents.findException(request.tripExceptionId(), scope.companyId())
                    .filter(found -> found.tripId().equals(trip.tripId()))
                    .orElseThrow(() -> new InvalidRequestException("The exception is not one of this trip's."));
        }
        if (orderId != null && orderNumbers(scope, Set.of(orderId)).isEmpty()) {
            throw new InvalidRequestException("The order does not exist in this company.");
        }

        List<FreightClaimRequests.ClaimLine> lines = listOf(request.lines());
        FreightClaim.Terms terms = termsOf(request.claimType(), request.incidentDate(), request.description(),
                request.claimedAmount(), request.currency(), lines);
        requireLinesBelongTo(scope, orderId, lines);

        String number = "RC-%06d".formatted(claimRepository.nextClaimNumberValue());
        FreightClaim claim = claimRepository.saveAndFlush(new FreightClaim(scope.companyId(), number,
                new FreightClaim.Subject(trip.carrierId(), trip.tripId(), orderId, request.orderDeliveryId(),
                        request.tripExceptionId()),
                terms, actor, OffsetDateTime.now(clock)));
        writeLines(scope, claim, lines);

        Map<String, Object> details = new LinkedHashMap<>();
        details.put("claimNumber", claim.claimNumber());
        details.put("claimType", claim.claimType().name());
        details.put("tripId", claim.tripId().toString());
        details.put("shipmentNumber", trip.shipmentNumber());
        details.put("carrierId", claim.carrierId().toString());
        details.put("claimedAmount", claim.claimedAmount().toPlainString());
        details.put("currency", claim.currency());
        if (claim.orderDeliveryId() != null) {
            details.put("orderDeliveryId", claim.orderDeliveryId().toString());
        }
        if (claim.tripExceptionId() != null) {
            details.put("tripExceptionId", claim.tripExceptionId().toString());
        }
        audit(scope, claim, AuditAction.CLAIM_OPENED, details);
        return detailOf(scope, claim);
    }

    @Transactional
    public ClaimDetail update(CompanyScope scope, UUID claimId, FreightClaimRequests.UpdateClaim request) {
        auditActorProvider.requireAppUserId();
        FreightClaim claim = locked(scope, claimId);
        List<FreightClaimRequests.ClaimLine> lines = listOf(request.lines());
        FreightClaim.Terms terms = termsOf(request.claimType(), request.incidentDate(), request.description(),
                request.claimedAmount(), request.currency(), lines);
        requireLinesBelongTo(scope, claim.orderId(), lines);
        claim.update(terms);
        claimRepository.saveAndFlush(claim);
        lineRepository.deleteAllOfClaim(scope.companyId(), claim.id());
        lineRepository.flush();
        writeLines(scope, claim, lines);
        audit(scope, claim, AuditAction.CLAIM_UPDATED, Map.of("claimNumber", claim.claimNumber(),
                "claimedAmount", claim.claimedAmount().toPlainString(), "currency", claim.currency(),
                "lines", String.valueOf(lines.size())));
        return detailOf(scope, claim);
    }

    @Transactional
    public ClaimDetail submit(CompanyScope scope, UUID claimId) {
        UUID actor = auditActorProvider.requireAppUserId();
        FreightClaim claim = locked(scope, claimId);
        claim.submit(actor, OffsetDateTime.now(clock));
        claimRepository.saveAndFlush(claim);
        audit(scope, claim, AuditAction.CLAIM_SUBMITTED, Map.of("claimNumber", claim.claimNumber()));
        return detailOf(scope, claim);
    }

    /** Accept, partially accept or reject. Four eyes on the first two, held by {@link FreightClaim}. */
    @Transactional
    public ClaimDetail decide(CompanyScope scope, UUID claimId, FreightClaimRequests.Decide request) {
        UUID actor = auditActorProvider.requireAppUserId();
        FreightClaim claim = locked(scope, claimId);
        OffsetDateTime now = OffsetDateTime.now(clock);
        AuditAction action = switch (request.decision()) {
            case ACCEPTED -> {
                if (request.acceptedAmount() != null
                        && request.acceptedAmount().compareTo(claim.claimedAmount()) != 0) {
                    throw new InvalidRequestException("Accepting a claim accepts the "
                            + claim.claimedAmount().toPlainString() + " " + claim.currency()
                            + " claimed; for less, record a partial agreement.");
                }
                claim.accept(actor, request.recoveryMethod(), request.reason(), now);
                yield AuditAction.CLAIM_ACCEPTED;
            }
            case PARTIALLY_ACCEPTED -> {
                claim.partiallyAccept(actor, request.acceptedAmount(), request.recoveryMethod(), request.reason(), now);
                yield AuditAction.CLAIM_PARTIALLY_ACCEPTED;
            }
            case REJECTED -> {
                if (request.acceptedAmount() != null || request.recoveryMethod() != null) {
                    throw new InvalidRequestException("A rejected claim accepts no amount and recovers nothing.");
                }
                claim.reject(actor, request.reason(), now);
                yield AuditAction.CLAIM_REJECTED;
            }
        };
        claimRepository.saveAndFlush(claim);
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("claimNumber", claim.claimNumber());
        details.put("claimedAmount", claim.claimedAmount().toPlainString());
        details.put("currency", claim.currency());
        if (claim.acceptedAmount() != null) {
            details.put("acceptedAmount", claim.acceptedAmount().toPlainString());
        }
        if (claim.recoveryMethod() != null) {
            details.put("recoveryMethod", claim.recoveryMethod().name());
        }
        if (claim.decisionReason() != null) {
            details.put("reason", claim.decisionReason());
        }
        audit(scope, claim, action, details);
        return detailOf(scope, claim);
    }

    /**
     * Files a decided claim. An accepted one is closed by the carrier document that recovered it,
     * and the document must actually do so: for a PENALTY, an invoice of this carrier carrying a
     * penalty line that references this claim; for a CREDIT_NOTE, a credit note of this carrier in
     * the claim's currency. Neither may have been rejected.
     */
    @Transactional
    public ClaimDetail close(CompanyScope scope, UUID claimId, FreightClaimRequests.Close request) {
        UUID actor = auditActorProvider.requireAppUserId();
        FreightClaim claim = locked(scope, claimId);
        UUID invoiceId = request.settlementInvoiceId();
        CarrierInvoice document = null;
        if (invoiceId != null && claim.resolution() != null && claim.resolution().recovers()) {
            document = invoiceRepository.findByIdAndCompanyId(invoiceId, scope.companyId())
                    .filter(found -> found.carrierId().equals(claim.carrierId()))
                    .orElseThrow(() -> new InvalidRequestException(
                            "The document does not exist for this claim's carrier."));
            if (document.status() == InvoiceStatus.REJECTED) {
                throw new InvalidRequestException("Document " + document.invoiceNumber()
                        + " was rejected, so it recovered nothing.");
            }
            if (claim.recoveryMethod() == RecoveryMethod.PENALTY) {
                UUID documentId = document.id();
                boolean carriesPenalty = queries.penaltyApplications(scope.companyId(), List.of(claim.id())).stream()
                        .anyMatch(application -> application.invoiceId().equals(documentId));
                if (!carriesPenalty) {
                    throw new InvalidRequestException("Invoice " + document.invoiceNumber()
                            + " has no penalty line for claim " + claim.claimNumber() + ".");
                }
            } else {
                if (document.documentKind() != DocumentKind.CREDIT_NOTE) {
                    throw new InvalidRequestException("Claim " + claim.claimNumber()
                            + " is recovered by a credit note, and " + document.invoiceNumber() + " is not one.");
                }
                if (!document.currency().equals(claim.currency())) {
                    throw new InvalidRequestException("Credit note " + document.invoiceNumber() + " is in "
                            + document.currency() + " and the claim in " + claim.currency() + ".");
                }
            }
        }
        claim.close(actor, invoiceId, request.notes(), OffsetDateTime.now(clock));
        claimRepository.saveAndFlush(claim);
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("claimNumber", claim.claimNumber());
        details.put("resolution", claim.resolution().name());
        if (document != null) {
            details.put("settlementInvoiceId", document.id().toString());
            details.put("settlementInvoiceNumber", document.invoiceNumber());
        }
        audit(scope, claim, AuditAction.CLAIM_CLOSED, details);
        return detailOf(scope, claim);
    }

    @Transactional
    public ClaimDetail cancel(CompanyScope scope, UUID claimId, FreightClaimRequests.Cancel request) {
        auditActorProvider.requireAppUserId();
        FreightClaim claim = locked(scope, claimId);
        claim.cancel(request.reason());
        claimRepository.saveAndFlush(claim);
        audit(scope, claim, AuditAction.CLAIM_CANCELLED, Map.of("claimNumber", claim.claimNumber(),
                "reason", claim.cancellationReason()));
        return detailOf(scope, claim);
    }

    // ------------------------------------------------------------------ the bridge to the invoice

    /**
     * The accepted claims an invoice of this carrier in this currency could deduct: recovered by
     * PENALTY, not yet filed, and not already deducted on a document that was not rejected. Narrowed
     * to the invoice's trips when given. A proposal - nothing is written.
     */
    @Transactional(readOnly = true)
    public List<PenaltySuggestion> penaltySuggestions(CompanyScope scope, UUID carrierId, String currency,
            Collection<UUID> tripIds) {
        if (carrierId == null || currency == null || currency.isBlank()) {
            throw new InvalidRequestException("Penalty suggestions are for one carrier and one currency.");
        }
        String code = currency.trim().toUpperCase(Locale.ROOT);
        List<FreightClaim> candidates = claimRepository
                .findByCompanyIdAndCarrierIdAndCurrencyAndRecoveryMethodAndStatusInOrderByDecidedAtAsc(
                        scope.companyId(), carrierId, code, RecoveryMethod.PENALTY, RECOVERABLE);
        if (tripIds != null && !tripIds.isEmpty()) {
            Set<UUID> wanted = new HashSet<>(tripIds);
            candidates = candidates.stream().filter(claim -> wanted.contains(claim.tripId())).toList();
        }
        Set<UUID> applied = queries.penaltyApplications(scope.companyId(),
                        candidates.stream().map(FreightClaim::id).toList()).stream()
                .map(PenaltyApplication::claimId)
                .collect(Collectors.toSet());
        List<FreightClaim> open = candidates.stream().filter(claim -> !applied.contains(claim.id())).toList();
        Map<UUID, ClaimableTrip> trips = incidents.findTrips(
                open.stream().map(FreightClaim::tripId).collect(Collectors.toSet()), scope.companyId());
        return open.stream()
                .map(claim -> {
                    String shipment = Optional.ofNullable(trips.get(claim.tripId()))
                            .map(ClaimableTrip::shipmentNumber).orElse(null);
                    return new PenaltySuggestion(claim.id(), claim.claimNumber(), claim.claimType(), claim.tripId(),
                            shipment, claim.acceptedAmount(), claim.currency(), claim.acceptedAmount().negate(),
                            penaltyReasonOf(claim, shipment));
                })
                .toList();
    }

    /**
     * Holds every PENALTY line of an incoming document that names a freight claim to what the claim
     * allows (called by {@code SettlementService.receive}, in its transaction). The claim is locked,
     * so two invoices keyed at once cannot both deduct it.
     */
    @Transactional
    public void requireRecoverablePenalties(CompanyScope scope, UUID carrierId, String currency, DocumentKind kind,
            List<CarrierInvoiceRequest.LineRequest> lines) {
        Set<UUID> seen = new HashSet<>();
        int number = 0;
        for (CarrierInvoiceRequest.LineRequest line : lines) {
            number++;
            if (line.penaltyReferenceType() != com.ebim.tms.settlement.domain.PenaltyReferenceType.FREIGHT_CLAIM) {
                continue;
            }
            String prefix = "Line " + number + ": ";
            if (line.chargeCode() != com.ebim.tms.settlement.domain.ChargeCode.PENALTY) {
                throw new InvalidRequestException(prefix + "only a PENALTY line recovers a freight claim.");
            }
            if (kind != DocumentKind.INVOICE) {
                throw new InvalidRequestException(prefix + "a freight claim is deducted on an invoice, not on a note.");
            }
            if (line.penaltyReferenceId() == null) {
                throw new InvalidRequestException(prefix + "a penalty for a freight claim names the claim.");
            }
            if (!seen.add(line.penaltyReferenceId())) {
                throw new InvalidRequestException(prefix + "the same claim is deducted twice on this document.");
            }
            FreightClaim claim = claimRepository.findByIdAndCompanyIdForUpdate(line.penaltyReferenceId(),
                            scope.companyId())
                    .orElseThrow(() -> new InvalidRequestException(prefix + "the freight claim does not exist."));
            String name = "Claim " + claim.claimNumber();
            if (!claim.carrierId().equals(carrierId)) {
                throw new InvalidRequestException(prefix + name + " is against another carrier.");
            }
            if (!RECOVERABLE.contains(claim.status()) || claim.recoveryMethod() != RecoveryMethod.PENALTY) {
                throw new InvalidRequestException(prefix + name + " is not an accepted claim recovered by a penalty.");
            }
            if (!claim.currency().equals(currency)) {
                throw new InvalidRequestException(prefix + name + " is in " + claim.currency()
                        + " and the document in " + currency + ".");
            }
            if (line.tripId() != null && !line.tripId().equals(claim.tripId())) {
                throw new InvalidRequestException(prefix + name + " is about another shipment.");
            }
            if (line.lineAmount() == null || line.lineAmount().negate().compareTo(claim.acceptedAmount()) > 0) {
                throw new InvalidRequestException(prefix + "the deduction exceeds the "
                        + claim.acceptedAmount().toPlainString() + " " + claim.currency() + " accepted on "
                        + claim.claimNumber() + ".");
            }
            List<PenaltyApplication> applied = queries.penaltyApplications(scope.companyId(), List.of(claim.id()));
            if (!applied.isEmpty()) {
                throw new ConflictException(name + " is already deducted on invoice "
                        + applied.getFirst().invoiceNumber() + ".");
            }
        }
    }

    // ------------------------------------------------------------------ helpers

    FreightClaim find(CompanyScope scope, UUID claimId) {
        return claimRepository.findByIdAndCompanyId(claimId, scope.companyId())
                .orElseThrow(() -> new ResourceNotFoundException("Claim not found."));
    }

    private FreightClaim locked(CompanyScope scope, UUID claimId) {
        return claimRepository.findByIdAndCompanyIdForUpdate(claimId, scope.companyId())
                .orElseThrow(() -> new ResourceNotFoundException("Claim not found."));
    }

    private Specification<FreightClaim> specificationOf(CompanyScope scope, ClaimFilter filter) {
        UUID companyId = scope.companyId();
        Specification<FreightClaim> spec = (root, query, cb) -> cb.equal(root.get("companyId"), companyId);
        if (filter.statuses() != null && !filter.statuses().isEmpty()) {
            List<ClaimStatus> statuses = List.copyOf(filter.statuses());
            spec = spec.and((root, query, cb) -> root.get("status").in(statuses));
        }
        if (filter.carrierId() != null) {
            spec = spec.and((root, query, cb) -> cb.equal(root.get("carrierId"), filter.carrierId()));
        }
        if (filter.claimType() != null) {
            spec = spec.and((root, query, cb) -> cb.equal(root.get("claimType"), filter.claimType()));
        }
        if (filter.tripId() != null) {
            spec = spec.and((root, query, cb) -> cb.equal(root.get("tripId"), filter.tripId()));
        }
        if (filter.from() != null) {
            OffsetDateTime from = filter.from().atStartOfDay(scope.zoneId()).toOffsetDateTime();
            spec = spec.and((root, query, cb) -> cb.greaterThanOrEqualTo(root.get("openedAt"), from));
        }
        if (filter.to() != null) {
            OffsetDateTime to = filter.to().plusDays(1).atStartOfDay(scope.zoneId()).toOffsetDateTime();
            spec = spec.and((root, query, cb) -> cb.lessThan(root.get("openedAt"), to));
        }
        if (filter.search() != null && !filter.search().isBlank()) {
            String pattern = "%" + filter.search().trim().toUpperCase(Locale.ROOT)
                    .replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%";
            spec = spec.and((root, query, cb) -> cb.like(cb.upper(root.get("claimNumber")), pattern, '\\'));
        }
        return spec;
    }

    List<ClaimSummary> summaries(CompanyScope scope, List<FreightClaim> claims) {
        if (claims.isEmpty()) {
            return List.of();
        }
        Map<UUID, String> carrierNames = carrierNames(scope,
                claims.stream().map(FreightClaim::carrierId).collect(Collectors.toSet()));
        Map<UUID, ClaimableTrip> trips = incidents.findTrips(
                claims.stream().map(FreightClaim::tripId).collect(Collectors.toSet()), scope.companyId());
        Map<UUID, String> orderNumbers = orderNumbers(scope, claims.stream().map(FreightClaim::orderId)
                .filter(Objects::nonNull).collect(Collectors.toSet()));
        OffsetDateTime now = OffsetDateTime.now(clock);
        return claims.stream().map(claim -> summaryOf(scope, claim, carrierNames, trips, orderNumbers, now)).toList();
    }

    private ClaimSummary summaryOf(CompanyScope scope, FreightClaim claim, Map<UUID, String> carrierNames,
            Map<UUID, ClaimableTrip> trips, Map<UUID, String> orderNumbers, OffsetDateTime now) {
        OffsetDateTime end = claim.closedAt() != null ? claim.closedAt() : now;
        return new ClaimSummary(claim.id(), claim.claimNumber(), claim.claimType(), claim.status(),
                claim.resolution(), claim.carrierId(), carrierNames.get(claim.carrierId()), claim.tripId(),
                Optional.ofNullable(trips.get(claim.tripId())).map(ClaimableTrip::shipmentNumber).orElse(null),
                claim.orderId(), claim.orderId() == null ? null : orderNumbers.get(claim.orderId()),
                claim.incidentDate(), claim.openedAt(),
                claim.status() == ClaimStatus.CANCELLED ? ageDays(claim.openedAt(), claim.updatedAt(), scope)
                        : ageDays(claim.openedAt(), end, scope),
                claim.claimedAmount(), claim.acceptedAmount(), claim.currency(), claim.recoveryMethod());
    }

    private ClaimDetail detailOf(CompanyScope scope, FreightClaim claim) {
        ClaimSummary summary = summaries(scope, List.of(claim)).getFirst();
        List<FreightClaimLine> lines = lineRepository
                .findByCompanyIdAndFreightClaimIdOrderByLineNumberAsc(scope.companyId(), claim.id());
        Set<UUID> orderLineIds = lines.stream().map(FreightClaimLine::orderLineId).filter(Objects::nonNull)
                .collect(Collectors.toSet());
        Map<UUID, OrderLineSnapshot> snapshots = orderLineIds.isEmpty() ? Map.of()
                : orders.linesByIds(orderLineIds, scope.companyId());
        List<ClaimLineView> lineViews = lines.stream().map(line -> {
            OrderLineSnapshot snapshot = line.orderLineId() == null ? null : snapshots.get(line.orderLineId());
            return new ClaimLineView(line.id(), line.lineNumber(), line.orderLineId(),
                    snapshot == null ? null : snapshot.lineNumber(), snapshot == null ? null : snapshot.materialCode(),
                    line.description(), line.quantity(), line.uom(), line.amount());
        }).toList();
        List<ClaimEvidenceView> evidence = evidenceRepository
                .findByCompanyIdAndFreightClaimIdOrderByCreatedAtAsc(scope.companyId(), claim.id()).stream()
                .map(item -> new ClaimEvidenceView(item.id(), item.contentType(), item.sizeBytes(),
                        item.originalFilename(), item.description(), item.uploadedBy(), item.createdAt()))
                .toList();
        List<PenaltyLineView> penalties = queries.penaltyApplications(scope.companyId(), List.of(claim.id())).stream()
                .map(application -> new PenaltyLineView(application.invoiceId(), application.invoiceNumber(),
                        application.invoiceStatus(), application.lineId(), application.lineAmount()))
                .toList();
        String settlementNumber = claim.settlementInvoiceId() == null ? null
                : invoiceRepository.findByIdAndCompanyId(claim.settlementInvoiceId(), scope.companyId())
                        .map(CarrierInvoice::invoiceNumber).orElse(null);
        UUID caller = auditActorProvider.current().map(actor -> actor.appUserId()).orElse(null);
        return new ClaimDetail(summary, claim.description(), claim.orderDeliveryId(), claim.tripExceptionId(),
                claim.openedBy(), claim.openedBy().equals(caller), claim.submittedBy(), claim.submittedAt(),
                claim.decisionReason(), claim.decidedBy(), claim.decidedAt(), claim.closedBy(), claim.closedAt(),
                claim.closeNotes(), claim.settlementInvoiceId(), settlementNumber, claim.cancellationReason(),
                lineViews, evidence, penalties, claim.allowedActions(), claim.version());
    }

    /** With lines, the claimed amount is their sum - stated or left out; a different figure is refused. */
    private static FreightClaim.Terms termsOf(ClaimType type, LocalDate incidentDate, String description,
            BigDecimal claimedAmount, String currency, List<FreightClaimRequests.ClaimLine> lines) {
        BigDecimal amount = claimedAmount;
        if (!lines.isEmpty()) {
            BigDecimal sum = BigDecimal.ZERO;
            int number = 0;
            for (FreightClaimRequests.ClaimLine line : lines) {
                number++;
                if (line.description() == null || line.description().isBlank()) {
                    throw new InvalidRequestException("Line " + number + " says what it is for.");
                }
                if (line.uom() != null && !line.uom().isBlank() && line.quantity() == null) {
                    throw new InvalidRequestException("Line " + number + " has a unit and no quantity.");
                }
                sum = sum.add(line.amount());
            }
            if (amount == null) {
                amount = sum;
            } else if (amount.compareTo(sum) != 0) {
                throw new InvalidRequestException("The claimed amount " + amount.toPlainString()
                        + " is not the sum of its lines (" + sum.toPlainString() + ").");
            }
        }
        if (amount == null) {
            throw new InvalidRequestException("A claim asks for an amount, or lists the lines it adds up from.");
        }
        return new FreightClaim.Terms(type, incidentDate, description, amount, currency);
    }

    /** An order line needs the claim's order, and must be this company's; the FK proves it is the order's. */
    private void requireLinesBelongTo(CompanyScope scope, UUID orderId, List<FreightClaimRequests.ClaimLine> lines) {
        Set<UUID> lineIds = lines.stream().map(FreightClaimRequests.ClaimLine::orderLineId).filter(Objects::nonNull)
                .collect(Collectors.toSet());
        if (lineIds.isEmpty()) {
            return;
        }
        if (orderId == null) {
            throw new InvalidRequestException("A line names an order line, so the claim names its order.");
        }
        Map<UUID, OrderLineSnapshot> found = orders.linesByIds(lineIds, scope.companyId());
        if (!found.keySet().containsAll(lineIds)) {
            throw new InvalidRequestException("An order line of the claim does not exist in this company.");
        }
    }

    private void writeLines(CompanyScope scope, FreightClaim claim, List<FreightClaimRequests.ClaimLine> lines) {
        List<FreightClaimLine> rows = new ArrayList<>();
        int number = 1;
        for (FreightClaimRequests.ClaimLine line : lines) {
            String uom = line.uom() == null || line.uom().isBlank() ? null : line.uom().trim();
            rows.add(new FreightClaimLine(scope.companyId(), claim.id(), number++,
                    line.orderLineId() == null ? null : claim.orderId(), line.orderLineId(), line.description().trim(),
                    line.quantity(), uom, line.amount()));
        }
        if (!rows.isEmpty()) {
            lineRepository.saveAllAndFlush(rows);
        }
    }

    private static String refusalOf(ClaimableTrip trip) {
        if (trip.draft()) {
            return "Shipment " + trip.shipmentNumber() + " is still a draft: it never ran, so there is nothing to claim.";
        }
        if (trip.carrierId() == null) {
            return "Shipment " + trip.shipmentNumber() + " has no carrier: an own-fleet trip has nobody to claim from.";
        }
        return null;
    }

    private static void requireSameTrip(UUID tripId, DeliveryIncident delivery, ExceptionIncident exception) {
        if (delivery != null && !delivery.tripId().equals(tripId)) {
            throw new InvalidRequestException("The delivery is not one of this trip's.");
        }
        if (exception != null && !exception.tripId().equals(tripId)) {
            throw new InvalidRequestException("The exception is not one of this trip's.");
        }
    }

    /** A PARTIAL delivery is most often a shortage; a refused one, damage; one that did not happen, a delay. */
    static ClaimType typeForDelivery(String result) {
        return switch (result) {
            case "PARTIAL" -> ClaimType.SHORTAGE;
            case "REJECTED", "DELIVERED" -> ClaimType.DAMAGE;
            default -> ClaimType.DELAY;
        };
    }

    static ClaimType typeForException(String type) {
        return switch (type) {
            case "DELIVERY_REJECTED" -> ClaimType.DAMAGE;
            case "OTHER" -> ClaimType.OTHER;
            default -> ClaimType.DELAY;
        };
    }

    private static String penaltyReasonOf(FreightClaim claim, String shipmentNumber) {
        String reason = "Freight claim " + claim.claimNumber() + " (" + claim.claimType().name().toLowerCase(Locale.ROOT)
                .replace('_', ' ') + ")" + (shipmentNumber == null ? "" : ", shipment " + shipmentNumber);
        return reason.length() <= 500 ? reason : reason.substring(0, 500);
    }

    private Map<UUID, String> carrierNames(CompanyScope scope, Set<UUID> ids) {
        if (ids.isEmpty()) {
            return Map.of();
        }
        Map<UUID, String> names = new HashMap<>();
        carriers.findAllInCompany(ids, scope.companyId()).forEach((id, reference) -> names.put(id, reference.name()));
        return names;
    }

    Map<UUID, String> carrierNamesOf(CompanyScope scope, Set<UUID> ids) {
        return carrierNames(scope, ids);
    }

    private Map<UUID, String> orderNumbers(CompanyScope scope, Set<UUID> ids) {
        if (ids.isEmpty()) {
            return Map.of();
        }
        Map<UUID, String> numbers = new HashMap<>();
        orders.costReferencesOf(ids, scope.companyId())
                .forEach((id, reference) -> numbers.put(id, reference.orderNumber()));
        return numbers;
    }

    /** Whole days in the company's calendar, so a claim opened at 23:50 is one day old at 00:10. */
    static long ageDays(OffsetDateTime from, OffsetDateTime to, CompanyScope scope) {
        if (from == null || to == null) {
            return 0;
        }
        LocalDate start = from.atZoneSameInstant(scope.zoneId()).toLocalDate();
        LocalDate end = to.atZoneSameInstant(scope.zoneId()).toLocalDate();
        return Math.max(0, ChronoUnit.DAYS.between(start, end));
    }

    private void audit(CompanyScope scope, FreightClaim claim, AuditAction action, Map<String, Object> details) {
        auditRecorder.record(scope, AuditAggregateType.FREIGHT_CLAIM, claim.id(), action, details);
    }

    private static <T> List<T> listOf(List<T> values) {
        return values == null ? List.of() : values;
    }
}
