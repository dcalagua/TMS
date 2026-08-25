package com.ebim.tms.planning.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.ebim.tms.planning.domain.DeliveryResult;
import com.ebim.tms.planning.domain.TenderStatus;
import com.ebim.tms.planning.domain.TripExceptionStatus;
import com.ebim.tms.planning.domain.TripStatus;
import com.ebim.tms.planning.infrastructure.OrderDeliveryRepository;
import com.ebim.tms.planning.infrastructure.TripExceptionRepository;
import com.ebim.tms.planning.infrastructure.TripRepository;
import com.ebim.tms.planning.infrastructure.TripStopRepository;
import com.ebim.tms.planning.infrastructure.TripTenderRepository;
import com.ebim.tms.shared.reference.OrderBacklogTotals;
import com.ebim.tms.shared.reference.OrderPlanningPort;
import com.ebim.tms.shared.reference.TripCostAnalyticsPort;
import com.ebim.tms.shared.reference.TripCostTotals;
import com.ebim.tms.shared.security.CompanyScope;
import com.ebim.tms.shared.security.Permission;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * The KPI report, exercised over stubbed aggregates.
 *
 * <p>Two things are worth testing here and nothing else is. The first is <b>who is told what</b>:
 * three sections are answered with null for a caller who does not hold the permission that owns
 * them, and getting that wrong discloses a company's tariffs to a dispatcher. The second is
 * <b>that the totals are the columns</b>: the headline cards are summed from the same daily rows
 * the chart is drawn from, and if they ever stop being, a screen starts showing two answers to one
 * question - which is the failure mode this whole design is arranged to avoid.
 *
 * <p>The counts themselves are not tested here, because they are not this class's: they are SQL,
 * and a mock that returned them would only be asserting that the stub was read.
 */
class KpiServiceTest {

    private static final UUID COMPANY = UUID.randomUUID();
    private static final LocalDate FROM = LocalDate.of(2026, 3, 1);
    private static final LocalDate TO = LocalDate.of(2026, 3, 3);
    private static final KpiFilter FILTER = new KpiFilter(FROM, TO);

    private TripRepository tripRepository;
    private TripStopRepository tripStopRepository;
    private OrderDeliveryRepository orderDeliveryRepository;
    private TripExceptionRepository tripExceptionRepository;
    private TripTenderRepository tripTenderRepository;
    private OrderPlanningPort orderPlanningPort;
    private TripCostAnalyticsPort tripCostAnalyticsPort;
    private KpiService service;

    @BeforeEach
    void setUp() {
        tripRepository = mock(TripRepository.class);
        tripStopRepository = mock(TripStopRepository.class);
        orderDeliveryRepository = mock(OrderDeliveryRepository.class);
        tripExceptionRepository = mock(TripExceptionRepository.class);
        tripTenderRepository = mock(TripTenderRepository.class);
        orderPlanningPort = mock(OrderPlanningPort.class);
        tripCostAnalyticsPort = mock(TripCostAnalyticsPort.class);
        service = new KpiService(tripRepository, tripStopRepository, orderDeliveryRepository,
                tripExceptionRepository, tripTenderRepository, orderPlanningPort, tripCostAnalyticsPort);

        // The two days of shipments the whole file is about. 1 March: four trips, one of them
        // cancelled, three departures measured and one of them late. 3 March: two trips, both
        // completed, both departures measured and on time. 2 March: nothing at all.
        when(tripRepository.countDailyByStatusForRange(COMPANY, FROM, TO)).thenReturn(List.of(
                new TripDay(FROM, TripStatus.COMPLETED, 3, 3, 1),
                new TripDay(FROM, TripStatus.CANCELLED, 1, 0, 0),
                new TripDay(TO, TripStatus.COMPLETED, 2, 2, 0)));
        when(orderDeliveryRepository.countDailyByResultForRange(COMPANY, FROM, TO)).thenReturn(List.of(
                new DeliveryDay(FROM, DeliveryResult.DELIVERED, 8),
                new DeliveryDay(FROM, DeliveryResult.REJECTED, 1),
                new DeliveryDay(FROM, DeliveryResult.NOT_ATTEMPTED, 1),
                new DeliveryDay(TO, DeliveryResult.DELIVERED, 5)));
        when(tripExceptionRepository.countDailyByStatusForRange(COMPANY, FROM, TO)).thenReturn(List.of(
                new ExceptionDay(FROM, TripExceptionStatus.OPEN, 2),
                new ExceptionDay(FROM, TripExceptionStatus.RESOLVED, 1)));
        when(tripStopRepository.serviceTotalsForRange(any(), any(), any(), anyString()))
                .thenReturn(new Stops(20, 17, 1, 2, 15, 3));
        when(tripRepository.utilizationForRange(COMPANY, FROM, TO))
                .thenReturn(new Utilisation(5, bd("60000"), bd("48000"), bd("300"), bd("210"), bd("150"), bd("120")));
    }

