package com.ebim.tms.planning.application;

import com.ebim.tms.planning.domain.DeliveryResult;
import com.ebim.tms.planning.domain.KpiRate;
import com.ebim.tms.planning.domain.TenderStatus;
import com.ebim.tms.planning.domain.TripExceptionStatus;
import com.ebim.tms.planning.domain.TripStatus;
import com.ebim.tms.planning.infrastructure.OrderDeliveryRepository;
import com.ebim.tms.planning.infrastructure.TripExceptionRepository;
import com.ebim.tms.planning.infrastructure.TripRepository;
import com.ebim.tms.planning.infrastructure.TripStopRepository;
import com.ebim.tms.planning.infrastructure.TripTenderRepository;
import com.ebim.tms.shared.io.DelimitedTextWriter;
import com.ebim.tms.shared.reference.OrderBacklogTotals;
import com.ebim.tms.shared.reference.OrderPlanningPort;
import com.ebim.tms.shared.reference.TripCostAnalyticsPort;
import com.ebim.tms.shared.security.CompanyScope;
import com.ebim.tms.shared.security.Permission;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The KPI report ({@code docs/domain/KPIS_REPORTING_V1.md}): one company, a span of operating days,
 * and the dozen numbers that say whether the transport operation is working.
 *
 * <p><b>It owns no state and, with one exception, no rule.</b> Every count is a database aggregate
 * over rows another part of the product wrote; the exception is arithmetic - the divisions - and
 * those live in {@code KpiRate} rather than here so that "what does an empty denominator mean" has
 * one answer and a unit test. Nothing on this screen is a fact this class invented.
 *
 * <p><b>Why it lives in {@code planning}.</b> The same argument {@code ControlTowerService} makes,
 * with one addition. Trips, stops, deliveries, exceptions and tenders are all planning's, so a
 * {@code reporting} module would reach five of its six sources through new ports - a boundary with
 * nothing on the other side of it. The addition is the sixth and seventh: cost belongs to
 * {@code rates} and the order backlog to {@code orders}, and both are read exactly as the rest of
 * planning reads them, through {@code shared.reference} ports
 * ({@link TripCostAnalyticsPort}, {@link OrderPlanningPort}).
 *
 * <h2>What is scoped by what</h2>
 *
 * <p>Everything is scoped by company and by the same range. Unlike the control tower there is no
 * second, narrower scope: this report has no filters below the date range, so there is no way for
 * one to hide a figure from another. Adding an origin or carrier filter later would reopen that
 * question, and the answer would have to be the control tower's - filters drill, they do not blind.
 *
 * <h2>Authority finer than the endpoint</h2>
 *
 * <p>Three sections are answered with null rather than with numbers when the caller is not entitled
 * to them: the order backlog ({@code orders.order:read}), tendering ({@code planning.tender:read})
 * and cost ({@code rates.trip_cost:read}). Null and not zero, and not a 403 either - the same
 * decision {@code ControlTowerService} made about the unplanned-order count, applied to whole
 * sections, for the same reason: taking a whole screen away over one figure is worse than the
 * screen saying which figure it may not show. The checks live here, on the fields they protect,
 * rather than as extra {@code hasAuthority} expressions on the controller.
 *
 * <h2>Query budget</h2>
 *
 * <p>The report costs eight statements regardless of how long the range is, and none of them
 * returns a row per shipment: one grouped trip count (by day and state), one grouped delivery
 * count, one grouped exception count, one grouped tender count, one utilisation aggregate, one stop
 * aggregate, and the two port calls - each one statement in its own module. The export costs the
 * three that feed the daily series. The range itself is capped at {@link KpiRange#MAX_DAYS}, which
 * is what bounds all eight.
 *
 * <p><b>The summary is summed from the daily series, not queried again.</b> The shipment, delivery
 * and exception headlines are totals of the same rows the chart is drawn from, so a card and the
 * column under it cannot disagree - which is the failure mode dashboards have, and the reason this
 * is worth a sentence.
 */
@Service
public class KpiService {

    private final TripRepository tripRepository;
    private final TripStopRepository tripStopRepository;
    private final OrderDeliveryRepository orderDeliveryRepository;
    private final TripExceptionRepository tripExceptionRepository;
    private final TripTenderRepository tripTenderRepository;
    private final OrderPlanningPort orderPlanningPort;
    private final TripCostAnalyticsPort tripCostAnalyticsPort;

    public KpiService(TripRepository tripRepository, TripStopRepository tripStopRepository,
            OrderDeliveryRepository orderDeliveryRepository, TripExceptionRepository tripExceptionRepository,
            TripTenderRepository tripTenderRepository, OrderPlanningPort orderPlanningPort,
            TripCostAnalyticsPort tripCostAnalyticsPort) {
        this.tripRepository = tripRepository;
        this.tripStopRepository = tripStopRepository;
        this.orderDeliveryRepository = orderDeliveryRepository;
        this.tripExceptionRepository = tripExceptionRepository;
        this.tripTenderRepository = tripTenderRepository;
        this.orderPlanningPort = orderPlanningPort;
        this.tripCostAnalyticsPort = tripCostAnalyticsPort;
    }

    /**
     * The whole report: the headline sections and the daily series behind them.
     *
     * <p>One transaction, so the shipment counts and the cost totals they are compared against are
     * read from the same snapshot. A report assembled from eight independently-timed reads could
     * show a shipment in the trip count that is missing from the cost sum, and there would be no
     * way to tell that from a data problem.
     */
    @Transactional(readOnly = true)
    public KpiReportView report(CompanyScope scope, KpiFilter filter) {
        KpiRange range = KpiRange.of(scope, filter);
        UUID companyId = scope.companyId();

        DailySeries series = daily(companyId, range);
        Totals totals = Totals.of(series.rows());

        return new KpiReportView(range.from(), range.to(), range.days(), OffsetDateTime.now(),
                shipments(series.tripsByStatus(), totals),
                service(scope, range, totals, series),
                exceptions(totals),
                utilization(companyId, range),
                scope.has(Permission.ORDERS_ORDER_READ) ? orders(companyId, range) : null,
                scope.has(Permission.PLANNING_TENDER_READ) ? tenders(companyId, range) : null,
                scope.has(Permission.RATES_TRIP_COST_READ) ? cost(companyId, range) : null,
                series.rows());
    }

    /**
     * The daily table as a CSV file - the tabular half of the report, in the one format every
     * spreadsheet and every finance system reads.
     *
     * <p>Exports the series and not the headline sections, deliberately. The daily rows are the
     * only part of this report that is a table; the sections are cards, and a CSV whose first
     * fifteen lines were label/value pairs and whose remainder was a grid would be a file nobody
     * can pivot. Everything on a card is a total of a column that is in here.
     *
     * <p>Runs the same {@link KpiRange} resolution and the same three queries as the screen, so an
     * export taken from a screen shows the same days as the screen it was taken from.
     */
    @Transactional(readOnly = true)
    public KpiExportFile exportDaily(CompanyScope scope, KpiFilter filter) {
        KpiRange range = KpiRange.of(scope, filter);
        List<KpiDailyRow> rows = daily(scope.companyId(), range).rows();
        return new KpiExportFile("tms-kpis-" + range.from() + "-to-" + range.to() + ".csv",
                DelimitedTextWriter.write(CSV_HEADER, rows.stream().map(KpiService::csvRow).toList()));
    }

    // --- the daily series ----------------------------------------------------------------

    /**
     * Everything three grouped queries produce, in the two shapes the report needs it: one row per
     * day for the chart and the table, and the totals that are not per-day.
     *
     * @param rows                  one per day in the range, empty days included
     * @param tripsByStatus         the whole range's lifecycle breakdown, accumulated in the same
     *                              pass rather than by a second, identical query
     * @param deliveriesShort       the customer was left short. Not on a daily row - a chart of
     *                              five delivery outcomes is unreadable - so it is carried here
     * @param deliveriesNotAttempted never taken off the vehicle; likewise
     */
    private record DailySeries(
            List<KpiDailyRow> rows,
            Map<TripStatus, Long> tripsByStatus,
            long deliveriesShort,
            long deliveriesNotAttempted) {
    }

    /**
     * One row per day in the range, empty days included, from three grouped queries.
     *
     * <p>The rows are built from {@link KpiRange#dates()} rather than from what the queries
     * returned, which is what puts a zero on a Sunday instead of leaving a hole in the chart. Each
     * query's absent days are simply the accumulator's defaults.
     *
     * <p>A row whose date is somehow outside the range is skipped rather than trusted: the
     * predicate that produced it says it cannot happen, and a null accumulator would otherwise be a
     * {@code NullPointerException} on a read-only report.
     */
    private DailySeries daily(UUID companyId, KpiRange range) {
        Map<LocalDate, DayAccumulator> byDate = new HashMap<>();
        for (LocalDate date : range.dates()) {
            byDate.put(date, new DayAccumulator());
        }
        Map<TripStatus, Long> tripsByStatus = new EnumMap<>(TripStatus.class);
        for (TripStatus status : TripStatus.values()) {
            tripsByStatus.put(status, 0L);
        }
        long deliveriesShort = 0;
        long deliveriesNotAttempted = 0;

        for (TripRepository.TripDailyStatusCount row
                : tripRepository.countDailyByStatusForRange(companyId, range.from(), range.to())) {
            tripsByStatus.merge(row.getStatus(), row.getTripCount(), Long::sum);
            DayAccumulator day = byDate.get(row.getPlanningDate());
            if (day == null) {
                continue;
            }
            day.trips += row.getTripCount();
            if (row.getStatus() == TripStatus.CANCELLED) {
                day.tripsCancelled += row.getTripCount();
            } else if (row.getStatus() == TripStatus.COMPLETED) {
                day.tripsCompleted += row.getTripCount();
            }
            day.departuresMeasured += row.getDeparturesMeasured();
            day.departuresLate += row.getDeparturesLate();
        }

        for (OrderDeliveryRepository.DeliveryDailyCount row
                : orderDeliveryRepository.countDailyByResultForRange(companyId, range.from(), range.to())) {
            DayAccumulator day = byDate.get(row.getPlanningDate());
            if (day == null) {
                continue;
            }
            day.deliveriesRecorded += row.getDeliveryCount();
            if (row.getResult() == DeliveryResult.DELIVERED) {
                day.deliveriesDelivered += row.getDeliveryCount();
            } else if (row.getResult().isShortfall()) {
                deliveriesShort += row.getDeliveryCount();
            } else {
                // NOT_ATTEMPTED, and nothing else: DeliveryResult.isShortfall covers the other
                // three, and this branch is what keeps "the customer got less than they were owed"
                // separate from "the goods never came off the truck" - see DeliveryResult.
                deliveriesNotAttempted += row.getDeliveryCount();
            }
        }

        for (TripExceptionRepository.ExceptionDailyCount row
                : tripExceptionRepository.countDailyByStatusForRange(companyId, range.from(), range.to())) {
            DayAccumulator day = byDate.get(row.getPlanningDate());
            if (day == null) {
                continue;
            }
            day.exceptions += row.getExceptionCount();
            if (row.getStatus() == TripExceptionStatus.OPEN) {
                day.exceptionsOpen += row.getExceptionCount();
            }
        }

        return new DailySeries(range.dates().stream().map(date -> byDate.get(date).toRow(date)).toList(),
                tripsByStatus, deliveriesShort, deliveriesNotAttempted);
    }

    /**
     * What each day of the range holds while it is being assembled.
     *
     * <p>A mutable class and not a record being rebuilt three times: three queries each contribute
     * a different slice of the same day, and expressing that as {@code with*} copies would allocate
     * three objects per day to say "add two to a counter". It never leaves this file - the rows the
     * caller gets are immutable {@link KpiDailyRow}s.
     */
    private static final class DayAccumulator {
        private long trips;
        private long tripsCancelled;
        private long tripsCompleted;
        private long departuresMeasured;
        private long departuresLate;
        private long deliveriesRecorded;
        private long deliveriesDelivered;
        private long exceptions;
        private long exceptionsOpen;

        private KpiDailyRow toRow(LocalDate date) {
            return new KpiDailyRow(date, trips, tripsCancelled, tripsCompleted,
                    departuresMeasured, departuresLate,
                    KpiRate.percentComplement(departuresLate, departuresMeasured),
                    deliveriesRecorded, deliveriesDelivered,
                    KpiRate.percent(deliveriesDelivered, deliveriesRecorded),
                    exceptions, exceptionsOpen);
        }
    }

    /**
     * The range's totals, summed from the daily rows - never queried a second time, so a headline
     * card and the column it came from cannot disagree.
     */
    private record Totals(
            long trips, long tripsCancelled, long tripsCompleted,
            long departuresMeasured, long departuresLate,
            long deliveriesRecorded, long deliveriesDelivered,
            long exceptions, long exceptionsOpen) {

        private static Totals of(List<KpiDailyRow> daily) {
            long trips = 0;
            long cancelled = 0;
            long completed = 0;
            long measured = 0;
            long late = 0;
            long recorded = 0;
            long delivered = 0;
            long exceptions = 0;
            long open = 0;
            for (KpiDailyRow row : daily) {
                trips += row.trips();
                cancelled += row.tripsCancelled();
                completed += row.tripsCompleted();
                measured += row.departuresMeasured();
                late += row.departuresLate();
                recorded += row.deliveriesRecorded();
                delivered += row.deliveriesDelivered();
                exceptions += row.exceptions();
                open += row.exceptionsOpen();
            }
            return new Totals(trips, cancelled, completed, measured, late, recorded, delivered, exceptions, open);
        }

        /** Shipments that were not withdrawn - the denominator of everything about how the day went. */
        private long tripsRun() {
            return trips - tripsCancelled;
        }
    }

    // --- sections ------------------------------------------------------------------------

    /**
     * @param byStatus the lifecycle breakdown, pre-padded so every state is present with at least a
     *     zero. Padded on the server and not left to the client: a screen that had to know the
     *     enum's members in order to render a missing one is a screen that silently drops a state
     *     the day a new one is added
     */
    private static KpiShipmentsView shipments(Map<TripStatus, Long> byStatus, Totals totals) {
        return new KpiShipmentsView(totals.trips(), totals.tripsRun(), totals.tripsCancelled(),
                totals.tripsCompleted(), byStatus, totals.departuresMeasured(), totals.departuresLate(),
                KpiRate.percentComplement(totals.departuresLate(), totals.departuresMeasured()),
                KpiRate.percent(totals.tripsCompleted(), totals.tripsRun()));
    }

    /**
     * The stop half comes from one aggregate; the delivery half is summed from the daily series.
     *
     * <p>The company's zone is passed into the query rather than applied afterwards, because
     * whether a stop was inside its window is a comparison between a local wall-clock time and an
     * instant and can only be made where both are - see
     * {@code TripStopRepository.serviceTotalsForRange}.
     */
    private KpiServiceView service(CompanyScope scope, KpiRange range, Totals totals, DailySeries series) {
        TripStopRepository.StopServiceTotals stops = tripStopRepository.serviceTotalsForRange(
                scope.companyId(), range.from(), range.to(), scope.zoneId().getId());
        return new KpiServiceView(stops.getStops(), stops.getStopsCompleted(), stops.getStopsSkipped(),
                stops.getStopsFailed(), stops.getWindowsMeasured(), stops.getWindowsMissed(),
                KpiRate.percentComplement(stops.getWindowsMissed(), stops.getWindowsMeasured()),
                totals.deliveriesRecorded(), totals.deliveriesDelivered(),
                series.deliveriesShort(), series.deliveriesNotAttempted(),
                KpiRate.percent(totals.deliveriesDelivered(), totals.deliveriesRecorded()));
    }

    private static KpiExceptionsView exceptions(Totals totals) {
        return new KpiExceptionsView(totals.exceptions(), totals.exceptionsOpen(),
                totals.exceptions() - totals.exceptionsOpen(),
                KpiRate.per100(totals.exceptions(), totals.tripsRun()));
    }

    private KpiUtilizationView utilization(UUID companyId, KpiRange range) {
        TripRepository.TripUtilizationTotals row =
                tripRepository.utilizationForRange(companyId, range.from(), range.to());
        return new KpiUtilizationView(row.getTrips(),
                row.getWeightUsed(), row.getWeightCapacity(),
                KpiRate.percentOf(row.getWeightUsed(), row.getWeightCapacity()),
                row.getVolumeUsed(), row.getVolumeCapacity(),
                KpiRate.percentOf(row.getVolumeUsed(), row.getVolumeCapacity()),
                row.getPalletsUsed(), row.getPalletCapacity(),
                KpiRate.percentOf(row.getPalletsUsed(), row.getPalletCapacity()));
    }

    private KpiOrdersView orders(UUID companyId, KpiRange range) {
        OrderBacklogTotals backlog = orderPlanningPort.backlogTotals(companyId, range.from(), range.to());
        return new KpiOrdersView(backlog.inputOrders(), backlog.planned(), backlog.unplanned(),
                backlog.readyToPlan(), backlog.notReady(), backlog.cancelled(),
                KpiRate.percent(backlog.planned(), backlog.inputOrders()));
    }

    private KpiTenderView tenders(UUID companyId, KpiRange range) {
        Map<TenderStatus, Long> byStatus = new EnumMap<>(TenderStatus.class);
        tripTenderRepository.countByStatusForRange(companyId, range.from(), range.to())
                .forEach(row -> byStatus.put(row.getStatus(), row.getTenderCount()));

        long accepted = byStatus.getOrDefault(TenderStatus.ACCEPTED, 0L);
        long rejected = byStatus.getOrDefault(TenderStatus.REJECTED, 0L);
        long answered = accepted + rejected;
        long attempts = byStatus.values().stream().mapToLong(Long::longValue).sum();

        return new KpiTenderView(attempts, accepted, rejected,
                byStatus.getOrDefault(TenderStatus.EXPIRED, 0L),
                byStatus.getOrDefault(TenderStatus.CANCELLED, 0L),
                byStatus.getOrDefault(TenderStatus.SENT, 0L),
                byStatus.getOrDefault(TenderStatus.DRAFT, 0L),
                answered, KpiRate.percent(accepted, answered), KpiRate.percent(rejected, answered));
    }

    private List<KpiCostView> cost(UUID companyId, KpiRange range) {
        return tripCostAnalyticsPort.totalsByCurrency(companyId, range.from(), range.to()).stream()
                .map(KpiCostView::from)
                .toList();
    }

    // --- CSV -----------------------------------------------------------------------------

    /**
     * The export's columns, in write order, named after the JSON fields they carry rather than
     * translated.
     *
     * <p>A CSV is the machine-readable copy: it is opened in a spreadsheet, pasted into a finance
     * system, and diffed against last month's. Localised headers would make the same report a
     * different document depending on who pressed the button, and a saved pivot table would break
     * the first time somebody switched the UI to English. The screen is where language belongs.
     */
    private static final List<String> CSV_HEADER = List.of(
            "date", "trips", "tripsCancelled", "tripsCompleted",
            "departuresMeasured", "departuresLate", "onTimeDeparturePercent",
            "deliveriesRecorded", "deliveriesDelivered", "deliverySuccessPercent",
            "exceptions", "exceptionsOpen");

    private static List<String> csvRow(KpiDailyRow row) {
        List<String> values = new ArrayList<>(CSV_HEADER.size());
        values.add(row.date().toString());
        values.add(Long.toString(row.trips()));
        values.add(Long.toString(row.tripsCancelled()));
        values.add(Long.toString(row.tripsCompleted()));
        values.add(Long.toString(row.departuresMeasured()));
        values.add(Long.toString(row.departuresLate()));
        values.add(decimal(row.onTimeDeparturePercent()));
        values.add(Long.toString(row.deliveriesRecorded()));
        values.add(Long.toString(row.deliveriesDelivered()));
        values.add(decimal(row.deliverySuccessPercent()));
        values.add(Long.toString(row.exceptions()));
        values.add(Long.toString(row.exceptionsOpen()));
        return values;
    }

    /**
     * A percentage as a plain dot-decimal, or an empty cell when nothing was measured.
     *
     * <p>Empty and not {@code 0}: the whole point of {@code KpiRate} returning null is that a day
     * with no measured departure is not a day with no punctual ones, and a zero here would average
     * into somebody's quarterly figure. {@code toPlainString} rather than {@code toString} so a
     * value can never arrive in scientific notation, which a spreadsheet reads as text.
     */
    private static String decimal(BigDecimal value) {
        return value == null ? "" : value.toPlainString();
    }
}
