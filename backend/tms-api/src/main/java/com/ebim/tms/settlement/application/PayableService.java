package com.ebim.tms.settlement.application;

import com.ebim.tms.settlement.domain.CarrierInvoice;
import com.ebim.tms.settlement.domain.CarrierPayment;
import com.ebim.tms.settlement.domain.CarrierPaymentRequest;
import com.ebim.tms.settlement.domain.tax.DocumentKind;
import com.ebim.tms.settlement.domain.InvoiceStatus;
import com.ebim.tms.settlement.domain.PayableBalance;
import com.ebim.tms.settlement.domain.ReportedWithholding;
import com.ebim.tms.settlement.domain.PaymentDetail;
import com.ebim.tms.settlement.domain.PaymentRequestStatus;
import com.ebim.tms.settlement.infrastructure.CarrierInvoiceRepository;
import com.ebim.tms.settlement.infrastructure.CarrierPaymentRepository;
import com.ebim.tms.settlement.infrastructure.CarrierPaymentRequestRepository;
import com.ebim.tms.settlement.infrastructure.PayableReportQueries;
import com.ebim.tms.shared.api.ConflictException;
import com.ebim.tms.shared.api.InvalidRequestException;
import com.ebim.tms.shared.api.ProblemType;
import com.ebim.tms.shared.api.ProblemTypedException;
import com.ebim.tms.shared.api.ResourceNotFoundException;
import com.ebim.tms.shared.audit.AuditAction;
import com.ebim.tms.shared.audit.AuditActorProvider;
import com.ebim.tms.shared.audit.AuditAggregateType;
import com.ebim.tms.shared.audit.AuditRecorder;
import com.ebim.tms.shared.reference.BusinessEventPublisher;
import com.ebim.tms.shared.reference.CarrierLookupPort;
import com.ebim.tms.shared.reference.PayablePaymentIntakePort;
import com.ebim.tms.shared.security.CompanyScope;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * What happens to an invoice after the export (migration V67, ADR-023 section 2).
 *
 * <h2>The boundary</h2>
 *
 * <p><b>TMS records what was paid; it pays nothing</b> (D-01). There is no bank account here, no bank
 * file and no execution. A payment arrives in one of two ways, and both end in the same append-only
 * {@code tms.carrier_payment} row:
 *
 * <ul>
 *   <li><b>The ERP reports it</b> ({@code POST /integration/v1/payable-payments}, through
 *       {@link PayablePaymentIntakePort}), attributed to its credential.
 *   <li><b>A person keys it and a different person confirms it</b>: the request is the maker, the
 *       confirmation the checker, and {@link CarrierPaymentRequest#requireDifferentPerson} is what turns
 *       two permissions into two people.
 * </ul>
 *
 * <h2>What this service refuses</h2>
 *
 * <ul>
 *   <li>A payment larger than the open balance (422 {@code payment-exceeds-balance}): either it belongs
 *       to another invoice or a note exists that TMS has not seen, and a person has to say which.
 *   <li>A payment in another currency (422 {@code payment-currency-mismatch}): no exchange rate is invented.
 *   <li>A payment before the export, or of a credit or debit note (409): TMS does not record the payment of
 *       something it never handed over, and a note adjusts its invoice rather than being paid itself.
 *   <li>The same reference twice with different figures (409). The same reference with the same figures
 *       is a repeat, and answers with what was recorded the first time.
 * </ul>
 *
 * <p>Every write locks the invoice ({@code SELECT ... FOR UPDATE}), so two payments of one invoice are
 * checked against the balance one after the other, never both against the same balance.
 */
@Service
public class PayableService implements PayablePaymentIntakePort {

    /** The Control Tower card's "por vencer" horizon. */
    static final int DUE_SOON_DAYS = 7;

    /** Bounds of the lists: a due list longer than this is a report to export, not a screen. */
    static final int MAX_DUE_ROWS = 500;
    static final int MAX_PENDING_ROWS = 200;
    static final int MAX_DUE_WITHIN_DAYS = 365;

    private final CarrierInvoiceRepository invoiceRepository;
    private final CarrierPaymentRepository paymentRepository;
    private final CarrierPaymentRequestRepository requestRepository;
    private final PayableReportQueries reportQueries;
    private final SettlementService settlementService;
    private final CarrierLookupPort carrierLookupPort;
    private final BusinessEventPublisher businessEventPublisher;
    private final AuditActorProvider auditActorProvider;
    private final AuditRecorder auditRecorder;
    private final Clock clock;
    private final FiscalSettingsResolver fiscalSettingsResolver;

    public PayableService(CarrierInvoiceRepository invoiceRepository, CarrierPaymentRepository paymentRepository,
            CarrierPaymentRequestRepository requestRepository, PayableReportQueries reportQueries,
            SettlementService settlementService, CarrierLookupPort carrierLookupPort,
            BusinessEventPublisher businessEventPublisher, AuditActorProvider auditActorProvider,
            AuditRecorder auditRecorder, Clock clock, FiscalSettingsResolver fiscalSettingsResolver) {
        this.invoiceRepository = invoiceRepository;
        this.paymentRepository = paymentRepository;
        this.requestRepository = requestRepository;
        this.reportQueries = reportQueries;
        this.settlementService = settlementService;
        this.carrierLookupPort = carrierLookupPort;
        this.businessEventPublisher = businessEventPublisher;
        this.auditActorProvider = auditActorProvider;
        this.auditRecorder = auditRecorder;
        this.clock = clock;
        this.fiscalSettingsResolver = fiscalSettingsResolver;
    }

    // ------------------------------------------------------------------ the ERP reports a payment

    @Override
    @Transactional
    public Result record(CompanyScope scope, Command command) {
        if (command.integrationClientId() == null) {
            throw new IllegalArgumentException("an integration payment names its credential");
        }
        PaymentDetail detail = detailOf(command.paymentReference(), command.paidOn(), command.amount(),
                command.currency(), command.withholdings() == null ? List.of() : command.withholdings().stream()
                        .map(withholding -> new ReportedWithholding(withholdingKindOf(withholding.kind()),
                                withholding.amount(), withholding.certificateNumber(), withholding.certificateDate()))
                        .toList());
        CarrierInvoice invoice = lockedInvoice(scope, resolveInvoiceId(scope, command.invoice()));

        Optional<CarrierPayment> existing = paymentRepository.findByCompanyIdAndCarrierInvoiceIdAndPaymentReference(
                scope.companyId(), invoice.id(), detail.paymentReference());
        if (existing.isPresent()) {
            return repeatOf(scope, invoice, existing.get(), detail);
        }

        PayableBalance balance = requireAdmissible(scope, invoice, detail);
        CarrierPayment payment = paymentRepository.saveAndFlush(
                CarrierPayment.reportedBy(command.integrationClientId(), invoice, detail));
        apply(scope, invoice, payment, balance, null);
        PayableBalance after = balanceOf(scope, invoice);
        return new Result(invoice.id(), invoice.invoiceNumber(), payment.id(), Outcome.RECORDED,
                invoice.status().name(), invoice.paidAmount(), after.openBalance(), invoice.dueDate());
    }

    /** The same reference again: the same figures are a repeat, other figures are a different payment (409). */
    private Result repeatOf(CompanyScope scope, CarrierInvoice invoice, CarrierPayment existing, PaymentDetail detail) {
        if (!existing.detail().sameAs(detail)) {
            throw new ConflictException("Payment " + detail.paymentReference() + " of invoice "
                    + invoice.invoiceNumber() + " was already recorded with other figures (" + existing.paidOn()
                    + ", " + existing.amount().toPlainString() + " " + existing.currency()
                    + "). A different payment needs its own reference.");
        }
        return new Result(invoice.id(), invoice.invoiceNumber(), existing.id(), Outcome.DUPLICATE,
                invoice.status().name(), invoice.paidAmount(), balanceOf(scope, invoice).openBalance(),
                invoice.dueDate());
    }

    /**
     * The invoice the ERP names, by exactly one of its three keys. Not found is 404: the ERP paid
     * something TMS does not have, which is the ERP's to look into.
     */
    private UUID resolveInvoiceId(CompanyScope scope, InvoiceKey key) {
        if (key == null) {
            throw new InvalidRequestException("invoice is required: invoiceId, or the fiscal document (carrierTaxId,"
                    + " documentType, documentNumber), or carrierCode and invoiceNumber.");
        }
        boolean byId = key.invoiceId() != null;
        boolean byFiscal = notBlank(key.issuerTaxId()) || notBlank(key.documentNumber());
        boolean byNumber = notBlank(key.carrierCode()) || notBlank(key.invoiceNumber());
        if ((byId ? 1 : 0) + (byFiscal ? 1 : 0) + (byNumber ? 1 : 0) != 1) {
            throw new InvalidRequestException("Name the invoice one way: invoiceId, or the fiscal document"
                    + " (carrierTaxId, documentType, documentNumber), or carrierCode and invoiceNumber.");
        }
        Optional<CarrierInvoice> found;
        if (byId) {
            found = invoiceRepository.findByIdAndCompanyId(key.invoiceId(), scope.companyId());
        } else if (byFiscal) {
            if (!notBlank(key.issuerTaxId()) || !notBlank(key.documentNumber())) {
                throw new InvalidRequestException("A fiscal document is named by carrierTaxId and documentNumber"
                        + " together.");
            }
            // The company's regime reads the number and the type, as it did when the document arrived.
            com.ebim.tms.settlement.domain.tax.TaxRules rules = fiscalSettingsResolver.rulesOf(scope.companyId());
            com.ebim.tms.settlement.domain.tax.TaxRegime regime = rules.regime();
            DocumentKind type = notBlank(key.documentType())
                    ? regime.kindOf(key.documentType()).orElseThrow(() -> new InvalidRequestException(
                            "documentType " + key.documentType().trim() + " is not a document type of the "
                                    + rules.country() + " tax regime."))
                    : DocumentKind.INVOICE;
            String number = regime.normalizeDocumentNumber(key.documentNumber()).orElseThrow(() ->
                    new InvalidRequestException("documentNumber " + key.documentNumber().trim() + " is not in the "
                            + rules.country() + " format: " + regime.documentNumberHint() + "."));
            found = invoiceRepository.findByCompanyIdAndFiscalCountryAndIssuerTaxIdAndDocumentKindAndFiscalDocumentNumber(
                    scope.companyId(), rules.country(), key.issuerTaxId().trim().toUpperCase(Locale.ROOT), type,
                    number);
        } else {
            if (!notBlank(key.carrierCode()) || !notBlank(key.invoiceNumber())) {
                throw new InvalidRequestException("A document without a fiscal identity is named by carrierCode and invoiceNumber"
                        + " together.");
            }
            found = carrierLookupPort.findIdByCode(key.carrierCode(), scope.companyId())
                    .flatMap(carrierId -> invoiceRepository.findByCompanyIdAndCarrierIdAndInvoiceNumber(
                            scope.companyId(), carrierId, key.invoiceNumber().trim()));
        }
        return found.map(CarrierInvoice::id)
                .orElseThrow(() -> new ResourceNotFoundException("No carrier invoice of this company matches the"
                        + " invoice named."));
    }

    // ------------------------------------------------------------------ four eyes: a person keys, another confirms

    @Transactional
    public PayableView requestPayment(CompanyScope scope, UUID invoiceId, PayableRequests.ManualPaymentRequest body) {
        UUID actorId = auditActorProvider.requireAppUserId();
        PaymentDetail detail = detailOf(body.paymentReference(), body.paidOn(), body.amount(), body.currency(),
                body.withholdings() == null ? List.of() : body.withholdings().stream()
                        .map(withholding -> new ReportedWithholding(withholding.kind(), withholding.amount(),
                                withholding.certificateNumber(), withholding.certificateDate()))
                        .toList());
        CarrierInvoice invoice = lockedInvoice(scope, invoiceId);
        requireReferenceUnused(scope, invoice, detail.paymentReference());
        if (requestRepository.existsByCompanyIdAndCarrierInvoiceIdAndPaymentReferenceAndStatus(scope.companyId(),
                invoice.id(), detail.paymentReference(), PaymentRequestStatus.PENDING)) {
            throw new ConflictException("Payment " + detail.paymentReference() + " is already waiting for confirmation.");
        }
        requireAdmissible(scope, invoice, detail);

        CarrierPaymentRequest request = requestRepository.saveAndFlush(
                new CarrierPaymentRequest(invoice, detail, actorId));
        auditRecorder.record(scope, AuditAggregateType.CARRIER_INVOICE, invoice.id(), AuditAction.PAYMENT_REQUESTED,
                paymentDetails(detail, Map.of("paymentRequestId", request.id().toString())));
        return view(scope, invoice);
    }

    /**
     * The checker. The balance is checked again: it may have moved since the request was keyed - the
     * ERP may have reported the same money in the meantime.
     */
    @Transactional
    public PayableView confirmPayment(CompanyScope scope, UUID invoiceId, UUID requestId) {
        UUID actorId = auditActorProvider.requireAppUserId();
        CarrierInvoice invoice = lockedInvoice(scope, invoiceId);
        CarrierPaymentRequest request = requestOf(scope, invoice, requestId);
        request.requirePending();
        request.requireDifferentPerson(actorId);
        PaymentDetail detail = request.detail();
        requireReferenceUnused(scope, invoice, detail.paymentReference());
        PayableBalance balance = requireAdmissible(scope, invoice, detail);

        CarrierPayment payment = paymentRepository.saveAndFlush(CarrierPayment.confirmed(request, invoice, actorId));
        request.confirm(actorId, payment.id());
        requestRepository.save(request);
        apply(scope, invoice, payment, balance, actorId);
        return view(scope, invoice);
    }

    @Transactional
    public PayableView rejectPayment(CompanyScope scope, UUID invoiceId, UUID requestId, String comment) {
        UUID actorId = auditActorProvider.requireAppUserId();
        CarrierInvoice invoice = lockedInvoice(scope, invoiceId);
        CarrierPaymentRequest request = requestOf(scope, invoice, requestId);
        request.reject(actorId, comment);
        requestRepository.save(request);
        auditRecorder.record(scope, AuditAggregateType.CARRIER_INVOICE, invoice.id(),
                AuditAction.PAYMENT_REQUEST_REJECTED, Map.of("paymentRequestId", request.id().toString(),
                        "paymentReference", request.paymentReference(), "reason", request.decisionComment()));
        return view(scope, invoice);
    }

    // ------------------------------------------------------------------ schedule and hold

    @Transactional
    public PayableView schedule(CompanyScope scope, UUID invoiceId, LocalDate date) {
        UUID actorId = auditActorProvider.requireAppUserId();
        CarrierInvoice invoice = lockedInvoice(scope, invoiceId);
        invoice.schedule(date, actorId);
        invoiceRepository.save(invoice);
        auditRecorder.record(scope, AuditAggregateType.CARRIER_INVOICE, invoice.id(), AuditAction.PAYABLE_SCHEDULED,
                Map.of("invoiceNumber", invoice.invoiceNumber(), "scheduledPaymentDate", date.toString()));
        return view(scope, invoice);
    }

    @Transactional
    public PayableView hold(CompanyScope scope, UUID invoiceId, String reason) {
        UUID actorId = auditActorProvider.requireAppUserId();
        CarrierInvoice invoice = lockedInvoice(scope, invoiceId);
        invoice.placeOnHold(reason, actorId);
        invoiceRepository.save(invoice);
        auditRecorder.record(scope, AuditAggregateType.CARRIER_INVOICE, invoice.id(), AuditAction.PAYABLE_HOLD_PLACED,
                Map.of("invoiceNumber", invoice.invoiceNumber(), "reason", invoice.onHoldReason()));
        return view(scope, invoice);
    }

    @Transactional
    public PayableView release(CompanyScope scope, UUID invoiceId, String reason) {
        UUID actorId = auditActorProvider.requireAppUserId();
        CarrierInvoice invoice = lockedInvoice(scope, invoiceId);
        invoice.releaseHold(reason, actorId);
        invoiceRepository.save(invoice);
        auditRecorder.record(scope, AuditAggregateType.CARRIER_INVOICE, invoice.id(),
                AuditAction.PAYABLE_HOLD_RELEASED, Map.of("invoiceNumber", invoice.invoiceNumber(),
                        "reason", reason.trim(), "status", invoice.status().name()));
        return view(scope, invoice);
    }

    // ------------------------------------------------------------------ reads

    @Transactional(readOnly = true)
    public PayableView get(CompanyScope scope, UUID invoiceId) {
        return view(scope, invoiceRepository.findByIdAndCompanyId(invoiceId, scope.companyId())
                .orElseThrow(PayableService::invoiceNotFound));
    }

    @Transactional(readOnly = true)
    public PayableReportViews.AgingView aging(CompanyScope scope, LocalDate asOf) {
        LocalDate date = asOf != null ? asOf : today(scope);
        List<PayableReportQueries.AgingRow> rows = reportQueries.aging(scope.companyId(), date);
        Map<UUID, String> names = carrierNames(scope, rows.stream().map(PayableReportQueries.AgingRow::carrierId)
                .collect(Collectors.toSet()));

        Map<String, Map<String, BigDecimal>> totals = new TreeMap<>();
        Map<String, Long> totalCounts = new TreeMap<>();
        Map<String, PayableReportViews.AgingCarrier> carriers = new LinkedHashMap<>();
        Map<String, Map<String, BigDecimal>> carrierAmounts = new LinkedHashMap<>();
        Map<String, Long> carrierCounts = new LinkedHashMap<>();
        for (PayableReportQueries.AgingRow row : rows) {
            totals.computeIfAbsent(row.currency(), currency -> emptyBuckets())
                    .merge(row.bucket(), row.amount(), BigDecimal::add);
            totalCounts.merge(row.currency(), row.invoices(), Long::sum);
            String key = row.carrierId() + "|" + row.currency();
            carrierAmounts.computeIfAbsent(key, ignored -> emptyBuckets())
                    .merge(row.bucket(), row.amount(), BigDecimal::add);
            carrierCounts.merge(key, row.invoices(), Long::sum);
            carriers.putIfAbsent(key, null);
        }
        List<PayableReportViews.AgingTotal> totalViews = totals.entrySet().stream()
                .map(entry -> new PayableReportViews.AgingTotal(entry.getKey(), entry.getValue(),
                        sum(entry.getValue()), totalCounts.get(entry.getKey())))
                .toList();
        List<PayableReportViews.AgingCarrier> carrierViews = new ArrayList<>();
        for (String key : carriers.keySet()) {
            UUID carrierId = UUID.fromString(key.substring(0, key.indexOf('|')));
            String currency = key.substring(key.indexOf('|') + 1);
            Map<String, BigDecimal> amounts = carrierAmounts.get(key);
            carrierViews.add(new PayableReportViews.AgingCarrier(carrierId, names.get(carrierId), currency, amounts,
                    sum(amounts), carrierCounts.get(key)));
        }
        carrierViews.sort(java.util.Comparator.comparing(PayableReportViews.AgingCarrier::currency)
                .thenComparing(PayableReportViews.AgingCarrier::total, java.util.Comparator.reverseOrder()));
        return new PayableReportViews.AgingView(date, PayableReportViews.BUCKETS, totalViews, carrierViews);
    }

    @Transactional(readOnly = true)
    public List<PayableReportViews.DuePayableView> due(CompanyScope scope, int withinDays, UUID carrierId) {
        if (withinDays < 0 || withinDays > MAX_DUE_WITHIN_DAYS) {
            throw new InvalidRequestException("within is 0 to " + MAX_DUE_WITHIN_DAYS + " days.");
        }
        LocalDate today = today(scope);
        List<PayableReportQueries.DueRow> rows = reportQueries.due(scope.companyId(), today.plusDays(withinDays),
                carrierId, MAX_DUE_ROWS);
        Map<UUID, String> names = carrierNames(scope, rows.stream().map(PayableReportQueries.DueRow::carrierId)
                .collect(Collectors.toSet()));
        return rows.stream()
                .map(row -> new PayableReportViews.DuePayableView(row.invoiceId(), row.invoiceNumber(), row.carrierId(),
                        names.get(row.carrierId()), row.currency(), InvoiceStatus.valueOf(row.status()),
                        row.invoiceDate(), row.dueDate(), ChronoUnit.DAYS.between(today, row.dueDate()),
                        SettlementService.money(row.openBalance()), SettlementService.money(row.paidAmount()),
                        SettlementService.money(row.payableAmount())))
                .toList();
    }

    @Transactional(readOnly = true)
    public PayableReportViews.SummaryView summary(CompanyScope scope) {
        LocalDate today = today(scope);
        List<PayableReportViews.CurrencySummary> currencies = reportQueries
                .summary(scope.companyId(), today, today.plusDays(DUE_SOON_DAYS)).stream()
                .map(row -> new PayableReportViews.CurrencySummary(row.currency(), row.overdueCount(),
                        SettlementService.money(row.overdueAmount()), row.dueSoonCount(),
                        SettlementService.money(row.dueSoonAmount())))
                .toList();
        long pending = requestRepository.countByCompanyIdAndStatus(scope.companyId(), PaymentRequestStatus.PENDING);
        return new PayableReportViews.SummaryView(today, DUE_SOON_DAYS, pending, currencies);
    }

    @Transactional(readOnly = true)
    public List<PayableReportViews.PendingPaymentRequestView> pendingRequests(CompanyScope scope) {
        List<CarrierPaymentRequest> requests = requestRepository.findByCompanyIdAndStatusOrderByRequestedAtAsc(
                scope.companyId(), PaymentRequestStatus.PENDING, PageRequest.of(0, MAX_PENDING_ROWS));
        Map<UUID, CarrierInvoice> invoices = invoiceRepository.findByCompanyIdAndIdIn(scope.companyId(),
                        requests.stream().map(CarrierPaymentRequest::carrierInvoiceId).collect(Collectors.toSet()))
                .stream().collect(Collectors.toMap(CarrierInvoice::id, invoice -> invoice));
        Map<UUID, String> names = carrierNames(scope, requests.stream().map(CarrierPaymentRequest::carrierId)
                .collect(Collectors.toSet()));
        return requests.stream().map(request -> {
            CarrierInvoice invoice = invoices.get(request.carrierInvoiceId());
            PaymentDetail detail = request.detail();
            return new PayableReportViews.PendingPaymentRequestView(request.id(), request.carrierInvoiceId(),
                    invoice == null ? null : invoice.invoiceNumber(), names.get(request.carrierId()),
                    detail.currency(), detail.amount(), detail.paymentReference(), detail.paidOn(),
                    request.requestedBy(), request.requestedAt(),
                    invoice == null ? null : balanceOf(scope, invoice).openBalance());
        }).toList();
    }

    /** What is owed on one invoice; also what the carrier portal shows. */
    PayableBalance balanceOf(CompanyScope scope, CarrierInvoice invoice) {
        BigDecimal net = invoice.documentKind().isNote() ? invoice.totalAmount()
                : settlementService.netAmountOf(scope, invoice);
        return PayableBalance.of(net, invoice.withholdingTotal(), invoice.paidAmount());
    }

    // ------------------------------------------------------------------ internals

    /**
     * The checks every payment passes, whichever channel it came by: an invoice (not a note), exported
     * and not paid, in its own currency, within its open balance. The balance is returned for the
     * caller to apply against.
     */
    private PayableBalance requireAdmissible(CompanyScope scope, CarrierInvoice invoice, PaymentDetail detail) {
        if (invoice.documentKind().isNote()) {
            throw new ConflictException("Document " + invoice.invoiceNumber() + " is a credit or debit note: it"
                    + " adjusts its invoice's balance and is not paid on its own. Report the payment against the"
                    + " invoice.");
        }
        if (!invoice.currency().equals(detail.currency())) {
            throw new ProblemTypedException(ProblemType.PAYMENT_CURRENCY_MISMATCH, "Invoice "
                    + invoice.invoiceNumber() + " is in " + invoice.currency() + " and the payment is in "
                    + detail.currency() + ". TMS converts no currency.");
        }
        PayableBalance balance = balanceOf(scope, invoice);
        if (invoice.status() != InvoiceStatus.PAID && !invoice.status().acceptsPayment()) {
            throw new ConflictException("Invoice " + invoice.invoiceNumber() + " is "
                    + invoice.status().name().toLowerCase(Locale.ROOT).replace('_', ' ')
                    + ". A payment is recorded only once the invoice has been exported for payment.");
        }
        if (!balance.admits(detail.amount())) {
            throw new ProblemTypedException(ProblemType.PAYMENT_EXCEEDS_BALANCE, "A payment of "
                    + detail.amount().toPlainString() + " " + detail.currency() + " exceeds the open balance of "
                    + "invoice " + invoice.invoiceNumber() + ", which is " + balance.openBalance().toPlainString()
                    + " " + invoice.currency() + " (net " + balance.netAmount().toPlainString() + ", withheld "
                    + balance.withholdingAmount().toPlainString() + ", paid " + balance.paidAmount().toPlainString()
                    + ").");
        }
        for (ReportedWithholding withholding : detail.withholdings()) {
            if (invoice.withholding(withholding.kind()).isEmpty()) {
                throw new InvalidRequestException("Invoice " + invoice.invoiceNumber() + " carries no "
                        + withholding.kind().label() + ", so there is none to report with the payment.");
            }
        }
        return balance;
    }

    /**
     * Applies an admitted payment to its invoice, in the transaction that inserted it: the status, the
     * paid amount, the certificate of each withholding the payment brought one for, the audit and - when the
     * invoice is now paid - the webhook.
     */
    private void apply(CompanyScope scope, CarrierInvoice invoice, CarrierPayment payment, PayableBalance balance,
            UUID personId) {
        boolean wasOnHold = invoice.status() == InvoiceStatus.ON_HOLD;
        String heldBecause = invoice.onHoldReason();
        invoice.applyPayment(payment.amount(), balance.openBalanceAfter(payment.amount()), personId);
        for (ReportedWithholding withholding : payment.reportedWithholdings()) {
            if (withholding.certificateNumber() != null) {
                invoice.recordWithholdingCertificate(withholding.kind(), withholding.certificateNumber(),
                        withholding.certificateDate());
            }
        }
        invoiceRepository.save(invoice);

        Map<String, Object> details = paymentDetails(payment.detail(), Map.of(
                "paymentId", payment.id().toString(),
                "source", payment.source().name(),
                "status", invoice.status().name(),
                "paidAmount", invoice.paidAmount().toPlainString()));
        auditRecorder.record(scope, AuditAggregateType.CARRIER_INVOICE, invoice.id(), AuditAction.PAYMENT_RECORDED,
                details);
        if (wasOnHold) {
            // The money left regardless of the hold; the record says so, with the hold's own reason.
            auditRecorder.record(scope, AuditAggregateType.CARRIER_INVOICE, invoice.id(),
                    AuditAction.PAYABLE_HOLD_RELEASED, Map.of("invoiceNumber", invoice.invoiceNumber(),
                            "reason", "Payment " + payment.paymentReference() + " was recorded while on hold ("
                                    + heldBecause + ")",
                            "status", invoice.status().name()));
        }
        if (invoice.status() == InvoiceStatus.PAID) {
            businessEventPublisher.publish(new BusinessEventPublisher.BusinessEvent(scope.companyId(),
                    BusinessEventPublisher.CARRIER_INVOICE, invoice.id(), invoice.invoiceNumber(),
                    BusinessEventPublisher.PAYABLE_PAID, OffsetDateTime.now(clock)));
        }
    }

    private void requireReferenceUnused(CompanyScope scope, CarrierInvoice invoice, String reference) {
        if (paymentRepository.findByCompanyIdAndCarrierInvoiceIdAndPaymentReference(scope.companyId(), invoice.id(),
                reference).isPresent()) {
            throw new ConflictException("Payment " + reference + " of invoice " + invoice.invoiceNumber()
                    + " is already recorded.");
        }
    }

    private PayableView view(CompanyScope scope, CarrierInvoice invoice) {
        PayableBalance balance = balanceOf(scope, invoice);
        LocalDate today = today(scope);
        Integer daysOverdue = invoice.dueDate() != null && balance.openBalance().signum() > 0
                && today.isAfter(invoice.dueDate()) && invoice.status() != InvoiceStatus.PAID
                ? (int) ChronoUnit.DAYS.between(invoice.dueDate(), today) : null;
        String carrierName = carrierNames(scope, Set.of(invoice.carrierId())).get(invoice.carrierId());
        return new PayableView(invoice.id(), invoice.invoiceNumber(), invoice.documentKind().name(),
                invoice.fiscalDocumentCode(),
                invoice.carrierId(), carrierName, invoice.currency(), invoice.status(), invoice.invoiceDate(),
                invoice.dueDate(), invoice.paymentTermsDays(), invoice.scheduledPaymentDate(), invoice.onHoldReason(),
                balance.netAmount(), CarrierInvoiceView.WithholdingView.of(invoice), balance.withholdingAmount(),
                balance.payableAmount(),
                balance.paidAmount(), balance.openBalance(), daysOverdue, allowedActions(invoice, balance),
                paymentRepository.findByCompanyIdAndCarrierInvoiceIdOrderByPaidOnAscCreatedAtAsc(scope.companyId(),
                        invoice.id()).stream().map(PayableView.PaymentView::of).toList(),
                requestRepository.findByCompanyIdAndCarrierInvoiceIdOrderByRequestedAtAsc(scope.companyId(),
                        invoice.id()).stream().map(PayableView.PaymentRequestView::of).toList());
    }

    static Set<PayableView.Action> allowedActions(CarrierInvoice invoice, PayableBalance balance) {
        Set<PayableView.Action> actions = EnumSet.noneOf(PayableView.Action.class);
        if (invoice.documentKind().isNote()) {
            return actions;
        }
        InvoiceStatus status = invoice.status();
        if (status == InvoiceStatus.EXPORTED || status == InvoiceStatus.SCHEDULED
                || status == InvoiceStatus.PARTIALLY_PAID) {
            actions.add(PayableView.Action.SCHEDULE);
            actions.add(PayableView.Action.HOLD);
        }
        if (status == InvoiceStatus.ON_HOLD) {
            actions.add(PayableView.Action.RELEASE);
        }
        if (status.acceptsPayment() && balance.openBalance().signum() > 0) {
            actions.add(PayableView.Action.REQUEST_PAYMENT);
        }
        return actions;
    }

    private CarrierInvoice lockedInvoice(CompanyScope scope, UUID invoiceId) {
        return invoiceRepository.findByIdAndCompanyIdForUpdate(invoiceId, scope.companyId())
                .orElseThrow(PayableService::invoiceNotFound);
    }

    private CarrierPaymentRequest requestOf(CompanyScope scope, CarrierInvoice invoice, UUID requestId) {
        return requestRepository.findByIdAndCompanyIdAndCarrierInvoiceId(requestId, scope.companyId(), invoice.id())
                .orElseThrow(() -> new ResourceNotFoundException("Payment request not found on this invoice."));
    }

    private Map<UUID, String> carrierNames(CompanyScope scope, Set<UUID> ids) {
        if (ids.isEmpty()) {
            return Map.of();
        }
        return carrierLookupPort.findAllInCompany(ids, scope.companyId()).entrySet().stream()
                .collect(Collectors.toMap(Map.Entry::getKey, entry -> entry.getValue().name()));
    }

    private LocalDate today(CompanyScope scope) {
        return LocalDate.now(clock.withZone(scope.zoneId()));
    }

    /** Normalised and checked once for both channels. Bean Validation has already run on each body. */
    private static PaymentDetail detailOf(String reference, LocalDate paidOn, BigDecimal amount, String currency,
            List<ReportedWithholding> withholdings) {
        if (reference == null || reference.isBlank() || reference.trim().length() > 80) {
            throw new InvalidRequestException("paymentReference is 1 to 80 characters.");
        }
        if (paidOn == null) {
            throw new InvalidRequestException("paidOn is required.");
        }
        if (amount == null || amount.signum() <= 0 || amount.scale() > 2) {
            throw new InvalidRequestException("amount is a positive amount with at most two decimals.");
        }
        if (currency == null || !currency.trim().matches("^[A-Za-z]{3}$")) {
            throw new InvalidRequestException("currency is an ISO-4217 code.");
        }
        List<ReportedWithholding> normalised = new ArrayList<>();
        Set<com.ebim.tms.settlement.domain.tax.WithholdingKind> kinds = new java.util.HashSet<>();
        for (ReportedWithholding withholding : withholdings) {
            if (withholding.kind() == null) {
                throw new InvalidRequestException("A reported withholding names its kind.");
            }
            if (!kinds.add(withholding.kind())) {
                throw new InvalidRequestException(withholding.kind() + " is reported twice.");
            }
            String certificate = withholding.certificateNumber() == null || withholding.certificateNumber().isBlank()
                    ? null : withholding.certificateNumber().trim();
            if (certificate == null && withholding.certificateDate() != null) {
                throw new InvalidRequestException("A certificate date needs its number.");
            }
            if (certificate != null && certificate.length() > 60) {
                throw new InvalidRequestException("A certificate number is at most 60 characters.");
            }
            if (withholding.amount() != null && withholding.amount().signum() < 0) {
                throw new InvalidRequestException("A withheld amount is not negative.");
            }
            normalised.add(new ReportedWithholding(withholding.kind(), SettlementService.money(withholding.amount()),
                    certificate, withholding.certificateDate()));
        }
        return new PaymentDetail(reference.trim(), paidOn, SettlementService.money(amount),
                currency.trim().toUpperCase(Locale.ROOT), normalised);
    }

    private static Map<String, Object> paymentDetails(PaymentDetail detail, Map<String, String> extra) {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("paymentReference", detail.paymentReference());
        details.put("paidOn", detail.paidOn().toString());
        details.put("amount", detail.amount().toPlainString());
        details.put("currency", detail.currency());
        if (!detail.withholdings().isEmpty()) {
            details.put("withholdings", detail.withholdings().stream().map(withholding -> {
                Map<String, Object> one = new LinkedHashMap<>();
                one.put("kind", withholding.kind().name());
                one.put("amount", withholding.amount() == null ? null : withholding.amount().toPlainString());
                one.put("certificateNumber", withholding.certificateNumber());
                return one;
            }).toList());
        }
        details.putAll(extra);
        return details;
    }

    private static Map<String, BigDecimal> emptyBuckets() {
        Map<String, BigDecimal> buckets = new LinkedHashMap<>();
        PayableReportViews.BUCKETS.forEach(bucket -> buckets.put(bucket, BigDecimal.ZERO.setScale(2)));
        return buckets;
    }

    private static BigDecimal sum(Map<String, BigDecimal> amounts) {
        return amounts.values().stream().reduce(BigDecimal.ZERO.setScale(2), BigDecimal::add);
    }

    /** A kind as the ERP names it; an unknown one is a 400 naming it. */
    private static com.ebim.tms.settlement.domain.tax.WithholdingKind withholdingKindOf(String kind) {
        if (kind == null || kind.isBlank()) {
            throw new InvalidRequestException("A reported withholding names its kind.");
        }
        try {
            return com.ebim.tms.settlement.domain.tax.WithholdingKind.valueOf(kind.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException unknown) {
            throw new InvalidRequestException("Unknown withholding kind " + kind.trim() + ".");
        }
    }

    private static boolean notBlank(String value) {
        return value != null && !value.isBlank();
    }

    private static ResourceNotFoundException invoiceNotFound() {
        return new ResourceNotFoundException("Invoice not found.");
    }
}
