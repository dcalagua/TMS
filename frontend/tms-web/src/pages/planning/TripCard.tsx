import { useTranslation } from 'react-i18next'
import { type TripView } from '../../shared/api/planningApi'
import { useEnumLabels } from '../../shared/i18n/enums'
import { useFormat } from '../../shared/i18n/format'
import { CapacityBar } from '../../shared/ui/components/CapacityBar'
import { StatusBadge } from '../../shared/ui/components/StatusBadge'
import { TRIP_STATUS_TONE } from '../../shared/ui/statusTones'

interface TripCardProps {
  trip: TripView
  onOpen: () => void
}

/**
 * One shipment on the board: both identities (the run-local trip number a planner reads and the
 * `shipmentNumber` everything outside the board uses), status, the vehicle with its type and the
 * carrier the shipment was *planned* with, the departure, how much is on it, the suggested route
 * if a planner picked one, and the three capacity dimensions rendered exactly as the backend
 * computed them (`TripCapacityView` - see `CapacityBar`).
 *
 * The whole card is the control that opens `TripDetailDrawer`: on a board of a dozen trips,
 * hunting for a small "Open" button in each footer is slower than clicking the card a planner
 * is already reading. The heading carries the accessible name.
 */
export function TripCard({ trip, onOpen }: TripCardProps) {
  const { t } = useTranslation('planning')
  const enumLabels = useEnumLabels()
  const format = useFormat()

  const title = t('card.title', { number: trip.tripNumber })
  const overCapacity = !trip.capacity.withinCapacity

  return (
    <article className={`tms-card h-100 d-flex flex-column${overCapacity ? ' border-danger' : ''}`}>
      <div className="tms-card-header">
        <span className="tms-min-w-0 d-flex flex-column">
          <span className="tms-code tms-cell-strong">{title}</span>
          {/* The shipment number, not the trip number, is what an external system, a manifest or
              a phone call refers to: trip 3 means nothing without naming its planning run. */}
          <span className="small text-body-secondary tms-truncate">
            {t('card.shipment')} <span className="tms-code">{trip.shipmentNumber}</span>
          </span>
        </span>
        <StatusBadge label={enumLabels.tripStatus(trip.status)} tone={TRIP_STATUS_TONE[trip.status]} />
      </div>

      <div className="tms-card-body flex-grow-1">
        <p className="mb-1">
          {trip.vehicleCode ? (
            <>
              <span className="fw-semibold">{trip.vehicleCode}</span>
              <span className="text-body-secondary"> · {trip.vehicleLicensePlate}</span>
            </>
          ) : (
            <span className="text-body-secondary fst-italic">{t('card.noVehicle')}</span>
          )}
        </p>
        <p className="small text-body-secondary mb-2 tms-truncate">
          {trip.carrierName ?? t('card.noCarrier')}
          {trip.vehicleTypeCode && (
            <>
              {' · '}
              {t('card.vehicleType')} {trip.vehicleTypeCode}
            </>
          )}
        </p>

        <dl className="row row-cols-1 small mb-3 g-0">
          <div className="d-flex justify-content-between gap-2">
            <dt className="fw-normal text-body-secondary">{t('card.departure')}</dt>
            <dd className="mb-0 text-end">
              {trip.plannedDepartureAt ? format.dateTime(trip.plannedDepartureAt) : t('card.noDeparture')}
            </dd>
          </div>
          <div className="d-flex justify-content-between gap-2">
            <dt className="fw-normal text-body-secondary">{t('card.ordersLabel')}</dt>
            <dd className="mb-0 text-end">{format.quantity(trip.orderCount)}</dd>
          </div>
          <div className="d-flex justify-content-between gap-2">
            <dt className="fw-normal text-body-secondary">{t('card.stopsLabel')}</dt>
            <dd className="mb-0 text-end">{format.quantity(trip.stopCount)}</dd>
          </div>
          {trip.routeCode && (
            <div className="d-flex justify-content-between gap-2">
              <dt className="fw-normal text-body-secondary">{t('card.route')}</dt>
              <dd className="mb-0 text-end tms-truncate">{trip.routeCode}</dd>
            </div>
          )}
        </dl>

        <CapacityBar kind="weight" dimension={trip.capacity.weight} />
        <CapacityBar kind="volume" dimension={trip.capacity.volume} />
        <CapacityBar kind="pallets" dimension={trip.capacity.pallets} />
      </div>

      <div className="tms-card-header border-top border-bottom-0 justify-content-end">
        {/* The visible label stays short; the accessible name says which trip it opens, so a
            board of a dozen cards does not present a dozen buttons all called "Abrir". */}
        <button
          type="button"
          className="btn btn-sm btn-outline-primary"
          aria-label={t('card.openNamed', { number: trip.tripNumber })}
          onClick={onOpen}
        >
          {t('card.open')}
        </button>
      </div>
    </article>
  )
}
