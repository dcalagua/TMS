package com.ebim.tms.planning.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.ebim.tms.planning.application.CarrierScorecardView.CarrierRow;
import com.ebim.tms.planning.application.CarrierScorecardView.CostFigures;
import com.ebim.tms.planning.infrastructure.CarrierScorecardQueries;
import com.ebim.tms.planning.infrastructure.CarrierScorecardQueries.DeliveryTotals;
import com.ebim.tms.planning.infrastructure.CarrierScorecardQueries.DocumentTotals;
import com.ebim.tms.planning.infrastructure.CarrierScorecardQueries.ExceptionTotals;
import com.ebim.tms.planning.infrastructure.CarrierScorecardQueries.StopTotals;
import com.ebim.tms.planning.infrastructure.CarrierScorecardQueries.TenderTotals;
import com.ebim.tms.planning.infrastructure.CarrierScorecardQueries.TripTotals;
import com.ebim.tms.planning.infrastructure.CarrierScorecardQueries.TripWeight;
import com.ebim.tms.shared.api.InvalidRequestException;
import com.ebim.tms.shared.reference.CarrierLookupPort;
import com.ebim.tms.shared.reference.MasterReference;
import com.ebim.tms.shared.reference.SettlementKpiPort;
import com.ebim.tms.shared.reference.SettlementKpiPort.CarrierSettlementTotals;
import com.ebim.tms.shared.reference.SettlementKpiPort.SettlementKpiTotals;
import com.ebim.tms.shared.reference.TripCostAnalyticsPort;
import com.ebim.tms.shared.reference.TripCostFigure;
import com.ebim.tms.shared.security.CompanyScope;
import com.ebim.tms.shared.security.Permission;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The carrier scorecard over stubbed aggregates: an empty denominator is null and never 0%, the four
 * finer blocks are absent - not zero - without their permission, money stays per currency, and the
 * CSV says what it holds.
 */
class CarrierScorecardServiceTest {

