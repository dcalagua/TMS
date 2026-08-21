import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { useId } from 'react'
import { createPortal } from 'react-dom'
import { useTranslation } from 'react-i18next'
import { useNavigate } from 'react-router-dom'
import {
  fetchNotifications,
  markAllNotificationsRead,
  markNotificationRead,
  type NotificationFeedView,
  type NotificationType,
  type NotificationView,
} from '../api/notificationsApi'
import type { DeliveryResult, TripExceptionType } from '../api/planningApi'
import { useCompany } from '../company/CompanyContext'
import { useEnumLabels } from '../i18n/enums'
import { useFormat } from '../i18n/format'
import { useMenu } from './components/useMenu'

/** How often the badge re-reads itself. See the component comment. */
const POLL_INTERVAL_MS = 60_000

/**
 * The icon per alert type.
 *
 * Per type rather than per severity: three shapes for seven facts would make the panel a wall of
 * identical triangles, and the icon is the fastest thing to scan. The colour still comes from the
 * severity, so "how bad" and "what happened" are read from two different channels.
 */
const TYPE_ICONS: Record<NotificationType, string> = {
  TRIP_DELAYED: 'bi-clock-history',
  EXCEPTION_OPENED: 'bi-exclamation-triangle',
  TENDER_REJECTED: 'bi-hand-thumbs-down',
  TENDER_EXPIRED: 'bi-hourglass-bottom',
  DRIVER_LICENSE_EXPIRING: 'bi-person-badge',
  TRIP_COMPLETED: 'bi-check2-circle',
  DELIVERY_FAILED: 'bi-box-seam',
}

/**
 * Where an alert leads. The backend constrains `entityType` to the two kinds it can navigate to
 * (`NotificationEntityType`), which is what keeps this exhaustive rather than defensive.
 */
function targetOf(notification: NotificationView): string {
  return notification.entityType === 'TRIP'
    ? `/trips/${notification.entityId}`
    : '/fleet/drivers'
}

/**
 * One placeholder out of the alert's argument bag.
 *
 * The bag is loose by design - its shape differs per alert type and the backend stores it as
 * JSON - so this is the seam where it becomes a value a sentence can carry. A missing placeholder
 * renders as nothing rather than as `undefined`: an alert from an older build whose arguments were
 * shaped differently should read short, not broken.
 */
function arg(notification: NotificationView, key: string): string | number {
  const value = notification.messageArgs[key]
  return value === null || value === undefined ? '' : value
}

/**
 * Alerts bell: the unread count, the last things that happened, and a way into each of them.
 *
 * **It renders for everybody and gates nothing.** `GET /notifications` needs no permission and
 * answers with the alert types this account is entitled to be told about, so an account with none
 * of them sees the same empty panel it saw before this module existed - never a 403 on a control
 * that is on screen at all times. The disclosure decision is the backend's, per alert type
 * (`docs/domain/ALERTS_NOTIFICATIONS_V1.md` section 6).
 *
 * **No text arrives from the server.** Each row is rendered from its `type` - which selects a
 * sentence in the `notifications` bundle - and its `messageArgs`. That is why switching language
 * re-reads the whole history correctly instead of leaving yesterday's alerts in yesterday's
 * language.
 *
 * **Polled, not pushed.** Once a minute, and only while the tab is in front. TMS has no realtime
 * channel and adding one for a bell would be a platform decision made by a top-bar control; a
 * minute is well inside the time it takes to act on any of these alerts, and the panel refetches
 * on open regardless.
 */
