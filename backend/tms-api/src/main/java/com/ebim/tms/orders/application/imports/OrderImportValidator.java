package com.ebim.tms.orders.application.imports;

import com.ebim.tms.orders.domain.DeclaredTotals;
import com.ebim.tms.orders.domain.OrderLineInput;
import com.ebim.tms.orders.domain.OrderPriority;
import com.ebim.tms.orders.domain.OrderTotals;
import com.ebim.tms.shared.reference.MasterReference;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * The whole of the import's business validation, as pure functions over already-parsed rows and
 * an already-resolved snapshot of the masters they refer to.
 *
 * <p>Pure on purpose. Every rule that decides whether an operator's file is accepted - grouping,
 * master resolution, dates, totals precedence, the cross-tenant guarantee - is exercised by
 * {@code OrderImportValidatorTest} with no Spring context, no HTTP and no database, which is the
 * difference between rules that are actually tested and rules that are tested only when Docker
 * happens to be running.
 *
 * <h2>The cross-tenant guarantee</h2>
 *
 * <p>{@link MasterSnapshot} is built by {@code OrderImportService} from
 * {@code OriginLookupPort}/{@code DestinationLookupPort} <em>scoped to the caller's company</em>.
 * A code belonging to another tenant is therefore simply absent from the snapshot and resolves to
 * "no such origin" - identical to a typo. This class never sees a company id, so there is no
 * code path here that could compare one incorrectly.
 */
final class OrderImportValidator {

    /**
     * What the caller's company currently contains, resolved once for the whole file rather than
     * once per row.
     *
     * @param originsByCode      active origins, keyed by their normalised (upper-case) code
     * @param destinationsByCode active destinations, same keying
     * @param existingReferences external references already used in this company under the
     *                           import's external source - the idempotency check
     */
    record MasterSnapshot(
            Map<String, MasterReference> originsByCode,
            Map<String, MasterReference> destinationsByCode,
            Set<String> existingReferences) {
    }

    record Result(List<OrderImportCandidate> candidates, List<OrderImportReport.Issue> issues) {
    }

    private OrderImportValidator() {}

    /** Every origin and destination code the file mentions, so the caller can resolve them in two queries. */
    static Set<String> referencedCodes(List<OrderImportRow> rows, OrderImportColumn column) {
        return rows.stream()
                .map(row -> row.value(column))
                .filter(code -> code != null && !code.isBlank())
                .map(code -> code.trim().toUpperCase(Locale.ROOT))
                .collect(Collectors.toUnmodifiableSet());
    }

    static Result validate(List<OrderImportRow> rows, MasterSnapshot snapshot) {
        List<OrderImportReport.Issue> issues = new ArrayList<>();
        Map<String, List<OrderImportRow>> groups = group(rows, issues);

        List<OrderImportCandidate> candidates = new ArrayList<>();
        if (groups.size() > OrderImportLimits.MAX_ORDERS) {
            issues.add(new OrderImportReport.Issue(0, null, null, "The file describes " + groups.size()
                    + " orders, more than the limit of " + OrderImportLimits.MAX_ORDERS
                    + ". Split it into smaller files."));
            return new Result(List.of(), List.copyOf(issues));
        }

        for (Map.Entry<String, List<OrderImportRow>> group : groups.entrySet()) {
            candidates.add(validateOrder(group.getKey(), group.getValue(), snapshot, issues));
        }
        return new Result(List.copyOf(candidates), List.copyOf(issues));
    }

    /**
     * Groups rows by external reference, preserving first-seen order so the report reads in file
     * order. Rows of one reference need not be adjacent: an export sorted by material rather than
     * by order is still a valid description of the same orders.
     */
    private static Map<String, List<OrderImportRow>> group(
            List<OrderImportRow> rows, List<OrderImportReport.Issue> issues) {
        Map<String, List<OrderImportRow>> groups = new LinkedHashMap<>();
        for (OrderImportRow row : rows) {
            String reference = row.value(OrderImportColumn.EXTERNAL_REFERENCE);
            if (reference == null) {
                issues.add(issue(row, OrderImportColumn.EXTERNAL_REFERENCE, null,
                        "This row has no external reference, so it cannot be attached to an order."));
                continue;
            }
            groups.computeIfAbsent(reference, key -> new ArrayList<>()).add(row);
        }
        return groups;
    }

