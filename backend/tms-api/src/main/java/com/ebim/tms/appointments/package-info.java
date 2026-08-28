/**
 * Dock and appointment scheduling (migration V41).
 *
 * <p>A shipment knows where it is going and roughly how long it will take to serve. What it could
 * not say is <b>when the door is free</b>, and every operation running more than a few trucks a day
 * solves that with a booking sheet outside the system. The cost of it being outside is the reason
 * this module exists: two trucks arrive at one door at 09:00, one of them waits two hours, and the
 * TMS that planned both had no way to know.
 *
 * <h2>The one invariant that matters</h2>
 *
 * <p><b>One vehicle per door at a time, guaranteed by the database.</b>
 * {@code ex_appointment_no_double_booking} is an {@code EXCLUDE ... USING gist} constraint, because
 * what is being refused is an <em>overlap</em> and no unique key can express that. Two dispatchers
 * booking 09:00 on the same door in the same instant both see a free door in their own snapshot;
 * the constraint is the one place they cannot both get past, and the service turns the loser's
 * violation into a sentence about the dock board rather than a 500.
 *
 * <p>That is also why a door takes <em>one</em> vehicle and a site with six doors has six rows.
 * PostgreSQL cannot refuse "more than N overlapping", so a capacity column would move the invariant
 * back into application code - which is precisely where the booking sheet already fails.
 *
 * <h2>Time</h2>
 *
 * <p>Windows are absolute instants. The <b>location's</b> zone is used for two things only: reading
 * the door's local opening hours, and displaying a booking. Never the server's - a dock in Arequipa
 * opens at 07:00 in Arequipa whatever the application runs on, and a deployment that moved regions
 * would otherwise silently move every site's hours.
 *
 * <h2>The WMS boundary</h2>
 *
 * <p>This module owns the <em>transport</em> side of a dock appointment: which shipment is coming,
 * when, to which door. A warehouse's own dock schedule - labour, equipment, put-away - is a
 * different system's record, and this one integrates with it through a port when somebody asks,
 * <b>never by sharing a table</b>. V41 creates no WMS or EWM column, foreign key or view, and the
 * adapter that will one day translate between them belongs in {@code integration}, not here. Mixing
 * the two databases is the shortcut that makes both systems unable to be upgraded separately.
 */
package com.ebim.tms.appointments;
