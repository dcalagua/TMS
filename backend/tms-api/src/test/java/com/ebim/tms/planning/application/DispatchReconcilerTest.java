package com.ebim.tms.planning.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.ebim.tms.planning.application.DispatchReconciler.Code;
import com.ebim.tms.planning.application.DispatchReconciler.Discrepancy;
import com.ebim.tms.planning.application.DispatchReconciler.Plan;
import com.ebim.tms.planning.application.DispatchReconciler.PlannedOrder;
import com.ebim.tms.planning.application.DispatchReconciler.Result;
import com.ebim.tms.planning.domain.DispatchSource;
import com.ebim.tms.planning.domain.DispatchVerificationStatus;
import com.ebim.tms.planning.domain.ExternalDispatchOrderMatch;
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
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/** Every rule of ADR-013 section 5, pinned without a database. */
class DispatchReconcilerTest {

    private static final OffsetDateTime AT = OffsetDateTime.parse("2026-09-28T08:12:00-05:00");
    private static final UUID ORDER_A = UUID.randomUUID();
    private static final UUID ORDER_B = UUID.randomUUID();
    private static final UUID ORDER_ELSEWHERE = UUID.randomUUID();

    // --- fixtures --------------------------------------------------------------------------------

    private static PlannedOrder wholeOrderA() {
        return new PlannedOrder(ORDER_A, "SAPB1_PE", "PED-1011", true, new OrderAmounts(bd("420"), bd("1.8"), bd("2")),
                List.of(new OrderLineSnapshot(1, "SKU-100", bd("100"), "UN")));
    }

    private static Plan plan(TripStatus status, List<PlannedOrder> orders) {
        return new Plan(status, null, null, Set.of("TRSA", "PROV-77"), "B7K-812", "Juan Perez", "40123456",
                Set.of("CD01", "CD-LIMA"), null, orders);
    }

    private static DispatchConfirmationCommand document(List<DispatchConfirmationCommand.Order> orders) {
        return document(orders, "TRSA", "B7K 812", "Juan Perez", null, "CD01", AT, "CRG-000001");
    }

    private static DispatchConfirmationCommand document(List<DispatchConfirmationCommand.Order> orders, String carrier,
            String plate, String driverName, String driverDocument, String warehouse, OffsetDateTime at, String load) {
        return new DispatchConfirmationCommand(UUID.randomUUID(), null, null, "EWM_EBIM", "SLS-000001", 1, "SH-00000845",
                load, warehouse, at, carrier, "Transportes SA", plate, "FURGON", driverName, driverDocument, "PRE-1",
                "T001-1", 3, bd("1180"), bd("4.6"), orders, "{}", "0".repeat(64));
    }

    private static DispatchConfirmationCommand.Order orderA(BigDecimal quantity, String uom) {
        return new DispatchConfirmationCommand.Order("SAPB1_PE", "PED-1011", "ORR-1", "SHIPPED", 2, bd("420"), null,
                List.of(new DispatchConfirmationCommand.Line(1, "SKU-100", "L1", quantity, uom, "HU-1")));
    }

    private static Result reconcile(DispatchConfirmationCommand document, Plan plan) {
        return DispatchReconciler.reconcile(document, plan,
                Map.of(new OrderExternalKey("SAPB1_PE", "PED-1011"), ORDER_A,
                        new OrderExternalKey("SAPB1_PE", "PED-9999"), ORDER_ELSEWHERE));
    }

    private static List<Code> codes(Result result) {
        return result.discrepancies().stream().map(Discrepancy::code).toList();
    }

    private static BigDecimal bd(String value) {
        return new BigDecimal(value);
    }

    // --- tests -----------------------------------------------------------------------------------

    @Test
    @DisplayName("a dispatch that agrees with the plan in everything is MATCHED with no discrepancy")
    void matched() {
        Result result = reconcile(document(List.of(orderA(bd("100"), "UN"))), plan(TripStatus.READY_FOR_DISPATCH,
                List.of(wholeOrderA())));

        assertThat(result.discrepancies()).isEmpty();
        assertThat(result.verification()).isEqualTo(DispatchVerificationStatus.MATCHED);
        assertThat(result.orders()).singleElement().satisfies(match -> {
            assertThat(match.orderId()).isEqualTo(ORDER_A);
            assertThat(match.result()).isEqualTo(ExternalDispatchOrderMatch.MATCHED);
        });
    }