export function NotificationsMenu() {
  // Two bindings rather than one over both namespaces: `navigation` owns the control (its label,
  // its empty state), `notifications` owns the alert sentences, and keeping them apart is what
  // lets the second one be read as a list of seven messages by whoever translates it.
  const { t } = useTranslation('navigation')
  const { t: tAlert } = useTranslation('notifications')
  const labels = useEnumLabels()
  const format = useFormat()
  const navigate = useNavigate()
  const queryClient = useQueryClient()
  const { selected } = useCompany()
  const companyId = selected?.id ?? null
  const menuId = useId()

  const queryKey = ['notifications', companyId]

  const feedQuery = useQuery<NotificationFeedView>({
    queryKey,
    queryFn: ({ signal }) => fetchNotifications(companyId as string, signal),
    enabled: companyId !== null,
    staleTime: 30_000,
    refetchInterval: POLL_INTERVAL_MS,
  })

  const notifications = feedQuery.data?.notifications ?? []
  const unreadCount = feedQuery.data?.unreadCount ?? 0

  const { open, toggle, close, containerRef, triggerRef, menuRef, menuStyle, registerItem, onKeyDown } =
    useMenu(notifications.length + 1, {
      // The last entry is "mark all read", which is disabled once there is nothing left to clear.
      // Without this, arrowing down the panel would park focus on a dead control.
      isEnabled: (index) => index < notifications.length || unreadCount > 0,
    })

  // Both mutations answer with the whole feed, so the badge and the list are replaced together
  // rather than invalidated and refetched - one round trip, and no frame where the count and the
  // rows disagree.
  const applyFeed = (feed: NotificationFeedView) => queryClient.setQueryData(queryKey, feed)

  const readOne = useMutation({
    mutationFn: (notificationId: string) => markNotificationRead(companyId as string, notificationId),
    onSuccess: applyFeed,
  })

  const readAll = useMutation({
    mutationFn: () => markAllNotificationsRead(companyId as string),
    onSuccess: applyFeed,
  })

  /**
   * The sentence for one alert.
   *
   * A switch over the seven types rather than one interpolated key, and not by preference:
   * i18next types `t()`'s options against the placeholders it parses out of the translation, so a
   * computed key resolves to all seven messages at once and would demand the union of their
   * placeholders on every call. Narrowing per type is what lets each call carry exactly the
   * arguments its own sentence uses.
   *
   * It is also where the two enum-shaped placeholders are labelled. The backend stores contract
   * values (`VEHICLE_BREAKDOWN`, `REJECTED`) because that is what they are; interpolating one
   * straight into a sentence would put a screaming constant in front of an operator, which is the
   * whole reason `useEnumLabels` exists.
   */
  function messageOf(notification: NotificationView): string {
    switch (notification.type) {
      case 'TRIP_DELAYED':
        return tAlert('types.TRIP_DELAYED.message', {
          shipmentNumber: arg(notification, 'shipmentNumber'),
          minutes: arg(notification, 'minutes'),
        })
      case 'EXCEPTION_OPENED':
        return tAlert('types.EXCEPTION_OPENED.message', {
          shipmentNumber: arg(notification, 'shipmentNumber'),
          exceptionType: labels.tripExceptionType(
            String(arg(notification, 'exceptionType')) as TripExceptionType,
          ),
        })
      case 'TENDER_REJECTED':
        return tAlert('types.TENDER_REJECTED.message', {
          shipmentNumber: arg(notification, 'shipmentNumber'),
          attempt: arg(notification, 'attempt'),
        })
      case 'TENDER_EXPIRED':
        return tAlert('types.TENDER_EXPIRED.message', {
          shipmentNumber: arg(notification, 'shipmentNumber'),
          attempt: arg(notification, 'attempt'),
        })
      case 'DRIVER_LICENSE_EXPIRING':
        return tAlert('types.DRIVER_LICENSE_EXPIRING.message', {
          driverName: arg(notification, 'driverName'),
          expiresOn: format.date(String(arg(notification, 'expiresOn'))),
          shipmentNumber: arg(notification, 'shipmentNumber'),
        })
      case 'TRIP_COMPLETED':
        return tAlert('types.TRIP_COMPLETED.message', {
          shipmentNumber: arg(notification, 'shipmentNumber'),
        })
      case 'DELIVERY_FAILED':
        return tAlert('types.DELIVERY_FAILED.message', {
          shipmentNumber: arg(notification, 'shipmentNumber'),
          orderNumber: arg(notification, 'orderNumber'),
          stopSequence: arg(notification, 'stopSequence'),
          result: labels.deliveryResult(String(arg(notification, 'result')) as DeliveryResult),
        })
    }
  }

  function handleOpen(notification: NotificationView) {
    close(false)
    if (notification.readAt === null) {
      // Fire and forget: the operator is already on their way to the shipment, and making them
      // wait for an acknowledgement they did not ask for would be the tail wagging the dog. A
      // failure leaves the alert unread, which is the safe direction to fail in.
      readOne.mutate(notification.id)
    }
    void navigate(targetOf(notification))
  }

  return (
    <div ref={containerRef}>
      <button
        ref={triggerRef}
        type="button"
        className={`tms-icon-btn tms-bell${open ? ' is-open' : ''}`}
        aria-haspopup="menu"
        aria-expanded={open}
        aria-controls={open ? menuId : undefined}
        aria-label={
          unreadCount > 0 ? t('notificationsUnread', { unread: unreadCount }) : t('notifications')
        }
        title={t('notifications')}
        onClick={toggle}
      >
        <i className="bi bi-bell" aria-hidden="true" />
        {unreadCount > 0 && (
          // aria-hidden: the same number is already in the button's accessible name, and a
          // screen reader announcing "12, Alerts, 12 unread" is the badge read twice.
          <span className="tms-bell-badge" aria-hidden="true">
            {unreadCount > 99 ? '99+' : unreadCount}
          </span>
        )}
      </button>

      {open &&
        createPortal(
          <div
            id={menuId}
            ref={menuRef}
            role="menu"
            tabIndex={-1}
            className="tms-menu tms-menu-wide"
            style={menuStyle}
            onKeyDown={onKeyDown}
          >
            <p className="tms-menu-header">{t('notifications')}</p>
            <hr className="tms-menu-divider" />

            {notifications.length === 0 ? (
              <div className="tms-menu-empty">
                <i className="bi bi-bell-slash tms-menu-empty-icon" aria-hidden="true" />
                <p className="tms-menu-empty-title">{t('notificationsEmpty')}</p>
                <p className="tms-menu-empty-hint">{t('notificationsEmptyHint')}</p>
              </div>
            ) : (
              <div className="tms-menu-body">
                {notifications.map((notification, index) => (
                  <button
                    key={notification.id}
                    ref={registerItem(index)}
                    type="button"
                    role="menuitem"
                    className={`tms-menu-action tms-alert-row${notification.readAt === null ? ' is-unread' : ''}${
                      notification.resolvedAt !== null ? ' is-resolved' : ''
                    }`}
                    onClick={() => handleOpen(notification)}
                  >
                    <span
                      className={`tms-menu-tile tms-alert-tile is-${notification.severity.toLowerCase()}`}
                      aria-hidden="true"
                    >
                      <i className={`bi ${TYPE_ICONS[notification.type]}`} />
                    </span>
                    <span className="tms-menu-action-text">
                      <span className="tms-menu-action-title">
                        {tAlert(`types.${notification.type}.title`)}
                      </span>
                      <span className="tms-menu-action-meta">{messageOf(notification)}</span>
                      <span className="tms-alert-time">
                        {format.dateTime(notification.occurredAt)}
                        {notification.resolvedAt !== null && <> · {tAlert('resolved')}</>}
                      </span>
                    </span>
                    <i className="bi bi-chevron-right tms-menu-action-caret" aria-hidden="true" />
                  </button>
                ))}

                <hr className="tms-menu-divider" />

                <button
                  ref={registerItem(notifications.length)}
                  type="button"
                  role="menuitem"
                  className="tms-menu-action tms-alert-readall"
                  disabled={unreadCount === 0 || readAll.isPending}
                  onClick={() => readAll.mutate()}
                >
                  <span className="tms-menu-tile" aria-hidden="true">
                    <i className="bi bi-check2-all" />
                  </span>
                  <span className="tms-menu-action-text">
                    <span className="tms-menu-action-title">{tAlert('markAllRead')}</span>
                  </span>
                </button>
              </div>
            )}
          </div>,
          document.body,
        )}
    </div>
  )
}