    @Nested
    @DisplayName("the sections a caller may not see")
    class Disclosure {

        @Test
        @DisplayName("are null - not zero, and not a 403 over the whole screen")
        void areNullForATransportMonitor() {
            KpiReportView report = service.report(scope(Permission.MONITORING_TRANSPORT_READ), FILTER);

            assertThat(report.orders()).isNull();
            assertThat(report.tenders()).isNull();
            assertThat(report.cost()).isNull();
            // The shipment half of the screen is still there: this caller is entitled to it.
            assertThat(report.shipments().trips()).isEqualTo(6);
        }

        @Test
        @DisplayName("are not even asked for, so a denied section costs no query")
        void areNotQueried() {
            service.report(scope(Permission.MONITORING_TRANSPORT_READ), FILTER);

            verifyNoInteractions(orderPlanningPort);
            verifyNoInteractions(tripCostAnalyticsPort);
            verify(tripTenderRepository, never()).countByStatusForRange(any(), any(), any());
        }

        @Test
        @DisplayName("appear one at a time, each behind the permission that owns it")
        void eachHasItsOwnPermission() {
            stubOrders();
            KpiReportView report = service.report(
                    scope(Permission.MONITORING_TRANSPORT_READ, Permission.ORDERS_ORDER_READ), FILTER);

            assertThat(report.orders()).isNotNull();
            assertThat(report.tenders()).isNull();
            assertThat(report.cost()).isNull();
        }
    }

    @Nested
    @DisplayName("the daily series")
    class Series {

        @Test
        @DisplayName("has one row per day in the range, including the day nothing happened on")
        void coversEveryDay() {
            List<KpiDailyRow> daily = service.report(scope(Permission.MONITORING_TRANSPORT_READ), FILTER).daily();

            assertThat(daily).extracting(KpiDailyRow::date)
                    .containsExactly(FROM, LocalDate.of(2026, 3, 2), TO);
            assertThat(daily.get(1).trips()).isZero();
        }

        @Test
        @DisplayName("reports no punctuality at all on a day nothing was measured, rather than zero percent")
        void anEmptyDayHasNoPercentage() {
            List<KpiDailyRow> daily = service.report(scope(Permission.MONITORING_TRANSPORT_READ), FILTER).daily();

            assertThat(daily.get(1).onTimeDeparturePercent()).isNull();
            assertThat(daily.get(1).deliverySuccessPercent()).isNull();
        }

        @Test
        @DisplayName("splits a day's trips into what ran and what was withdrawn")
        void separatesCancellations() {
            KpiDailyRow first = service.report(scope(Permission.MONITORING_TRANSPORT_READ), FILTER).daily().get(0);

            assertThat(first.trips()).isEqualTo(4);
            assertThat(first.tripsCancelled()).isEqualTo(1);
            assertThat(first.tripsCompleted()).isEqualTo(3);
        }
    }

    @Nested
    @DisplayName("the headline sections")
    class Summary {

