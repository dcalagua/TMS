package com.ebim.tms.settlement.application;

import com.ebim.tms.settlement.infrastructure.CostToServeQueries;
import com.ebim.tms.settlement.infrastructure.CostToServeQueries.GroupBy;
import com.ebim.tms.shared.io.DelimitedTextWriter;
import com.ebim.tms.shared.security.CompanyScope;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * "Cost per customer / destination / route" (V68, ADR-026 section 7): cost, cost per kg, per m3 and
 * per order, and the orders that received nothing, over the current allocation of each trip.
 */
@Service
public class CostToServeReportService {

    private final CostToServeQueries queries;
    private final CostAllocationService allocationService;

    public CostToServeReportService(CostToServeQueries queries, CostAllocationService allocationService) {
        this.queries = queries;
        this.allocationService = allocationService;
    }

    @Transactional(readOnly = true)
    public CostToServeView report(CompanyScope scope, LocalDate from, LocalDate to, GroupBy groupBy) {
        AccrualService.requireRange(from, to);
        GroupBy by = groupBy == null ? GroupBy.CUSTOMER : groupBy;
        List<CostToServeView.Totals> totals = queries.totals(scope.companyId(), from, to).stream()
                .map(row -> new CostToServeView.Totals(row.currency(), row.trips(), money(row.cost()),
                        money(row.allocated()), row.tripsNotAllocated(), row.unallocatedOrders(), row.invoiced(),
                        row.actual(), row.estimated(), per(row.allocated(), row.weightKg())))
                .toList();
        List<CostToServeView.Group> groups = queries.groups(scope.companyId(), from, to, by).stream()
                .map(row -> new CostToServeView.Group(row.key(), row.label(), row.currency(), row.trips(),
                        row.orders(), row.unallocatedOrders(), money(row.cost()), row.weightKg(), row.volumeM3(),
                        per(row.cost(), row.weightKg()), per(row.cost(), row.volumeM3()),
                        row.orders() == 0 ? null : per(row.cost(), BigDecimal.valueOf(row.orders()))))
                .toList();
        return new CostToServeView(from, to, by.name(), allocationService.basisOf(scope.companyId()).name(),
                false, CostToServeView.NO_MARGIN_NOTE, totals, groups);
    }

    /** The groups as CSV: one row per group and currency, the no-margin note in no cell (it is in the header name). */
    public static byte[] csv(CostToServeView report) {
        List<String> header = List.of("group_by", "key", "label", "currency", "trips", "orders", "unallocated_orders",
                "cost", "weight_kg", "volume_m3", "cost_per_kg", "cost_per_m3", "cost_per_order", "basis");
        List<List<String>> rows = new ArrayList<>();
        for (CostToServeView.Group group : report.groups()) {
            rows.add(List.of(report.groupBy(), text(group.key()), text(group.label()), group.currency(),
                    String.valueOf(group.trips()), String.valueOf(group.orders()),
                    String.valueOf(group.unallocatedOrders()), text(group.cost()), text(group.weightKg()),
                    text(group.volumeM3()), text(group.costPerKg()), text(group.costPerM3()),
                    text(group.costPerOrder()), report.basis()));
        }
        return DelimitedTextWriter.write(header, rows);
    }

    /** Four decimals: a cost per kg of freight is routinely below one cent. Null when there is nothing to divide by. */
    static BigDecimal per(BigDecimal amount, BigDecimal divisor) {
        if (amount == null || divisor == null || divisor.signum() <= 0) {
            return null;
        }
        return amount.divide(divisor, 4, RoundingMode.HALF_UP);
    }

    private static BigDecimal money(BigDecimal value) {
        return value == null ? BigDecimal.ZERO.setScale(2) : value.setScale(2, RoundingMode.HALF_UP);
    }

    private static String text(Object value) {
        if (value == null) {
            return "";
        }
        return value instanceof BigDecimal decimal ? decimal.toPlainString() : value.toString();
    }
}