    @Test
    @DisplayName("an unknown transport reference is recorded, MISMATCH, and its orders still classified")
    void unknownTransportReference() {
        Result result = reconcile(document(List.of(orderA(bd("100"), "UN"))), null);

        assertThat(codes(result)).containsExactly(Code.UNKNOWN_TRANSPORT_REFERENCE);
        assertThat(result.verification()).isEqualTo(DispatchVerificationStatus.MISMATCH);
        assertThat(result.orders()).singleElement()
                .satisfies(match -> assertThat(match.result()).isEqualTo(ExternalDispatchOrderMatch.EXTRA));
    }

    @Test
    @DisplayName("a cancelled trip and a draft trip are named for what they are")
    void tripState() {
        assertThat(codes(reconcile(document(List.of(orderA(bd("100"), "UN"))),
                plan(TripStatus.CANCELLED, List.of(wholeOrderA()))))).contains(Code.TRIP_CANCELLED);
        assertThat(codes(reconcile(document(List.of(orderA(bd("100"), "UN"))),
                plan(TripStatus.DRAFT, List.of(wholeOrderA()))))).contains(Code.TRIP_NOT_COMMITTED);
    }

    @Nested
    @DisplayName("orders")
    class Orders {

        @Test
        @DisplayName("97 of 100 on a line is QUANTITY_VARIANCE with both figures, and MISMATCH")
        void lineVariance() {
            Result result = reconcile(document(List.of(orderA(bd("97"), "UN"))),
                    plan(TripStatus.IN_TRANSIT, List.of(wholeOrderA())));

            assertThat(result.discrepancies()).singleElement().satisfies(discrepancy -> {
                assertThat(discrepancy.code()).isEqualTo(Code.QUANTITY_VARIANCE);
                assertThat(discrepancy.orderReference()).isEqualTo("PED-1011");
                assertThat(discrepancy.lineNumber()).isEqualTo(1);
                assertThat(discrepancy.planned()).isEqualByComparingTo("100");
                assertThat(discrepancy.dispatched()).isEqualByComparingTo("97");
                assertThat(discrepancy.uom()).isEqualTo("UN");
            });
            assertThat(result.verification()).isEqualTo(DispatchVerificationStatus.MISMATCH);
            assertThat(result.orders().getFirst().result()).isEqualTo(ExternalDispatchOrderMatch.VARIANCE);
        }

        @Test
        @DisplayName("several handling units of the same line are summed before comparing")
        void linesAreSummedAcrossHandlingUnits() {
            DispatchConfirmationCommand.Order split = new DispatchConfirmationCommand.Order("SAPB1_PE", "PED-1011",
                    "ORR-1", "SHIPPED", 2, null, null, List.of(
                            new DispatchConfirmationCommand.Line(1, "SKU-100", "L1", bd("60"), "UN", "HU-1"),
                            new DispatchConfirmationCommand.Line(1, "SKU-100", "L2", bd("40"), "un", "HU-2")));

            assertThat(reconcile(document(List.of(split)), plan(TripStatus.IN_TRANSIT, List.of(wholeOrderA())))
                    .discrepancies()).isEmpty();
        }

        @Test
        @DisplayName("a line in another unit is QUANTITY_UNCOMPARABLE, a warning that leaves it MATCHED")
        void otherUnit() {
            Result result = reconcile(document(List.of(orderA(bd("10"), "CJ"))),
                    plan(TripStatus.IN_TRANSIT, List.of(wholeOrderA())));

            assertThat(codes(result)).containsExactly(Code.QUANTITY_UNCOMPARABLE);
            assertThat(result.verification()).isEqualTo(DispatchVerificationStatus.MATCHED);
        }