        @Test
        @DisplayName("are the sums of the daily rows, so a card and its column cannot disagree")
        void totalsMatchTheSeries() {
            KpiReportView report = service.report(scope(Permission.MONITORING_TRANSPORT_READ), FILTER);

            assertThat(report.shipments().trips())
                    .isEqualTo(report.daily().stream().mapToLong(KpiDailyRow::trips).sum());
            assertThat(report.service().deliveriesRecorded())
                    .isEqualTo(report.daily().stream().mapToLong(KpiDailyRow::deliveriesRecorded).sum());
            assertThat(report.exceptions().exceptions())
                    .isEqualTo(report.daily().stream().mapToLong(KpiDailyRow::exceptions).sum());
        }

        @Test
        @DisplayName("count punctuality over the departures that were measured and no others")
        void punctualityIsOverMeasuredDepartures() {
            KpiShipmentsView shipments =
                    service.report(scope(Permission.MONITORING_TRANSPORT_READ), FILTER).shipments();

            // Five measured, one late: four on time.
            assertThat(shipments.departuresMeasured()).isEqualTo(5);
            assertThat(shipments.departuresLate()).isEqualTo(1);
            assertThat(shipments.onTimeDeparturePercent()).isEqualByComparingTo("80.0");
        }

        @Test
        @DisplayName("take a cancelled shipment out of the denominator rather than counting it as a failure")
        void cancellationsAreNotFailures() {
            KpiShipmentsView shipments =
                    service.report(scope(Permission.MONITORING_TRANSPORT_READ), FILTER).shipments();

            assertThat(shipments.trips()).isEqualTo(6);
            assertThat(shipments.tripsCancelled()).isEqualTo(1);
            assertThat(shipments.tripsRun()).isEqualTo(5);
            assertThat(shipments.completionPercent()).isEqualByComparingTo("100.0");
        }

        @Test
        @DisplayName("pad the lifecycle breakdown so every state is present, even the empty ones")
        void everyStateIsPresent() {
            KpiShipmentsView shipments =
                    service.report(scope(Permission.MONITORING_TRANSPORT_READ), FILTER).shipments();

            assertThat(shipments.byStatus()).containsOnlyKeys(TripStatus.values());
            assertThat(shipments.byStatus().get(TripStatus.DRAFT)).isZero();
            assertThat(shipments.byStatus().get(TripStatus.COMPLETED)).isEqualTo(5);
        }

        @Test
        @DisplayName("keep a shortfall and a delivery that was never attempted apart")
        void shortfallIsNotTheSameAsNotAttempted() {
            KpiServiceView view = service.report(scope(Permission.MONITORING_TRANSPORT_READ), FILTER).service();

            assertThat(view.deliveriesRecorded()).isEqualTo(15);
            assertThat(view.deliveriesDelivered()).isEqualTo(13);
            assertThat(view.deliveriesShort()).isEqualTo(1);
            assertThat(view.deliveriesNotAttempted()).isEqualTo(1);
        }

        @Test
        @DisplayName("report problems per hundred shipments that ran, not as a percentage of anything")
        void exceptionsArePerHundredTrips() {
            KpiExceptionsView view = service.report(scope(Permission.MONITORING_TRANSPORT_READ), FILTER).exceptions();

            assertThat(view.exceptions()).isEqualTo(3);
            assertThat(view.open()).isEqualTo(2);
            assertThat(view.resolved()).isEqualTo(1);
            // Three problems over five shipments that ran.
            assertThat(view.per100Trips()).isEqualByComparingTo("60.0");
        }

        @Test
        @DisplayName("divide the summed load by the summed capacity, never averaging percentages")
        void utilisationIsSummedThenDivided() {
            KpiUtilizationView view =
                    service.report(scope(Permission.MONITORING_TRANSPORT_READ), FILTER).utilization();

            assertThat(view.trips()).isEqualTo(5);
            assertThat(view.weightPercent()).isEqualByComparingTo("80.0");
            assertThat(view.volumePercent()).isEqualByComparingTo("70.0");
            assertThat(view.palletsPercent()).isEqualByComparingTo("80.0");
        }
    }

    @Nested
    @DisplayName("the gated sections, once the caller holds them")
    class GatedSections {

