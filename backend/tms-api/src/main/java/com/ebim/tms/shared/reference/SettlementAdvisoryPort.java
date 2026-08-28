package com.ebim.tms.shared.reference;

import java.math.BigDecimal;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

/**
 * Open money questions on given shipments, for the control tower to <em>mention</em> (JOB 23).
 *
 * <p><b>Read-only, and deliberately so.</b> A freight discrepancy's state lives in
 * {@code tms.freight_discrepancy} (V46) and moves through the settlement screens, where somebody
 * with {@code settlement.invoice:match} accepts or rejects it. The control tower shows that one
 * exists and links to it. It does not store it, restate it, or offer to close it. Copying the state
 * would be two records of one dispute, drifting apart the first time somebody resolved it on the
 * screen that does not write back.
 *
 * <p><b>Takes trip ids and not a date</b>, which is the shape that keeps the modules apart.
 * Settlement has no idea what an operating day is - that is planning's concept, and a query here
 * joining {@code Trip} to find out would be a cross-module dependency hidden inside a string, where
 * ArchUnit cannot see it. The caller already holds the day's shipments; it passes them in.
 */
public interface SettlementAdvisoryPort {

    /**
     * Open discrepancies against any of {@code tripIds}, newest first.
     *
     * <p>Empty when settlement has nothing to say, which is the normal case and not a failure.
     */
    List<SettlementAdvisory> findOpenDiscrepancies(UUID companyId, Collection<UUID> tripIds, int limit);

    /** How many there are in total, so a capped panel can say how deep the list goes. */
    long countOpenDiscrepancies(UUID companyId, Collection<UUID> tripIds);

    /**
     * @param differenceAmount invoiced minus expected, or <b>null when either side is unknown</b> -
     *                         V46's own rule, carried through rather than zeroed on the way out
     */
    record SettlementAdvisory(
            UUID discrepancyId,
            UUID carrierInvoiceId,
            String invoiceNumber,
            UUID tripId,
            String type,
            BigDecimal differenceAmount,
            String currency,
            String detail) {
    }
}