        @Test
        @DisplayName("a share of a split order is compared by weight, never line by line")
        void splitShareByWeight() {
            PlannedOrder share = new PlannedOrder(ORDER_A, "SAPB1_PE", "PED-1011", false,
                    new OrderAmounts(bd("252"), bd("1"), bd("1")), List.of(new OrderLineSnapshot(1, "SKU", bd("100"), "UN")));
            DispatchConfirmationCommand.Order sixty = new DispatchConfirmationCommand.Order("SAPB1_PE", "PED-1011",
                    "ORR-1", "PARTIALLY_SHIPPED", 1, bd("253"), null,
                    List.of(new DispatchConfirmationCommand.Line(1, "SKU", null, bd("60"), "UN", null)));

            assertThat(reconcile(document(List.of(sixty)), plan(TripStatus.IN_TRANSIT, List.of(share))).discrepancies())
                    .as("253 kg against 252 planned is inside 1%").isEmpty();

            DispatchConfirmationCommand.Order heavy = new DispatchConfirmationCommand.Order("SAPB1_PE", "PED-1011",
                    "ORR-1", "PARTIALLY_SHIPPED", 1, bd("300"), null, List.of());
            assertThat(codes(reconcile(document(List.of(heavy)), plan(TripStatus.IN_TRANSIT, List.of(share)))))
                    .containsExactly(Code.QUANTITY_VARIANCE);
        }

        @Test
        @DisplayName("neither lines nor weight: QUANTITY_UNCOMPARABLE")
        void nothingToCompare() {
            DispatchConfirmationCommand.Order bare = new DispatchConfirmationCommand.Order("SAPB1_PE", "PED-1011", null,
                    null, null, null, null, List.of());
            assertThat(codes(reconcile(document(List.of(bare)), plan(TripStatus.IN_TRANSIT, List.of(wholeOrderA())))))
                    .containsExactly(Code.QUANTITY_UNCOMPARABLE);
        }

        @Test
        @DisplayName("a planned order absent from the dispatch is MISSING_ORDER")
        void missing() {
            PlannedOrder orderB = new PlannedOrder(ORDER_B, "SAPB1_PE", "PED-1014", true, OrderAmounts.NONE, List.of());
            Result result = reconcile(document(List.of(orderA(bd("100"), "UN"))),
                    plan(TripStatus.IN_TRANSIT, List.of(wholeOrderA(), orderB)));

            assertThat(result.discrepancies()).singleElement().satisfies(discrepancy -> {
                assertThat(discrepancy.code()).isEqualTo(Code.MISSING_ORDER);
                assertThat(discrepancy.orderReference()).isEqualTo("PED-1014");
            });
        }

        @Test
        @DisplayName("a dispatched order not on the trip is EXTRA_ORDER - known elsewhere or unknown - and nothing is assigned")
        void extra() {
            DispatchConfirmationCommand.Order elsewhere = new DispatchConfirmationCommand.Order("SAPB1_PE", "PED-9999",
                    null, null, null, null, null, List.of());
            DispatchConfirmationCommand.Order unknown = new DispatchConfirmationCommand.Order("SAPB1_PE", "PED-0000",
                    null, null, null, null, null, List.of());
            Result result = reconcile(document(List.of(orderA(bd("100"), "UN"), elsewhere, unknown)),
                    plan(TripStatus.IN_TRANSIT, List.of(wholeOrderA())));

            assertThat(codes(result)).containsExactly(Code.EXTRA_ORDER, Code.EXTRA_ORDER);
            assertThat(result.orders()).extracting(DispatchReconciler.OrderMatch::result)
                    .containsExactly(ExternalDispatchOrderMatch.MATCHED, ExternalDispatchOrderMatch.EXTRA,
                            ExternalDispatchOrderMatch.UNKNOWN);
        }

        @Test
        @DisplayName("the ERP namespace is compared byte for byte: sapb1_pe is not SAPB1_PE")
        void namespaceIsExact() {
            PlannedOrder lowerCase = new PlannedOrder(ORDER_A, "SAPB1_EC", "PED-1011", true, OrderAmounts.NONE, List.of());
            assertThat(codes(reconcile(document(List.of(orderA(bd("100"), "UN"))),
                    plan(TripStatus.IN_TRANSIT, List.of(lowerCase)))))
                    .containsExactlyInAnyOrder(Code.EXTRA_ORDER, Code.MISSING_ORDER);
        }
    }

    @Nested
    @DisplayName("transport")
    class Transport {

        @Test
        @DisplayName("the carrier matches by code or by external reference, case-insensitively")
        void carrier() {
            assertThat(codes(reconcile(document(List.of(orderA(bd("100"), "UN")), "prov-77", "B7K812", null, null,
                    "CD01", AT, null), plan(TripStatus.IN_TRANSIT, List.of(wholeOrderA()))))).isEmpty();
            assertThat(codes(reconcile(document(List.of(orderA(bd("100"), "UN")), "OTHER", "B7K812", null, null,
                    "CD01", AT, null), plan(TripStatus.IN_TRANSIT, List.of(wholeOrderA())))))
                    .containsExactly(Code.CARRIER_MISMATCH);
        }

