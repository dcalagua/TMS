import { useEnumLabels } from '../../shared/i18n/enums'
import { useQuery, useQueryClient } from '@tanstack/react-query'
import { useEffect, useState } from 'react'
import { useTranslation } from 'react-i18next'
import type { ApiError } from '../../shared/api/httpClient'
import {
  cancelTrip,
  fetchTrip,
  moveOrderToTrip,
  removeOrderFromTrip,
  reorderTripStops,
  type TripDetailView,
  type TripView,
} from '../../shared/api/planningApi'
import { describePlanningError } from '../../shared/api/problemMessages'
import { useFormat } from '../../shared/i18n/format'
import { CapacityBar, confirmDialog, Drawer, StatusBadge, type StatusTone } from '../../shared/ui/components'
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
  const { t } = useTranslation('planning')
  const { t: tc } = useTranslation('common')
  const format = useFormat()
  const enumLabels = useEnumLabels()
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
      notifySuccess(t('drawer.orderRemoved'), t('drawer.orderRemovedDetail', { number: orderNumber }))
    } catch (error) {
      notifyError(t('drawer.removeError'), describePlanningError(error as ApiError))
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
      notifySuccess(t('drawer.orderMoved'), t('drawer.orderMovedDetail', { number: orderNumber }))
    } catch (error) {
      notifyError(t('drawer.moveError'), describePlanningError(error as ApiError))
    } finally {
      setBusyOrderId(null)
    }
  }

  async function saveStopOrder() {
    setSavingStops(true)
    try {
      const next = await reorderTripStops(companyId, tripId, { destinationIds: stopOrder })
      applyDetail(next)
      notifySuccess(t('drawer.stopOrderSaved'))
    } catch (error) {
      notifyError(t('drawer.stopOrderError'), describePlanningError(error as ApiError))
    } finally {
      setSavingStops(false)
    }
  }

  async function cancelThisTrip() {
    if (!detail) return
    const confirmed = await confirmDialog({
      title: t('drawer.cancelTripTitle'),
      text: t('drawer.cancelTripText', { number: detail.trip.tripNumber }),
      confirmLabel: t('drawer.cancelTrip'),
      dangerous: true,
    })
    if (!confirmed) return

    try {
      const next = await cancelTrip(companyId, tripId, { version: detail.trip.version })
      applyDetail(next)
      notifySuccess(t('drawer.tripCancelled'))
    } catch (error) {
      notifyError(t('drawer.cancelTripError'), describePlanningError(error as ApiError))
    }
  }

  const stopsDirty = stopOrder.join('|') !== serverStopsKey

  return (
    <Drawer
      open
      title={detail ? t('card.title', { number: detail.trip.tripNumber }) : t('drawer.titleFallback')}
      onClose={onClose}
    >
      <div>
        {detail && (
          <p className="mb-3">
            <StatusBadge label={enumLabels.tripStatus(detail.trip.status)} tone={STATUS_TONE[detail.trip.status]} />
          </p>
        )}
        {tripQuery.isError && (
          <div className="alert alert-danger py-2 small" role="alert">
            {describePlanningError(tripQuery.error as ApiError)}
          </div>
        )}
        {!detail && !tripQuery.isError && <LoadingState label={t('drawer.loading')} />}

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
                    <span className="text-body-secondary fst-italic">{t('card.noVehicle')}</span>
                  )}
                </p>
                <p className="small text-body-secondary mb-0">{detail.trip.carrierName ?? t('card.noCarrier')}</p>
              </div>
              {canManage && detail.trip.status === 'DRAFT' && (
                <div className="btn-group btn-group-sm">
                  <button type="button" className="btn btn-outline-secondary" onClick={() => setShowVehicleModal(true)}>
                    {detail.trip.vehicleId ? t('drawer.changeVehicle') : t('drawer.assignVehicle')}
                  </button>
                  <button type="button" className="btn btn-outline-danger" onClick={() => void cancelThisTrip()}>
                    {t('drawer.cancelTrip')}
                  </button>
                </div>
              )}
            </div>

            <CapacityBar kind="weight" dimension={detail.trip.capacity.weight} />
            <CapacityBar kind="volume" dimension={detail.trip.capacity.volume} />
            <CapacityBar kind="pallets" dimension={detail.trip.capacity.pallets} />

            <h3 className="tms-section-title mt-4 mb-2">{t('drawer.assignedOrders')}</h3>
            {detail.assignments.length === 0 && <p className="small text-body-secondary">{t('drawer.noAssignedOrders')}</p>}
            {detail.assignments.length > 0 && (
              <div className="table-responsive mb-3">
                <table className="table table-sm align-middle">
                  <thead>
                    <tr>
                      <th scope="col">{tc('columns.orderNumber')}</th>
                      <th scope="col">{tc('columns.destination')}</th>
                      <th scope="col">{t('drawer.amounts')}</th>
                      {canManage && detail.trip.status === 'DRAFT' && <th scope="col" />}
                    </tr>
                  </thead>
                  <tbody>
                    {detail.assignments.map((assignment) => (
                      <tr key={assignment.assignmentId}>
                        <td>{assignment.orderNumber}</td>
                        <td>{assignment.destinationName ?? assignment.destinationCode ?? '—'}</td>
                        <td className="small text-body-secondary">
                          {format.weight(assignment.assignedWeightKg)} · {format.volume(assignment.assignedVolumeM3)} ·{' '}
                          {format.decimal(assignment.assignedPallets)} {t('capacity.palletsUnit')}
                        </td>
                        {canManage && detail.trip.status === 'DRAFT' && (
                          <td>
                            <div className="d-flex gap-1 justify-content-end">
                              <select
                                className="form-select form-select-sm"
                                style={{ width: '10rem' }}
                                aria-label={t('drawer.moveAria', { number: assignment.orderNumber })}
                                value={moveTargets[assignment.orderId] ?? targetTrips[0]?.id ?? ''}
                                disabled={targetTrips.length === 0}
                                onChange={(event) =>
                                  setMoveTargets({ ...moveTargets, [assignment.orderId]: event.target.value })
                                }
                              >
                                {targetTrips.length === 0 && <option value="">{t('drawer.noOtherTrips')}</option>}
                                {targetTrips.map((trip) => (
                                  <option key={trip.id} value={trip.id}>
                                    {t('drawer.tripOption', { number: trip.tripNumber })}
                                  </option>
                                ))}
                              </select>
                              <button
                                type="button"
                                className="btn btn-sm btn-outline-secondary"
                                disabled={targetTrips.length === 0 || busyOrderId === assignment.orderId}
                                onClick={() => void moveOrder(assignment.orderId, assignment.orderNumber)}
                              >
                                {t('drawer.move')}
                              </button>
                              <button
                                type="button"
                                className="btn btn-sm btn-outline-danger"
                                disabled={busyOrderId === assignment.orderId}
                                onClick={() => void removeOrder(assignment.orderId, assignment.orderNumber)}
                              >
                                {t('drawer.remove')}
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

            <h3 className="tms-section-title mt-4 mb-2">{t('drawer.stopSequence')}</h3>
            {stopOrder.length === 0 && <p className="small text-body-secondary">{t('drawer.noStops')}</p>}
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
    </Drawer>
  )
}