        @Test
        @DisplayName("report the planning invariant: everything owed is either planned or not")
        void ordersSplitIntoPlannedAndUnplanned() {
            stubOrders();

            KpiOrdersView orders = service.report(
                    scope(Permission.MONITORING_TRANSPORT_READ, Permission.ORDERS_ORDER_READ), FILTER).orders();

            assertThat(orders.inputOrders()).isEqualTo(orders.planned() + orders.unplanned());
            assertThat(orders.unplanned()).isEqualTo(orders.readyToPlan() + orders.notReady());
            // The cancelled ones sit outside all of it.
            assertThat(orders.cancelled()).isEqualTo(4);
            assertThat(orders.plannedPercent()).isEqualByComparingTo("60.0");
        }

        @Test
        @DisplayName("rate carrier acceptance over the offers that were answered, not over every offer made")
        void acceptanceIsOverAnsweredOffers() {
            when(tripTenderRepository.countByStatusForRange(COMPANY, FROM, TO)).thenReturn(List.of(
                    new Tenders(TenderStatus.ACCEPTED, 6),
                    new Tenders(TenderStatus.REJECTED, 2),
                    new Tenders(TenderStatus.EXPIRED, 1),
                    new Tenders(TenderStatus.SENT, 3)));

            KpiTenderView tenders = service.report(
                    scope(Permission.MONITORING_TRANSPORT_READ, Permission.PLANNING_TENDER_READ), FILTER).tenders();

            assertThat(tenders.attempts()).isEqualTo(12);
            assertThat(tenders.answered()).isEqualTo(8);
            assertThat(tenders.acceptancePercent()).isEqualByComparingTo("75.0");
            assertThat(tenders.rejectionPercent()).isEqualByComparingTo("25.0");
        }

        @Test
        @DisplayName("keep two currencies apart rather than adding money that cannot be added")
        void costIsPerCurrency() {
            when(tripCostAnalyticsPort.totalsByCurrency(COMPANY, FROM, TO)).thenReturn(List.of(
                    new TripCostTotals("PEN", 10, bd("30000"), 4, bd("13000"), 4, bd("12000"), bd("13000")),
                    new TripCostTotals("USD", 2, bd("5000"), 0, bd("0"), 0, bd("0"), bd("0"))));

            List<KpiCostView> cost = service.report(
                    scope(Permission.MONITORING_TRANSPORT_READ, Permission.RATES_TRIP_COST_READ), FILTER).cost();

            assertThat(cost).extracting(KpiCostView::currency).containsExactly("PEN", "USD");
            assertThat(cost.get(0).variance()).isEqualByComparingTo("1000");
            // Over the four shipments that have both figures, never over the ten that were estimated.
            assertThat(cost.get(0).variancePercent()).isEqualByComparingTo("8.3");
            assertThat(cost.get(1).variance()).isNull();
            assertThat(cost.get(1).variancePercent()).isNull();
        }
    }

    @Nested
    @DisplayName("the CSV export")
    class Export {

        @Test
        @DisplayName("names the file after the range it covers")
        void namesTheFileAfterTheRange() {
            KpiExportFile file = service.exportDaily(scope(Permission.MONITORING_TRANSPORT_READ), FILTER);

            assertThat(file.fileName()).isEqualTo("tms-kpis-2026-03-01-to-2026-03-03.csv");
        }

        @Test
        @DisplayName("writes a header and one row per day, with an empty cell where nothing was measured")
        void writesARowPerDay() {
            KpiExportFile file = service.exportDaily(scope(Permission.MONITORING_TRANSPORT_READ), FILTER);
            String[] lines = new String(file.content(), StandardCharsets.UTF_8).split("\r\n");

            assertThat(lines).hasSize(4);
            assertThat(lines[0]).endsWith("date,trips,tripsCancelled,tripsCompleted,departuresMeasured,"
                    + "departuresLate,onTimeDeparturePercent,deliveriesRecorded,deliveriesDelivered,"
                    + "deliverySuccessPercent,exceptions,exceptionsOpen");
            assertThat(lines[1]).isEqualTo("2026-03-01,4,1,3,3,1,66.7,10,8,80.0,3,2");
            assertThat(lines[2]).isEqualTo("2026-03-02,0,0,0,0,0,,0,0,,0,0");
        }
    }

