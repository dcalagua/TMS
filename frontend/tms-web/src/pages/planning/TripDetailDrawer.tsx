import { useQuery, useQueryClient } from '@tanstack/react-query'
import { useEffect, useState } from 'react'
import type { ApiError } from '../../shared/api/httpClient'
import {
  cancelTrip,
  fetchTrip,
  moveOrderToTrip,
  removeOrderFromTrip,
  reorderTripStops,
  TRIP_STATUS_LABELS,
  type TripDetailView,
  type TripView,
} from '../../shared/api/planningApi'
import { describePlanningError } from '../../shared/api/problemMessages'
import { CapacityBar, confirmDialog, StatusBadge, type StatusTone } from '../../shared/ui/components'
import { LoadingState } from '../../shared/ui/components/LoadingState'
import { notifyError, notifySuccess } from '../../shared/ui/alerts'
import { TripVehicleModal } from './TripVehicleModal'

const STATUS_TONE: Record<TripView['status'], StatusTone> = {
  DRAFT: 'info',
  CONFIRMED: 'success',
  CANCELLED: 'danger',
}

interface TripDetailDrawerProps {
  companyId: string
  tripId: string
  /** The board's other trips, for the "move to" target list and their display labels only - the
   * move itself is re-validated server-side against the target's real, current load. */
  siblingTrips: TripView[]
  canManage: boolean
  onClose: () => void
  /** Fired after any mutation that changes this trip or a sibling (move), so the board behind the
   * drawer can refresh its cards instead of showing a load that no longer matches the database. */
  onChanged: () => void
}

function moveItem<T>(items: T[], from: number, to: number): T[] {
  const next = items.slice()
  const [moved] = next.splice(from, 1) as [T]
  next.splice(to, 0, moved)
  return next
}

/**
 * The board's detail drawer: one trip's assignments (remove, move to another trip) and its stop
 * sequence, plus vehicle assignment and trip cancellation. Everything here mutates through the
 * trip endpoints and repaints from their response - `docs/overnight/10_MANUAL_PLANNING_BACKEND.md`
 * section 8 point 4: every mutation returns the updated `TripDetailView`.
 */
