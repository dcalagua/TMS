package com.ebim.tms.settlement.infrastructure;

import com.ebim.tms.shared.reference.CarrierLookupPort;
import com.ebim.tms.shared.reference.SettlementKpiPort;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Answers {@link SettlementKpiPort} from the module that owns the invoices (V64, ADR-024 item 7).
 *
 * <p>One grouped query, so the per-carrier rows and the company figures come from one snapshot.
 * Hours are rounded to one decimal, {@link RoundingMode#HALF_UP}, here and not on a screen: two
 * clients must not show the same cycle differently.
 */
@Component
class SettlementKpiAdapter implements SettlementKpiPort {

    private final CarrierInvoiceRepository invoiceRepository;
    private final CarrierLookupPort carrierLookupPort;

    SettlementKpiAdapter(CarrierInvoiceRepository invoiceRepository, CarrierLookupPort carrierLookupPort) {
        this.invoiceRepository = invoiceRepository;
        this.carrierLookupPort = carrierLookupPort;
    }

    @Override
    @Transactional(readOnly = true)
    public SettlementKpiTotals totals(UUID companyId, LocalDate from, LocalDate to) {
        List<CarrierInvoiceRepository.SettlementKpiRow> rows = invoiceRepository.settlementKpis(companyId, from, to);

        Set<UUID> carrierIds = rows.stream().map(CarrierInvoiceRepository.SettlementKpiRow::getCarrierId)
                .filter(Objects::nonNull).collect(Collectors.toSet());
        Map<UUID, String> names = carrierIds.isEmpty() ? Map.of()
                : carrierLookupPort.findAllInCompany(carrierIds, companyId).entrySet().stream()
                        .collect(Collectors.toMap(Map.Entry::getKey, entry -> entry.getValue().name()));

        List<CarrierSettlementTotals> byCarrier = new ArrayList<>();
        CarrierInvoiceRepository.SettlementKpiRow grand = null;
        for (CarrierInvoiceRepository.SettlementKpiRow row : rows) {
            if (row.getGrandTotal()) {
                grand = row;
                continue;
            }
            byCarrier.add(new CarrierSettlementTotals(row.getCarrierId(), names.get(row.getCarrierId()),
                    row.getCurrency(), row.getInvoices(), row.getApproved(), row.getWithDiscrepancy(),
                    money(row.getAmountObserved()), money(row.getAmountSaved()),
                    hours(row.getCycleMedianHours()), hours(row.getCycleP90Hours())));
        }
        if (grand == null || grand.getInvoices() == 0) {
            return new SettlementKpiTotals(0, 0, 0, null, null, byCarrier);
        }
        return new SettlementKpiTotals(grand.getInvoices(), grand.getApproved(), grand.getWithDiscrepancy(),
                hours(grand.getCycleMedianHours()), hours(grand.getCycleP90Hours()), byCarrier);
    }

    private static BigDecimal hours(Double value) {
        return value == null ? null : BigDecimal.valueOf(value).setScale(1, RoundingMode.HALF_UP);
    }

    private static BigDecimal money(BigDecimal value) {
        return value == null ? BigDecimal.ZERO.setScale(2) : value.setScale(2, RoundingMode.HALF_UP);
    }
}
