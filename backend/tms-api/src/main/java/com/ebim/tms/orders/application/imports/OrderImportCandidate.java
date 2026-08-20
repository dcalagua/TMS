package com.ebim.tms.orders.application.imports;

import com.ebim.tms.orders.domain.DeclaredTotals;
import com.ebim.tms.orders.domain.OrderLineInput;
import com.ebim.tms.orders.domain.OrderPriority;
import com.ebim.tms.shared.reference.MasterReference;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

/**
 * One order as a validated file describes it: the rows of a single external reference, resolved
 * into exactly the arguments {@code OrderService}'s domain objects take.
 *
 * <p>A candidate exists whether or not it will be written - {@link #outcome()} says which - so
 * that the dry-run preview and the apply pass are built from the same objects and cannot
 * disagree about what a row meant.
 *
 * @param origin      the resolved ship-from master; {@code null} only on a {@code REJECTED}
 *                    candidate, where the code did not resolve
 * @param destination the resolved ship-to master, same caveat
 */
public record OrderImportCandidate(
        String externalReference,
        OrderImportReport.Outcome outcome,
        int firstRowNumber,
        MasterReference origin,
        MasterReference destination,
        String customerName,
        String customerReference,
        LocalDate serviceDate,
        OrderPriority priority,
        LocalTime windowStart,
        LocalTime windowEnd,
        DeclaredTotals declaredTotals,
        List<OrderLineInput> lines) {

    public OrderImportCandidate {
        lines = List.copyOf(lines);
    }

    /** True when this candidate is one the apply pass should actually insert. */
    public boolean isCreatable() {
        return outcome == OrderImportReport.Outcome.CREATE;
    }

    /** The same candidate with a different outcome - used when a later pass reclassifies it. */
    public OrderImportCandidate withOutcome(OrderImportReport.Outcome newOutcome) {
        return new OrderImportCandidate(externalReference, newOutcome, firstRowNumber, origin, destination,
                customerName, customerReference, serviceDate, priority, windowStart, windowEnd, declaredTotals, lines);
    }
}
