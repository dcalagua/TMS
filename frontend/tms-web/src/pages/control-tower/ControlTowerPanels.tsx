import { useTranslation } from 'react-i18next'
import { Link } from 'react-router-dom'
import type {
  ControlTowerExceptionView,
  ControlTowerStopView,
  ControlTowerWorkloadView,
} from '../../shared/api/controlTowerApi'
import { useEnumLabels } from '../../shared/i18n/enums'
import { useFormat } from '../../shared/i18n/format'
import { AppCard, EmptyState, StatusBadge } from '../../shared/ui/components'
import { TRIP_STATUS_TONE } from '../../shared/ui/statusTones'

/**
 * The control tower's three side panels.
 *
 * They share a shape on purpose: a heading, the worst few rows, and - when the server capped the
 * list - one line saying how many there are in total. A panel that showed twenty rows and stopped
 * would read as "there are twenty", which on the one screen built to surface problems is the worst
 * thing it could say.
 *
 * Every row is a link into the trip workspace. A panel entry the operator cannot act on is a
 * poster, and the action for all three of these is the same: open the shipment.
 */

/** The line under a capped list. Rendered only when something did not fit. */
function PanelFooter({ shown, total, label }: { shown: number; total: number; label: string }) {
  if (total <= shown) {
    return null
  }
  return <p className="text-body-secondary small mb-0 pt-2">{label}</p>
}

/**
 * The fill for one workload row.
 *
 * Not `CapacityBar`, which renders one named dimension with its units and its own labels: this is
 * the *worst* of the three, chosen by the backend, and three bars per row would make a five-row
 * panel taller than the table it sits beside. It borrows that component's CSS and, more
 * importantly, its rule - the width is the server's percentage and the colour is the server's
 * `withinCapacity` verdict. Nothing here decides what "full" means.
 */
function WorkloadBar({ percentUsed, withinCapacity, label }: {
  percentUsed: number | null
  withinCapacity: boolean
  label: string
}) {
  if (percentUsed === null) {
    return null
  }
  const width = Math.min(100, Math.max(0, percentUsed))
  return (
    <div
      className="tms-capacity"
      role="progressbar"
      aria-label={label}
      aria-valuenow={Math.round(percentUsed)}
      aria-valuemin={0}
      aria-valuemax={100}
    >
      <div
        className={`tms-capacity-fill ${withinCapacity ? 'tms-capacity-fill-success' : 'tms-capacity-fill-danger'}`}
        style={{ width: `${width}%` }}
      />
    </div>
  )
}

export function WorkloadPanel({ rows }: { rows: ControlTowerWorkloadView[] }) {
  const { t } = useTranslation('controlTower')
  const format = useFormat()

  return (
    <AppCard title={t('panels.workload.title')}>
      <p className="text-body-secondary small mb-2">{t('panels.workload.hint')}</p>
      {rows.length === 0 ? (
        <EmptyState title={t('panels.workload.empty')} />
      ) : (
        <ul className="list-unstyled mb-0 d-flex flex-column gap-3">
          {rows.map((row) => (
            <li key={row.trip.id}>
              <div className="d-flex align-items-center justify-content-between gap-2">
                <Link to={`/trips/${row.trip.id}`} className="fw-semibold text-decoration-none tms-truncate">
                  {row.trip.shipmentNumber}
                </Link>
                <span className="small text-body-secondary flex-shrink-0 tms-code">
                  {format.percent(row.percentUsed, 0)}
                </span>
              </div>
              <WorkloadBar
                percentUsed={row.percentUsed}
                withinCapacity={row.trip.capacity.withinCapacity}
                label={format.percent(row.percentUsed, 0)}
              />
              <div className="small text-body-secondary tms-truncate">
                {row.trip.vehicleLicensePlate ?? row.trip.vehicleCode ?? '—'}
                {row.trip.carrierName ? ` · ${row.trip.carrierName}` : ''}
              </div>
            </li>
          ))}
        </ul>
      )}
    </AppCard>
  )
}

export function ExceptionsPanel({ rows, total }: { rows: ControlTowerExceptionView[]; total: number }) {
  const { t } = useTranslation('controlTower')
  const enumLabels = useEnumLabels()
  const format = useFormat()

  return (
    <AppCard title={t('panels.exceptions.title')}>
      {rows.length === 0 ? (
        <EmptyState title={t('panels.exceptions.empty')} />
      ) : (
        <>
          <ul className="list-unstyled mb-0 d-flex flex-column gap-3">
            {rows.map((row) => (
              <li key={row.id}>
                <div className="d-flex align-items-center justify-content-between gap-2">
                  <Link to={`/trips/${row.tripId}`} className="fw-semibold text-decoration-none tms-truncate">
                    {row.shipmentNumber ?? '—'}
                  </Link>
                  {row.tripStatus && (
                    <StatusBadge
                      label={enumLabels.tripStatus(row.tripStatus)}
                      tone={TRIP_STATUS_TONE[row.tripStatus]}
                    />
                  )}
                </div>
                <div className="small">{enumLabels.tripExceptionType(row.exceptionType)}</div>
                <div className="small text-body-secondary">
                  {format.time(row.reportedAt)}
                  {row.notes ? ` · ${row.notes}` : ''}
                </div>
              </li>
            ))}
          </ul>
          <PanelFooter
            shown={rows.length}
            total={total}
            label={t('panels.exceptions.more', { shown: rows.length, total })}
          />
        </>
      )}
    </AppCard>
  )
}

export function OutstandingStopsPanel({ rows, total }: { rows: ControlTowerStopView[]; total: number }) {
  const { t } = useTranslation('controlTower')
  const enumLabels = useEnumLabels()
  const format = useFormat()

  return (
    <AppCard title={t('panels.stops.title')}>
      {rows.length === 0 ? (
        <EmptyState title={t('panels.stops.empty')} />
      ) : (
        <>
          <ul className="list-unstyled mb-0 d-flex flex-column gap-3">
            {rows.map((row) => (
              <li key={row.stopId}>
                <div className="d-flex align-items-center justify-content-between gap-2">
                  <Link to={`/trips/${row.tripId}`} className="fw-semibold text-decoration-none tms-truncate">
                    {row.shipmentNumber ?? '—'}
                  </Link>
                  {/* Only the late ones take a badge. Badging every outstanding stop would make
                      the panel one colour and tell the eye nothing. */}
                  {row.minutesPastWindow !== null && (
                    <StatusBadge
                      label={t('pastWindowMinutes', { minutes: format.quantity(row.minutesPastWindow) })}
                      tone="danger"
                    />
                  )}
                </div>
                <div className="small tms-truncate">
                  {row.sequence}. {row.destinationName ?? row.destinationCode ?? '—'}
                </div>
                <div className="small text-body-secondary">
                  {row.windowEndsAt ? t('dueBy', { time: format.time(row.windowEndsAt) }) : t('noWindow')}
                  {' · '}
                  {enumLabels.stopExecutionStatus(row.executionStatus)}
                </div>
              </li>
            ))}
          </ul>
          <PanelFooter
            shown={rows.length}
            total={total}
            label={t('panels.stops.more', { shown: rows.length, total })}
          />
        </>
      )}
    </AppCard>
  )
}
