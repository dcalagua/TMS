package com.ebim.tms.shared.notification;

import com.ebim.tms.shared.security.Permission;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.Set;
import java.util.UUID;

/**
 * The seven operational facts TMS raises an in-app alert for (migration V32), each with the
 * severity it is always shown at and the permission that gates being told about it.
 *
 * <p><b>Every value has a real source.</b> Each one is written by a business transaction that was
 * going to happen anyway - a dispatch, a stop outcome, a tender response, a driver assignment - and
 * none of them needs a scheduler, because nothing in this installation runs on a timer (V31 section
 * 1b). That constraint is what shaped the list: {@link #TRIP_DELAYED} is raised when a shipment
 * <em>departs</em> late and not while it sits in the yard past its time, because the second has no
 * event to hang on. The control tower answers that one live, on every read, which is where a
 * question with no source belongs.
 *
 * <p><b>Severity is fixed here, not passed by the raiser.</b> A caller that could choose would
 * eventually choose differently at two call sites for one fact, and the panel's colours would stop
 * meaning anything. {@link #DELIVERY_FAILED} is the only {@link NotificationSeverity#CRITICAL} one:
 * it is the only type that says a customer did not get their goods.
 *
 * <p><b>The permission is the disclosure control</b>, not a gate on the panel. See
 * {@link #requiredPermission()} and migration V32 section 4.
 *
 * <p>Lives in {@code shared.notification} and not in {@code com.ebim.tms.notification.domain}
 * because it is the vocabulary three modules have to agree in: {@code planning} and {@code fleet}
 * raise these, {@code notification} stores and serves them, and {@code ModuleBoundaryTest} forbids
 * any of the three from importing another.
 */
public enum NotificationType {

    /**
     * A shipment left later than it was planned to. Raised at dispatch, from the same
     * {@code DepartureDelay} rule the control tower reports with, so the two can never disagree
     * about what "late" means.
     */
    TRIP_DELAYED(NotificationSeverity.WARNING, NotificationEntityType.TRIP,
            Permission.MONITORING_TRANSPORT_READ),

    /**
     * A problem was opened on a trip - reported directly, or opened automatically because a stop
     * was skipped or failed. Resolved (never deleted) when the problem is closed out - one of the
     * two types whose alert cleans itself up.
     */
    EXCEPTION_OPENED(NotificationSeverity.WARNING, NotificationEntityType.TRIP,
            Permission.MONITORING_TRANSPORT_READ),

    /** A carrier said no. The shipment still needs placing, which is what makes it actionable. */
    TENDER_REJECTED(NotificationSeverity.WARNING, NotificationEntityType.TRIP,
            Permission.PLANNING_TENDER_READ),

    /** A carrier never answered and the deadline passed. Same action, worse silence. */
    TENDER_EXPIRED(NotificationSeverity.WARNING, NotificationEntityType.TRIP,
            Permission.PLANNING_TENDER_READ),

    /**
     * A driver was put on a shipment with a licence that runs out within
     * {@code DriverLicenseStatus.EXPIRY_WARNING_DAYS}. Raised at assignment - the moment somebody
     * can still choose a different driver - rather than by a nightly sweep over the master, which
     * would need the scheduler this installation does not have.
     */
    DRIVER_LICENSE_EXPIRING(NotificationSeverity.WARNING, NotificationEntityType.DRIVER,
            Permission.FLEET_DRIVER_READ),

    /**
     * A shipment finished. The one {@link NotificationSeverity#INFO} type, and it earns its place
     * for a reason that is not "good news is nice": customer service is regularly asked whether a
     * load is closed, and the alternative is refreshing a board.
     */
    TRIP_COMPLETED(NotificationSeverity.INFO, NotificationEntityType.TRIP,
            Permission.MONITORING_TRANSPORT_READ),

    /**
     * Goods did not reach the customer in full: refused, partially taken, or the attempt failed.
     * Not raised for {@code NOT_ATTEMPTED}, whose stop was skipped or failed and has already raised
     * {@link #EXCEPTION_OPENED} - one fact, one alert.
     *
     * <p>The other resolvable type. A delivery record is corrected in place (migration V28), so a
     * result later fixed to {@code DELIVERED} resolves the alert it raised - a bell still showing a
     * failure somebody has since sorted out is worse than no bell.
     */
    DELIVERY_FAILED(NotificationSeverity.CRITICAL, NotificationEntityType.TRIP,
            Permission.MONITORING_TRANSPORT_READ);

    private final NotificationSeverity severity;
    private final NotificationEntityType entityType;
    private final Permission requiredPermission;

    NotificationType(NotificationSeverity severity, NotificationEntityType entityType,
            Permission requiredPermission) {
        this.severity = severity;
        this.entityType = entityType;
        this.requiredPermission = requiredPermission;
    }

    public NotificationSeverity severity() {
        return severity;
    }

    /** What this alert is about, and therefore where the panel navigates. */
    public NotificationEntityType entityType() {
        return entityType;
    }

    /**
     * The permission a caller must hold to be told about this type at all.
     *
     * <p>Not a gate on the notifications endpoint - that one is open to any authenticated member,
     * because the bell is always on screen and answering 403 to a control nobody can hide would be
     * a worse experience than an empty panel. This is a <em>disclosure</em> control: a licence
     * expiry names a person and their document, a tender outcome is commercial, and neither should
     * reach an account that could not open the screen it came from.
     *
     * <p>Reuses permissions that already exist and already mean the thing being disclosed, for the
     * reason {@code ControlTowerController} gives about {@code monitoring.transport:read}: a new
     * one would give every installation another grant to make before a bell worked.
     */
    public Permission requiredPermission() {
        return requiredPermission;
    }

    /**
     * The types a caller holding {@code held} may be told about - possibly none, which is a legal
     * and quiet outcome.
     */
    public static Set<NotificationType> visibleTo(Set<Permission> held) {
        EnumSet<NotificationType> visible = EnumSet.noneOf(NotificationType.class);
        Arrays.stream(values())
                .filter(type -> held.contains(type.requiredPermission))
                .forEach(visible::add);
        return visible;
    }

    /**
     * The {@code dedupe_key} for an alert keyed on one row.
     *
     * <p>Composed here rather than at each call site so that raising an alert and resolving it
     * later cannot compute the key two different ways - the failure mode where the bell never
     * clears. See {@code uq_notification_company_dedupe}.
     *
     * @param keyId whatever makes this fact <em>one</em> fact: the exception's id for
     *     {@link #EXCEPTION_OPENED}, the trip's for {@link #TRIP_DELAYED}, the delivery's for
     *     {@link #DELIVERY_FAILED}
     */
    public String dedupeKey(UUID keyId) {
        return name() + ":" + keyId;
    }

    /**
     * {@link #dedupeKey(UUID)} for a fact that can legitimately recur on the same row.
     *
     * @param discriminator what makes the recurrence a new fact rather than the same one - the
     *     licence expiry date for {@link #DRIVER_LICENSE_EXPIRING}, so renewing a licence and then
     *     letting the new one run down alerts again, while planning the same driver onto six trips
     *     this week does not
     */
    public String dedupeKey(UUID keyId, String discriminator) {
        return name() + ":" + keyId + ":" + discriminator;
    }
}