    private static final UUID COMPANY = UUID.randomUUID();
    private static final UUID CARRIER_A = UUID.nameUUIDFromBytes("scorecard-a".getBytes());
    private static final UUID CARRIER_B = UUID.nameUUIDFromBytes("scorecard-b".getBytes());
    private static final String A = CARRIER_A.toString();
    private static final String B = CARRIER_B.toString();
    private static final String OWN = CarrierScorecardQueries.OWN_FLEET;
    private static final KpiFilter FILTER = new KpiFilter(LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 20));

    private CarrierScorecardQueries queries;
    private CarrierLookupPort carriers;
    private SettlementKpiPort settlement;
    private TripCostAnalyticsPort cost;
    private CarrierScorecardService service;

    @BeforeEach
    void setUp() {
        queries = mock(CarrierScorecardQueries.class);
        carriers = mock(CarrierLookupPort.class);
        settlement = mock(SettlementKpiPort.class);
        cost = mock(TripCostAnalyticsPort.class);
        service = new CarrierScorecardService(queries, carriers, settlement, cost,
                Clock.fixed(Instant.parse("2026-10-21T12:00:00Z"), ZoneOffset.UTC));

        when(queries.tripTotals(any(), any(), any())).thenReturn(List.of(
                new TripTotals(A, 10, 1, 9, 8, 8, 2),
                new TripTotals(OWN, 3, 0, 3, 3, 0, 0),
                new TripTotals(null, 4, 0, 0, 0, 0, 0)));
        when(queries.stopTotals(any(), any(), any(), anyString())).thenReturn(List.of(new StopTotals(A, 20, 10, 1)));
        when(queries.deliveryTotals(any(), any(), any())).thenReturn(List.of(new DeliveryTotals(A, 12, 9, 2, 1)));
        when(queries.exceptionTotals(any(), any(), any())).thenReturn(List.of(new ExceptionTotals(A, 3, 1)));
        when(queries.documentTotals(any(), any(), any())).thenReturn(List.of(new DocumentTotals(A, 8, 6, 3)));
        // B was offered loads and ran none: precisely the carrier a scorecard must show.
        when(queries.tenderTotals(any(), any(), any(), any())).thenReturn(List.of(
                new TenderTotals(A, 5, 3, 1, 1, 0, 4, new BigDecimal("42.5")),
                new TenderTotals(B, 2, 0, 0, 2, 0, 0, null)));
        when(carriers.findAllInCompany(any(), any())).thenReturn(Map.of(
                CARRIER_A, MasterReference.of(CARRIER_A, "TRLIT", "Transportes Litoral"),
                CARRIER_B, MasterReference.of(CARRIER_B, "TRAND", "Transandina")));
    }

    @Test
    @DisplayName("percentages divide by what was measured, and are null - not 0% - when nothing was")
    void nullWithoutDenominator() {
        CarrierScorecardView view = service.report(scope(Permission.PLANNING_TENDER_READ), FILTER);

        CarrierRow a = row(view, A);
        assertThat(a.carrierName()).isEqualTo("Transportes Litoral");
        assertThat(a.onTimeDeparturePercent()).isEqualByComparingTo("75.0");
        assertThat(a.inWindowPercent()).isEqualByComparingTo("90.0");
        assertThat(a.deliverySuccessPercent()).isEqualByComparingTo("75.0");
        assertThat(a.exceptionsPer100Trips()).isEqualByComparingTo("30.0");

        CarrierRow own = row(view, OWN);
        assertThat(own.ownFleet()).isTrue();
        assertThat(own.carrierId()).isNull();
        assertThat(own.trips()).isEqualTo(3);
        assertThat(own.onTimeDeparturePercent()).isNull();
        assertThat(own.inWindowPercent()).isNull();
        assertThat(own.deliverySuccessPercent()).isNull();
        assertThat(own.exceptionsPer100Trips()).isEqualByComparingTo("0.0");

        CarrierRow b = row(view, B);
        assertThat(b.trips()).isZero();
        assertThat(b.exceptionsPer100Trips()).isNull();

        // Unattributed trips are counted, and are nobody's row.
        assertThat(view.unattributedTrips()).isEqualTo(4);
        assertThat(view.carriers()).extracting(CarrierRow::carrierKey).containsExactly(A, OWN, B);
    }

    @Test
    @DisplayName("an expired offer counts against the carrier; the response time is over timed answers only")
    void tenders() {
        CarrierScorecardView view = service.report(scope(Permission.PLANNING_TENDER_READ), FILTER);

        assertThat(view.tendersVisible()).isTrue();
        var a = row(view, A).tenders();
        assertThat(a.acceptancePercent()).isEqualByComparingTo("60.0"); // 3 of 3 + 1 + 1
        assertThat(a.averageResponseMinutes()).isEqualByComparingTo("42.5");
        var b = row(view, B).tenders();
        assertThat(b.acceptancePercent()).isEqualByComparingTo("0.0");
        assertThat(b.averageResponseMinutes()).isNull();
        // A carrier with no offer has zero offers and no percentage, rather than no block.
        var own = row(view, OWN).tenders();
        assertThat(own.offered()).isZero();
        assertThat(own.acceptancePercent()).isNull();
    }

    @Test
    @DisplayName("without their permission the four finer blocks are null and their sources are not even asked")
    void finerBlocksAbsent() {
        CarrierScorecardView view = service.report(scope(), FILTER);

        assertThat(view.tendersVisible()).isFalse();
        assertThat(view.documentsVisible()).isFalse();
        assertThat(view.invoicesVisible()).isFalse();
        assertThat(view.costVisible()).isFalse();
        assertThat(view.carriers()).allSatisfy(row -> {
            assertThat(row.tenders()).isNull();
            assertThat(row.documents()).isNull();
            assertThat(row.invoices()).isNull();
            assertThat(row.cost()).isNull();
        });
        verifyNoInteractions(settlement, cost);
        // B is known only through the offers it was made, which this caller may not see: no row.
        assertThat(view.carriers()).extracting(CarrierRow::carrierKey).containsExactly(A, OWN);
    }

    @Test
    @DisplayName("documents: delivered over carried, cargo returned over delivered")
    void documents() {
        CarrierScorecardView view = service.report(scope(Permission.PLANNING_DOCUMENT_DELIVERY_READ), FILTER);

        assertThat(row(view, A).documents().deliveredPercent()).isEqualByComparingTo("75.0");
        assertThat(row(view, A).documents().cargoReturnPercent()).isEqualByComparingTo("50.0");
        assertThat(row(view, OWN).documents().cargoReturnPercent()).isNull();
    }

    @Test
    @DisplayName("invoices come per carrier and currency from the settlement port, never summed across currencies")
    void invoices() {
        when(settlement.totals(any(), any(), any())).thenReturn(new SettlementKpiTotals(5, 4, 2, null, null, List.of(
                new CarrierSettlementTotals(CARRIER_A, "Transportes Litoral", "USD", 1, 1, 0, BigDecimal.ZERO,
                        BigDecimal.ZERO, null, null),
                new CarrierSettlementTotals(CARRIER_A, "Transportes Litoral", "PEN", 4, 3, 2,
                        new BigDecimal("120.00"), BigDecimal.ZERO, null, null))));

        CarrierScorecardView view = service.report(scope(Permission.SETTLEMENT_INVOICE_READ), FILTER);

        var invoices = row(view, A).invoices();
        assertThat(invoices).extracting(CarrierScorecardView.InvoiceFigures::currency).containsExactly("PEN", "USD");
        assertThat(invoices.get(0).discrepancyRatePercent()).isEqualByComparingTo("50.0");
        assertThat(invoices.get(0).amountObserved()).isEqualByComparingTo("120.00");
        assertThat(row(view, OWN).invoices()).isEmpty();
    }

    @Test
    @DisplayName("cost per trip over costed trips, cost per kg over the weighed ones only, per currency")
    void costByCarrier() {
        UUID t1 = UUID.randomUUID();
        UUID t2 = UUID.randomUUID();
        UUID t3 = UUID.randomUUID();
        UUID cancelled = UUID.randomUUID();
        Map<UUID, TripWeight> weights = Map.of(
                t1, new TripWeight(t1, A, new BigDecimal("1000")),
                t2, new TripWeight(t2, A, BigDecimal.ZERO),
                t3, new TripWeight(t3, A, new BigDecimal("500")));
        Map<String, List<CostFigures>> result = CarrierScorecardService.costByCarrier(weights, List.of(
                new TripCostFigure(t1, "PEN", new BigDecimal("900.00"), new BigDecimal("1000.00")),
                new TripCostFigure(t2, "PEN", new BigDecimal("300.00"), null),
                new TripCostFigure(t3, "USD", new BigDecimal("150.00"), null),
                // Not one of the attributed trips that ran: left out.
                new TripCostFigure(cancelled, "PEN", new BigDecimal("999.00"), null)));

        List<CostFigures> a = result.get(A);
        assertThat(a).extracting(CostFigures::currency).containsExactly("PEN", "USD");
        CostFigures pen = a.get(0);
        assertThat(pen.tripsCosted()).isEqualTo(2);
        assertThat(pen.tripsWithActual()).isEqualTo(1);
        assertThat(pen.amount()).isEqualByComparingTo("1300.00");
        assertThat(pen.costPerTrip()).isEqualByComparingTo("650.00");
        // 1000 over 1000 kg: the trip that carried no weight is not in the per-kg figure at all.
        assertThat(pen.costPerKg()).isEqualByComparingTo("1.0000");
        assertThat(a.get(1).costPerKg()).isEqualByComparingTo("0.3000");

        Map<String, List<CostFigures>> weightless = CarrierScorecardService.costByCarrier(
                Map.of(t2, new TripWeight(t2, A, BigDecimal.ZERO)),
                List.of(new TripCostFigure(t2, "PEN", new BigDecimal("300.00"), null)));
        assertThat(weightless.get(A).get(0).costPerKg()).isNull();
    }

    @Test
    @DisplayName("the CSV leaves out the columns the caller may not see and keeps currencies on their own rows")
    void csv() {
        when(settlement.totals(any(), any(), any())).thenReturn(new SettlementKpiTotals(1, 1, 0, null, null, List.of(
                new CarrierSettlementTotals(CARRIER_A, "Transportes Litoral", "PEN", 2, 2, 1,
                        new BigDecimal("10.00"), BigDecimal.ZERO, null, null))));

        String withoutInvoices = new String(service.export(scope(), FILTER).content(), StandardCharsets.UTF_8);
        assertThat(withoutInvoices).doesNotContain("invoices").doesNotContain("tendersOffered");

        KpiExportFile file = service.export(scope(Permission.SETTLEMENT_INVOICE_READ), FILTER);
        assertThat(file.fileName()).isEqualTo("tms-carrier-scorecard-2026-10-01-to-2026-10-20.csv");
        String csv = new String(file.content(), StandardCharsets.UTF_8);
        assertThat(csv).contains("invoiceDiscrepancyPercent");
        String[] lines = csv.split("\r?\n");
        // Header, A's operation, A's PEN money, the own fleet. B is a tender-only carrier, not visible here.
        assertThat(lines).hasSize(4);
        assertThat(lines[1]).startsWith(A + ",TRLIT,Transportes Litoral,false,,10,");
        assertThat(lines[2]).startsWith(A + ",TRLIT,Transportes Litoral,false,PEN,,").contains(",2,1,50.0,10.00");
    }

    @Test
    @DisplayName("the drill-down takes a carrier id or OWN_FLEET, and refuses anything else by name")
    void carrierKey() {
        assertThat(CarrierScorecardService.carrierKey(" own_fleet ")).isEqualTo(OWN);
        assertThat(CarrierScorecardService.carrierKey(A.toUpperCase())).isEqualTo(A);
        assertThatThrownBy(() -> CarrierScorecardService.carrierKey("TRLIT"))
                .isInstanceOf(InvalidRequestException.class).hasMessageContaining("TRLIT");
        assertThatThrownBy(() -> CarrierScorecardService.carrierKey(" "))
                .isInstanceOf(InvalidRequestException.class);
    }

    private static CarrierRow row(CarrierScorecardView view, String key) {
        return view.carriers().stream().filter(row -> row.carrierKey().equals(key)).findFirst().orElseThrow();
    }

    private static CompanyScope scope(Permission... permissions) {
        Set<Permission> held = new java.util.HashSet<>(Set.of(permissions));
        held.add(Permission.MONITORING_TRANSPORT_READ);
        return new CompanyScope(COMPANY, "C1", "Company One", "America/Lima",
                UUID.randomUUID(), "ORG", "Organization", held);
    }
}
