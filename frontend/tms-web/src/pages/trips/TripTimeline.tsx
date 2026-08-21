import { useTranslation } from 'react-i18next'
import type { TransportEventType, TransportEventView } from '../../shared/api/planningApi'
import { useEnumLabels } from '../../shared/i18n/enums'
import { useFormat } from '../../shared/i18n/format'
import { ErrorState } from '../../shared/ui/components'

/**
 * The icon each kind of entry carries. Never the only signal - every row shows its translated
 * label too - but it is what lets a dispatcher find the exception in a day of forty entries
 * without reading any of them.
 */
const EVENT_ICON: Record<TransportEventType, string> = {
  TRIP_CONFIRMED: 'clipboard-check',
  TRIP_READY: 'box-seam',
  TRIP_DISPATCHED: 'truck',
  TRIP_COMPLETED: 'flag',
  TRIP_CANCELLED: 'x-circle',
  ARRIVED_AT_STOP: 'geo-alt',
  SERVICE_STARTED: 'hourglass-split',
  STOP_COMPLETED: 'check-circle',
  STOP_SKIPPED: 'skip-forward',
  STOP_FAILED: 'exclamation-triangle',
  DELIVERY_RECORDED: 'clipboard-check',
  TENDER_SENT: 'send',
  TENDER_ACCEPTED: 'hand-thumbs-up',
  TENDER_REJECTED: 'hand-thumbs-down',
  TENDER_EXPIRED: 'clock-history',
  TENDER_CANCELLED: 'slash-circle',
  EXCEPTION_REPORTED: 'exclamation-octagon',
  EXCEPTION_RESOLVED: 'check2-circle',
}

const EVENT_TONE: Record<TransportEventType, string> = {
  TRIP_CONFIRMED: 'text-secondary',
  TRIP_READY: 'text-secondary',
  TRIP_DISPATCHED: 'text-primary',
  TRIP_COMPLETED: 'text-success',
  TRIP_CANCELLED: 'text-danger',
  ARRIVED_AT_STOP: 'text-primary',
  SERVICE_STARTED: 'text-secondary',
  STOP_COMPLETED: 'text-success',
  STOP_SKIPPED: 'text-warning',
  STOP_FAILED: 'text-danger',
  DELIVERY_RECORDED: 'text-success',
  // An offer waiting for an answer is outstanding work, so amber; so is one whose clock ran out,
  // because nobody did anything wrong. A carrier saying no is what somebody has to act on today.
  TENDER_SENT: 'text-warning',
  TENDER_ACCEPTED: 'text-success',
  TENDER_REJECTED: 'text-danger',
  TENDER_EXPIRED: 'text-warning',
  TENDER_CANCELLED: 'text-secondary',
  EXCEPTION_REPORTED: 'text-danger',
  EXCEPTION_RESOLVED: 'text-success',
}

interface TripTimelineProps {
  events: TransportEventView[]
  loading: boolean
  /**
   * The events request failed. Distinct from an empty `events`, and the distinction is the whole
   * reason this prop exists: a log that could not be read is not a log with nothing in it.
   */
  failed?: boolean
  onRetry?: () => void
}

/**
 * The trip's day, oldest first (`docs/domain/TRIP_EXECUTION_V1.md`).
 *
 * <p>Read-only by construction: the log is append-only on the server, and a screen that offered to
 * edit an entry would be promising something the database refuses. Every action that *adds* to it
 * lives on the thing the entry is about - the trip's buttons, or a stop's.
 *
 * <p>`recordedAt` is shown only when it differs from `eventTime` by more than a minute. An arrival
 * typed as it happened has nothing to say about when it was typed; one backdated six hours has,
 * and that is exactly the case a supervisor needs to see rather than to go looking for.
 *
 * <p>A failed request is reported as a failure and never as an empty day. The log is append-only,
 * so "nothing has been recorded" is a claim about the trip - and making it because a fetch failed
 * would tell a dispatcher checking whether their driver reported an arrival that no such report
 * exists. The card offers the read again rather than making the operator reload the workspace.
 */
export function TripTimeline({ events, loading, failed = false, onRetry }: TripTimelineProps) {
  const { t } = useTranslation('trips')
  const enumLabels = useEnumLabels()
  const format = useFormat()

  if (loading) {
    return (
      <p className="text-secondary small mb-0" role="status">
        {t('workspace.timeline.loading')}
      </p>
    )
  }
  if (failed) {
    return <ErrorState message={t('workspace.timeline.failed')} onRetry={onRetry} />
  }
  if (events.length === 0) {
    return <p className="text-secondary small mb-0">{t('workspace.timeline.empty')}</p>
  }

  function backdatedBy(event: TransportEventView): number {
    return Math.round(
      (new Date(event.recordedAt).getTime() - new Date(event.eventTime).getTime()) / 60000,
    )
  }

  return (
    <ul className="list-unstyled mb-0 small">
      {events.map((event) => (
        <li key={event.id} className="d-flex gap-2 border-bottom py-2">
          <i
            className={`bi bi-${EVENT_ICON[event.eventType]} ${EVENT_TONE[event.eventType]} mt-1`}
            aria-hidden="true"
          />
          <div className="flex-grow-1 tms-min-w-0">
            <div className="d-flex justify-content-between gap-2">
              <span className="fw-semibold">{enumLabels.transportEventType(event.eventType)}</span>
              <span className="text-nowrap text-secondary">{format.dateTime(event.eventTime)}</span>
            </div>
            {event.stopSequence !== null && (
              <span className="d-block text-secondary">
                {t('workspace.timeline.atStop', {
                  sequence: event.stopSequence,
                  name: event.stopDestinationName ?? event.stopDestinationCode ?? '',
                })}
              </span>
            )}
            {event.notes !== null && <span className="d-block">{event.notes}</span>}
            <span className="d-block text-secondary">
              {event.actorName ?? t('workspace.timeline.unknownActor')}
              {backdatedBy(event) > 1 && (
                <> · {t('workspace.timeline.recordedLater', { minutes: backdatedBy(event) })}</>
              )}
            </span>
          </div>
        </li>
      ))}
    </ul>
  )
}
