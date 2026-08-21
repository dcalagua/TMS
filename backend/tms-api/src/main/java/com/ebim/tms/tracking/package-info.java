/**
 * Where the vehicles are - a provider-agnostic position feed (migration V29).
 *
 * <p>This module exists so that TMS can sell tracking without buying a telematics vendor. It owns
 * the normalised contract, the storage, the sampling policy and the read; it owns no vendor code at
 * all, and the two ports are where a vendor would attach:
 *
 * <ul>
 *   <li><b>Push</b> - {@code shared.reference.TrackingIntakePort}, implemented here, called by
 *       {@code integration}'s machine-to-machine endpoint. This is the half that works today: a
 *       provider (or a customer's own middleware) posts positions against shipment numbers with a
 *       credential holding {@code integration.tracking:write}.</li>
 *   <li><b>Pull</b> - {@code domain.TrackingProviderPort}, whose only implementation refuses
 *       politely. This is the half with no vendor behind it, and the interface exists so that the
 *       first vendor to arrive implements an interface instead of becoming one (ADR-007).</li>
 * </ul>
 *
 * <h2>What tracking is not allowed to do</h2>
 *
 * <p>Nothing in TMS reads {@code tms.tracking_position} except the screen that draws it. No status
 * is derived from a position, no stop is closed by one, no exception is opened by one and no
 * timeline entry is written for one. That restraint is the module's main design decision: a vehicle
 * standing at a customer's gate and a vehicle standing in traffic outside it produce the same
 * point, and a lifecycle that moved on a GPS reading would shift accountability for the delivery
 * record from the person who reported it to a box on a windscreen. Positions inform people;
 * people record facts.
 *
 * <p>The consequence is worth stating plainly, because it is what makes this module safe to run:
 * losing this table entirely would cost a map and no business fact.
 *
 * <h2>Volume</h2>
 *
 * <p>The one table here is by design the largest in the schema, and the sampling rule in
 * {@code application.TrackingIngestionService} - one point per configured interval per (shipment,
 * feed) - is what keeps its size a function of the fleet rather than of a vendor's default push
 * rate. See {@code docs/domain/TRACKING_V1.md}, "Volume and retention".
 */
package com.ebim.tms.tracking;