export function TripDetailDrawer({ companyId, tripId, siblingTrips, canManage, onClose, onChanged }: TripDetailDrawerProps) {
  const queryClient = useQueryClient()
  const queryKey = ['trip', companyId, tripId]

  const tripQuery = useQuery({
    queryKey,
    queryFn: ({ signal }) => fetchTrip(companyId, tripId, signal),
  })
  const detail = tripQuery.data ?? null

  const [moveTargets, setMoveTargets] = useState<Record<string, string>>({})
  const [busyOrderId, setBusyOrderId] = useState<string | null>(null)
  const [showVehicleModal, setShowVehicleModal] = useState(false)
  const [stopOrder, setStopOrder] = useState<string[]>([])
  const [savingStops, setSavingStops] = useState(false)

  const serverStopIds = detail ? detail.stops.slice().sort((a, b) => a.sequence - b.sequence).map((s) => s.destinationId) : []
  const serverStopsKey = serverStopIds.join('|')

  // Re-seeds the local ordering only when the *set* of stops changes (an assignment/move added or
  // removed a destination) - not on every refetch, so an in-progress manual reorder is not
  // clobbered by an unrelated capacity refresh. Deliberately keyed on `serverStopsKey`, not
  // `detail`/`serverStopIds`, which change reference on every refetch even when the set does not.
  useEffect(() => {
    setStopOrder(serverStopIds)
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [serverStopsKey])

  function applyDetail(next: TripDetailView) {
    queryClient.setQueryData(queryKey, next)
    onChanged()
  }

  const targetTrips = siblingTrips.filter((trip) => trip.id !== tripId && trip.status === 'DRAFT')

  async function removeOrder(orderId: string, orderNumber: string) {
    setBusyOrderId(orderId)
    try {
      const next = await removeOrderFromTrip(companyId, tripId, orderId)
      applyDetail(next)
      notifySuccess('Order removed', `${orderNumber} returned to the eligible pool`)
    } catch (error) {
      notifyError('Could not remove order', describePlanningError(error as ApiError))
    } finally {
      setBusyOrderId(null)
    }
  }

  async function moveOrder(orderId: string, orderNumber: string) {
    const targetTripId = moveTargets[orderId] ?? targetTrips[0]?.id
    if (!targetTripId) return

    setBusyOrderId(orderId)
    try {
      const next = await moveOrderToTrip(companyId, tripId, orderId, { targetTripId })
      applyDetail(next)
      notifySuccess('Order moved', `${orderNumber} moved to another trip`)
    } catch (error) {
      notifyError('Could not move order', describePlanningError(error as ApiError))
    } finally {
      setBusyOrderId(null)
    }
  }

  async function saveStopOrder() {
    setSavingStops(true)
    try {
      const next = await reorderTripStops(companyId, tripId, { destinationIds: stopOrder })
      applyDetail(next)
      notifySuccess('Stop order saved')
    } catch (error) {
      notifyError('Could not save stop order', describePlanningError(error as ApiError))
    } finally {
      setSavingStops(false)
    }
  }

  async function cancelThisTrip() {
    if (!detail) return
    const confirmed = await confirmDialog({
      title: 'Cancel trip?',
      text: `Trip ${detail.trip.tripNumber} will be cancelled and every order on it returned to the eligible pool.`,
      confirmLabel: 'Cancel trip',
      dangerous: true,
    })
    if (!confirmed) return

    try {
      const next = await cancelTrip(companyId, tripId, { version: detail.trip.version })
      applyDetail(next)
      notifySuccess('Trip cancelled')
    } catch (error) {
      notifyError('Could not cancel the trip', describePlanningError(error as ApiError))
    }
  }

  const stopsDirty = stopOrder.join('|') !== serverStopsKey

  return (
    <div
      className="offcanvas offcanvas-end show"
      tabIndex={-1}
      role="dialog"
      aria-modal="true"
      style={{ visibility: 'visible', width: 'min(720px, 100vw)' }}
      onKeyDown={(event) => {
        if (event.key === 'Escape') onClose()
      }}
    >
      <div className="offcanvas-header border-bottom">
        <h5 className="offcanvas-title">
          {detail ? `Trip ${detail.trip.tripNumber}` : 'Trip'}
          {detail && (
            <span className="ms-2">
              <StatusBadge label={TRIP_STATUS_LABELS[detail.trip.status]} tone={STATUS_TONE[detail.trip.status]} />
            </span>
          )}
        </h5>
        <button type="button" className="btn-close" aria-label="Close" onClick={onClose} />
      </div>
      <div className="offcanvas-body">
        {tripQuery.isError && (
          <div className="alert alert-danger py-2 small" role="alert">
            {describePlanningError(tripQuery.error as ApiError)}
          </div>
        )}
        {!detail && !tripQuery.isError && <LoadingState label="Loading trip..." />}

        {detail && (
          <>
            <div className="d-flex justify-content-between align-items-start mb-3">
              <div>
                <p className="mb-1">
                  {detail.trip.vehicleCode ? (
                    <>
                      <span className="fw-semibold">{detail.trip.vehicleCode}</span> · {detail.trip.vehicleLicensePlate}
                    </>
                  ) : (
                    <span className="text-body-secondary fst-italic">No vehicle assigned</span>
                  )}
                </p>
                <p className="small text-body-secondary mb-0">{detail.trip.carrierName ?? 'No carrier'}</p>
              </div>
              {canManage && detail.trip.status === 'DRAFT' && (
                <div className="btn-group btn-group-sm">
                  <button type="button" className="btn btn-outline-secondary" onClick={() => setShowVehicleModal(true)}>
                    {detail.trip.vehicleId ? 'Change vehicle' : 'Assign vehicle'}
                  </button>
                  <button type="button" className="btn btn-outline-danger" onClick={() => void cancelThisTrip()}>
                    Cancel trip
                  </button>
                </div>
              )}
            </div>

            <CapacityBar label="Weight" unit="kg" dimension={detail.trip.capacity.weight} />
            <CapacityBar label="Volume" unit="m³" dimension={detail.trip.capacity.volume} />
            <CapacityBar label="Pallets" unit="plt" dimension={detail.trip.capacity.pallets} />

            <h6 className="mt-4">Assigned orders</h6>
            {detail.assignments.length === 0 && <p className="small text-body-secondary">No orders assigned yet.</p>}
            {detail.assignments.length > 0 && (
              <div className="table-responsive mb-3">
                <table className="table table-sm align-middle">
                  <thead>
                    <tr>
                      <th scope="col">Order #</th>
                      <th scope="col">Destination</th>
                      <th scope="col">Weight/Volume/Pallets</th>
                      {canManage && detail.trip.status === 'DRAFT' && <th scope="col" />}
                    </tr>
                  </thead>
                  <tbody>
                    {detail.assignments.map((assignment) => (
                      <tr key={assignment.assignmentId}>
                        <td>{assignment.orderNumber}</td>
                        <td>{assignment.destinationName ?? assignment.destinationCode ?? '—'}</td>
                        <td className="small text-body-secondary">
                          {assignment.assignedWeightKg} kg · {assignment.assignedVolumeM3} m³ · {assignment.assignedPallets} plt
                        </td>
                        {canManage && detail.trip.status === 'DRAFT' && (
                          <td>
                            <div className="d-flex gap-1 justify-content-end">
                              <select
                                className="form-select form-select-sm"
                                style={{ width: '10rem' }}
                                aria-label={`Move ${assignment.orderNumber} to trip`}
                                value={moveTargets[assignment.orderId] ?? targetTrips[0]?.id ?? ''}
                                disabled={targetTrips.length === 0}
                                onChange={(event) =>
                                  setMoveTargets({ ...moveTargets, [assignment.orderId]: event.target.value })
                                }
                              >
                                {targetTrips.length === 0 && <option value="">No other trips</option>}
                                {targetTrips.map((trip) => (
                                  <option key={trip.id} value={trip.id}>
                                    Trip {trip.tripNumber}
                                  </option>
                                ))}
                              </select>
                              <button
                                type="button"
                                className="btn btn-sm btn-outline-secondary"
                                disabled={targetTrips.length === 0 || busyOrderId === assignment.orderId}
                                onClick={() => void moveOrder(assignment.orderId, assignment.orderNumber)}
                              >
                                Move
                              </button>
                              <button
                                type="button"
                                className="btn btn-sm btn-outline-danger"
                                disabled={busyOrderId === assignment.orderId}
                                onClick={() => void removeOrder(assignment.orderId, assignment.orderNumber)}
                              >
                                Remove
                              </button>
                            </div>
                          </td>
                        )}
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
            )}

            <h6 className="mt-4">Stop sequence</h6>
            {stopOrder.length === 0 && <p className="small text-body-secondary">No stops yet - assign an order first.</p>}
            {stopOrder.length > 0 && (
              <ol className="list-group list-group-numbered mb-2">
                {stopOrder.map((destinationId, index) => {
                  const stop = detail.stops.find((s) => s.destinationId === destinationId)
                  return (
                    <li key={destinationId} className="list-group-item d-flex justify-content-between align-items-center">
                      <span>
                        {stop?.destinationName ?? stop?.destinationCode ?? destinationId}
                        <span className="text-body-secondary small ms-2">
                          {stop?.orderCount ?? 0} order{stop?.orderCount === 1 ? '' : 's'}
                        </span>
                      </span>
                      {canManage && detail.trip.status === 'DRAFT' && (
                        <div className="btn-group btn-group-sm">
                          <button
                            type="button"
                            className="btn btn-outline-secondary"
                            aria-label={`Move stop ${index + 1} up`}
                            disabled={index === 0}
                            onClick={() => setStopOrder(moveItem(stopOrder, index, index - 1))}
                          >
                            ↑
                          </button>
                          <button
                            type="button"
                            className="btn btn-outline-secondary"
                            aria-label={`Move stop ${index + 1} down`}
                            disabled={index === stopOrder.length - 1}
                            onClick={() => setStopOrder(moveItem(stopOrder, index, index + 1))}
                          >
                            ↓
                          </button>
                        </div>
                      )}
                    </li>
                  )
                })}
              </ol>
            )}
            {canManage && detail.trip.status === 'DRAFT' && stopOrder.length > 0 && (
              <button type="button" className="btn btn-sm btn-primary" disabled={!stopsDirty || savingStops} onClick={() => void saveStopOrder()}>
                {savingStops ? 'Saving...' : 'Save stop order'}
              </button>
            )}
          </>
        )}
      </div>

      {showVehicleModal && detail && (
        <TripVehicleModal
          companyId={companyId}
          trip={detail.trip}
          onClose={() => setShowVehicleModal(false)}
          onUpdated={(next) => {
            setShowVehicleModal(false)
            applyDetail(next)
            notifySuccess('Vehicle saved')
          }}
        />
      )}
    </div>
  )
}
