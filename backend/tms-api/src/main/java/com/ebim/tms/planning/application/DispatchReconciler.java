package com.ebim.tms.planning.application;

import com.ebim.tms.planning.domain.DispatchSource;
import com.ebim.tms.planning.domain.DispatchVerificationStatus;
import com.ebim.tms.planning.domain.ExternalDispatchOrderMatch;
import com.ebim.tms.planning.domain.TripStatus;
import com.ebim.tms.shared.reference.DispatchConfirmationCommand;
import com.ebim.tms.shared.reference.OrderAmounts;
import com.ebim.tms.shared.reference.OrderExternalKey;
import com.ebim.tms.shared.reference.OrderLineSnapshot;
import java.math.BigDecimal;
import java.math.MathContext;
import java.text.Normalizer;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Compares what a warehouse system says left with what TMS planned (ADR-013 section 5), and says
 * nothing else. Pure: no repository, no clock, no Spring - every input is a parameter, which is what
 * lets every rule below be pinned by a plain unit test.
 *
 * <p><b>Plan and reality are never merged.</b> The result is a list of differences to store beside
 * the document and show a person. It creates no assignment for an extra order, changes no quantity
 * and rewrites no vehicle or driver.
 *
 * <p><b>Severity decides the verification.</b> An {@link Severity#ERROR} makes the document
 * {@code MISMATCH}; a {@link Severity#WARNING} or {@link Severity#INFO} is shown but does not, so a
 * driver typed differently or a truck leaving twenty minutes after the plan was keyed does not turn
 * a correct dispatch red.
 */
public final class DispatchReconciler {

    /** The fixed tolerance of ADR-013: not a setting yet, and never a reason to refuse. */
    public static final Duration DISPATCH_TIME_TOLERANCE = Duration.ofMinutes(15);

    /**
     * Relative tolerance when an order can only be compared by weight: a warehouse weighs what
     * left (gross, with packaging) and an ERP declares what was ordered, and the two never agree to
     * the gram. One percent is wide enough for that and narrow enough to catch a missing pallet.
     */
    static final BigDecimal WEIGHT_TOLERANCE = new BigDecimal("0.01");

    public enum Severity {
        ERROR,
        WARNING,
        INFO
    }

    public enum Code {
        UNKNOWN_TRANSPORT_REFERENCE(Severity.ERROR),
        TRIP_CANCELLED(Severity.ERROR),
        TRIP_NOT_COMMITTED(Severity.ERROR),
        MISSING_ORDER(Severity.ERROR),
        EXTRA_ORDER(Severity.ERROR),
        QUANTITY_VARIANCE(Severity.ERROR),
        QUANTITY_UNCOMPARABLE(Severity.WARNING),
        CARRIER_MISMATCH(Severity.ERROR),
        VEHICLE_MISMATCH(Severity.ERROR),
        DRIVER_MISMATCH(Severity.WARNING),
        WAREHOUSE_MISMATCH(Severity.ERROR),
        DISPATCH_TIME(Severity.INFO),
        UNKNOWN_LOAD(Severity.ERROR),
        /** A check {@code DispatchReadiness} would have refused a person with (ADR-013 section 3). */
        DISPATCH_BLOCKER(Severity.WARNING),
        /**
         * The document would dispatch but cannot without breaking a database invariant. An ERROR: the
         * warehouse and TMS now disagree about whether the truck left, which is exactly a mismatch.
         */
        NOT_APPLIED(Severity.ERROR);

        private final Severity severity;

        Code(Severity severity) {
            this.severity = severity;
        }

        public Severity severity() {
            return severity;
        }
    }

    public record Discrepancy(Code code, String orderReference, Integer lineNumber, BigDecimal planned,
            BigDecimal dispatched, String uom, String detail) {

        public Severity severity() {
            return code.severity();
        }

        static Discrepancy of(Code code, String detail) {
            return new Discrepancy(code, null, null, null, null, null, detail);
        }
    }

    /** One order the trip carries: what planning committed to take. */
    public record PlannedOrder(UUID orderId, String externalSource, String externalReference, boolean wholeOrder,
            OrderAmounts assigned, List<OrderLineSnapshot> lines) {

        public PlannedOrder {
            lines = lines == null ? List.of() : List.copyOf(lines);
        }

        OrderExternalKey key() {
            return new OrderExternalKey(externalSource, externalReference);
        }
    }

    /**
     * The plan the document is compared with.
     *
     * @param warehouseCodes the codes the trip's origin may be called by: its external reference and,
     *     as a fallback for an origin that has none, its own code
     * @param previousLoadReference the load an earlier current document of the same trip named, if any
     */
    public record Plan(TripStatus status, OffsetDateTime actualDepartureAt, DispatchSource dispatchSource,
            Set<String> carrierCodes, String vehicleLicensePlate, String driverName, String driverDocumentNumber,
            Set<String> warehouseCodes, String previousLoadReference, List<PlannedOrder> orders) {

        public Plan {
            carrierCodes = carrierCodes == null ? Set.of() : Set.copyOf(carrierCodes);
            warehouseCodes = warehouseCodes == null ? Set.of() : Set.copyOf(warehouseCodes);
            orders = orders == null ? List.of() : List.copyOf(orders);
        }
    }

    public record OrderMatch(OrderExternalKey key, UUID orderId, ExternalDispatchOrderMatch result) {
    }

    public record Result(List<Discrepancy> discrepancies, List<OrderMatch> orders,
            DispatchVerificationStatus verification) {
    }

    private DispatchReconciler() {
    }

    /**
     * @param plan null when {@code transportReference} matched no trip of the company
     * @param knownOrders the company's orders among the document's keys, to tell an order that is
     *     merely not on this trip ({@code EXTRA}) from one TMS has never heard of ({@code UNKNOWN})
     */
    public static Result reconcile(DispatchConfirmationCommand document, Plan plan,
            Map<OrderExternalKey, UUID> knownOrders) {
        List<Discrepancy> found = new ArrayList<>();
        if (plan == null) {
            found.add(Discrepancy.of(Code.UNKNOWN_TRANSPORT_REFERENCE,
                    "No shipment " + document.transportReference() + " exists in this company."));
            List<OrderMatch> orders = document.orders().stream()
                    .map(order -> matchOf(order.key(), knownOrders, false))
                    .toList();
            return new Result(List.copyOf(found), orders, DispatchVerificationStatus.MISMATCH);
        }

        if (plan.status() == TripStatus.CANCELLED) {
            found.add(Discrepancy.of(Code.TRIP_CANCELLED, "The shipment was cancelled in TMS."));
        } else if (plan.status() == TripStatus.DRAFT) {
            found.add(Discrepancy.of(Code.TRIP_NOT_COMMITTED, "The shipment's plan is still a draft in TMS."));
        }

        List<OrderMatch> orders = compareOrders(document, plan, knownOrders, found);
        compareTransport(document, plan, found);

        boolean mismatch = found.stream().anyMatch(discrepancy -> discrepancy.severity() == Severity.ERROR);
        DispatchVerificationStatus verification = mismatch
                ? DispatchVerificationStatus.MISMATCH
                : plan.dispatchSource() == DispatchSource.OPERATOR_OVERRIDE
                        ? DispatchVerificationStatus.OVERRIDDEN
                        : DispatchVerificationStatus.MATCHED;
        return new Result(List.copyOf(found), orders, verification);
    }

    // --- orders ------------------------------------------------------------------------------

    private static List<OrderMatch> compareOrders(DispatchConfirmationCommand document, Plan plan,
            Map<OrderExternalKey, UUID> knownOrders, List<Discrepancy> found) {
        Map<OrderExternalKey, PlannedOrder> planned = new LinkedHashMap<>();
        for (PlannedOrder order : plan.orders()) {
            if (order.externalSource() != null && order.externalReference() != null) {
                planned.put(order.key(), order);
            }
        }

        List<OrderMatch> matches = new ArrayList<>();
        Set<OrderExternalKey> dispatched = new LinkedHashSet<>();
        for (DispatchConfirmationCommand.Order order : document.orders()) {
            OrderExternalKey key = order.key();
            dispatched.add(key);
            PlannedOrder plannedOrder = planned.get(key);
            if (plannedOrder == null) {
                // Never an assignment created from here: plan and reality stay apart.
                found.add(new Discrepancy(Code.EXTRA_ORDER, reference(key), null, null, null, null,
                        knownOrders.containsKey(key)
                                ? "The order exists in TMS but is not on this shipment."
                                : "No order " + reference(key) + " exists in TMS."));
                matches.add(matchOf(key, knownOrders, false));
                continue;
            }
            ExternalDispatchOrderMatch result = compareQuantities(order, plannedOrder, found);
            matches.add(new OrderMatch(key, plannedOrder.orderId(), result));
        }

        for (PlannedOrder order : plan.orders()) {
            boolean keyed = order.externalSource() != null && order.externalReference() != null;
            if (!keyed || !dispatched.contains(order.key())) {
                found.add(new Discrepancy(Code.MISSING_ORDER,
                        keyed ? reference(order.key()) : null, null, null, null, null,
                        keyed ? "Planned on this shipment and absent from the dispatch."
                              : "A planned order has no ERP reference, so the warehouse cannot name it."));
            }
        }
        return matches;
    }

    private static OrderMatch matchOf(OrderExternalKey key, Map<OrderExternalKey, UUID> knownOrders, boolean onTrip) {
        UUID orderId = knownOrders.get(key);
        return new OrderMatch(key, orderId, orderId == null
                ? ExternalDispatchOrderMatch.UNKNOWN
                : onTrip ? ExternalDispatchOrderMatch.MATCHED : ExternalDispatchOrderMatch.EXTRA);
    }

    /**
     * Per line when the whole order is on this trip and both sides name the line and its unit;
     * per order by weight otherwise; uncomparable when neither is possible. A share of a split order
     * is never compared line by line: which units of a line were planned onto this trip is not
     * something TMS records.
     */
    private static ExternalDispatchOrderMatch compareQuantities(DispatchConfirmationCommand.Order order,
            PlannedOrder planned, List<Discrepancy> found) {
        String reference = reference(order.key());
        boolean documentHasLines = order.lines().stream().anyMatch(line -> line.lineNumber() != null);
        if (planned.wholeOrder() && documentHasLines && !planned.lines().isEmpty()) {
            return compareLines(order, planned, reference, found);
        }
        BigDecimal plannedWeight = planned.assigned() == null ? null : planned.assigned().weightKg();
        if (order.weightKg() != null && plannedWeight != null && plannedWeight.signum() > 0) {
            BigDecimal difference = order.weightKg().subtract(plannedWeight).abs();
            if (difference.compareTo(plannedWeight.multiply(WEIGHT_TOLERANCE, MathContext.DECIMAL64)) > 0) {
                found.add(new Discrepancy(Code.QUANTITY_VARIANCE, reference, null, plannedWeight, order.weightKg(),
                        "KG", "Dispatched weight differs from the planned weight by more than 1%."));
                return ExternalDispatchOrderMatch.VARIANCE;
            }
            return ExternalDispatchOrderMatch.MATCHED;
        }
        found.add(new Discrepancy(Code.QUANTITY_UNCOMPARABLE, reference, null, null, null, null,
                "Neither the lines nor the weight can be compared for this order."));
        return ExternalDispatchOrderMatch.UNCOMPARABLE;
    }

    private static ExternalDispatchOrderMatch compareLines(DispatchConfirmationCommand.Order order,
            PlannedOrder planned, String reference, List<Discrepancy> found) {
        // Several handling units can carry the same line: the dispatched quantity is their sum.
        Map<Integer, BigDecimal> dispatchedQuantity = new LinkedHashMap<>();
        Map<Integer, String> dispatchedUom = new LinkedHashMap<>();
        Set<Integer> mixedUnits = new LinkedHashSet<>();
        for (DispatchConfirmationCommand.Line line : order.lines()) {
            if (line.lineNumber() == null || line.quantity() == null) {
                continue;
            }
            dispatchedQuantity.merge(line.lineNumber(), line.quantity(), BigDecimal::add);
            String previous = dispatchedUom.putIfAbsent(line.lineNumber(), normalizedUom(line.uom()));
            if (previous != null && !previous.equals(normalizedUom(line.uom()))) {
                mixedUnits.add(line.lineNumber());
            }
        }

        boolean variance = false;
        boolean uncomparable = false;
        Set<Integer> plannedNumbers = new LinkedHashSet<>();
        for (OrderLineSnapshot line : planned.lines()) {
            plannedNumbers.add(line.lineNumber());
            BigDecimal quantity = dispatchedQuantity.get(line.lineNumber());
            String unit = dispatchedUom.get(line.lineNumber());
            if (quantity == null) {
                found.add(new Discrepancy(Code.QUANTITY_VARIANCE, reference, line.lineNumber(), line.quantity(),
                        BigDecimal.ZERO, line.uom(), "The line was planned and nothing of it was dispatched."));
                variance = true;
            } else if (mixedUnits.contains(line.lineNumber()) || !Objects.equals(unit, normalizedUom(line.uom()))) {
                found.add(new Discrepancy(Code.QUANTITY_UNCOMPARABLE, reference, line.lineNumber(), line.quantity(),
                        quantity, unit, "The dispatched unit is not the planned one (" + line.uom() + ")."));
                uncomparable = true;
            } else if (quantity.compareTo(line.quantity()) != 0) {
                found.add(new Discrepancy(Code.QUANTITY_VARIANCE, reference, line.lineNumber(), line.quantity(),
                        quantity, line.uom(), null));
                variance = true;
            }
        }
        for (Map.Entry<Integer, BigDecimal> extra : dispatchedQuantity.entrySet()) {
            if (!plannedNumbers.contains(extra.getKey())) {
                found.add(new Discrepancy(Code.QUANTITY_VARIANCE, reference, extra.getKey(), BigDecimal.ZERO,
                        extra.getValue(), dispatchedUom.get(extra.getKey()),
                        "The line was dispatched but is not on the order."));
                variance = true;
            }
        }
        return variance
                ? ExternalDispatchOrderMatch.VARIANCE
                : uncomparable ? ExternalDispatchOrderMatch.UNCOMPARABLE : ExternalDispatchOrderMatch.MATCHED;
    }

    // --- transport -------------------------------------------------------------------------

    private static void compareTransport(DispatchConfirmationCommand document, Plan plan, List<Discrepancy> found) {
        if (present(document.carrierCode())) {
            boolean known = plan.carrierCodes().stream()
                    .anyMatch(code -> code != null && code.trim().equalsIgnoreCase(document.carrierCode().trim()));
            if (!known) {
                found.add(Discrepancy.of(Code.CARRIER_MISMATCH, "The dispatch names carrier "
                        + document.carrierCode() + ", which is neither the shipment carrier's code nor its external "
                        + "reference."));
            }
        }
        if (present(document.vehicleLicensePlate())
                && !Objects.equals(normalizedPlate(document.vehicleLicensePlate()),
                        normalizedPlate(plan.vehicleLicensePlate()))) {
            found.add(Discrepancy.of(Code.VEHICLE_MISMATCH, "Dispatched on plate " + document.vehicleLicensePlate()
                    + "; the shipment plans " + (plan.vehicleLicensePlate() == null ? "no vehicle" : plan.vehicleLicensePlate())
                    + "."));
        }
        compareDriver(document, plan, found);
        if (present(document.warehouseCode())) {
            boolean known = plan.warehouseCodes().stream()
                    .anyMatch(code -> code != null && code.trim().equalsIgnoreCase(document.warehouseCode().trim()));
            if (!known) {
                found.add(Discrepancy.of(Code.WAREHOUSE_MISMATCH, "Dispatched from warehouse "
                        + document.warehouseCode() + ", which is not the shipment's origin."));
            }
        }
        if (plan.actualDepartureAt() != null && document.actualDispatchAt() != null) {
            Duration gap = Duration.between(plan.actualDepartureAt(), document.actualDispatchAt()).abs();
            if (gap.compareTo(DISPATCH_TIME_TOLERANCE) > 0) {
                found.add(Discrepancy.of(Code.DISPATCH_TIME, "The warehouse dispatched at "
                        + document.actualDispatchAt() + "; TMS recorded the departure at " + plan.actualDepartureAt()
                        + " (" + gap.toMinutes() + " minutes apart)."));
            }
        }
        if (present(plan.previousLoadReference()) && present(document.loadReference())
                && !plan.previousLoadReference().equals(document.loadReference())) {
            found.add(Discrepancy.of(Code.UNKNOWN_LOAD, "An earlier dispatch of this shipment named load "
                    + plan.previousLoadReference() + "; this one names " + document.loadReference() + "."));
        }
    }

    /**
     * A warning only: the warehouse holds the driver as free text. Compared by document number when
     * both have one, by name otherwise; silent when either side names nobody.
     */
    private static void compareDriver(DispatchConfirmationCommand document, Plan plan, List<Discrepancy> found) {
        if (present(document.driverDocumentNumber()) && present(plan.driverDocumentNumber())) {
            if (!normalizedToken(document.driverDocumentNumber()).equals(normalizedToken(plan.driverDocumentNumber()))) {
                found.add(Discrepancy.of(Code.DRIVER_MISMATCH, "Driver document " + document.driverDocumentNumber()
                        + " is not the planned driver's."));
            }
            return;
        }
        if (present(document.driverName()) && present(plan.driverName())
                && !normalizedName(document.driverName()).equals(normalizedName(plan.driverName()))) {
            found.add(Discrepancy.of(Code.DRIVER_MISMATCH, "Driver " + document.driverName()
                    + " is not the planned driver (" + plan.driverName() + ")."));
        }
    }

    // --- normalisation -----------------------------------------------------------------------

    /** Upper case, no spaces, no hyphens: B7K-812, b7k 812 and B7K812 are one plate (ADR-013). */
    static String normalizedPlate(String plate) {
        return plate == null ? null : plate.toUpperCase(Locale.ROOT).replaceAll("[\\s-]", "");
    }

    /** "Pérez, Juan" and "JUAN PEREZ" name the same person; word order and accents do not count. */
    static String normalizedName(String name) {
        String plain = Normalizer.normalize(name, Normalizer.Form.NFD).replaceAll("\\p{M}", "");
        return java.util.Arrays.stream(plain.toUpperCase(Locale.ROOT).split("[^A-Z0-9]+"))
                .filter(word -> !word.isEmpty())
                .sorted()
                .reduce((first, second) -> first + " " + second)
                .orElse("");
    }

    private static String normalizedToken(String value) {
        return value.toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9]", "");
    }

    private static String normalizedUom(String uom) {
        return uom == null ? null : uom.trim().toUpperCase(Locale.ROOT);
    }

    private static boolean present(String value) {
        return value != null && !value.isBlank();
    }

    private static String reference(OrderExternalKey key) {
        return key.externalReference();
    }
}
