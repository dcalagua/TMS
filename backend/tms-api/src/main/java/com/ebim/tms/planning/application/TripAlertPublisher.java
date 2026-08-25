package com.ebim.tms.planning.application;

import com.ebim.tms.planning.domain.DepartureDelay;
import com.ebim.tms.planning.domain.DepartureTimeliness;
import com.ebim.tms.planning.domain.OrderDelivery;
import com.ebim.tms.planning.domain.Trip;
import com.ebim.tms.planning.domain.TripException;
import com.ebim.tms.planning.domain.TripStop;
import com.ebim.tms.planning.domain.TripTender;
import com.ebim.tms.shared.notification.NotificationPublisher;
import com.ebim.tms.shared.notification.NotificationRequest;
import com.ebim.tms.shared.notification.NotificationType;
import com.ebim.tms.shared.reference.DriverLicenseStatus;
import com.ebim.tms.shared.reference.DriverReference;
import com.ebim.tms.shared.security.CompanyScope;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * Every in-app alert this module raises, composed in one place (migration V32).
 *
 * <p>The services keep the rules; this keeps the <em>wording contract</em>. Each method turns a
 * typed domain fact into the three things an alert is made of - which type, what makes it one fact
 * ({@code dedupeKey}), and the placeholders its sentence needs - and nothing else in
 * {@code planning} composes any of the three. That matters more here than the usual "don't repeat
 * yourself": a dedupe key computed one way when the alert is raised and another way when it is
 * resolved produces a bell that never clears, and it produces it silently. Keeping both sides of
 * every pair in one file is what makes that reviewable.
 *
 * <p><b>It is a thin front on {@link NotificationPublisher} and adds no transaction of its own.</b>
 * Every call runs in the caller's transaction, so an alert rolls back with the fact it announces -
 * see the port's own contract, including the guarantee that a raise can never be the reason the
 * business change fails to commit.
 *
 * <p><b>Placeholders only, never sentences.</b> The maps below hold a shipment number, a count of
 * minutes, a stop sequence - the arguments the frontend's {@code notifications} namespace
 * interpolates. Nothing here renders text, in any language, and nothing here carries a business
 * detail the entity's own table already holds. Migration V32 section 2 says why.
 */
@Component
public class TripAlertPublisher {

    private final NotificationPublisher notifications;

    public TripAlertPublisher(NotificationPublisher notifications) {
        this.notifications = notifications;
    }

    /**
     * The shipment left later than planned.
     *
     * <p>Judged with {@link DepartureDelay}, the same pure rule the control tower reports with, so
     * the bell and the board can never disagree about what "late" means. Only
     * {@link DepartureTimeliness#LATE} raises anything: {@code OVERDUE} - planned time gone, still
     * in the yard - has no event to hang on without a scheduler and stays the control tower's live
     * question (V32 section 6).
     *
     * <p>Keyed on the trip, so a dispatch retried after a timeout rings once.
     */
    public void departed(CompanyScope scope, Trip trip, OffsetDateTime departedAt) {
        DepartureDelay delay = DepartureDelay.of(
                trip.status(), trip.plannedDepartureAt(), trip.actualDepartureAt(), departedAt);
        if (delay.timeliness() != DepartureTimeliness.LATE) {
            return;
        }
        Map<String, Object> args = shipmentArgs(trip);
        args.put("minutes", delay.minutes());
        raise(scope, NotificationType.TRIP_DELAYED, trip, NotificationType.TRIP_DELAYED.dedupeKey(trip.id()),
                departedAt, args);
    }

    /** The shipment is closed out. The one informational alert - see {@link NotificationType}. */
    public void completed(CompanyScope scope, Trip trip, OffsetDateTime completedAt) {
        raise(scope, NotificationType.TRIP_COMPLETED, trip,
                NotificationType.TRIP_COMPLETED.dedupeKey(trip.id()), completedAt, shipmentArgs(trip));
    }

    /**
     * A problem was opened on the shipment - reported by a dispatcher, or opened automatically
     * because a stop was skipped or failed.
     *
     * <p>Keyed on the <em>exception</em> and not on the trip: a day with a breakdown and two refused
     * docks is three problems, and folding them into one alert would hide two of them. The alert
     * still points at the trip, because that is where somebody goes to deal with it.
     *
     * @param stop the stop the problem is about, or null when it is the trip's
     */
    public void exceptionOpened(CompanyScope scope, Trip trip, TripException exception, TripStop stop) {
        Map<String, Object> args = shipmentArgs(trip);
        args.put("exceptionType", exception.exceptionType().name());
        if (stop != null) {
            args.put("stopSequence", stop.sequence());
        }
        raise(scope, NotificationType.EXCEPTION_OPENED, trip,
                NotificationType.EXCEPTION_OPENED.dedupeKey(exception.id()), exception.reportedAt(), args);
    }

