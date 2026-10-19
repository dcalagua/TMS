package com.ebim.tms.planning.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.ebim.tms.planning.application.DispatchReconciler.Code;
import com.ebim.tms.planning.application.DispatchReconciler.Discrepancy;
import com.ebim.tms.planning.application.DispatchReconciler.Plan;
import com.ebim.tms.planning.application.DispatchReconciler.PlannedGeometry;
import com.ebim.tms.planning.application.DispatchReconciler.PlannedOrder;
import com.ebim.tms.planning.application.DispatchReconciler.Result;
import com.ebim.tms.planning.application.DispatchReconciler.Severity;
import com.ebim.tms.planning.domain.DispatchVerificationStatus;
import com.ebim.tms.planning.domain.ExternalDispatchOrderMatch;
import com.ebim.tms.planning.domain.TripOrderAssignment;
import com.ebim.tms.planning.domain.TripStatus;
import com.ebim.tms.shared.reference.DispatchConfirmationCommand;
import com.ebim.tms.shared.reference.OrderAmounts;
import com.ebim.tms.shared.reference.OrderExternalKey;
import com.ebim.tms.shared.reference.OrderLineSnapshot;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

/**
 * The warehouse's footprint against the planned one (ADR-022 section 9, contract v1.1 section 7.3):
 * an informative {@code GEOMETRY_VARIANCE}, never a {@code MISMATCH} and never a changed match.
 */
class DispatchGeometryReconcilerTest {

    private static final OffsetDateTime AT = OffsetDateTime.parse("2026-10-18T08:12:00-05:00");
    private static final UUID ORDER_A = UUID.randomUUID();

    /** PLP-571 as TMS plans it: 10 stacks, 0.1503 m3 effective, 0.05 loading metres. */
    private static final PlannedGeometry PLP_571 = new PlannedGeometry(10, bd("0.1503"), bd("0.050"));

    private static PlannedOrder planned(PlannedGeometry geometry) {
        return new PlannedOrder(ORDER_A, "SAPB1_PE", "PED-1011", true,
                new OrderAmounts(bd("420"), bd("0.1503"), bd("2")),
                List.of(new OrderLineSnapshot(1, "PLP-571", bd("100"), "UN", 10, bd("0.1503"))), geometry);
    }

    private static Plan plan(PlannedOrder order) {
        return new Plan(TripStatus.READY_FOR_DISPATCH, null, null, Set.of("TRSA"), "B7K-812", null, null,
                Set.of("CD01"), null, List.of(order));
    }

    private static DispatchConfirmationCommand document(Integer stacks, String effectiveVolume, String loadingMeters,
            String reason) {
        DispatchConfirmationCommand.Order order = new DispatchConfirmationCommand.Order("SAPB1_PE", "PED-1011",
                "ORR-1", "SHIPPED", 2, bd("420"), null,
                List.of(new DispatchConfirmationCommand.Line(1, "PLP-571", "L1", bd("100"), "UN", "HU-1")),
                stacks, bd(effectiveVolume), bd(loadingMeters), reason);
        return new DispatchConfirmationCommand(UUID.randomUUID(), null, null, "EWM_EBIM", "SLS-000001", 1,
                "SH-00000845", "CRG-000001", "CD01", AT, "TRSA", "Transportes SA", "B7K-812", "FURGON", null, null,
                null, "PRE-1", "T001-1", 2, bd("420"), null, List.of(order), "{}", "0".repeat(64));
    }

    private static Result reconcile(DispatchConfirmationCommand document, PlannedOrder planned) {
        return DispatchReconciler.reconcile(document, plan(planned),
                Map.of(new OrderExternalKey("SAPB1_PE", "PED-1011"), ORDER_A));
    }

    private static List<Discrepancy> geometry(Result result) {
        return result.discrepancies().stream().filter(d -> d.code() == Code.GEOMETRY_VARIANCE).toList();
    }

    private static BigDecimal bd(String value) {
        return value == null ? null : new BigDecimal(value);
    }

    @Test
    @DisplayName("the same footprint on both sides says nothing")
    void agreeing() {
        Result result = reconcile(document(10, "0.1503", "0.050", null), planned(PLP_571));

        assertThat(result.discrepancies()).isEmpty();
        assertThat(result.verification()).isEqualTo(DispatchVerificationStatus.MATCHED);
    }

    @Test
    @DisplayName("different stacks, volume and loading metres are three INFO lines and the dispatch stays MATCHED")
    void informativeVariance() {
        // The EWM stacks without a vehicle height: fewer, taller stacks.
        Result result = reconcile(document(7, "0.1800", "0.070", null), planned(PLP_571));

        assertThat(geometry(result)).hasSize(3).allSatisfy(d -> {
            assertThat(d.severity()).isEqualTo(Severity.INFO);
            assertThat(d.orderReference()).isEqualTo("PED-1011");
        });
        assertThat(geometry(result)).extracting(Discrepancy::uom).containsExactly("STACKS", "M3", "LDM");
        assertThat(geometry(result).get(0).planned()).isEqualByComparingTo("10");
        assertThat(geometry(result).get(0).dispatched()).isEqualByComparingTo("7");
        assertThat(result.verification()).isEqualTo(DispatchVerificationStatus.MATCHED);
        assertThat(result.orders()).singleElement()
                .satisfies(match -> assertThat(match.result()).isEqualTo(ExternalDispatchOrderMatch.MATCHED));
    }

