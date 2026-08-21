import { apiRequest } from './httpClient'

/**
 * Mirrors the backend's `NotificationType` (migration V32).
 *
 * Each value is also a translation key: `notifications:types.<TYPE>.title` and `.message`. That is
 * the whole reason the API sends a type and arguments instead of a sentence - a rendered message
 * would arrive in whichever language the server felt like, and would still be in it after the
 * operator switched. See `docs/domain/ALERTS_NOTIFICATIONS_V1.md` section 4.
 */
export const NOTIFICATION_TYPES = [
  'TRIP_DELAYED',
  'EXCEPTION_OPENED',
  'TENDER_REJECTED',
  'TENDER_EXPIRED',
  'DRIVER_LICENSE_EXPIRING',
  'TRIP_COMPLETED',
  'DELIVERY_FAILED',
] as const
export type NotificationType = (typeof NOTIFICATION_TYPES)[number]

/** Mirrors the backend's `NotificationSeverity`. Fixed per type, never chosen by the raiser. */
export const NOTIFICATION_SEVERITIES = ['INFO', 'WARNING', 'CRITICAL'] as const
export type NotificationSeverity = (typeof NOTIFICATION_SEVERITIES)[number]

/** Mirrors the backend's `NotificationEntityType` - what the alert is about, and where it goes. */
export const NOTIFICATION_ENTITY_TYPES = ['TRIP', 'DRIVER'] as const
export type NotificationEntityType = (typeof NOTIFICATION_ENTITY_TYPES)[number]

/**
 * Mirrors the backend's `NotificationView`.
 *
 * `messageArgs` is deliberately loose: it is the placeholder bag for one sentence, its shape
 * differs per type, and typing it per type would put a second copy of the backend's message
 * contract in TypeScript that could disagree with the translation files.
 */
export interface NotificationView {
  id: string
  type: NotificationType
  severity: NotificationSeverity
  entityType: NotificationEntityType
  entityId: string
  entityLabel: string | null
  messageArgs: Record<string, string | number | null>
  occurredAt: string
  readAt: string | null
  resolvedAt: string | null
}

/**
 * Mirrors the backend's `NotificationFeedView`.
 *
 * `unreadCount` counts the whole history, not `notifications.length` - the list is capped and the
 * badge is not, so a desk that let a hundred alerts pile up says so.
 */
export interface NotificationFeedView {
  unreadCount: number
  notifications: NotificationView[]
}

/**
 * The badge and the panel in one request.
 *
 * No permission is required to call this: the backend answers with the alerts this account is
 * entitled to be told about, which for an account with none of the three relevant permissions is
 * an empty list rather than a 403. The bell is a permanent control, so it has to render for
 * everybody.
 */
export function fetchNotifications(
  companyId: string,
  signal?: AbortSignal,
): Promise<NotificationFeedView> {
  return apiRequest<NotificationFeedView>('/notifications', { companyId, signal })
}

/**
 * Acknowledges one alert on behalf of the company - not of the user. Two dispatchers share one
 * badge on purpose; `docs/domain/ALERTS_NOTIFICATIONS_V1.md` section 5 says why.
 *
 * Answers with the refreshed feed rather than the one alert, so the badge cannot paint a stale
 * count for a frame.
 */
export function markNotificationRead(
  companyId: string,
  notificationId: string,
): Promise<NotificationFeedView> {
  return apiRequest<NotificationFeedView>(`/notifications/${notificationId}/read`, {
    method: 'POST',
    companyId,
  })
}

/** Clears the badge over every alert this account is entitled to see. */
export function markAllNotificationsRead(companyId: string): Promise<NotificationFeedView> {
  return apiRequest<NotificationFeedView>('/notifications/read-all', {
    method: 'POST',
    companyId,
  })
}
