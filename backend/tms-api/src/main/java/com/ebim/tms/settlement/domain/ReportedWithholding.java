package com.ebim.tms.settlement.domain;

import com.ebim.tms.settlement.domain.tax.WithholdingKind;
import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * One withholding as a payment report states it (V67, ADR-027): what the ERP or a person says was
 * withheld and deposited, and its certificate (the detraction constancy, Ecuador's comprobante de
 * retención). Informative: the balance is computed from the document's own withholdings.
 */
public record ReportedWithholding(WithholdingKind kind, BigDecimal amount, String certificateNumber,
        LocalDate certificateDate) {
}
