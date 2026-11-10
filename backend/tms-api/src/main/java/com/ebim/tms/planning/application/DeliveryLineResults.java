package com.ebim.tms.planning.application;

import com.ebim.tms.planning.domain.DeliveryResult;
import com.ebim.tms.planning.infrastructure.OrderDeliveryLineQueries.DeliveryLineWrite;
import com.ebim.tms.shared.api.InvalidRequestException;
import com.ebim.tms.shared.reference.OrderAmounts;
import com.ebim.tms.shared.reference.OrderLineReference;
import java.math.BigDecimal;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * What a delivery says about each product of the order (V75): the rows of
 * {@code tms.order_delivery_line} (V45) that answer the question kilos cannot - <em>which</em>
 * product the customer refused.
 *
 * <p><b>Only for an order that travels whole on the trip.</b> A line's attempt is the quantity
 * ordered, and that is only what the truck carried when the whole order is on it. A share of a
 * split order (V37) has no per-line allocation to attempt against, so its refusal stays in kilos and
 * nothing is written per product.
 *
 * <p><b>Every result writes every line, except a partial one without detail.</b> A delivery hands
 * over everything, a rejection refuses everything, a failed attempt hands over nothing, and an
 * attempt that never happened attempted nothing - the lines follow from the result, so the
 * warehouse's proof of delivery and the claim draft see the same products whatever the result. A
 * partial delivery needs the person to say which products came back; one recorded without that
 * (kilos only, or an office correction of the receiver's name) leaves the lines already on file
 * alone rather than inventing a split. A correction overwrites: the table grants no DELETE, so all
 * the order's lines are written each time.
 *
 * <p>Pure: no repository, no clock. {@code TripDeliveryService} loads and writes.
 */
final class DeliveryLineResults {

    private DeliveryLineResults() {
    }

    /**
     * The line rows to write, or empty to leave the ones on file as they are.
     *
     * @param requested the refused products the caller sent, or null when it sent none
     * @param orderLines every line of the order, by line number
     * @param wholeOrder whether the whole order travels on this trip
     */
    static Optional<List<DeliveryLineWrite>> of(DeliveryResult result,
            List<DeliveryResultRequest.RefusedLineRequest> requested, List<OrderLineReference> orderLines,
            boolean wholeOrder) {
        if (requested != null && !wholeOrder) {
            throw new InvalidRequestException("This trip carries only part of the order, so a refusal is recorded"
                    + " in kilos, not per product.");
        }
        if (requested != null && result != DeliveryResult.PARTIAL) {
            throw new InvalidRequestException("Refused products are recorded only for a partial delivery: a"
                    + " rejection refuses every product and a delivery none.");
        }
        if (!wholeOrder || orderLines.isEmpty()) {
            return Optional.empty();
        }
        if (result == DeliveryResult.PARTIAL && requested == null) {
            return Optional.empty();
        }

        Map<UUID, DeliveryResultRequest.RefusedLineRequest> refusedByLine = refusedByLine(requested, orderLines);
        List<DeliveryLineWrite> writes = orderLines.stream().map(line -> {
            BigDecimal ordered = line.quantity();
            return switch (result) {
                case DELIVERED -> write(line, ordered, ordered, BigDecimal.ZERO, null);
                case REJECTED -> write(line, ordered, BigDecimal.ZERO, ordered, null);
                case FAILED -> write(line, ordered, BigDecimal.ZERO, BigDecimal.ZERO, null);
                case NOT_ATTEMPTED -> write(line, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, null);
                case PARTIAL -> {
                    DeliveryResultRequest.RefusedLineRequest refused = refusedByLine.get(line.id());
                    BigDecimal quantity = refused == null ? BigDecimal.ZERO : refused.quantity();
                    yield write(line, ordered, ordered.subtract(quantity), quantity,
                            refused == null ? null : blankToNull(refused.notes()));
                }
            };
        }).toList();
        return Optional.of(writes);
    }

    /**
     * The kilos and cubic metres of the refused products, from each line's unit weight and volume -
     * so a driver who says "10 of PLP-1601" does not also have to weigh them. Pallets are not
     * derivable from a unit count and come back zero. A product with no unit weight adds nothing,
     * which is why a caller that gets zero kilos back asks for them.
     */
    static OrderAmounts refusedAmounts(List<DeliveryResultRequest.RefusedLineRequest> requested,
            List<OrderLineReference> orderLines) {
        Map<UUID, DeliveryResultRequest.RefusedLineRequest> refusedByLine = refusedByLine(requested, orderLines);
        BigDecimal weight = BigDecimal.ZERO;
        BigDecimal volume = BigDecimal.ZERO;
        for (OrderLineReference line : orderLines) {
            DeliveryResultRequest.RefusedLineRequest refused = refusedByLine.get(line.id());
            if (refused == null) {
                continue;
            }
            if (line.unitWeightKg() != null) {
                weight = weight.add(line.unitWeightKg().multiply(refused.quantity()));
            }
            if (line.unitVolumeM3() != null) {
                volume = volume.add(line.unitVolumeM3().multiply(refused.quantity()));
            }
        }
        return new OrderAmounts(weight, volume, BigDecimal.ZERO);
    }

    /**
     * The request indexed by line, after the checks every refused product must pass: a line of this
     * order, named once, refused no more than was ordered - and something refused at all.
     */
    private static Map<UUID, DeliveryResultRequest.RefusedLineRequest> refusedByLine(
            List<DeliveryResultRequest.RefusedLineRequest> requested, List<OrderLineReference> orderLines) {
        Map<UUID, DeliveryResultRequest.RefusedLineRequest> byLine = new HashMap<>();
        if (requested == null) {
            return byLine;
        }
        Map<UUID, OrderLineReference> lines = new HashMap<>();
        orderLines.forEach(line -> lines.put(line.id(), line));
        Set<UUID> seen = new HashSet<>();
        boolean anyRefused = false;
        for (DeliveryResultRequest.RefusedLineRequest refused : requested) {
            OrderLineReference line = lines.get(refused.orderLineId());
            if (line == null) {
                throw new InvalidRequestException("A refused product is not a line of this order.");
            }
            if (!seen.add(line.id())) {
                throw new InvalidRequestException("Line " + line.lineNumber() + " (" + line.materialCode()
                        + ") is listed twice.");
            }
            if (refused.quantity().signum() < 0) {
                throw new InvalidRequestException("Refused quantities cannot be negative.");
            }
            if (refused.quantity().compareTo(line.quantity()) > 0) {
                throw new InvalidRequestException("More of " + line.materialCode() + " was refused ("
                        + plain(refused.quantity()) + ") than the order has (" + plain(line.quantity()) + " "
                        + line.uom() + ").");
            }
            anyRefused |= refused.quantity().signum() > 0;
            byLine.put(line.id(), refused);
        }
        if (!anyRefused) {
            throw new InvalidRequestException("A partial delivery refuses something: enter how many of which product"
                    + " came back.");
        }
        return byLine;
    }

    private static DeliveryLineWrite write(OrderLineReference line, BigDecimal attempted, BigDecimal delivered,
            BigDecimal refused, String notes) {
        return new DeliveryLineWrite(line.id(), line.uom(), attempted, delivered, refused, notes);
    }

    private static String plain(BigDecimal value) {
        return value.stripTrailingZeros().toPlainString();
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