    @Test
    @DisplayName("rounding inside 5% is not a difference; stacks are compared exactly")
    void tolerance() {
        assertThat(geometry(reconcile(document(10, "0.1550", "0.052", null), planned(PLP_571)))).isEmpty();
        assertThat(geometry(reconcile(document(11, "0.1503", "0.050", null), planned(PLP_571))))
                .extracting(Discrepancy::uom).containsExactly("STACKS");
    }

    @Test
    @DisplayName("a v1 document, a reason without figures, or an order TMS never measured compares nothing")
    void nothingToCompare() {
        assertThat(geometry(reconcile(document(null, null, null, null), planned(PLP_571)))).isEmpty();
        assertThat(geometry(reconcile(document(null, null, null, "NEST_INCREMENT_MISSING"), planned(PLP_571))))
                .isEmpty();
        assertThat(geometry(reconcile(document(7, "0.1800", null, null), planned(PlannedGeometry.UNKNOWN))))
                .isEmpty();
        assertThat(geometry(reconcile(document(7, "0.1800", null, null),
                new PlannedOrder(ORDER_A, "SAPB1_PE", "PED-1011", true, new OrderAmounts(bd("420"), bd("1"), bd("2")),
                        List.of(new OrderLineSnapshot(1, "PLP-571", bd("100"), "UN")))))).isEmpty();
    }

    @Test
    @DisplayName("a geometry variance beside a real quantity variance does not soften the MISMATCH")
    void besideAnError() {
        DispatchConfirmationCommand.Order short97 = new DispatchConfirmationCommand.Order("SAPB1_PE", "PED-1011",
                "ORR-1", "SHIPPED", 2, bd("420"), null,
                List.of(new DispatchConfirmationCommand.Line(1, "PLP-571", "L1", bd("97"), "UN", "HU-1")),
                7, null, null, null);
        DispatchConfirmationCommand base = document(null, null, null, null);
        DispatchConfirmationCommand document = new DispatchConfirmationCommand(base.integrationClientId(), null, null,
                base.sourceSystem(), base.dispatchReference(), base.revision(), base.transportReference(),
                base.loadReference(), base.warehouseCode(), base.actualDispatchAt(), base.carrierCode(),
                base.carrierName(), base.vehicleLicensePlate(), base.vehicleType(), null, null, null,
                base.sealNumber(), base.transportDocumentNumber(), 2, bd("420"), null, List.of(short97), "{}",
                base.payloadHash());

        Result result = reconcile(document, planned(PLP_571));

        assertThat(result.discrepancies()).extracting(Discrepancy::code)
                .contains(Code.QUANTITY_VARIANCE, Code.GEOMETRY_VARIANCE);
        assertThat(result.verification()).isEqualTo(DispatchVerificationStatus.MISMATCH);
    }

    @Test
    @DisplayName("the planned footprint: whole and fully measured sums the lines; a share keeps only loading metres")
    void plannedGeometry() {
        List<OrderLineSnapshot> measured = List.of(
                new OrderLineSnapshot(1, "PLP-571", bd("100"), "UN", 10, bd("0.1503")),
                new OrderLineSnapshot(2, "PLP-572", bd("20"), "UN", 2, bd("0.0400")));
        TripOrderAssignment whole = assignment(true, "0.1103");
        PlannedGeometry geometry = ExternalDispatchService.plannedGeometry(whole, measured, bd("2.40"), bd("0.85"));
        assertThat(geometry.stacks()).isEqualTo(12);
        assertThat(geometry.effectiveVolumeM3()).isEqualByComparingTo("0.1903");
        assertThat(geometry.loadingMeters()).isEqualByComparingTo("0.054");

        PlannedGeometry share = ExternalDispatchService.plannedGeometry(assignment(false, "0.0551"), measured,
                bd("2.40"), bd("0.85"));
        assertThat(share.stacks()).isNull();
        assertThat(share.effectiveVolumeM3()).isNull();
        assertThat(share.loadingMeters()).isEqualByComparingTo("0.027");

        List<OrderLineSnapshot> partlyMeasured = List.of(measured.get(0),
                new OrderLineSnapshot(2, "SKU-X", bd("5"), "UN"));
        PlannedGeometry partial = ExternalDispatchService.plannedGeometry(whole, partlyMeasured, null, bd("0.85"));
        assertThat(partial).isEqualTo(PlannedGeometry.UNKNOWN);
    }

    private static TripOrderAssignment assignment(boolean wholeOrder, String floorAreaM2) {
        TripOrderAssignment assignment = Mockito.mock(TripOrderAssignment.class);
        Mockito.when(assignment.wholeOrder()).thenReturn(wholeOrder);
        Mockito.when(assignment.assigned())
                .thenReturn(new OrderAmounts(bd("420"), bd("0.15"), bd("2"), bd(floorAreaM2), bd("1.363")));
        return assignment;
    }
}
