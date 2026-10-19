package com.ebim.tms.settlement.application;

import com.ebim.tms.settlement.domain.CarrierInvoice;
import com.ebim.tms.settlement.domain.InvoiceStatus;
import com.ebim.tms.settlement.domain.PayableBalance;
import com.ebim.tms.settlement.domain.SettlementApproval;
import com.ebim.tms.settlement.infrastructure.CarrierInvoiceRepository;
import com.ebim.tms.settlement.infrastructure.CarrierPaymentRepository;
import com.ebim.tms.settlement.infrastructure.SettlementApprovalRepository;
import com.ebim.tms.shared.api.ResourceNotFoundException;
import com.ebim.tms.shared.security.CompanyScope;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The carrier portal's "Mis facturas" (V67, ADR-023 section 2, ADR-018).
 *
 * <p>Two fences, as for the portal's trips: every query here names the caller's carrier, and the
 * database's {@code p_carrier_scope} policies filter {@code carrier_invoice}, its lines, approvals and
 * payments by {@code tms.current_carrier_id()} on their own. Either alone would keep one carrier out of
 * another's invoices; the test proves each.
 */
@Service
public class PortalInvoiceService {

    /** A carrier's documents of the last months: the list is a phone screen, not an archive. */
    static final int MAX_INVOICES = 200;

    private final CarrierInvoiceRepository invoiceRepository;
    private final CarrierPaymentRepository paymentRepository;
    private final SettlementApprovalRepository approvalRepository;
    private final PayableService payableService;

    public PortalInvoiceService(CarrierInvoiceRepository invoiceRepository, CarrierPaymentRepository paymentRepository,
            SettlementApprovalRepository approvalRepository, PayableService payableService) {
        this.invoiceRepository = invoiceRepository;
        this.paymentRepository = paymentRepository;
        this.approvalRepository = approvalRepository;
        this.payableService = payableService;
    }

    @Transactional(readOnly = true)
    public List<PortalInvoiceViews.Summary> list(CompanyScope scope) {
        UUID carrierId = requireCarrier(scope);
        return invoiceRepository.findByCompanyIdAndCarrierIdOrderByInvoiceDateDescCreatedAtDesc(scope.companyId(),
                        carrierId, PageRequest.of(0, MAX_INVOICES)).stream()
                .map(invoice -> {
                    PayableBalance balance = owed(scope, invoice);
                    return new PortalInvoiceViews.Summary(invoice.id(), invoice.invoiceNumber(),
                            invoice.documentKind().name(), invoice.fiscalDocumentCode(), invoice.invoiceDate(),
                            invoice.currency(), invoice.totalAmount(),
                            PortalInvoiceViews.PortalInvoiceStatus.of(invoice.status()), invoice.dueDate(),
                            invoice.paidAmount(), balance == null ? null : balance.openBalance(),
                            invoice.withholdings().isEmpty() ? null : invoice.withholdingTotal());
                })
                .toList();
    }

    @Transactional(readOnly = true)
    public PortalInvoiceViews.Detail detail(CompanyScope scope, UUID invoiceId) {
        UUID carrierId = requireCarrier(scope);
        // Another carrier's id answers exactly as a missing one: the portal does not confirm it exists.
        CarrierInvoice invoice = invoiceRepository.findByIdAndCompanyIdAndCarrierId(invoiceId, scope.companyId(),
                carrierId).orElseThrow(() -> new ResourceNotFoundException("Invoice not found."));
        PayableBalance balance = owed(scope, invoice);
        String rejectionReason = invoice.status() != InvoiceStatus.REJECTED ? null
                : approvalRepository.findByCompanyIdAndCarrierInvoiceIdOrderByDecidedAtAsc(scope.companyId(),
                                invoice.id()).stream()
                        .filter(approval -> approval.decision() == SettlementApproval.Decision.REJECTED)
                        .reduce((first, second) -> second)
                        .map(SettlementApproval::comment)
                        .orElse(null);
        String vatLabel = invoice.fiscalCountry() == null ? null
                : com.ebim.tms.settlement.domain.tax.TaxRegimes.forCountry(invoice.fiscalCountry()).vatLabel();
        return new PortalInvoiceViews.Detail(invoice.id(), invoice.invoiceNumber(), invoice.documentKind().name(),
                invoice.fiscalDocumentCode(), vatLabel, invoice.invoiceDate(), invoice.currency(),
                invoice.totalAmount(), PortalInvoiceViews.PortalInvoiceStatus.of(invoice.status()), invoice.dueDate(),
                invoice.paidAmount(), balance == null ? null : balance.openBalance(),
                CarrierInvoiceView.WithholdingView.of(invoice), invoice.withholdingTotal(),
                balance == null ? null : balance.netAmount(),
                balance == null ? null : balance.payableAmount(), rejectionReason,
                invoice.lines().stream()
                        .map(line -> new PortalInvoiceViews.Line(line.lineNumber(), line.description(),
                                line.lineAmount(), line.chargeCode().name()))
                        .toList(),
                paymentRepository.findByCompanyIdAndCarrierInvoiceIdOrderByPaidOnAscCreatedAtAsc(scope.companyId(),
                                invoice.id()).stream()
                        .map(payment -> new PortalInvoiceViews.Payment(payment.paymentReference(), payment.paidOn(),
                                payment.amount(), payment.reportedWithholdings()))
                        .toList());
    }

    /** What is owed on an authorised invoice; nothing to say about a note, or before the approval. */
    private PayableBalance owed(CompanyScope scope, CarrierInvoice invoice) {
        if (invoice.documentKind().isNote() || !InvoiceStatus.AUTHORISED.contains(invoice.status())) {
            return null;
        }
        return payableService.balanceOf(scope, invoice);
    }

    /** The carrier this caller acts for, or a refusal: the portal is for carrier users only. */
    static UUID requireCarrier(CompanyScope scope) {
        if (!scope.isCarrierScoped()) {
            throw new AccessDeniedException("The carrier portal is for carrier users.");
        }
        return scope.carrierId();
    }
}