        @Test
        @DisplayName("plates are compared upper case without spaces or hyphens")
        void plates() {
            assertThat(DispatchReconciler.normalizedPlate(" b7k-8 12")).isEqualTo("B7K812");
            assertThat(codes(reconcile(document(List.of(orderA(bd("100"), "UN")), "TRSA", "C1D-234", null, null,
                    "CD01", AT, null), plan(TripStatus.IN_TRANSIT, List.of(wholeOrderA())))))
                    .containsExactly(Code.VEHICLE_MISMATCH);
        }

        @Test
        @DisplayName("the driver is a warning only, by document when both have one, by name otherwise")
        void driver() {
            Result byName = reconcile(document(List.of(orderA(bd("100"), "UN")), "TRSA", "B7K812", "Pérez, Juan", null,
                    "CD01", AT, null), plan(TripStatus.IN_TRANSIT, List.of(wholeOrderA())));
            assertThat(byName.discrepancies()).as("accents and word order do not count").isEmpty();

            Result other = reconcile(document(List.of(orderA(bd("100"), "UN")), "TRSA", "B7K812", "Ana Quispe",
                    "99999999", "CD01", AT, null), plan(TripStatus.IN_TRANSIT, List.of(wholeOrderA())));
            assertThat(codes(other)).containsExactly(Code.DRIVER_MISMATCH);
            assertThat(other.verification()).isEqualTo(DispatchVerificationStatus.MATCHED);
        }

        @Test
        @DisplayName("a warehouse other than the origin's is WAREHOUSE_MISMATCH")
        void warehouse() {
            assertThat(codes(reconcile(document(List.of(orderA(bd("100"), "UN")), "TRSA", "B7K812", null, null,
                    "CD02", AT, null), plan(TripStatus.IN_TRANSIT, List.of(wholeOrderA())))))
                    .containsExactly(Code.WAREHOUSE_MISMATCH);
        }

        @Test
        @DisplayName("up to 15 minutes from the recorded departure is silence; beyond it DISPATCH_TIME, informative")
        void dispatchTime() {
            Plan departedAt = new Plan(TripStatus.IN_TRANSIT, AT.minusMinutes(15), DispatchSource.OPERATOR,
                    Set.of("TRSA"), "B7K812", null, null, Set.of("CD01"), null, List.of(wholeOrderA()));
            assertThat(reconcile(document(List.of(orderA(bd("100"), "UN"))), departedAt).discrepancies()).isEmpty();

            Plan earlier = new Plan(TripStatus.IN_TRANSIT, AT.minusMinutes(16), DispatchSource.OPERATOR,
                    Set.of("TRSA"), "B7K812", null, null, Set.of("CD01"), null, List.of(wholeOrderA()));
            Result result = reconcile(document(List.of(orderA(bd("100"), "UN"))), earlier);
            assertThat(codes(result)).containsExactly(Code.DISPATCH_TIME);
            assertThat(result.verification()).isEqualTo(DispatchVerificationStatus.MATCHED);
        }

        @Test
        @DisplayName("a load other than the one an earlier document of the trip named is UNKNOWN_LOAD")
        void load() {
            Plan withLoad = new Plan(TripStatus.IN_TRANSIT, null, null, Set.of("TRSA"), "B7K812", null, null,
                    Set.of("CD01"), "CRG-000001", List.of(wholeOrderA()));
            assertThat(codes(reconcile(document(List.of(orderA(bd("100"), "UN")), "TRSA", "B7K812", null, null, "CD01",
                    AT, "CRG-000002"), withLoad))).containsExactly(Code.UNKNOWN_LOAD);
        }

        @Test
        @DisplayName("a matching document of a trip dispatched by override is OVERRIDDEN")
        void overridden() {
            Plan byOverride = new Plan(TripStatus.IN_TRANSIT, AT, DispatchSource.OPERATOR_OVERRIDE, Set.of("TRSA"),
                    "B7K812", null, null, Set.of("CD01"), null, List.of(wholeOrderA()));
            assertThat(reconcile(document(List.of(orderA(bd("100"), "UN"))), byOverride).verification())
                    .isEqualTo(DispatchVerificationStatus.OVERRIDDEN);
        }
    }
}
