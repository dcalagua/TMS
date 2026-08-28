package com.ebim.tms.settlement.application;

import com.ebim.tms.settlement.domain.CarrierInvoice;
import com.ebim.tms.settlement.domain.CarrierInvoiceLine;
import com.ebim.tms.settlement.domain.DiscrepancyType;
import com.ebim.tms.settlement.domain.FreightDiscrepancy;
import com.ebim.tms.settlement.domain.FreightMatch;
import com.ebim.tms.settlement.domain.FreightMatchResult;
import com.ebim.tms.settlement.domain.FreightMatcher;
import com.ebim.tms.settlement.domain.InvoiceStatus;
import com.ebim.tms.settlement.domain.MatchStatus;
import com.ebim.tms.settlement.domain.PayableExport;
import com.ebim.tms.settlement.domain.SettlementApproval;
import com.ebim.tms.settlement.domain.Tolerance;
import com.ebim.tms.settlement.domain.TolerancePolicy;
import com.ebim.tms.settlement.infrastructure.CarrierInvoiceRepository;
import com.ebim.tms.settlement.infrastructure.FreightDiscrepancyRepository;
import com.ebim.tms.settlement.infrastructure.FreightMatchRepository;
import com.ebim.tms.settlement.infrastructure.PayableExportRepository;
import com.ebim.tms.settlement.infrastructure.SettlementApprovalRepository;
import com.ebim.tms.settlement.infrastructure.TolerancePolicyRepository;
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
import com.ebim.tms.shared.reference.TripCostLookupPort;
import com.ebim.tms.shared.reference.TripSettlementLookupPort;
import com.ebim.tms.shared.security.CompanyScope;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Freight audit: receive a carrier's invoice, compare it, surface the difference, have a person
 * decide, hand the approved obligation over (migration V46).
 *
 * <h2>The boundary</h2>
 *
 * <p><b>TMS validates and exports. The ERP pays.</b> Nothing here is a ledger entry, a payment or a
 * bank instruction, and {@link #export} produces an artifact rather than a transfer.
 *
 * <h2>What this service refuses to do</h2>
 *
 * <ul>
 *   <li><b>Approve automatically, at any tolerance.</b> Matching decides whether a person needs to
 *       look; it never decides to pay. {@code settlement_approval.decided_by} is NOT NULL and
 *       {@code requireAppUserId} refuses machines, so an unattended approval is not merely
 *       forbidden - it is unrepresentable.
 *   <li><b>Write what a shipment cost.</b> V30's close/reopen owns that figure. Settlement reads it
 *       through {@link TripCostLookupPort} and never writes back: two owners of one number is how
 *       the two come to disagree.
 *   <li><b>Convert a currency.</b> Two currencies do not add up and this product invents no rate.
 *   <li><b>Export twice.</b> {@code uq_payable_export_invoice} makes that a database fact; this
 *       service returns the existing export rather than failing, so a retried click is idempotent
 *       rather than an error the operator has to interpret.
 * </ul>
 */
@Service
public class SettlementService {

    /**
     * What happened to money we were asked to pay (JOB 24).
     *
     * <p>Tagged by outcome rather than counted once: a rising <b>rejection</b> rate is an operations
     * signal - a carrier billing off an old tariff, or a matcher tuned too tight - and it is
     * invisible in a single "invoices processed" number. An approval is business as usual; a
     * refusal is somebody deciding not to pay, and those want telling apart on a dashboard.
     *
     * <p>Counts only. <b>No amounts.</b> A metric endpoint is not an authorisation boundary and
     * these gauges are readable by anything scraping it, so what a company pays its carriers stays
     * in the database where RLS covers it.
     */
    private static final String DECISION_METRIC = "tms.settlement.decisions";

    private final io.micrometer.core.instrument.MeterRegistry meterRegistry;

    private final CarrierInvoiceRepository invoiceRepository;
    private final FreightMatchRepository matchRepository;
    private final FreightDiscrepancyRepository discrepancyRepository;
    private final SettlementApprovalRepository approvalRepository;
    private final PayableExportRepository exportRepository;
    private final TolerancePolicyRepository tolerancePolicyRepository;
    private final TripCostLookupPort tripCostLookupPort;
    private final TripSettlementLookupPort tripSettlementLookupPort;
    private final CarrierLookupPort carrierLookupPort;
    private final PayableExportPort payableExportPort;
    private final AuditActorProvider auditActorProvider;
    private final AuditRecorder auditRecorder;

    public SettlementService(CarrierInvoiceRepository invoiceRepository, FreightMatchRepository matchRepository,
            FreightDiscrepancyRepository discrepancyRepository, SettlementApprovalRepository approvalRepository,
            PayableExportRepository exportRepository, TolerancePolicyRepository tolerancePolicyRepository,
            TripCostLookupPort tripCostLookupPort, TripSettlementLookupPort tripSettlementLookupPort,
            CarrierLookupPort carrierLookupPort, PayableExportPort payableExportPort,
            AuditActorProvider auditActorProvider, AuditRecorder auditRecorder,
            io.micrometer.core.instrument.MeterRegistry meterRegistry) {
        this.invoiceRepository = invoiceRepository;
        this.matchRepository = matchRepository;
        this.discrepancyRepository = discrepancyRepository;
        this.approvalRepository = approvalRepository;
        this.exportRepository = exportRepository;
        this.tolerancePolicyRepository = tolerancePolicyRepository;
        this.tripCostLookupPort = tripCostLookupPort;
        this.tripSettlementLookupPort = tripSettlementLookupPort;
        this.carrierLookupPort = carrierLookupPort;
        this.payableExportPort = payableExportPort;
        this.auditActorProvider = auditActorProvider;
        this.auditRecorder = auditRecorder;
        this.meterRegistry = meterRegistry;
    }

    // ------------------------------------------------------------------ receiving

    @Transactional
    public CarrierInvoiceView receive(CompanyScope scope, CarrierInvoiceRequest request) {
        UUID actorId = auditActorProvider.requireAppUserId();
        requireCarrierInScope(scope, request.carrierId());

        // The duplicate guard, checked here so the message names the number, and backed by
        // uq_carrier_invoice_number for the race this check cannot close.
        if (invoiceRepository.existsByCompanyIdAndCarrierIdAndInvoiceNumber(
                scope.companyId(), request.carrierId(), request.invoiceNumber().trim())) {
            throw new ConflictException("This carrier has already billed invoice "
                    + request.invoiceNumber().trim() + ".");
        }

        requireTripsInScope(scope, request);

        CarrierInvoice invoice = new CarrierInvoice(scope.companyId(), request.carrierId(),
                request.invoiceNumber().trim(), request.invoiceDate(), request.currency().toUpperCase(java.util.Locale.ROOT),
                request.totalAmount(), blankToNull(request.externalReference()), blankToNull(request.notes()),
                actorId);
        invoice.replaceLines(linesOf(scope, request), actorId);

        CarrierInvoice saved = saveWithDuplicateBackstop(invoice, request.invoiceNumber().trim());
        auditRecorder.record(scope, AuditAggregateType.CARRIER_INVOICE, saved.id(), AuditAction.INVOICE_RECEIVED,
                Map.of("invoiceNumber", saved.invoiceNumber(), "totalAmount", saved.totalAmount().toPlainString(),
                        "currency", saved.currency()));
        return toView(scope, saved);
    }

    /**
     * Every shipment a line names must belong to this company.
     *
     * <p>{@code fk_carrier_invoice_line_trip_company} refuses it in the database, which is what makes
     * the isolation a fact rather than a check somebody can forget. This exists so the refusal
     * arrives as a sentence naming the line rather than as a constraint violation the operator has
     * to interpret - the readable layer above a structural guarantee, which is the shape every
     * invariant in this codebase has.
     */
    private void requireTripsInScope(CompanyScope scope, CarrierInvoiceRequest request) {
        Set<UUID> named = request.lines().stream()
                .map(CarrierInvoiceRequest.LineRequest::tripId)
                .filter(Objects::nonNull)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        if (named.isEmpty()) {
            return;
        }
        Set<UUID> ours = tripSettlementLookupPort.findForSettlement(named, scope.companyId()).keySet();
        List<UUID> foreign = named.stream().filter(id -> !ours.contains(id)).toList();
        if (!foreign.isEmpty()) {
            throw new InvalidRequestException("This invoice bills " + foreign.size() + " shipment"
                    + (foreign.size() == 1 ? "" : "s") + " that do not belong to this company.");
        }
    }

    private List<CarrierInvoiceLine> linesOf(CompanyScope scope, CarrierInvoiceRequest request) {
        List<CarrierInvoiceLine> lines = new ArrayList<>();
        for (CarrierInvoiceRequest.LineRequest line : request.lines()) {
            lines.add(new CarrierInvoiceLine(scope.companyId(), line.tripId(), line.description().trim(),
                    line.quantity(), line.unitAmount(), line.lineAmount()));
        }
        return lines;
    }

    // ------------------------------------------------------------------ matching

    /**
     * Compares the invoice with what TMS expected, and records the verdict.
     *
     * <p>The arithmetic is {@link FreightMatcher}, a pure function that knows nothing about this
     * service. What happens here is the gathering - the shipments, their costs, the tolerance that
     * applies - and the writing of a verdict that must stay reproducible afterwards.
     */
    @Transactional
    public CarrierInvoiceView match(CompanyScope scope, UUID invoiceId) {
        UUID actorId = auditActorProvider.requireAppUserId();
        CarrierInvoice invoice = lockedInvoice(scope, invoiceId);
        invoice.transitionTo(InvoiceStatus.MATCHING, actorId);

        Set<UUID> tripIds = invoice.lines().stream()
                .map(CarrierInvoiceLine::tripId)
                .filter(Objects::nonNull)
                .collect(Collectors.toCollection(LinkedHashSet::new));

        Map<UUID, TripSettlementLookupPort.TripSettlementSummary> trips =
                tripSettlementLookupPort.findForSettlement(tripIds, scope.companyId());
        Map<UUID, TripCostLookupPort.TripCostSummary> costs =
                tripCostLookupPort.findCosts(tripIds, scope.companyId());

        Tolerance tolerance = toleranceFor(scope, invoice.carrierId());
        FreightMatchResult result = FreightMatcher.match(invoice.currency(), invoice.totalAmount(),
                matcherLines(invoice, trips), matcherCosts(trips, costs, invoice.carrierId()), tolerance);

        persistMatch(scope, invoice, result, tolerance, actorId);

        InvoiceStatus next = result.status() == MatchStatus.MATCHED
                ? InvoiceStatus.MATCHED
                : InvoiceStatus.DISCREPANCY;
        invoice.transitionTo(next, actorId);
        invoiceRepository.save(invoice);

        auditRecorder.record(scope, AuditAggregateType.CARRIER_INVOICE, invoice.id(), AuditAction.INVOICE_MATCHED,
                Map.of("matchStatus", result.status().name(),
                        "discrepancies", String.valueOf(result.discrepancies().size())));
        return toView(scope, invoice);
    }

    /**
     * The lines as the matcher wants them, with a shipment this carrier did not run reduced to "no
     * shipment".
     *
     * <p>An invoice from carrier A billing carrier B's shipment is either a mistake or a fraud, and
     * it is the first thing a freight auditor checks. Dropping the trip id here makes the matcher
     * report it as {@link DiscrepancyType#UNMATCHED_TRIP} - which is exactly what it is - rather
     * than comparing an amount against work somebody else did.
     */
    private List<FreightMatcher.InvoiceLine> matcherLines(CarrierInvoice invoice,
            Map<UUID, TripSettlementLookupPort.TripSettlementSummary> trips) {
        List<FreightMatcher.InvoiceLine> lines = new ArrayList<>();
        for (CarrierInvoiceLine line : invoice.lines()) {
            UUID tripId = line.tripId();
            if (tripId != null) {
                TripSettlementLookupPort.TripSettlementSummary trip = trips.get(tripId);
                if (trip == null || !invoice.carrierId().equals(trip.carrierId())) {
                    tripId = null;
                }
            }
            lines.add(new FreightMatcher.InvoiceLine(line.id(), tripId, line.lineAmount(), line.description()));
        }
        return lines;
    }

    private Map<UUID, FreightMatcher.TripCostSnapshot> matcherCosts(
            Map<UUID, TripSettlementLookupPort.TripSettlementSummary> trips,
            Map<UUID, TripCostLookupPort.TripCostSummary> costs, UUID invoiceCarrierId) {
        Map<UUID, FreightMatcher.TripCostSnapshot> snapshots = new java.util.HashMap<>();
        trips.forEach((tripId, trip) -> {
            if (!invoiceCarrierId.equals(trip.carrierId())) {
                return;
            }
            TripCostLookupPort.TripCostSummary cost = costs.get(tripId);
            snapshots.put(tripId, new FreightMatcher.TripCostSnapshot(tripId,
                    // Null stays null throughout. A shipment with no cost row at all and one whose
                    // cost row was never estimated are both "no expected figure", never zero.
                    cost == null ? null : cost.expectedAmount(),
                    cost == null ? null : cost.actualAmount(),
                    cost == null ? null : cost.currency()));
        });
        return snapshots;
    }

    private void persistMatch(CompanyScope scope, CarrierInvoice invoice, FreightMatchResult result,
            Tolerance tolerance, UUID actorId) {
        // Re-matching replaces the verdict and its discrepancies. What was claimed before lives in
        // the audit trail, which is append-only - the same argument V28 makes for corrections.
        discrepancyRepository.deleteByCompanyIdAndCarrierInvoiceId(scope.companyId(), invoice.id());
        FreightMatch match = matchRepository
                .findByCompanyIdAndCarrierInvoiceId(scope.companyId(), invoice.id())
                .orElse(null);
        if (match == null) {
            match = new FreightMatch(scope.companyId(), invoice.id(), invoice.currency(), result, tolerance,
                    actorId);
        } else {
            match.apply(result);
        }
        matchRepository.save(match);

        for (FreightMatchResult.Discrepancy discrepancy : result.discrepancies()) {
            discrepancyRepository.save(new FreightDiscrepancy(scope.companyId(), invoice.id(), null,
                    discrepancy.type(), discrepancy.expectedAmount(), discrepancy.invoicedAmount(),
                    discrepancy.differenceAmount(), invoice.currency(), discrepancy.detail()));
        }
    }

    /**
     * The tolerance that applies: this carrier's if it has one, otherwise the company's, otherwise
     * none at all.
     *
     * <p>{@link Tolerance#NONE} is the safe default and the honest one - a company that has not said
     * what it will accept has not authorised anything, and a permissive default would let invoices
     * through on an assumption nobody made.
     */
    private Tolerance toleranceFor(CompanyScope scope, UUID carrierId) {
        return tolerancePolicyRepository
                .findByCompanyIdAndCarrierIdAndActiveTrue(scope.companyId(), carrierId)
                .or(() -> tolerancePolicyRepository.findByCompanyIdAndCarrierIdIsNullAndActiveTrue(
                        scope.companyId()))
                .map(TolerancePolicy::toTolerance)
                .orElse(Tolerance.NONE);
    }

    // ------------------------------------------------------------------ review and decision

    @Transactional
    public CarrierInvoiceView beginReview(CompanyScope scope, UUID invoiceId) {
        UUID actorId = auditActorProvider.requireAppUserId();
        CarrierInvoice invoice = lockedInvoice(scope, invoiceId);
        invoice.transitionTo(InvoiceStatus.UNDER_REVIEW, actorId);
        return toView(scope, invoiceRepository.save(invoice));
    }

    /**
     * A person authorises the obligation.
     *
     * <p>{@code requireAppUserId} refuses a machine principal, and {@code decided_by} is NOT NULL,
     * so an automated approval cannot be represented at all. That is debt D4's refusal applied where
     * it matters most: an expenditure is authorised by somebody who can be asked about it.
     */
    @Transactional
    public CarrierInvoiceView approve(CompanyScope scope, UUID invoiceId, String comment) {
        UUID actorId = auditActorProvider.requireAppUserId();
        CarrierInvoice invoice = lockedInvoice(scope, invoiceId);

        // Already authorised: return what is, and write NOTHING.
        //
        // Found by twoApprovalsOneDecision, and it was a real defect rather than a test artefact.
        // Trip.transitionTo returns silently when the invoice is already in the target state - which
        // is right for a transition and wrong for what follows it, because the approval row was
        // still being inserted. Two rows meant one expenditure authorised twice, which is precisely
        // what an approval record exists to make impossible. The pessimistic lock serialises the two
        // transactions; this is what makes the second one a no-op instead of a second signature.
        if (invoice.status() == InvoiceStatus.APPROVED) {
            // Not counted. The metric answers "how many expenditures were authorised", and a second
            // click on an already-approved invoice authorised nothing - counting it here would
            // reproduce the double-approval defect in the telemetry after fixing it in the data.
            return toView(scope, invoice);
        }

        requireDifferentApprover(invoice, actorId);
        requireNoOpenDiscrepancies(scope, invoice);
        invoice.transitionTo(InvoiceStatus.APPROVED, actorId);

        approvalRepository.save(new SettlementApproval(scope.companyId(), invoice.id(),
                SettlementApproval.Decision.APPROVED, actorId, blankToNull(comment)));
        countDecision("approved");
        invoiceRepository.save(invoice);
        auditRecorder.record(scope, AuditAggregateType.CARRIER_INVOICE, invoice.id(), AuditAction.INVOICE_APPROVED,
                Map.of("invoiceNumber", invoice.invoiceNumber(),
                        "totalAmount", invoice.totalAmount().toPlainString()));
        return toView(scope, invoice);
    }

    /**
     * Maker and checker must be two people.
     *
     * <p><b>Separate permissions were not enough, and JOB 20 overstated them.</b> That job said
     * "whoever keys an invoice cannot approve their own" on the strength of
     * {@code settlement.invoice:manage} and {@code settlement.invoice:approve} being distinct - but
     * distinct permissions are <em>separable</em>, not separated: one account can hold both, and
     * nothing stopped it. This is the rule that makes the claim true.
     *
     * <p>Deliberately the simplest form that is real: the person who recorded the invoice may not
     * authorise it. No approval matrix, no authorisation limits, no BPM - none of which this
     * product has a requirement for, and each of which would be a model invented ahead of a need.
     *
     * <p>Rejection is <b>not</b> covered by this rule, and that asymmetry is the point. Refusing to
     * pay commits nothing and creates no obligation; a clerk who spots their own keying error must
     * be able to reject it rather than needing a second person to undo their mistake. The control
     * exists to stop money leaving, not to stop it staying.
     */
    private static void requireDifferentApprover(CarrierInvoice invoice, UUID actorId) {
        if (actorId.equals(invoice.createdBy())) {
            throw new ConflictException("The person who recorded an invoice cannot approve it."
                    + " Somebody else has to authorise this expenditure.");
        }
    }

    /**
     * Every difference must have been dealt with before somebody authorises payment.
     *
     * <p>Not the same rule as the transition table, and both are needed. The table stops a
     * {@code DISCREPANCY} invoice being approved without review; this stops one that reached review
     * being approved while the differences are still open. Without it, "under review" would be a
     * state somebody could click through.
     */
    private void requireNoOpenDiscrepancies(CompanyScope scope, CarrierInvoice invoice) {
        long open = discrepancyRepository
                .findByCompanyIdAndCarrierInvoiceIdOrderByCreatedAtAsc(scope.companyId(), invoice.id()).stream()
                .filter(discrepancy -> discrepancy.status() == FreightDiscrepancy.Status.OPEN)
                .count();
        if (open > 0) {
            throw new ConflictException("This invoice still has " + open + " unresolved difference"
                    + (open == 1 ? "" : "s") + ". Accept or reject each one before approving it.");
        }
    }

    @Transactional
    public CarrierInvoiceView reject(CompanyScope scope, UUID invoiceId, String comment) {
        UUID actorId = auditActorProvider.requireAppUserId();
        if (comment == null || comment.isBlank()) {
            throw new InvalidRequestException("A rejection must say why: the carrier has to be able to answer it.");
        }
        CarrierInvoice invoice = lockedInvoice(scope, invoiceId);
        // The same no-op as approval, for the same reason: one refusal, one record.
        if (invoice.status() == InvoiceStatus.REJECTED) {
            return toView(scope, invoice);
        }
        invoice.transitionTo(InvoiceStatus.REJECTED, actorId);

        approvalRepository.save(new SettlementApproval(scope.companyId(), invoice.id(),
                SettlementApproval.Decision.REJECTED, actorId, comment.trim()));
        countDecision("rejected");
        invoiceRepository.save(invoice);
        auditRecorder.record(scope, AuditAggregateType.CARRIER_INVOICE, invoice.id(), AuditAction.INVOICE_REJECTED,
                Map.of("invoiceNumber", invoice.invoiceNumber(), "reason", comment.trim()));
        return toView(scope, invoice);
    }

    @Transactional
    public FreightDiscrepancyView resolveDiscrepancy(CompanyScope scope, UUID invoiceId, UUID discrepancyId,
            FreightDiscrepancy.Status decision, String notes) {
        UUID actorId = auditActorProvider.requireAppUserId();
        FreightDiscrepancy discrepancy = discrepancyRepository
                .findByIdAndCompanyId(discrepancyId, scope.companyId())
                .filter(found -> found.carrierInvoiceId().equals(invoiceId))
                .orElseThrow(() -> new ResourceNotFoundException("Difference not found on this invoice."));
        discrepancy.resolve(decision, blankToNull(notes), actorId);
        return FreightDiscrepancyView.of(discrepancyRepository.save(discrepancy));
    }

    // ------------------------------------------------------------------ export

    /**
     * Hands the approved obligation to whoever pays.
     *
     * <p><b>Idempotent by design and by constraint.</b> Two clicks must not create two obligations,
     * so an invoice already exported returns its existing export rather than failing - the operator
     * gets the same reference, which is the honest answer to "did that work?" - and
     * {@code uq_payable_export_invoice} makes it impossible even if two transactions interleave.
     *
     * <p>TMS does not pay. This produces an artifact and records that it was handed over.
     */
    @Transactional
    public PayableExportView export(CompanyScope scope, UUID invoiceId) {
        UUID actorId = auditActorProvider.requireAppUserId();
        CarrierInvoice invoice = lockedInvoice(scope, invoiceId);

        Optional<PayableExport> existing =
                exportRepository.findByCompanyIdAndCarrierInvoiceId(scope.companyId(), invoice.id());
        if (existing.isPresent()) {
            // Not an error. The obligation was already handed over, and saying so is more useful
            // than refusing a click somebody made because the first response was slow.
            return PayableExportView.of(existing.get(), true);
        }

        if (!invoice.status().isExportable()) {
            throw new ConflictException("Only an approved invoice can be exported. This one is "
                    + invoice.status().name().toLowerCase(java.util.Locale.ROOT).replace('_', ' ') + ".");
        }

        String carrierName = carrierLookupPort.findAllInCompany(Set.of(invoice.carrierId()), scope.companyId())
                .values().stream().findFirst().map(reference -> reference.name()).orElse(null);
        PayableExportPort.PayableDocument document = payableExportPort.render(
                new PayableExportPort.PayableInvoice(invoice.id(), invoice.invoiceNumber(), invoice.invoiceDate(),
                        invoice.carrierId(), carrierName, invoice.currency(), invoice.totalAmount(),
                        invoice.lines().stream()
                                .map(line -> new PayableExportPort.PayableLine(line.lineNumber(), line.tripId(),
                                        line.description(), line.lineAmount()))
                                .toList()));

        PayableExport export = exportRepository.save(new PayableExport(scope.companyId(), invoice.id(),
                document.reference(), document.format(), document.payload(), actorId));
        invoice.transitionTo(InvoiceStatus.EXPORTED, actorId);
        invoiceRepository.save(invoice);

        auditRecorder.record(scope, AuditAggregateType.CARRIER_INVOICE, invoice.id(), AuditAction.INVOICE_EXPORTED,
                Map.of("invoiceNumber", invoice.invoiceNumber(), "exportReference", export.exportReference()));
        return PayableExportView.of(export, false);
    }

    // ------------------------------------------------------------------ reads

    @Transactional(readOnly = true)
    public PageResponse<CarrierInvoiceSummaryView> list(CompanyScope scope, UUID carrierId,
            List<InvoiceStatus> statuses, PageQuery pageQuery) {
        PageRequest pageRequest = PageRequest.of(pageQuery.page(), pageQuery.size());
        Page<CarrierInvoice> page;
        if (carrierId != null) {
            page = invoiceRepository.findByCompanyIdAndCarrierId(scope.companyId(), carrierId, pageRequest);
        } else if (statuses != null && !statuses.isEmpty()) {
            page = invoiceRepository.findByCompanyIdAndStatusIn(scope.companyId(), statuses, pageRequest);
        } else {
            page = invoiceRepository.findByCompanyId(scope.companyId(), pageRequest);
        }

        // One query for every verdict on the page, never one per row.
        Map<UUID, FreightMatch> matches = matchRepository
                .findByCompanyIdAndCarrierInvoiceIdIn(scope.companyId(),
                        page.getContent().stream().map(CarrierInvoice::id).toList())
                .stream()
                .collect(Collectors.toMap(FreightMatch::carrierInvoiceId, match -> match));
        Map<UUID, String> carriers = carrierNames(scope, page.getContent());

        List<CarrierInvoiceSummaryView> content = page.getContent().stream()
                .map(invoice -> CarrierInvoiceSummaryView.of(invoice, matches.get(invoice.id()),
                        carriers.get(invoice.carrierId())))
                .toList();
        return new PageResponse<>(content, pageQuery.page(), pageQuery.size(), page.getTotalElements());
    }

    @Transactional(readOnly = true)
    public CarrierInvoiceView get(CompanyScope scope, UUID invoiceId) {
        return toView(scope, invoiceRepository.findByIdAndCompanyId(invoiceId, scope.companyId())
                .orElseThrow(SettlementService::invoiceNotFound));
    }

    // ------------------------------------------------------------------ internals

    private CarrierInvoice lockedInvoice(CompanyScope scope, UUID invoiceId) {
        return invoiceRepository.findByIdAndCompanyIdForUpdate(invoiceId, scope.companyId())
                .orElseThrow(SettlementService::invoiceNotFound);
    }

    private static ResourceNotFoundException invoiceNotFound() {
        return new ResourceNotFoundException("Invoice not found.");
    }

    private void requireCarrierInScope(CompanyScope scope, UUID carrierId) {
        if (carrierLookupPort.findAllInCompany(Set.of(carrierId), scope.companyId()).isEmpty()) {
            throw new InvalidRequestException("That carrier does not belong to this company.");
        }
    }

    private Map<UUID, String> carrierNames(CompanyScope scope, List<CarrierInvoice> invoices) {
        Set<UUID> ids = invoices.stream().map(CarrierInvoice::carrierId).collect(Collectors.toSet());
        if (ids.isEmpty()) {
            return Map.of();
        }
        return carrierLookupPort.findAllInCompany(ids, scope.companyId()).entrySet().stream()
                .collect(Collectors.toMap(Map.Entry::getKey, entry -> entry.getValue().name()));
    }

    private CarrierInvoiceView toView(CompanyScope scope, CarrierInvoice invoice) {
        FreightMatch match = matchRepository
                .findByCompanyIdAndCarrierInvoiceId(scope.companyId(), invoice.id()).orElse(null);
        List<FreightDiscrepancy> discrepancies = discrepancyRepository
                .findByCompanyIdAndCarrierInvoiceIdOrderByCreatedAtAsc(scope.companyId(), invoice.id());
        List<SettlementApproval> approvals = approvalRepository
                .findByCompanyIdAndCarrierInvoiceIdOrderByDecidedAtAsc(scope.companyId(), invoice.id());
        PayableExport export = exportRepository
                .findByCompanyIdAndCarrierInvoiceId(scope.companyId(), invoice.id()).orElse(null);
        String carrierName = carrierNames(scope, List.of(invoice)).get(invoice.carrierId());

        Set<UUID> tripIds = invoice.lines().stream().map(CarrierInvoiceLine::tripId)
                .filter(Objects::nonNull).collect(Collectors.toSet());
        Map<UUID, TripSettlementLookupPort.TripSettlementSummary> trips =
                tripSettlementLookupPort.findForSettlement(tripIds, scope.companyId());
        Map<UUID, TripCostLookupPort.TripCostSummary> costs =
                tripCostLookupPort.findCosts(tripIds, scope.companyId());

        return CarrierInvoiceView.of(invoice, carrierName, match, discrepancies, approvals, export, trips, costs);
    }

    private CarrierInvoice saveWithDuplicateBackstop(CarrierInvoice invoice, String invoiceNumber) {
        try {
            return invoiceRepository.saveAndFlush(invoice);
        } catch (org.springframework.dao.DataIntegrityViolationException e) {
            String message = String.valueOf(e.getMostSpecificCause().getMessage());
            if (message.contains("uq_carrier_invoice_number")) {
                throw new ConflictException("This carrier has already billed invoice " + invoiceNumber + ".");
            }
            throw e;
        }
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    /** Money, never floating point. Kept here so no view has to remember the scale. */
    static BigDecimal money(BigDecimal value) {
        return value == null ? null : value.setScale(2, java.math.RoundingMode.HALF_UP);
    }

    /**
     * One decision about money, tagged by which way it went (JOB 24).
     *
     * <p>Counts only - never amounts, and never a carrier's name. A metrics endpoint is not an
     * authorisation boundary, and what a company pays which carrier stays in the database where RLS
     * covers it. The useful operational signal is the <b>ratio</b>: rejections climbing means a
     * tariff is out of date or the matcher is tuned too tight, and neither shows up in a single
     * "invoices processed" count.
     */
    private void countDecision(String outcome) {
        io.micrometer.core.instrument.Counter.builder(DECISION_METRIC)
                .tag("outcome", outcome).register(meterRegistry).increment();
    }
}
