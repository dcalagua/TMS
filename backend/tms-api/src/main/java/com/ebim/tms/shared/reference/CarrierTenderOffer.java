package com.ebim.tms.shared.reference;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;

/**
 * One offer as <em>the carrier</em> sees it, carrying no {@code planning} type so that
 * {@code com.ebim.tms.integration} can host the tender endpoint without depending on the module
 * that owns tendering (migration V31). Assembled by
 * {@code planning.infrastructure.CarrierTenderAdapter}, the only producer.
 *
 * <p><b>What is deliberately absent is the design.</b> This is the one read in TMS whose audience is
 * outside the company, so it carries the smallest set of facts a haulier needs in order to answer
 * yes or no, and nothing that would tell them about the shipper's other business:
 *
 * <ul>
 *   <li>no vehicle, no licence plate and no driver - the shipper planned a truck onto this
 *       shipment, but who the carrier sends is the carrier's decision and their personnel data is
 *       not this API's to echo back;</li>
 *   <li>no capacity figures, no order numbers and no customer names - the destinations a load is
 *       going to are the shipper's commercial relationships, and a carrier learns them when they
 *       accept and get the manifest, not while they are deciding;</li>
 *   <li>no TMS uuid anywhere. A shipment is named by its number, exactly as
 *       {@link TrackingIntakePort} names one, so a carrier integration never has to keep a mapping
 *       table of our primary keys.</li>
 * </ul>
 *
 * <p>What is left is what an offer is: which shipment, when it runs, from where, how big the job is
 * ({@code stopCount}), what is being paid, and by when they must answer.
 *
 * @param status any {@code planning.domain.TenderStatus}, already resolved against the deadline -
 *     a lapsed offer reports {@code "EXPIRED"} whatever the column says, so a carrier can never be
 *     shown an offer as answerable that TMS would refuse to accept
 * @param originName the depot the shipment leaves from. The carrier has to send a truck there, so
 *     it is the one master reference this record does carry
 * @param stopCount how many deliveries the run has - the coarsest honest measure of the job, and
 *     the only one that does not disclose who the deliveries are to
 * @param respondedAt null while the offer is open; set once, and never rewritten
 */
public record CarrierTenderOffer(
        String shipmentNumber,
        int attempt,
        String status,
        LocalDate planningDate,
        OffsetDateTime plannedDepartureAt,
        String originCode,
        String originName,
        int stopCount,
        BigDecimal offeredAmount,
        String currency,
        String notes,
        OffsetDateTime sentAt,
        OffsetDateTime expiresAt,
        OffsetDateTime respondedAt,
        String responseNotes) {
}
