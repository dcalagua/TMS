/**
 * In-app operational alerts: the module that owns {@code tms.notification} (migration V32).
 *
 * <p>It stores what other modules raise and serves it to the bell in the top bar. It contains no
 * business rule of its own beyond visibility and acknowledgement - deciding <em>that</em> a
 * shipment is late or a licence is running out belongs to the module that owns the fact, and the
 * only way that module reaches this one is
 * {@link com.ebim.tms.shared.notification.NotificationPublisher}.
 *
 * <p>Deliberately not the audit trail and deliberately not the outbox: see the head of
 * {@code V32__notification.sql} for what each of the three answers and why one table cannot.
 */
package com.ebim.tms.notification;
