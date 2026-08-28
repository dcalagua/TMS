package com.ebim.tms.planning.domain;

import java.time.Duration;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;

/**
 * When the vehicle reaches each stop, given when it leaves and how long each leg takes
 * (migration V43, ADR-011).
 *
 * <p><b>A pure function.</b> No repository, no clock, no randomness - the same shape the planning
 * engines have, and for the same reason: an arrival time that cannot be reproduced from its inputs
 * cannot be defended when somebody disputes it. Everything this needs is a parameter.
 *
 * <p>The arithmetic is not the interesting part. These three rules are.
 *
 * <h2>1. An unmeasurable leg ends the chain</h2>
 * A stop whose incoming leg has no travel time gets <b>no estimate at all</b>, and neither does any
 * stop after it. Not a guess, not the previous stop's time, not zero.
 *
 * <p>This is the most important rule here. A schedule that silently absorbed one missing leg would
 * show eight plausible arrival times of which five are wrong, and nothing on the board would say
 * which five. A visible gap looks worse and is true.
 *
 * <h2>2. Provenance degrades and never upgrades</h2>
 * One straight-line leg makes every stop after it {@link EtaSource#FALLBACK}. A later measured leg
 * does not repair the estimate it was added to - see {@link EtaSource}.
 *
 * <h2>3. A window is never made to fit</h2>
 * Arriving before the window opens means <em>waiting</em>: the truck is there, the site is not
 * open, and the next leg does not start until it is. Arriving after the window closes is reported
 * as computed, with {@link StopSchedule#missesWindow()} raised. The engine never moves an arrival
 * to make a window work, because that turns a schedule that does not work into one that appears to.
 */
public final class StopScheduleEngine {

    private StopScheduleEngine() {
    }

    /**
     * One leg of the run, as the engine needs it.
     *
     * @param sequence       the stop this leg arrives at
     * @param travelMinutes  driving time to it, or null when the leg could not be measured
     * @param source         what that leg was measured over. Ignored when {@code travelMinutes} is
     *                       null
     * @param serviceMinutes how long the vehicle spends at the stop. Zero is a legitimate value -
     *                       a site that configured nothing has said nothing, not "instant"
     * @param windowStart    the earliest the site will receive, in its own local time, or null
     * @param windowEnd      the latest, or null. V11 stores the pair together or not at all
     */
    public record Leg(
            int sequence,
            Long travelMinutes,
            EtaSource source,
            int serviceMinutes,
            LocalTime windowStart,
            LocalTime windowEnd) {
    }

    /**
     * Walks the run in the planner's own sequence.
     *
     * @param departureAt when the vehicle leaves the origin. Never null - a shipment with no
     *                    planned departure has no schedule, and the caller is the one who knows
     *                    that
     * @param zone        the zone the service windows are written in. A window is a wall-clock
     *                    time at the site, and comparing it to an instant needs a day and a place;
     *                    this is the place
     * @return one entry per leg, in order, some possibly {@link StopSchedule#unscheduled(int)}
     */
    public static List<StopSchedule> schedule(OffsetDateTime departureAt, ZoneId zone, List<Leg> legs) {
        List<StopSchedule> schedule = new ArrayList<>(legs.size());
        OffsetDateTime cursor = departureAt;
        EtaSource carried = EtaSource.MEASURED_ROUTE;
        boolean broken = false;

        for (Leg leg : legs) {
            if (broken || leg.travelMinutes() == null) {
                // Rule 1. Once the chain is broken it stays broken: the vehicle's position in time
                // is unknown from here on, and every later stop inherits that and not a number.
                broken = true;
                schedule.add(StopSchedule.unscheduled(leg.sequence()));
                continue;
            }

            carried = carried.degradedWith(leg.source() == null ? EtaSource.FALLBACK : leg.source());
            OffsetDateTime arrival = cursor.plusMinutes(leg.travelMinutes());

            long wait = 0;
            OffsetDateTime serviceStart = arrival;
            if (leg.windowStart() != null) {
                OffsetDateTime opens = at(arrival, zone, leg.windowStart());
                if (arrival.isBefore(opens)) {
                    // Rule 3, first half. The truck is there and the site is not open.
                    wait = Duration.between(arrival, opens).toMinutes();
                    serviceStart = opens;
                }
            }

            boolean missesWindow = false;
            if (leg.windowEnd() != null) {
                // Judged on the ARRIVAL and not on the wait-adjusted start: a vehicle that turns up
                // after closing has missed the window, and waiting until tomorrow morning is not
                // what the schedule means.
                missesWindow = arrival.isAfter(at(arrival, zone, leg.windowEnd()));
            }

            OffsetDateTime departure = serviceStart.plusMinutes(leg.serviceMinutes());
            schedule.add(new StopSchedule(leg.sequence(), arrival, departure, carried, missesWindow, wait));
            cursor = departure;
        }

        return List.copyOf(schedule);
    }

    /**
     * A wall-clock time at the site, on the day the vehicle gets there.
     *
     * <p>Resolved against the arrival's own local date rather than the shipment's planning date: a
     * run that starts at 22:00 and reaches its third stop after midnight is comparing against that
     * stop's <em>next</em> morning, and using the planning date would put the window a day behind
     * the truck.
     */
    private static OffsetDateTime at(OffsetDateTime arrival, ZoneId zone, LocalTime localTime) {
        return arrival.atZoneSameInstant(zone).toLocalDate().atTime(localTime).atZone(zone).toOffsetDateTime();
    }
}
