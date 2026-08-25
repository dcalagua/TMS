import { useTranslation } from 'react-i18next'
import type { TripTrackingView } from '../../shared/api/trackingApi'
import { useFormat } from '../../shared/i18n/format'

/**
 * How old a position may be before it is shown as a warning rather than as a fact.
 *
 * Fifteen minutes, which is deliberately far above the 60-second default sampling interval: a feed
 * skipping one or two points is normal (a tunnel, a dead spot, a device rebooting), and a card that
 * turned orange every time would be a card people stop reading. Fifteen minutes of silence from a
 * vehicle that is supposed to be moving is a real signal.
 */
const STALE_AFTER_MINUTES = 15

export interface TripTrackingCardProps {
  tracking: TripTrackingView | undefined
  loading: boolean
  /** True when the tracking request itself failed - see the component comment. */
  failed: boolean
}

/** Minutes between a reported position and now, rounded to the nearest minute. */
function minutesSince(instant: string): number {
  return Math.round((Date.now() - new Date(instant).getTime()) / 60000)
}

/**
 * A link that opens the point in Google Maps, which needs no API key and no billing account.
 *
 * The embedded map (`TripStopMap`) draws the same position when Maps is configured; this works
 * regardless, and is also what a dispatcher pastes into a chat when somebody asks where the truck
 * is. `toFixed` and not the locale formatter, deliberately: Google's `q` parameter wants a decimal
 * point, and a Spanish locale writing "-12,046374" would send a dispatcher somewhere else.
 */
function mapsHref(latitude: number, longitude: number): string {
  return `https://www.google.com/maps?q=${latitude.toFixed(6)},${longitude.toFixed(6)}`
}

/**
 * Where the vehicle is (`docs/domain/TRACKING_V1.md`).
 *
 * <p>The card renders one of five states and each of them says something different, because
 * "no position" has several causes and a dispatcher does something different about each:
 *
 *   * the request failed        - TMS's problem, and explicitly not the trip's;
 *   * the trip is not on the road - the status already explains it, nothing to do;
 *   * no feed in this deployment - somebody's job, but not today's dispatcher's;
 *   * a feed that has said nothing about this shipment - the one worth a phone call;
 *   * a position, with how old it is.
 *
 * <p>A failed tracking request never takes the page down with it: the workspace's own query is
 * separate, so a provider outage costs the tracking card and nothing else. That is the same
 * reasoning the timeline's separate query follows, applied to a harder failure.
 */
export function TripTrackingCard({ tracking, loading, failed }: TripTrackingCardProps) {
  const { t } = useTranslation('trips')
  const format = useFormat()

  if (loading) {
    return <p className="text-secondary small mb-0">{t('workspace.tracking.loading')}</p>
  }
  if (failed || !tracking) {
    return <p className="text-secondary small mb-0">{t('workspace.tracking.failed')}</p>
  }
  if (!tracking.trackable) {
    return <p className="text-secondary small mb-0">{t('workspace.tracking.notOnTheRoad')}</p>
  }

  const position = tracking.lastPosition
  if (position === null) {
    return (
      <p className="text-secondary small mb-0">
        {tracking.providerConfigured
          ? t('workspace.tracking.noneReported')
          : t('workspace.tracking.noProvider')}
      </p>
    )
  }

  const age = minutesSince(position.occurredAt)
  const stale = age >= STALE_AFTER_MINUTES

  return (
    <div className="small">
      <div className="d-flex justify-content-between align-items-start gap-2">
        <span className="fw-semibold">
          {position.latitude.toFixed(5)}, {position.longitude.toFixed(5)}
        </span>
        <a
          className="text-nowrap"
          href={mapsHref(position.latitude, position.longitude)}
          target="_blank"
          rel="noreferrer noopener"
        >
          {t('workspace.tracking.openInMaps')}
        </a>
      </div>

      <span className={`d-block ${stale ? 'text-warning-emphasis fw-semibold' : 'text-secondary'}`}>
        {t('workspace.tracking.reportedAt', { at: format.dateTime(position.occurredAt) })}
        {' · '}
        {age <= 1
          ? t('workspace.tracking.ageJustNow')
          : t('workspace.tracking.age', { minutes: age })}
      </span>

      {position.speedKph !== null && (
        <span className="d-block text-secondary">
          {t('workspace.tracking.speed', { speed: position.speedKph })}
        </span>
      )}

      <span className="d-block text-secondary">
        {t('workspace.tracking.source', { provider: position.provider })}
        {tracking.vehicleLicensePlate !== null && <> · {tracking.vehicleLicensePlate}</>}
      </span>

      {tracking.track.length > 1 && (
        <span className="d-block text-secondary">
          {t('workspace.tracking.trackPoints', { count: tracking.track.length })}
        </span>
      )}
    </div>
  )
}
