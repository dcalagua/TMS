package com.ebim.tms.notification.application;

import java.util.List;

/**
 * What the bell needs in one round trip: the badge and the panel behind it.
 *
 * <p>Two fields rather than two endpoints because they are read together, always - the badge is
 * never shown without the panel being one click away, and splitting them would make every refresh
 * two requests to render one control. It is the opposite decision to
 * {@code ControlTowerController}'s two endpoints, and for the opposite reason: those two refresh on
 * different triggers, these two never do.
 *
 * @param unreadCount unread alerts <em>of the types this caller may see</em>, over the whole
 *     history and not only over {@link #notifications}. A badge that counted the page would say
 *     "20" forever on a desk that had let a hundred pile up
 * @param notifications the most recent alerts, newest first, read and unread together. Capped - see
 *     {@code NotificationService.MAX_FEED_SIZE}. Read ones are kept in the list on purpose: the
 *     panel is the last thing that happened, not an inbox that empties
 */
public record NotificationFeedView(long unreadCount, List<NotificationView> notifications) {
}
