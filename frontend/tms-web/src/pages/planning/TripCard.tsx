import { CapacityBar } from '../../shared/ui/components/CapacityBar'
import { StatusBadge, type StatusTone } from '../../shared/ui/components/StatusBadge'
import { TRIP_STATUS_LABELS, type TripStatus, type TripView } from '../../shared/api/planningApi'

const STATUS_TONE: Record<TripStatus, StatusTone> = {
  DRAFT: 'info',
  CONFIRMED: 'success',
  CANCELLED: 'danger',
}

interface TripCardProps {
  trip: TripView
  onOpen: () => void
}

/** One trip on the board: vehicle, carrier, order/destination counts and the three capacity
 * bars the step brief asks every card to show, rendered exactly as the backend computed them
 * (`TripCapacityView`) - see `CapacityBar`. Opens `TripDetailDrawer` for assignments and stops. */
export function TripCard({ trip, onOpen }: TripCardProps) {
  return (
    <div className="card shadow-sm h-100">
      <div className="card-header bg-white d-flex justify-content-between align-items-center">
        <span className="fw-semibold">Trip {trip.tripNumber}</span>
        <StatusBadge label={TRIP_STATUS_LABELS[trip.status]} tone={STATUS_TONE[trip.status]} />
      </div>
      <div className="card-body">
        <p className="mb-1">
          {trip.vehicleCode ? (
            <>
              <span className="fw-semibold">{trip.vehicleCode}</span> · {trip.vehicleLicensePlate}
            </>
          ) : (
            <span className="text-body-secondary fst-italic">No vehicle assigned</span>
          )}
        </p>
        <p className="small text-body-secondary mb-2">{trip.carrierName ?? 'No carrier'}</p>
        <p className="small mb-3">
          {trip.orderCount} order{trip.orderCount === 1 ? '' : 's'} · {trip.stopCount} stop
          {trip.stopCount === 1 ? '' : 's'}
        </p>

        <CapacityBar label="Weight" unit="kg" dimension={trip.capacity.weight} />
        <CapacityBar label="Volume" unit="m³" dimension={trip.capacity.volume} />
        <CapacityBar label="Pallets" unit="plt" dimension={trip.capacity.pallets} />
      </div>
      <div className="card-footer bg-white text-end">
        <button type="button" className="btn btn-sm btn-outline-primary" onClick={onOpen}>
          Open
        </button>
      </div>
    </div>
  )
}