    private static OrderImportCandidate validateOrder(String reference, List<OrderImportRow> rows,
            MasterSnapshot snapshot, List<OrderImportReport.Issue> issues) {
        int issuesBefore = issues.size();
        OrderImportRow first = rows.get(0);

        MasterReference origin = resolveMaster(first, OrderImportColumn.ORIGIN_CODE, snapshot.originsByCode(),
                "origin", reference, issues);
        MasterReference destination = resolveMaster(first, OrderImportColumn.DESTINATION_CODE,
                snapshot.destinationsByCode(), "destination", reference, issues);

        LocalDate serviceDate = convert(first, OrderImportColumn.SERVICE_DATE, reference, issues,
                OrderImportValues::date);
        if (!first.hasValue(OrderImportColumn.SERVICE_DATE)) {
            issues.add(issue(first, OrderImportColumn.SERVICE_DATE, reference, "A service date is required."));
        }

        OrderPriority priority = convert(first, OrderImportColumn.PRIORITY, reference, issues,
                raw -> OrderImportValues.enumeration(raw, OrderPriority.class, "LOW, NORMAL, HIGH, URGENT"));
        LocalTime windowStart = convert(first, OrderImportColumn.WINDOW_START, reference, issues,
                OrderImportValues::time);
        LocalTime windowEnd = convert(first, OrderImportColumn.WINDOW_END, reference, issues, OrderImportValues::time);
        validateWindow(first, reference, windowStart, windowEnd, issues);

        DeclaredTotals declared = new DeclaredTotals(
                convert(first, OrderImportColumn.DECLARED_WEIGHT_KG, reference, issues,
                        OrderImportValues::nonNegativeDecimal),
                convert(first, OrderImportColumn.DECLARED_VOLUME_M3, reference, issues,
                        OrderImportValues::nonNegativeDecimal),
                convert(first, OrderImportColumn.DECLARED_PALLETS, reference, issues,
                        OrderImportValues::nonNegativeDecimal));

        requireConsistentHeaders(rows, reference, issues);
        List<OrderLineInput> lines = readLines(rows, reference, issues);
        validateDeclaredAgainstLines(first, reference, lines, declared, issues);

        boolean rejected = issues.size() > issuesBefore;
        OrderImportReport.Outcome outcome;
        if (rejected) {
            outcome = OrderImportReport.Outcome.REJECTED;
        } else if (snapshot.existingReferences().contains(reference)) {
            outcome = OrderImportReport.Outcome.SKIPPED_DUPLICATE;
        } else {
            outcome = OrderImportReport.Outcome.CREATE;
        }

        return new OrderImportCandidate(reference, outcome, first.rowNumber(), origin, destination,
                first.value(OrderImportColumn.CUSTOMER_NAME), first.value(OrderImportColumn.CUSTOMER_REFERENCE),
                serviceDate, priority == null ? OrderPriority.NORMAL : priority, windowStart, windowEnd,
                declared, lines);
    }

    private static MasterReference resolveMaster(OrderImportRow row, OrderImportColumn column,
            Map<String, MasterReference> byCode, String what, String reference, List<OrderImportReport.Issue> issues) {
        String code = row.value(column);
        if (code == null) {
            issues.add(issue(row, column, reference, "A " + what + " code is required."));
            return null;
        }
        MasterReference resolved = byCode.get(code.trim().toUpperCase(Locale.ROOT));
        if (resolved == null) {
            // Deliberately one message for "does not exist", "is inactive" and "belongs to another
            // company". The three are indistinguishable to a caller by design: telling an operator
            // that a code exists but is not theirs would confirm the existence of another tenant's
            // master, which is the leak DATA_MODEL.md rule 9 exists to prevent.
            issues.add(issue(row, column, reference,
                    "'" + code + "' does not match an active " + what + " in this company."));
        }
        return resolved;
    }

    /**
     * Every row of one order must repeat the same header values. Taking the first row's silently
     * would mean a file whose second row says a different service date imports as though the
     * operator had never written it - the classic copy-paste error a preview exists to catch.
     */
    private static void requireConsistentHeaders(
            List<OrderImportRow> rows, String reference, List<OrderImportReport.Issue> issues) {
        OrderImportRow first = rows.get(0);
        List<OrderImportColumn> headerColumns = List.of(
                OrderImportColumn.ORIGIN_CODE, OrderImportColumn.DESTINATION_CODE, OrderImportColumn.SERVICE_DATE,
                OrderImportColumn.PRIORITY, OrderImportColumn.WINDOW_START, OrderImportColumn.WINDOW_END,
                OrderImportColumn.CUSTOMER_NAME, OrderImportColumn.CUSTOMER_REFERENCE,
                OrderImportColumn.DECLARED_WEIGHT_KG, OrderImportColumn.DECLARED_VOLUME_M3,
                OrderImportColumn.DECLARED_PALLETS);

        for (int index = 1; index < rows.size(); index++) {
            OrderImportRow row = rows.get(index);
            for (OrderImportColumn column : headerColumns) {
                String expected = first.value(column);
                String actual = row.value(column);
                // A blank on a continuation row is not a contradiction: repeating the header on
                // every line is common, and leaving it off every line but the first is just as
                // common. Only a different non-blank value is a mistake.
                if (actual != null && !actual.equalsIgnoreCase(expected == null ? "" : expected)) {
                    issues.add(issue(row, column, reference, "This row gives '" + actual + "' but row "
                            + first.rowNumber() + " of the same order gives "
                            + (expected == null ? "nothing" : "'" + expected + "'")
                            + ". Every row of one order must repeat the same header values."));
                }
            }
        }
    }

