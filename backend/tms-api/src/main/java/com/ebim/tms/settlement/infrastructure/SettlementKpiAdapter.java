package com.ebim.tms.settlement.infrastructure;

import com.ebim.tms.shared.reference.CarrierLookupPort;
import com.ebim.tms.shared.reference.SettlementKpiPort;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.jdbc.core.simple.JdbcClient;
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

    private final JdbcClient jdbcClient;

    SettlementKpiAdapter(CarrierInvoiceRepository invoiceRepository, CarrierLookupPort carrierLookupPort,
            JdbcClient jdbcClient) {
        this.invoiceRepository = invoiceRepository;
        this.carrierLookupPort = carrierLookupPort;
        this.jdbcClient = jdbcClient;
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

    private static final String OPEN_ACCRUALS_SQL = """
            SELECT currency, count(*) AS accruals,
                   coalesce(sum(accrued_amount - consumed_amount - reversed_amount), 0) AS balance
              FROM tms.freight_accrual
             WHERE company_id = :companyId AND status IN ('OPEN', 'PARTIALLY_SETTLED') AND service_date <= :to
             GROUP BY currency
            """;

    // The invoiced side reads the documents, not the consumption: an accrual is consumed up to its
    // balance, and the variance is precisely what was billed beyond (or short of) it.
    private static final String VARIANCE_SQL = """
            SELECT fa.currency, count(*) AS accruals, coalesce(sum(fa.accrued_amount), 0) AS accrued,
                   coalesce(sum(billed.amount), 0) AS invoiced
              FROM tms.freight_accrual fa
              CROSS JOIN LATERAL (
                    SELECT coalesce(sum(l.line_amount), 0) AS amount
                      FROM tms.carrier_invoice_line l
                      JOIN tms.carrier_invoice i ON i.id = l.carrier_invoice_id AND i.company_id = l.company_id
                     WHERE l.company_id = fa.company_id AND l.trip_id = fa.trip_id AND i.currency = fa.currency
                       AND i.status IN ('APPROVED', 'EXPORTED', 'SCHEDULED', 'PARTIALLY_PAID', 'PAID', 'ON_HOLD')
                   ) billed
             WHERE fa.company_id = :companyId AND fa.service_date BETWEEN :from AND :to
               AND fa.consumed_amount > 0 AND fa.status <> 'REVERSED'
             GROUP BY fa.currency
            """;

    private static final String DAYS_TO_PAYMENT_SQL = """
            SELECT count(*) AS invoices,
                   avg(paid.last_paid_on - (approved.decided_at AT TIME ZONE :zone)::date) AS average_days
              FROM tms.carrier_invoice i
              CROSS JOIN LATERAL (
                    SELECT max(p.paid_on) AS last_paid_on FROM tms.carrier_payment p
                     WHERE p.carrier_invoice_id = i.id AND p.company_id = i.company_id) paid
              CROSS JOIN LATERAL (
                    SELECT min(a.decided_at) AS decided_at FROM tms.settlement_approval a
                     WHERE a.carrier_invoice_id = i.id AND a.company_id = i.company_id
                       AND a.decision = 'APPROVED') approved
             WHERE i.company_id = :companyId AND i.status = 'PAID'
               AND paid.last_paid_on BETWEEN :from AND :to AND approved.decided_at IS NOT NULL
            """;

    private static final String COST_PER_KG_SQL = """
            SELECT r.currency, coalesce(sum(a.allocated_amount), 0) AS cost, coalesce(sum(a.weight_kg), 0) AS weight_kg
              FROM tms.trip_cost_allocation_run r
              JOIN tms.trip_cost_allocation a ON a.run_id = r.id AND a.company_id = r.company_id
             WHERE r.company_id = :companyId AND r.superseded_at IS NULL
               AND r.service_date BETWEEN :from AND :to AND a.allocated_amount IS NOT NULL
             GROUP BY r.currency
            """;

    @Override
    @Transactional(readOnly = true)
    public FinanceKpiTotals finance(UUID companyId, ZoneId zone, LocalDate from, LocalDate to) {
        Map<String, BigDecimal[]> byCurrency = new TreeMap<>();
        Map<String, long[]> counts = new TreeMap<>();
        jdbcClient.sql(OPEN_ACCRUALS_SQL).param("companyId", companyId).param("to", to)
                .query((rs, rowNum) -> {
                    String currency = rs.getString("currency").trim();
                    figures(byCurrency, currency)[0] = rs.getBigDecimal("balance");
                    counts.computeIfAbsent(currency, key -> new long[2])[0] = rs.getLong("accruals");
                    return currency;
                }).list();
        jdbcClient.sql(VARIANCE_SQL).param("companyId", companyId).param("from", from).param("to", to)
                .query((rs, rowNum) -> {
                    String currency = rs.getString("currency").trim();
                    BigDecimal[] figures = figures(byCurrency, currency);
                    figures[1] = rs.getBigDecimal("accrued");
                    figures[2] = rs.getBigDecimal("invoiced");
                    counts.computeIfAbsent(currency, key -> new long[2])[1] = rs.getLong("accruals");
                    return currency;
                }).list();
        jdbcClient.sql(COST_PER_KG_SQL).param("companyId", companyId).param("from", from).param("to", to)
                .query((rs, rowNum) -> {
                    String currency = rs.getString("currency").trim();
                    BigDecimal[] figures = figures(byCurrency, currency);
                    figures[3] = rs.getBigDecimal("cost");
                    figures[4] = rs.getBigDecimal("weight_kg");
                    return currency;
                }).list();

        List<CurrencyFinance> rows = new ArrayList<>();
        byCurrency.forEach((currency, figures) -> {
            long[] count = counts.getOrDefault(currency, new long[2]);
            BigDecimal accrued = money(figures[1]);
            BigDecimal invoiced = money(figures[2]);
            BigDecimal weight = figures[4] == null ? BigDecimal.ZERO : figures[4];
            rows.add(new CurrencyFinance(currency, money(figures[0]), count[0], count[1], accrued, invoiced,
                    invoiced.subtract(accrued), money(figures[3]), weight,
                    weight.signum() > 0 && figures[3] != null
                            ? figures[3].divide(weight, 4, RoundingMode.HALF_UP) : null));
        });

        Map<String, Object> days = jdbcClient.sql(DAYS_TO_PAYMENT_SQL)
                .param("companyId", companyId).param("zone", zone.getId()).param("from", from).param("to", to)
                .query().singleRow();
        long paid = ((Number) days.get("invoices")).longValue();
        Object average = days.get("average_days");
        BigDecimal averageDays = average == null ? null
                : new BigDecimal(average.toString()).setScale(1, RoundingMode.HALF_UP);
        return new FinanceKpiTotals(rows, paid == 0 ? null : averageDays, paid);
    }

    private static BigDecimal[] figures(Map<String, BigDecimal[]> byCurrency, String currency) {
        return byCurrency.computeIfAbsent(currency, key -> new BigDecimal[5]);
    }

    private static BigDecimal hours(Double value) {
        return value == null ? null : BigDecimal.valueOf(value).setScale(1, RoundingMode.HALF_UP);
    }

    private static BigDecimal money(BigDecimal value) {
        return value == null ? BigDecimal.ZERO.setScale(2) : value.setScale(2, RoundingMode.HALF_UP);
    }
}
