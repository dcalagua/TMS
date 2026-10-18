package com.ebim.tms.integration.application;

import com.ebim.tms.shared.reference.PayablePaymentIntakePort;
import org.springframework.stereotype.Service;

/**
 * The payment half of the inbound API (V67, ADR-023 section 2): the ERP reports what it paid, and
 * {@code settlement}, which owns the rules, records it through {@link PayablePaymentIntakePort}. The
 * payment is attributed to the credential, never to a person.
 */
@Service
public class IntegrationPayablePaymentService {

    private final PayablePaymentIntakePort intake;

    public IntegrationPayablePaymentService(PayablePaymentIntakePort intake) {
        this.intake = intake;
    }

    public IntegrationOutcome<PayablePaymentResultV1> record(IntegrationPrincipal principal, PayablePaymentV1 payment) {
        PayablePaymentV1.InvoiceRef ref = payment.invoice();
        PayablePaymentV1.Detraction detraction = payment.detraction();
        PayablePaymentIntakePort.Result result = intake.record(principal.companyScope(),
                new PayablePaymentIntakePort.Command(principal.id(),
                        new PayablePaymentIntakePort.InvoiceKey(ref.invoiceId(), ref.carrierTaxId(), ref.documentType(),
                                ref.series(), ref.correlative(), ref.carrierCode(), ref.invoiceNumber()),
                        payment.paymentReference(), payment.paidOn(), payment.amount(), payment.currency(),
                        detraction == null ? null : detraction.constancyNumber(),
                        detraction == null ? null : detraction.constancyDate(),
                        detraction == null ? null : detraction.amount(),
                        payment.retentionAmount()));
        PayablePaymentResultV1 body = new PayablePaymentResultV1(result.invoiceId(), result.invoiceNumber(),
                result.paymentId(), result.outcome().name(), result.status(), result.paidAmount(),
                result.openBalance(), result.dueDate());
        int httpStatus = result.outcome() == PayablePaymentIntakePort.Outcome.RECORDED ? 201 : 200;
        return IntegrationOutcome.single(body, httpStatus, result.paymentId(), payment.sourceSystem(),
                payment.paymentReference());
    }
}
