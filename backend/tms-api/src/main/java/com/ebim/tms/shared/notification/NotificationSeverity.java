package com.ebim.tms.shared.notification;

/**
 * How much an alert is asking for (migration V32).
 *
 * <p>Three values and no numeric scale. A number invites arithmetic - "show me everything above
 * 5" - and there is nothing to compute: an operator reads a colour and decides whether to act
 * now, today, or not at all.
 *
 * <p>It is a property of {@link NotificationType} rather than a column somebody tunes per company.
 * A per-tenant severity policy is a configuration product; no customer has asked for one, and a
 * severity that varies by installation would make every screenshot in a support ticket ambiguous.
 */
public enum NotificationSeverity {

    /** Something finished the way it was meant to. Worth knowing, never worth interrupting for. */
    INFO,

    /** Something is off plan and a person should look at it today. */
    WARNING,

    /**
     * A customer did not get their goods. The only level that says "somebody has to make a phone
     * call", which is why exactly one type carries it.
     */
    CRITICAL
}