    /**
     * Reads the line columns of every row. A row with no material code carries no line - that is
     * how a header-only order is written - but a row that fills <em>some</em> of the line columns
     * and not the others is an incomplete line, not an absent one, and is reported as such.
     */
    private static List<OrderLineInput> readLines(
            List<OrderImportRow> rows, String reference, List<OrderImportReport.Issue> issues) {
        List<OrderLineInput> lines = new ArrayList<>();
        for (OrderImportRow row : rows) {
            String materialCode = row.value(OrderImportColumn.MATERIAL_CODE);
            boolean anyLineValue = materialCode != null
                    || row.hasValue(OrderImportColumn.MATERIAL_DESCRIPTION)
                    || row.hasValue(OrderImportColumn.QUANTITY)
                    || row.hasValue(OrderImportColumn.UOM);
            if (!anyLineValue) {
                continue;
            }
            if (materialCode == null) {
                issues.add(issue(row, OrderImportColumn.MATERIAL_CODE, reference,
                        "This row describes a line but has no material code."));
                continue;
            }

            String description = row.value(OrderImportColumn.MATERIAL_DESCRIPTION);
            String uom = row.value(OrderImportColumn.UOM);
            BigDecimal quantity = convert(row, OrderImportColumn.QUANTITY, reference, issues,
                    OrderImportValues::positiveDecimal);
            if (quantity == null && !row.hasValue(OrderImportColumn.QUANTITY)) {
                issues.add(issue(row, OrderImportColumn.QUANTITY, reference, "A quantity is required on a line."));
            }
            if (uom == null) {
                issues.add(issue(row, OrderImportColumn.UOM, reference,
                        "A unit of measure is required on a line, for example EA, CS or KG."));
            }

            BigDecimal unitWeight = convert(row, OrderImportColumn.UNIT_WEIGHT_KG, reference, issues,
                    OrderImportValues::positiveDecimal);
            BigDecimal unitVolume = convert(row, OrderImportColumn.UNIT_VOLUME_M3, reference, issues,
                    OrderImportValues::positiveDecimal);
            BigDecimal pallets = convert(row, OrderImportColumn.PALLET_QUANTITY, reference, issues,
                    OrderImportValues::nonNegativeDecimal);

            if (quantity == null || uom == null) {
                continue;
            }
            lines.add(new OrderLineInput(materialCode, description == null ? materialCode : description, quantity,
                    uom.toUpperCase(Locale.ROOT), unitWeight, unitVolume, pallets));

            if (lines.size() > OrderImportLimits.MAX_LINES_PER_ORDER) {
                issues.add(issue(row, null, reference, "This order has more than "
                        + OrderImportLimits.MAX_LINES_PER_ORDER
                        + " lines, which is far likelier to be a grouping mistake than a real order."));
                return List.copyOf(lines);
            }
        }
        return List.copyOf(lines);
    }

    /** The file's half of the totals precedence rule; see {@link OrderTotals} for the rule itself. */
    private static void validateDeclaredAgainstLines(OrderImportRow row, String reference,
            List<OrderLineInput> lines, DeclaredTotals declared, List<OrderImportReport.Issue> issues) {
        for (OrderTotals.Mismatch mismatch : OrderTotals.mismatches(lines, declared)) {
            OrderImportColumn column = switch (mismatch.measure()) {
                case WEIGHT_KG -> OrderImportColumn.DECLARED_WEIGHT_KG;
                case VOLUME_M3 -> OrderImportColumn.DECLARED_VOLUME_M3;
                case PALLETS -> OrderImportColumn.DECLARED_PALLETS;
            };
            issues.add(issue(row, column, reference, "Declared as " + plain(mismatch.declared())
                    + " but the lines of this order add up to " + plain(mismatch.calculated())
                    + ". Correct one of the two, or leave the declared value blank."));
        }
    }

    private static void validateWindow(OrderImportRow row, String reference, LocalTime start, LocalTime end,
            List<OrderImportReport.Issue> issues) {
        if ((start == null) != (end == null)) {
            issues.add(issue(row, start == null ? OrderImportColumn.WINDOW_START : OrderImportColumn.WINDOW_END,
                    reference, "A requested time window needs both a start and an end, or neither."));
            return;
        }
        if (start != null && !start.isBefore(end)) {
            issues.add(issue(row, OrderImportColumn.WINDOW_END, reference,
                    "The window end must be later than the window start."));
        }
    }

    /**
     * Applies one converter, turning a {@link OrderImportValues.Rejected} into an issue against
     * the cell that caused it. The whole reason conversion failures are exceptions inside
     * {@code OrderImportValues} and issues out here: the conversion code stays readable, and no
     * caller can forget to record why a value was dropped.
     */
    private static <T> T convert(OrderImportRow row, OrderImportColumn column, String reference,
            List<OrderImportReport.Issue> issues, Function<String, T> converter) {
        try {
            return converter.apply(row.value(column));
        } catch (OrderImportValues.Rejected rejected) {
            issues.add(issue(row, column, reference, rejected.getMessage()));
            return null;
        }
    }

    private static OrderImportReport.Issue issue(
            OrderImportRow row, OrderImportColumn column, String reference, String message) {
        return new OrderImportReport.Issue(
                row.rowNumber(), column == null ? null : column.header(), reference, message);
    }

    private static String plain(BigDecimal value) {
        return value.stripTrailingZeros().toPlainString();
    }
}