    // --- fixtures ------------------------------------------------------------------------

    private void stubOrders() {
        when(orderPlanningPort.backlogTotals(COMPANY, FROM, TO))
                .thenReturn(new OrderBacklogTotals(30, 15, 5, 4));
    }

    private static CompanyScope scope(Permission... permissions) {
        return new CompanyScope(COMPANY, "C1", "Company One", "America/Lima",
                UUID.randomUUID(), "ORG", "Organization", Set.of(permissions));
    }

    private static BigDecimal bd(String value) {
        return new BigDecimal(value);
    }

    /**
     * The projections are interfaces with {@code getX()} accessors, so the stubs are records that
     * implement them explicitly rather than Mockito mocks: five stubbed getters per row would bury
     * the arrangement this file is about under the mechanics of stubbing it.
     */
    private record TripDay(LocalDate date, TripStatus status, long trips, long measured, long late)
            implements TripRepository.TripDailyStatusCount {

        @Override
        public LocalDate getPlanningDate() {
            return date;
        }

        @Override
        public TripStatus getStatus() {
            return status;
        }

        @Override
        public long getTripCount() {
            return trips;
        }

        @Override
        public long getDeparturesMeasured() {
            return measured;
        }

        @Override
        public long getDeparturesLate() {
            return late;
        }
    }

    private record DeliveryDay(LocalDate date, DeliveryResult result, long count)
            implements OrderDeliveryRepository.DeliveryDailyCount {

        @Override
        public LocalDate getPlanningDate() {
            return date;
        }

        @Override
        public DeliveryResult getResult() {
            return result;
        }

        @Override
        public long getDeliveryCount() {
            return count;
        }
    }

    private record ExceptionDay(LocalDate date, TripExceptionStatus status, long count)
            implements TripExceptionRepository.ExceptionDailyCount {

        @Override
        public LocalDate getPlanningDate() {
            return date;
        }

        @Override
        public TripExceptionStatus getStatus() {
            return status;
        }

        @Override
        public long getExceptionCount() {
            return count;
        }
    }

    private record Tenders(TenderStatus status, long count) implements TripTenderRepository.TenderStatusCount {

        @Override
        public TenderStatus getStatus() {
            return status;
        }

        @Override
        public long getTenderCount() {
            return count;
        }
    }

    private record Stops(long stops, long completed, long skipped, long failed, long measured, long missed)
            implements TripStopRepository.StopServiceTotals {

        @Override
        public long getStops() {
            return stops;
        }

        @Override
        public long getStopsCompleted() {
            return completed;
        }

        @Override
        public long getStopsSkipped() {
            return skipped;
        }

        @Override
        public long getStopsFailed() {
            return failed;
        }

        @Override
        public long getWindowsMeasured() {
            return measured;
        }

        @Override
        public long getWindowsMissed() {
            return missed;
        }
    }

    private record Utilisation(long trips, BigDecimal weightCapacity, BigDecimal weightUsed,
            BigDecimal volumeCapacity, BigDecimal volumeUsed, BigDecimal palletCapacity, BigDecimal palletsUsed)
            implements TripRepository.TripUtilizationTotals {

        @Override
        public long getTrips() {
            return trips;
        }

        @Override
        public BigDecimal getWeightCapacity() {
            return weightCapacity;
        }

        @Override
        public BigDecimal getWeightUsed() {
            return weightUsed;
        }

        @Override
        public BigDecimal getVolumeCapacity() {
            return volumeCapacity;
        }

        @Override
        public BigDecimal getVolumeUsed() {
            return volumeUsed;
        }

        @Override
        public BigDecimal getPalletCapacity() {
            return palletCapacity;
        }

        @Override
        public BigDecimal getPalletsUsed() {
            return palletsUsed;
        }
    }
}
