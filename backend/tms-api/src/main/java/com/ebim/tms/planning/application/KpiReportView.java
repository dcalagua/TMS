package com.ebim.tms.planning.application;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;

/**
 * The KPI report: one company, one span of operating days, every figure counted server-side
 * ({@code docs/domain/KPIS_REPORTING_V1.md}).
 *
 * <p><b>Three of the sections are null for callers who may not see them</b>, and null is a
 * different statement from an empty one. {@link #orders} needs {@code orders.order:read},
 * {@link #tenders} needs {@code planning.tender:read} and {@link #cost} needs
 * {@code rates.trip_cost:read}; a caller holding only {@code monitoring.transport:read} gets the
 * screen with those cards saying "not available to you" rather than a 403 over one number, or -
 * worse - a zero that would assert an empty backlog nobody was allowed to look at. Same shape
 * {@code ControlTowerSummaryView.ordersUnplanned} uses, applied to whole sections.
 *
 * @param from        the first operating day, inclusive
 * @param to          the last operating day, inclusive
 * @param days        how many days that is, both ends included - sent so a client never has to do
 *                    date arithmetic to label the range or to divide by it
 * @param generatedAt when the server read all of this. Every figure here is historical and none of
 *                    them moves on its own, unlike the control tower's; it is sent anyway, because
 *                    an export saved to a shared drive has to be able to say when it was taken
 * @param shipments   what was planned and whether it left on time
 * @param service     what happened at the customer's door
 * @param exceptions  how much went wrong
 * @param utilization how full the vehicles ran
 * @param orders      planned versus unplanned demand, or null - see above
 * @param tenders     whether carriers took what they were offered, or null - see above
 * @param cost        estimated versus actual, one entry per currency, or null - see above
 * @param daily       one row per day in the range, empty days included. The chart, the detail
 *                    table, and what the CSV export writes
 */
public record KpiReportView(
        LocalDate from,
        LocalDate to,
        int days,
        OffsetDateTime generatedAt,
        KpiShipmentsView shipments,
        KpiServiceView service,
        KpiExceptionsView exceptions,
        KpiUtilizationView utilization,
        KpiOrdersView orders,
        KpiTenderView tenders,
        List<KpiCostView> cost,
        List<KpiDailyRow> daily) {
}
