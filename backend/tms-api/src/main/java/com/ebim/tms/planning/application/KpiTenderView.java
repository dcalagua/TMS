package com.ebim.tms.planning.application;

import java.math.BigDecimal;

/**
 * Whether carriers took the loads they were offered
 * ({@code docs/domain/KPIS_REPORTING_V1.md}, section "Tendering").
 *
 * <p><b>Counted in attempts, not in shipments.</b> A shipment refused by two carriers and taken by
 * a third is three attempts and one shipment; an acceptance rate over shipments would report that
 * as 100% and hide the two refusals, which are the whole reason anybody looks at this. See
 * {@code TripTenderRepository.countByStatusForRange}.
 *
 * <p><b>Null, not zero, when the caller may not be told.</b> The whole record is absent from the
 * report for a caller without {@code planning.tender:read}, which is the permission that already
 * decides who may see what a load was offered at - see {@code KpiService}.
 *
 * @param attempts          every offer made on the range's shipments, in any state
 * @param accepted          the carrier took it. At most one per shipment, ever
 * @param rejected          the carrier said no, with a reason
 * @param expired           the deadline passed with no answer. A floor rather than an exact figure:
 *                          expiry is applied when a tender is next touched, so an offer that lapsed
 *                          this morning may still be sitting in {@code awaitingResponse} - migration
 *                          V31 section 1b explains why there is no sweep
 * @param cancelled         the shipper withdrew the offer
 * @param awaitingResponse  sent and not yet answered
 * @param draft             prepared and not yet offered
 * @param answered          {@code accepted + rejected} - the denominator of the two rates below,
 *                          and the only honest one: an offer nobody has answered yet is not a
 *                          refusal, and one that lapsed is not a decision the carrier made
 * @param acceptancePercent {@code accepted / answered}, or null when nothing has been answered
 * @param rejectionPercent  {@code rejected / answered}, or null likewise. Sent rather than left to
 *                          the client to subtract, so the two cannot round to something other than
 *                          100 on the screen
 */
public record KpiTenderView(
        long attempts,
        long accepted,
        long rejected,
        long expired,
        long cancelled,
        long awaitingResponse,
        long draft,
        long answered,
        BigDecimal acceptancePercent,
        BigDecimal rejectionPercent) {
}
