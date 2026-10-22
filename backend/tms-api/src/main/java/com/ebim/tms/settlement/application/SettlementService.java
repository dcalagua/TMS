package com.ebim.tms.settlement.application;

import com.ebim.tms.settlement.domain.CarrierInvoice;
import com.ebim.tms.settlement.domain.tax.DocumentKind;
import com.ebim.tms.settlement.domain.GlAccountResolver;
import com.ebim.tms.settlement.domain.GlConcept;
import com.ebim.tms.settlement.domain.NoteMatcher;
import com.ebim.tms.settlement.domain.tax.TaxCalculator;
import com.ebim.tms.settlement.domain.tax.TaxRegime;
import com.ebim.tms.settlement.domain.tax.TaxRules;
import com.ebim.tms.settlement.domain.CarrierInvoiceWithholding;
import com.ebim.tms.settlement.infrastructure.GlAccountMappingRepository;
import com.ebim.tms.shared.api.ProblemType;
import com.ebim.tms.shared.api.ProblemTypedException;
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
 *   <li><b>Let a shipment be paid for twice in silence.</b> {@code uq_carrier_invoice_number} stops
 *       one document arriving twice and says nothing about the same shipment appearing on two
 *       documents with different numbers - or twice on one. {@link #billedElsewhere} asks the
 *       question the constraint cannot, {@link FreightMatcher} asks it within the document, and
 *       either answer is a {@link DiscrepancyType#DUPLICATE_INVOICE} that
 *       {@code requireNoOpenDiscrepancies} will not let anybody approve past. Evidence for a
 *       person, not a refusal: re-billing after a credit note is legal.
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
    private final List<PayableExportPort> payableExportPorts;
    private final AuditActorProvider auditActorProvider;
    private final AuditRecorder auditRecorder;
    private final AccrualService accrualService;
    private final FiscalSettingsResolver fiscalSettingsResolver;
    private final FiscalDocumentValidationPort fiscalDocumentValidationPort;
    private final GlAccountMappingRepository glAccountMappingRepository;
    private final PaymentTermsResolver paymentTermsResolver;
    private final com.ebim.tms.shared.reference.BusinessEventPublisher businessEventPublisher;
    private final java.time.Clock clock;
    private final CostAllocationService costAllocationService;
    private final FreightClaimService freightClaimService;

    public SettlementService(CarrierInvoiceRepository invoiceRepository, FreightMatchRepository matchRepository,
            FreightDiscrepancyRepository discrepancyRepository, SettlementApprovalRepository approvalRepository,
            PayableExportRepository exportRepository, TolerancePolicyRepository tolerancePolicyRepository,
            TripCostLookupPort tripCostLookupPort, TripSettlementLookupPort tripSettlementLookupPort,
            CarrierLookupPort carrierLookupPort, List<PayableExportPort> payableExportPorts,
            AuditActorProvider auditActorProvider, AuditRecorder auditRecorder,
            io.micrometer.core.instrument.MeterRegistry meterRegistry, AccrualService accrualService,
            FiscalSettingsResolver fiscalSettingsResolver, FiscalDocumentValidationPort fiscalDocumentValidationPort,
            GlAccountMappingRepository glAccountMappingRepository, PaymentTermsResolver paymentTermsResolver,
            com.ebim.tms.shared.reference.BusinessEventPublisher businessEventPublisher, java.time.Clock clock,
            CostAllocationService costAllocationService, FreightClaimService freightClaimService) {
        this.invoiceRepository = invoiceRepository;
        this.matchRepository = matchRepository;
        this.discrepancyRepository = discrepancyRepository;
        this.approvalRepository = approvalRepository;
        this.exportRepository = exportRepository;
        this.tolerancePolicyRepository = tolerancePolicyRepository;
        this.tripCostLookupPort = tripCostLookupPort;
        this.tripSettlementLookupPort = tripSettlementLookupPort;
        this.carrierLookupPort = carrierLookupPort;
        this.payableExportPorts = List.copyOf(payableExportPorts);
        this.auditActorProvider = auditActorProvider;
        this.auditRecorder = auditRecorder;
        this.meterRegistry = meterRegistry;
        this.accrualService = accrualService;
        this.fiscalSettingsResolver = fiscalSettingsResolver;
        this.fiscalDocumentValidationPort = fiscalDocumentValidationPort;
        this.glAccountMappingRepository = glAccountMappingRepository;
        this.paymentTermsResolver = paymentTermsResolver;
        this.businessEventPublisher = businessEventPublisher;
        this.clock = clock;
        this.costAllocationService = costAllocationService;
        this.freightClaimService = freightClaimService;
    }

    // ------------------------------------------------------------------ receiving

    @Transactional
    public CarrierInvoiceView receive(CompanyScope scope, CarrierInvoiceRequest request) {
        UUID actorId = auditActorProvider.requireAppUserId();
        requireCarrierInScope(scope, request.carrierId());

        String currency = request.currency().toUpperCase(java.util.Locale.ROOT);
        // The regime the document is received under, frozen on it (ADR-027).
        TaxRules rules = fiscalSettingsResolver.rulesOf(scope.companyId());
        DocumentKind kind = documentKindOf(request);
        FiscalIdentity identity = fiscalIdentityOf(scope, request, kind, rules);
        String invoiceNumber = identity != null ? identity.invoiceNumber() : blankToNull(request.invoiceNumber());
        if (invoiceNumber == null) {
            throw new InvalidRequestException("A document needs its number, or its fiscal number and the issuer's "
                    + rules.regime().taxIdLabel() + ".");
        }
        CarrierInvoice referenced = referencedInvoiceOf(scope, request, kind, currency);

        // The duplicate guard, checked here so the message names the number, and backed by
        // uq_carrier_invoice_number for the race this check cannot close.
        if (invoiceRepository.existsByCompanyIdAndCarrierIdAndInvoiceNumber(
                scope.companyId(), request.carrierId(), invoiceNumber)) {
            throw new ConflictException("This carrier has already billed invoice " + invoiceNumber + ".");
        }

        requireTripsInScope(scope, request);
        // A penalty that names a freight claim deducts no more than was accepted, from the claim's
        // carrier and currency, once (V71). The claim is locked for the rest of this transaction.
        freightClaimService.requireRecoverablePenalties(scope, request.carrierId(), currency, kind, request.lines());

        CarrierInvoice invoice = new CarrierInvoice(scope.companyId(), request.carrierId(),
                invoiceNumber, request.invoiceDate(), currency,
                request.totalAmount(), blankToNull(request.externalReference()), blankToNull(request.notes()),
                actorId);
        invoice.identifyAs(rules.country(), kind, rules.regime().documentCode(kind).orElse(null),
                identity == null ? null : identity.documentNumber(), identity == null ? null : identity.issuerTaxId(),
                referenced == null ? null : referenced.id());
        applyFiscalDetail(invoice, request, rules);
        invoice.replaceLines(linesOf(scope, request), actorId);

        CarrierInvoice saved = saveWithDuplicateBackstop(invoice, invoiceNumber);
        Map<String, Object> details = new java.util.LinkedHashMap<>();
        details.put("invoiceNumber", saved.invoiceNumber());
        details.put("totalAmount", saved.totalAmount().toPlainString());
        details.put("currency", saved.currency());
        details.put("fiscalCountry", saved.fiscalCountry());
        details.put("documentKind", kind.name());
        if (saved.fiscalDocumentCode() != null) {
            details.put("fiscalDocumentCode", saved.fiscalDocumentCode());
        }
        if (referenced != null) {
            details.put("referencedInvoiceId", referenced.id().toString());
            details.put("referencedInvoiceNumber", referenced.invoiceNumber());
        }
        AuditAction action = switch (kind) {
            case INVOICE -> AuditAction.INVOICE_RECEIVED;
            case CREDIT_NOTE -> AuditAction.CREDIT_NOTE_RECEIVED;
            case DEBIT_NOTE -> AuditAction.DEBIT_NOTE_RECEIVED;
        };
        auditRecorder.record(scope, AuditAggregateType.CARRIER_INVOICE, saved.id(), action, details);
        for (CarrierInvoiceWithholding withholding : saved.withholdings()) {
            if (withholding.overrideReason() != null) {
                // RETENTION_OVERRIDDEN is any withholding overridden: "retención" in both regimes (ADR-027).
                Map<String, Object> override = new java.util.LinkedHashMap<>();
                override.put("kind", withholding.kind().name());
                override.put("computedAmount", withholding.computedAmount().toPlainString());
                override.put("amount", withholding.amount().toPlainString());
                override.put("reason", withholding.overrideReason());
                auditRecorder.record(scope, AuditAggregateType.CARRIER_INVOICE, saved.id(),
                        AuditAction.RETENTION_OVERRIDDEN, override);
            }
        }
        return toView(scope, saved);
    }

    // ------------------------------------------------------------------ fiscal detail (V66, ADR-027)

    /** A fiscal identity, checked and normalised by the regime; null when the document brought none. */
    record FiscalIdentity(String documentNumber, String issuerTaxId, String invoiceNumber) {
    }

    private static DocumentKind documentKindOf(CarrierInvoiceRequest request) {
        return request.documentKind() == null ? DocumentKind.INVOICE : request.documentKind();
    }

    /**
     * The fiscal number and the issuer's tax id, both or neither (ADR-027). The number is checked and
     * normalised by the regime (Peru {@code F001-123}, Ecuador {@code 001-001-000000123}); the tax id's
     * check digit is verified (a typing mistake), and it must be the carrier's own when the carrier has
     * one of the regime's type (422 {@code issuer-tax-id-mismatch}). Unique per issuer, kind and number.
     */
    private FiscalIdentity fiscalIdentityOf(CompanyScope scope, CarrierInvoiceRequest request, DocumentKind kind,
            TaxRules rules) {
        TaxRegime regime = rules.regime();
        String rawNumber = blankToNull(request.documentNumber());
        String taxId = blankToNull(request.issuerTaxId());
        if (rawNumber == null && taxId == null) {
            return null;
        }
        if (rawNumber == null || taxId == null) {
            throw new InvalidRequestException("A fiscal document is identified by its number and the issuer's "
                    + regime.taxIdLabel() + " together.");
        }
        String number = regime.normalizeDocumentNumber(rawNumber).orElseThrow(() -> new InvalidRequestException(
                "Document number " + rawNumber + " is not in the " + rules.country() + " format: "
                        + regime.documentNumberHint() + "."));
        taxId = taxId.toUpperCase(java.util.Locale.ROOT);
        if (!regime.taxIdRule().isValid(taxId)) {
            throw new InvalidRequestException(regime.taxIdLabel() + " " + taxId + " is not valid ("
                    + regime.taxIdHint() + ").");
        }
        String issuer = taxId;
        carrierLookupPort.taxIdOf(request.carrierId(), scope.companyId(), regime.carrierTaxIdTypes())
                .ifPresent(carrierTaxId -> {
                    if (!carrierTaxId.equalsIgnoreCase(issuer)) {
                        throw new ProblemTypedException(ProblemType.ISSUER_TAX_ID_MISMATCH, "This document was issued"
                                + " by " + regime.taxIdLabel() + " " + issuer + " and the carrier's "
                                + regime.taxIdLabel() + " is " + carrierTaxId + ".");
                    }
                });
        if (invoiceRepository.existsByCompanyIdAndFiscalCountryAndIssuerTaxIdAndDocumentKindAndFiscalDocumentNumber(
                scope.companyId(), rules.country(), issuer, kind, number)) {
            throw new ConflictException(regime.taxIdLabel() + " " + issuer + " has already sent "
                    + kind.label().toLowerCase(java.util.Locale.ROOT) + " " + number + ".");
        }
        // A note's number carries its code: F001-123 may be a factura and a credit note at once, and
        // uq_carrier_invoice_number would otherwise refuse the second.
        String prefix = kind.isNote() ? regime.documentCode(kind).orElse(kind.name()) + "-" : "";
        return new FiscalIdentity(number, issuer, prefix + number);
    }

    /**
     * The invoice a note corrects: of this company and carrier (also the composite FK), a factura,
     * not rejected, in the same currency. An invoice references nothing.
     */
    private CarrierInvoice referencedInvoiceOf(CompanyScope scope, CarrierInvoiceRequest request, DocumentKind kind,
            String currency) {
        if (!kind.isNote()) {
            if (request.referencedInvoiceId() != null) {
                throw new InvalidRequestException("Only a credit or debit note references another invoice.");
            }
            return null;
        }
        if (request.referencedInvoiceId() == null) {
            throw new InvalidRequestException("A credit or debit note names the invoice it corrects.");
        }
        CarrierInvoice referenced = invoiceRepository
                .findByIdAndCompanyId(request.referencedInvoiceId(), scope.companyId())
                .filter(found -> found.carrierId().equals(request.carrierId()))
                .orElseThrow(() -> new InvalidRequestException(
                        "The referenced invoice does not exist for this carrier."));
        if (referenced.documentKind() != DocumentKind.INVOICE) {
            throw new InvalidRequestException("A note corrects an invoice, not another note.");
        }
        if (referenced.status() == InvoiceStatus.REJECTED) {
            throw new InvalidRequestException("Invoice " + referenced.invoiceNumber()
                    + " was rejected: there is nothing to correct.");
        }
        if (!referenced.currency().equals(currency)) {
            throw new InvalidRequestException("Invoice " + referenced.invoiceNumber() + " is in "
                    + referenced.currency() + "; a note corrects it in the same currency.");
        }
        return referenced;
    }

    /**
     * Breakdown (required in the regime's fiscal currency), the stated VAT rate against the rates the
     * regime allows, the withholdings computed from the company's rules, and the certificates and
     * overrides the document brought (ADR-027).
     */
    private void applyFiscalDetail(CarrierInvoice invoice, CarrierInvoiceRequest request, TaxRules rules) {
        TaxRegime regime = rules.regime();
        String vat = regime.vatLabel();
        boolean anyBreakdown = request.subtotalAmount() != null || request.taxRate() != null
                || request.taxAmount() != null;
        if (regime.breakdownRequired(invoice.currency()) && (request.subtotalAmount() == null
                || request.taxRate() == null || request.taxAmount() == null)) {
            throw new InvalidRequestException("A document in " + invoice.currency() + " states its subtotal, "
                    + vat + " rate and " + vat + " amount.");
        }
        if (anyBreakdown) {
            if (request.subtotalAmount() == null || request.taxRate() == null || request.taxAmount() == null) {
                throw new InvalidRequestException("A tax breakdown states subtotal, " + vat + " rate and " + vat
                        + " amount together.");
            }
            if (!TaxCalculator.breakdownAddsUp(request.subtotalAmount(), request.taxAmount(), request.totalAmount())) {
                throw new InvalidRequestException("Subtotal " + request.subtotalAmount().toPlainString() + " + " + vat
                        + " " + request.taxAmount().toPlainString() + " does not equal the total "
                        + request.totalAmount().toPlainString() + " (rounding tolerance "
                        + TaxCalculator.BREAKDOWN_TOLERANCE + ").");
            }
            if (!rules.allowsVatRate(request.taxRate())) {
                throw new InvalidRequestException("The " + vat + " rate " + request.taxRate().toPlainString()
                        + "% is not one the " + rules.country() + " regime allows"
                        + (rules.allowedVatRates().isEmpty() ? " (0 to below 100)."
                                : " (" + rules.allowedVatRates().stream().sorted()
                                        .map(BigDecimal::toPlainString).collect(Collectors.joining(", ")) + ")."));
            }
            invoice.applyBreakdown(money(request.subtotalAmount()), request.taxRate(), money(request.taxAmount()));
        }

        invoice.applyWithholdings(TaxCalculator.compute(rules, invoice.documentKind(), invoice.currency(),
                invoice.subtotalAmount(), invoice.taxAmount(), invoice.totalAmount()));

        for (CarrierInvoiceRequest.WithholdingCertificate certificate : listOf(request.withholdingCertificates())) {
            if (invoice.withholding(certificate.kind()).isEmpty()) {
                throw new InvalidRequestException("No " + certificate.kind().label()
                        + " applies to this document, so it has no certificate.");
            }
            invoice.recordWithholdingCertificate(certificate.kind(), blankToNull(certificate.certificateNumber()),
                    certificate.certificateDate());
        }
        Set<com.ebim.tms.settlement.domain.tax.WithholdingKind> overridden = new java.util.HashSet<>();
        for (CarrierInvoiceRequest.WithholdingOverride override : listOf(request.withholdingOverrides())) {
            if (!overridden.add(override.kind())) {
                throw new InvalidRequestException(override.kind().label() + " is overridden twice.");
            }
            if (invoice.withholding(override.kind()).isEmpty()) {
                throw new InvalidRequestException("No " + override.kind().label() + " applies to this document.");
            }
            if (!override.kind().overridable()) {
                throw new InvalidRequestException(override.kind().label() + " is the law's and cannot be changed by"
                        + " hand.");
            }
            if (override.amount() == null || blankToNull(override.reason()) == null) {
                throw new InvalidRequestException("Changing " + override.kind().label()
                        + " needs the amount and the reason.");
            }
            try {
                invoice.overrideWithholding(override.kind(), money(override.amount()), override.reason());
            } catch (IllegalArgumentException refused) {
                throw new InvalidRequestException(capitalised(refused.getMessage()) + ".");
            }
        }
    }

    private static <T> List<T> listOf(List<T> values) {
        return values == null ? List.of() : values;
    }

    private static String capitalised(String message) {
        return message == null || message.isEmpty() ? "" : Character.toUpperCase(message.charAt(0)) + message.substring(1);
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
        int number = 1;
        for (CarrierInvoiceRequest.LineRequest line : request.lines()) {
            try {
                lines.add(new CarrierInvoiceLine(scope.companyId(), line.tripId(), line.description().trim(),
                        line.quantity(), line.unitAmount(), line.lineAmount(), line.chargeCode())
                        .withPenalty(blankToNull(line.penaltyReason()), line.penaltyReferenceType(),
                                line.penaltyReferenceId()));
            } catch (IllegalArgumentException refused) {
                // ck_carrier_invoice_line_sign and its penalty rules, as a sentence naming the line.
                throw new InvalidRequestException("Line " + number + ": " + refused.getMessage() + ".");
            }
            number++;
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

        Tolerance tolerance = toleranceFor(scope, invoice.carrierId(), invoice.currency());
        // The subtotal when the document is broken down (V66): a rate card prices the service, and the
        // VAT on top of it is fiscal credit, not part of what the shipment cost.
        FreightMatchResult result = invoice.documentKind().isNote()
                ? NoteMatcher.match(invoice.documentKind(), invoice.currency(), invoice.comparableAmount(),
                        matcherLines(invoice, trips), referencedForMatch(scope, invoice),
                        matcherCosts(trips, costs, invoice.carrierId()), tolerance)
                : FreightMatcher.match(invoice.currency(), invoice.comparableAmount(),
                        matcherLines(invoice, trips), matcherCosts(trips, costs, invoice.carrierId()), tolerance,
                        billedElsewhere(scope, invoice, tripIds));

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
     * The invoice a note corrects, as {@link NoteMatcher} needs it: what a credit note may still take
     * back (the invoice's comparable amount, less every other live credit note, plus approved debit
     * notes) and what it billed per shipment.
     */
    private NoteMatcher.ReferencedInvoice referencedForMatch(CompanyScope scope, CarrierInvoice note) {
        CarrierInvoice referenced = invoiceRepository.findByIdAndCompanyId(note.referencedInvoiceId(), scope.companyId())
                .orElseThrow(SettlementService::invoiceNotFound);
        BigDecimal remaining = referenced.comparableAmount();
        for (CarrierInvoice other : invoiceRepository.findByCompanyIdAndReferencedInvoiceIdOrderByCreatedAtAsc(
                scope.companyId(), referenced.id())) {
            if (other.id().equals(note.id()) || other.status() == InvoiceStatus.REJECTED) {
                continue;
            }
            if (other.documentKind() == DocumentKind.CREDIT_NOTE) {
                remaining = remaining.subtract(other.comparableAmount());
            } else if (AccrualService.CONSUMING_STATUSES.contains(other.status())) {
                remaining = remaining.add(other.comparableAmount());
            }
        }
        Map<UUID, BigDecimal> billed = new java.util.LinkedHashMap<>();
        for (CarrierInvoiceLine line : referenced.lines()) {
            if (line.tripId() != null && line.chargeCode() != com.ebim.tms.settlement.domain.ChargeCode.PENALTY) {
                billed.merge(line.tripId(), line.lineAmount(), BigDecimal::add);
            }
        }
        return new NoteMatcher.ReferencedInvoice(referenced.invoiceNumber(), referenced.currency(), remaining, billed);
    }

    /**
     * What is owed on an invoice once its approved notes are counted (ADR-024 part 2, item 5):
     * {@code total - approved 07 + approved 08}. Null for a note, which is its own document.
     */
    BigDecimal netAmountOf(CompanyScope scope, CarrierInvoice invoice) {
        if (invoice.documentKind().isNote()) {
            return null;
        }
        BigDecimal net = invoice.totalAmount();
        for (CarrierInvoice note : invoiceRepository.findByCompanyIdAndReferencedInvoiceIdOrderByCreatedAtAsc(
                scope.companyId(), invoice.id())) {
            if (AccrualService.CONSUMING_STATUSES.contains(note.status())) {
                net = net.add(note.totalAmount().multiply(BigDecimal.valueOf(note.effectiveSign())));
            }
        }
        return net;
    }

    /**
     * The shipments on this invoice that some other live invoice of this company already bills.
     *
     * <p>Answered here rather than in {@link FreightMatcher} because it is the one duplicate
     * question that needs a database: the matcher can see one invoice, and a shipment paid twice
     * is usually two. Gathered fresh on every match so a second invoice keyed after the first was
     * matched is caught when the first is re-matched, which is why {@code MATCHED} is re-matchable
     * at all.
     *
     * <p><b>This is evidence, not a refusal.</b> It becomes an OPEN discrepancy, and
     * {@code requireNoOpenDiscrepancies} is what stops the approval - so a genuine re-bill after a
     * credit note is accepted by a person rather than blocked by a rule that cannot know.
     */
    private Set<FreightMatcher.TripCharge> billedElsewhere(CompanyScope scope, CarrierInvoice invoice,
            Set<UUID> tripIds) {
        if (tripIds.isEmpty()) {
            return Set.of();
        }
        // Since V64 at the grain of (shipment, charge): a toll billed on one invoice and the freight
        // on another are two halves of one bill, not the shipment paid twice.
        return Set.copyOf(invoiceRepository.findTripChargesBilledOnOtherInvoices(
                scope.companyId(), tripIds, invoice.id()));
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
    static List<FreightMatcher.InvoiceLine> matcherLines(CarrierInvoice invoice,
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
            lines.add(new FreightMatcher.InvoiceLine(line.id(), tripId, line.lineAmount(), line.description(),
                    line.chargeCode()));
        }
        return lines;
    }

    static Map<UUID, FreightMatcher.TripCostSnapshot> matcherCosts(
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
                    cost == null ? null : cost.currency(),
                    cost != null && cost.closed(),
                    cost == null ? Map.of() : cost.estimatedComponents()));
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
            // The line it is about, since V64 (ADR-024): V46 carried the id into the matcher and
            // dropped it here, so a difference could not say which line made it.
            discrepancyRepository.save(new FreightDiscrepancy(scope.companyId(), invoice.id(), discrepancy.lineId(),
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
    private Tolerance toleranceFor(CompanyScope scope, UUID carrierId, String invoiceCurrency) {
        return tolerancePolicyRepository
                .findByCompanyIdAndCarrierIdAndActiveTrue(scope.companyId(), carrierId)
                .or(() -> tolerancePolicyRepository.findByCompanyIdAndCarrierIdIsNullAndActiveTrue(
                        scope.companyId()))
                // V64: an absolute bound in another currency does not apply (TolerancePolicy#toleranceFor).
                .map(policy -> policy.toleranceFor(invoiceCurrency))
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
        // V67 (ADR-023 section 2): the due date is frozen now, under the terms in force now. A note is
        // not paid on its own, so it has none.
        if (!invoice.documentKind().isNote()) {
            invoice.fixDueDate(paymentTermsResolver.termsDaysFor(scope.companyId(), invoice.carrierId()));
        }

        approvalRepository.save(new SettlementApproval(scope.companyId(), invoice.id(),
                SettlementApproval.Decision.APPROVED, actorId, blankToNull(comment)));
        countDecision("approved");
        invoiceRepository.save(invoice);
        auditRecorder.record(scope, AuditAggregateType.CARRIER_INVOICE, invoice.id(), AuditAction.INVOICE_APPROVED,
                Map.of("invoiceNumber", invoice.invoiceNumber(),
                        "totalAmount", invoice.totalAmount().toPlainString()));
        // The accrual of each billed trip is consumed in this same transaction (V65, ADR-023): an
        // approval that committed without it would leave an obligation counted twice.
        accrualService.consumeApprovedInvoice(scope, invoice, actorId);
        // And the trips it billed are re-allocated to their orders on the invoiced figure (V68, ADR-026).
        costAllocationService.allocateApprovedInvoice(scope, invoice, actorId);
        publish(scope, invoice, com.ebim.tms.shared.reference.BusinessEventPublisher.INVOICE_APPROVED);
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
        if (discrepancy.type() == DiscrepancyType.PENALTY && decision == FreightDiscrepancy.Status.ACCEPTED) {
            // The approver accepted the shipper's deduction on its own line (ADR-024 part 2, item 6).
            Map<String, Object> details = new java.util.LinkedHashMap<>();
            details.put("discrepancyId", discrepancy.id().toString());
            details.put("amount", String.valueOf(discrepancy.invoicedAmount()));
            if (discrepancy.invoiceLineId() != null) {
                details.put("invoiceLineId", discrepancy.invoiceLineId().toString());
            }
            auditRecorder.record(scope, AuditAggregateType.CARRIER_INVOICE, invoiceId, AuditAction.PENALTY_ACCEPTED,
                    details);
        }
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
        return export(scope, invoiceId, PayableExport.Format.JSON);
    }

    /**
     * The same, in the format asked for (V64: CSV joins JSON). The format is chosen by the first
     * export; a repeat returns that export as it was, whatever format the repeat asks for - one
     * obligation, one artifact.
     */
    @Transactional
    public PayableExportView export(CompanyScope scope, UUID invoiceId, PayableExport.Format format) {
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
        CarrierInvoice referenced = invoice.referencedInvoiceId() == null ? null
                : invoiceRepository.findByIdAndCompanyId(invoice.referencedInvoiceId(), scope.companyId()).orElse(null);
        PayableExportPort.PayableDocument document = exporterFor(format).render(
                new PayableExportPort.PayableInvoice(invoice.id(), invoice.invoiceNumber(), invoice.invoiceDate(),
                        invoice.carrierId(), carrierName, invoice.currency(), invoice.totalAmount(),
                        expectedBasisOf(scope, invoice),
                        invoice.lines().stream()
                                .map(line -> new PayableExportPort.PayableLine(line.lineNumber(), line.tripId(),
                                        line.description(), line.lineAmount(), line.chargeCode().name(),
                                        line.penaltyReason()))
                                .toList(),
                        PayableExportPort.FiscalDetail.of(invoice, referenced, netAmountOf(scope, invoice),
                                companyGlAccounts(scope))));

        PayableExport export = exportRepository.save(new PayableExport(scope.companyId(), invoice.id(),
                document.reference(), document.format(), document.payload(), actorId));
        invoice.transitionTo(InvoiceStatus.EXPORTED, actorId);
        invoiceRepository.save(invoice);

        auditRecorder.record(scope, AuditAggregateType.CARRIER_INVOICE, invoice.id(), AuditAction.INVOICE_EXPORTED,
                Map.of("invoiceNumber", invoice.invoiceNumber(), "exportReference", export.exportReference()));
        publish(scope, invoice, com.ebim.tms.shared.reference.BusinessEventPublisher.PAYABLE_EXPORTED);
        return PayableExportView.of(export, false);
    }

    /**
     * One carrier-invoice webhook (V67, ADR-023 section 2), through the business outbox in this same
     * transaction: an approval or an export that rolls back takes its event with it.
     */
    private void publish(CompanyScope scope, CarrierInvoice invoice, String eventType) {
        businessEventPublisher.publish(new com.ebim.tms.shared.reference.BusinessEventPublisher.BusinessEvent(
                scope.companyId(), com.ebim.tms.shared.reference.BusinessEventPublisher.CARRIER_INVOICE, invoice.id(),
                invoice.invoiceNumber(), eventType, java.time.OffsetDateTime.now(clock)));
    }

    private PayableExportPort exporterFor(PayableExport.Format format) {
        PayableExport.Format wanted = format == null ? PayableExport.Format.JSON : format;
        return payableExportPorts.stream()
                .filter(port -> port.format() == wanted)
                .findFirst()
                .orElseThrow(() -> new InvalidRequestException("No exporter renders " + wanted + "."));
    }

    /**
     * The accounts of the fiscal concepts at company level (ADR-023 mapping, V65): a document spans
     * shipments of several routes, so the route and origin mappings of the accrual journal do not
     * pick one here. A concept with no mapping is absent, never invented.
     */
    private java.util.function.BiFunction<GlConcept, com.ebim.tms.settlement.domain.tax.WithholdingKind, String>
            companyGlAccounts(CompanyScope scope) {
        GlAccountResolver resolver = new GlAccountResolver(
                glAccountMappingRepository.findByCompanyIdAndActiveTrue(scope.companyId()));
        return (concept, kind) -> resolver.resolve(concept, kind, null, null)
                .map(com.ebim.tms.settlement.domain.GlAccountMapping::accountCode)
                .orElse(null);
    }

    /** What the approved figure was compared against, carried into the export (ADR-024 item 4). */
    private String expectedBasisOf(CompanyScope scope, CarrierInvoice invoice) {
        return matchRepository.findByCompanyIdAndCarrierInvoiceId(scope.companyId(), invoice.id())
                .map(FreightMatch::expectedBasis)
                .map(Enum::name)
                .orElse(null);
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

        List<CarrierInvoiceView.NoteView> notes = invoice.documentKind().isNote() ? List.of()
                : invoiceRepository.findByCompanyIdAndReferencedInvoiceIdOrderByCreatedAtAsc(scope.companyId(),
                        invoice.id()).stream().map(CarrierInvoiceView.NoteView::of).toList();
        String referencedNumber = invoice.referencedInvoiceId() == null ? null
                : invoiceRepository.findByIdAndCompanyId(invoice.referencedInvoiceId(), scope.companyId())
                        .map(CarrierInvoice::invoiceNumber).orElse(null);
        FiscalDocumentValidationPort.FiscalDocumentValidationStatus validation =
                invoice.fiscalDocumentNumber() == null ? null
                        : fiscalDocumentValidationPort.validate(invoice.fiscalCountry(), invoice.issuerTaxId(),
                                invoice.fiscalDocumentCode(), invoice.fiscalDocumentNumber());
        CarrierInvoiceView.FiscalView fiscal = CarrierInvoiceView.FiscalView.of(invoice, referencedNumber,
                netAmountOf(scope, invoice), notes, validation);
        return CarrierInvoiceView.of(invoice, carrierName, match, discrepancies, approvals, export, trips, costs,
                fiscal);
    }

    private CarrierInvoice saveWithDuplicateBackstop(CarrierInvoice invoice, String invoiceNumber) {
        try {
            return invoiceRepository.saveAndFlush(invoice);
        } catch (org.springframework.dao.DataIntegrityViolationException e) {
            String message = String.valueOf(e.getMostSpecificCause().getMessage());
            if (message.contains("uq_carrier_invoice_number")) {
                throw new ConflictException("This carrier has already billed invoice " + invoiceNumber + ".");
            }
            if (message.contains("uq_carrier_invoice_fiscal_document")) {
                throw new ConflictException("This issuer has already sent document " + invoiceNumber + ".");
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