    /**
     * The problem was closed out, so the alert it raised is no longer outstanding.
     *
     * <p>The other half of {@link #exceptionOpened}, and the reason both live in this class. Not a
     * delete: the alert stays on the board with its resolution time, because "this happened and was
     * dealt with" is a more useful statement than silence.
     */
    public void exceptionResolved(CompanyScope scope, TripException exception, OffsetDateTime resolvedAt) {
        notifications.resolve(scope, NotificationType.EXCEPTION_OPENED.dedupeKey(exception.id()), resolvedAt);
    }

    /**
     * The carrier said no, or never answered before the deadline.
     *
     * <p>Keyed on the tender and not on the trip, so attempt 2's rejection is its own alert - a
     * shipment refused twice is a harder problem than one refused once, and a bell that said so only
     * the first time would be reporting the opposite.
     *
     * @param type {@link NotificationType#TENDER_REJECTED} or
     *     {@link NotificationType#TENDER_EXPIRED} - the caller has just decided which, and deriving
     *     it again here from the tender's status would be a second encoding of that decision
     */
    public void tenderRefused(CompanyScope scope, Trip trip, TripTender tender, NotificationType type,
            OffsetDateTime occurredAt) {
        Map<String, Object> args = shipmentArgs(trip);
        args.put("attempt", tender.attempt());
        raise(scope, type, trip, type.dedupeKey(tender.id()), occurredAt, args);
    }

    /**
     * What the customer actually got, when it was not everything.
     *
     * <p>Both directions, deliberately. A shortfall raises the alert; a result later corrected to
     * {@code DELIVERED} resolves it, because a delivery record is edited in place (V28) and a bell
     * still showing a failure somebody has since fixed is worse than no bell. {@code NOT_ATTEMPTED}
     * resolves it too and raises nothing of its own: its stop was skipped or failed and has already
     * raised {@link NotificationType#EXCEPTION_OPENED}, so alerting again would report one fact
     * twice.
     *
     * @param orderNumber the order that fell short - the one thing a dispatcher needs before they
     *     pick up the phone, and not derivable from the delivery row alone
     */
    public void deliveryRecorded(CompanyScope scope, Trip trip, TripStop stop, OrderDelivery delivery,
            String orderNumber, OffsetDateTime occurredAt) {
        String dedupeKey = NotificationType.DELIVERY_FAILED.dedupeKey(delivery.id());
        if (!delivery.result().isShortfall()) {
            notifications.resolve(scope, dedupeKey, occurredAt);
            return;
        }
        Map<String, Object> args = shipmentArgs(trip);
        args.put("orderNumber", orderNumber);
        args.put("result", delivery.result().name());
        args.put("stopSequence", stop.sequence());
        raise(scope, NotificationType.DELIVERY_FAILED, trip, dedupeKey, occurredAt, args);
    }

    /**
     * A driver was put on a shipment with a licence that runs out inside
     * {@link DriverLicenseStatus#EXPIRY_WARNING_DAYS}.
     *
     * <p>Raised at assignment, which is the moment somebody can still choose somebody else. An
     * expired licence is not alerted at all - {@code TripService} refuses the assignment outright,
     * and an alert about something that did not happen is noise.
     *
     * <p>Keyed on the driver <em>and their expiry date</em>: planning the same person onto six trips
     * this week is one warning, while a renewed licence that later runs down again is a new one.
     * The alert points at the driver rather than at the trip, because renewing a licence is a fleet
     * job and the shipment is only where it surfaced.
     */
    public void driverAssigned(CompanyScope scope, Trip trip, DriverReference driver, OffsetDateTime occurredAt) {
        if (driver.licenseStatusOn(trip.planningDate()) != DriverLicenseStatus.EXPIRING_SOON) {
            return;
        }
        Map<String, Object> args = new LinkedHashMap<>();
        args.put("driverName", driver.fullName());
        args.put("expiresOn", driver.licenseExpiresOn().toString());
        args.put("shipmentNumber", trip.shipmentNumber());
        notifications.raise(scope, new NotificationRequest(
                NotificationType.DRIVER_LICENSE_EXPIRING,
                driver.id(),
                driver.code(),
                NotificationType.DRIVER_LICENSE_EXPIRING.dedupeKey(
                        driver.id(), driver.licenseExpiresOn().toString()),
                occurredAt,
                args));
    }

    /** Every trip-shaped alert names the shipment, and names it the way the outside world does. */
    private static Map<String, Object> shipmentArgs(Trip trip) {
        Map<String, Object> args = new LinkedHashMap<>();
        args.put("shipmentNumber", trip.shipmentNumber());
        return args;
    }

    private void raise(CompanyScope scope, NotificationType type, Trip trip, String dedupeKey,
            OffsetDateTime occurredAt, Map<String, Object> args) {
        notifications.raise(scope, new NotificationRequest(
                type, trip.id(), trip.shipmentNumber(), dedupeKey, occurredAt, args));
    }
}
