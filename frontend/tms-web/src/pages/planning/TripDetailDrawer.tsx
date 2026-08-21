import { useEnumLabels } from '../../shared/i18n/enums'
import { useQuery, useQueryClient } from '@tanstack/react-query'
import { useEffect, useMemo, useState } from 'react'
import { useTranslation } from 'react-i18next'
import type { ApiError } from '../../shared/api/httpClient'
import {
  cancelTrip,
  fetchTrip,
  moveOrderToTrip,
  removeOrderFromTrip,
  reorderTripStops,
  updateTripRoute,
  type TripDetailView,
  type TripView,
} from '../../shared/api/planningApi'
import { describePlanningError } from '../../shared/api/problemMessages'
import { fetchRoutes } from '../../shared/api/routesApi'
import { useFormat } from '../../shared/i18n/format'
import { TripStopMap, type TripStopMapOrigin, type TripStopMapStop } from '../../shared/maps/TripStopMap'
import { CapacityBar, confirmDialog, Select, StatusBadge, type StatusTone, TmsDrawer } from '../../shared/ui/components'
import { LoadingState } from '../../shared/ui/components/LoadingState'
import { notifyError, notifySuccess } from '../../shared/ui/alerts'
import { TripVehicleDrawer } from './TripVehicleDrawer'

/** `LocalTime` strings from the backend ("HH:mm" or "HH:mm:ss") trimmed to "HH:mm" for display. */
function formatServiceWindow(start: string | null, end: string | null): string | null {
  if (!start && !end) return null
  const short = (value: string) => value.slice(0, 5)
  if (start && end) return `${short(start)}–${short(end)}`
  return short(start ?? end ?? '')
}

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
 * The board's detail drawer: the shipment header, one trip's assignments (remove, move to another
 * trip), its suggested route and its stop sequence, plus vehicle assignment and trip cancellation.
 * Everything here mutates through the trip endpoints and repaints from their response -
 * `docs/overnight/10_MANUAL_PLANNING_BACKEND.md` section 8 point 4: every mutation returns the
 * updated `TripDetailView`.
 *
 * The header is read-only and resolved entirely server-side (`docs/domain/SHIPMENT_V2.md`): the
 * browser never joins an origin, a carrier or a route to a trip itself, so what a planner reads
 * here is what an outbound integration would publish.
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
  const editable = detail !== null && canManage && detail.trip.status === 'DRAFT'

  const [moveTargets, setMoveTargets] = useState<Record<string, string>>({})
  const [busyOrderId, setBusyOrderId] = useState<string | null>(null)
  const [showVehicleModal, setShowVehicleModal] = useState(false)
  const [stopOrder, setStopOrder] = useState<string[]>([])
  const [savingStops, setSavingStops] = useState(false)
  const [routeId, setRouteId] = useState<string>('')
  const [applyRouteSequence, setApplyRouteSequence] = useState(true)
  const [savingRoute, setSavingRoute] = useState(false)
  const [selectedStopId, setSelectedStopId] = useState<string | null>(null)
  const [mobileStopTab, setMobileStopTab] = useState<'map' | 'list'>('list')

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

  // A selection that fell off the set (the destination was removed/moved elsewhere) would
  // otherwise keep highlighting a marker/list row that no longer exists.
  useEffect(() => {
    if (selectedStopId && !stopOrder.includes(selectedStopId)) {
      setSelectedStopId(null)
    }
  }, [stopOrder, selectedStopId])

  const mapOrigin = useMemo<TripStopMapOrigin | null>(() => {
    if (!detail || !detail.trip.originId) return null
    return {
      latitude: detail.trip.originLatitude,
      longitude: detail.trip.originLongitude,
      label: detail.trip.originName ?? detail.trip.originCode ?? t('drawer.header.origin'),
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [detail?.trip.originId, detail?.trip.originLatitude, detail?.trip.originLongitude, detail?.trip.originName, detail?.trip.originCode])

  // Numbered from the planner's current (possibly unsaved) order, not the last-saved sequence, so
  // the map reflects an up/down move immediately instead of only after "Guardar el orden".
  const mapStops = useMemo<TripStopMapStop[]>(() => {
    const stops = detail?.stops
    if (!stops) return []
    return stopOrder.map((destinationId, index) => {
      const stop = stops.find((s) => s.destinationId === destinationId)
      return {
        id: destinationId,
        sequence: index + 1,
        latitude: stop?.latitude ?? null,
        longitude: stop?.longitude ?? null,
        label: stop?.destinationName ?? stop?.destinationCode ?? destinationId,
      }
    })
  }, [stopOrder, detail?.stops])

  const selectedStop = detail?.stops.find((s) => s.destinationId === selectedStopId) ?? null
  const selectedStopPosition = selectedStopId ? stopOrder.indexOf(selectedStopId) + 1 : 0

  const serverRouteId = detail?.trip.routeId ?? ''
  useEffect(() => {
    setRouteId(serverRouteId)
  }, [serverRouteId])

  // Only the corridors that actually depart from this shipment's origin: the backend refuses any
  // other with a 400, so offering them would be offering a guaranteed error. Skipped entirely
  // while the trip cannot be edited - a confirmed shipment's route is a fact, not a choice.
  const originId = detail?.trip.originId ?? null
  const routesQuery = useQuery({
    queryKey: ['routes', companyId, 'for-origin', originId],
    enabled: editable && originId !== null,
    queryFn: ({ signal }) =>
      fetchRoutes({ companyId, originId: originId ?? undefined, active: true, size: 200, sort: 'code,asc', signal }),
  })
  const routes = routesQuery.data?.content ?? []

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

  /**
   * Saves the route reference. `applySequence` is what turns a suggestion into a one-time
   * reordering; without it the corridor is only recorded. Either way the set of stops is the
   * backend's to decide and does not change here.
   */
  async function saveRoute(nextRouteId: string | null) {
    if (!detail) return
    setSavingRoute(true)
    try {
      const next = await updateTripRoute(companyId, tripId, {
        routeId: nextRouteId,
        applySequence: nextRouteId !== null && applyRouteSequence,
        version: detail.trip.version,
      })
      applyDetail(next)
      notifySuccess(nextRouteId === null ? t('drawer.route.cleared') : t('drawer.route.saved'))
    } catch (error) {
      notifyError(t('drawer.route.error'), describePlanningError(error as ApiError))
    } finally {
      setSavingRoute(false)
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
  const routeDirty = routeId !== serverRouteId

  return (
    <TmsDrawer
      open
      title={detail ? t('card.title', { number: detail.trip.tripNumber }) : t('drawer.titleFallback')}
      subtitle={t('drawer.subtitle')}
      size="xl"
      onClose={onClose}
      // Stops reordered or a route picked but not yet saved are unsaved work like any edited field.
      dirty={stopsDirty || routeDirty}
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
            <div className="d-flex justify-content-between align-items-start mb-3 gap-2">
              <div className="tms-min-w-0">
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
              {editable && (
                <div className="btn-group btn-group-sm flex-shrink-0">
                  <button type="button" className="btn btn-outline-secondary" onClick={() => setShowVehicleModal(true)}>
                    {detail.trip.vehicleId ? t('drawer.changeVehicle') : t('drawer.assignVehicle')}
                  </button>
                  <button type="button" className="btn btn-outline-danger" onClick={() => void cancelThisTrip()}>
                    {t('drawer.cancelTrip')}
                  </button>
                </div>
              )}
            </div>

            <h3 className="tms-section-title mb-2">{t('drawer.header.title')}</h3>
            {/* One definition list rather than a table: this is a set of labelled facts about one
                shipment, and on a phone it has to wrap into a single readable column. */}
            <dl className="row g-2 small mb-4">
              <ShipmentFact label={t('drawer.header.shipment')} value={detail.trip.shipmentNumber} code />
              <ShipmentFact label={t('drawer.header.plan')} value={detail.trip.planNumber} code />
              <ShipmentFact label={t('drawer.header.planningDate')} value={format.date(detail.trip.planningDate)} />
              <ShipmentFact
                label={t('drawer.header.origin')}
                value={detail.trip.originName ?? detail.trip.originCode}
              />
              <ShipmentFact label={t('drawer.header.carrier')} value={detail.trip.carrierName} />
              <ShipmentFact
                label={t('drawer.header.vehicle')}
                value={
                  detail.trip.vehicleCode
                    ? `${detail.trip.vehicleCode} · ${detail.trip.vehicleLicensePlate ?? ''}`.trim()
                    : null
                }
              />
              <ShipmentFact label={t('drawer.header.vehicleType')} value={detail.trip.vehicleTypeCode} />
              <ShipmentFact
                label={t('drawer.header.departure')}
                value={detail.trip.plannedDepartureAt ? format.dateTime(detail.trip.plannedDepartureAt) : null}
              />
              <ShipmentFact
                label={t('drawer.header.route')}
                value={detail.trip.routeCode ? `${detail.trip.routeCode} — ${detail.trip.routeName ?? ''}`.trim() : null}
              />
            </dl>

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
                      {editable && <th scope="col" />}
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
                        {editable && (
                          <td>
                            <div className="d-flex gap-1 justify-content-end align-items-start">
                              <div style={{ width: '10rem' }}>
                                {/* `aria-label`, not a visible `<label>`: this text repeats the
                                    order number already on screen in the row, and a real DOM
                                    label (even visually hidden) would make it match twice
                                    wherever a test looks that text up. */}
                                <Select
                                  aria-label={t('drawer.moveAria', { number: assignment.orderNumber })}
                                  size="sm"
                                  value={moveTargets[assignment.orderId] ?? targetTrips[0]?.id ?? ''}
                                  onChange={(next) => setMoveTargets({ ...moveTargets, [assignment.orderId]: next })}
                                  disabled={targetTrips.length === 0}
                                  options={
                                    targetTrips.length === 0
                                      ? [{ value: '', label: t('drawer.noOtherTrips') }]
                                      : targetTrips.map((trip) => ({
                                          value: trip.id,
                                          label: t('drawer.tripOption', { number: trip.tripNumber }),
                                        }))
                                  }
                                />
                              </div>
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

            {editable && (
              <>
                <h3 className="tms-section-title mt-4 mb-1">{t('drawer.route.title')}</h3>
                <p className="small text-body-secondary mb-2">{t('drawer.route.subtitle')}</p>
                <div className="d-flex flex-column flex-sm-row gap-2 align-items-sm-end mb-2">
                  <div className="flex-grow-1">
                    <label className="form-label small mb-1" htmlFor="trip-route">
                      {t('drawer.header.route')}
                    </label>
                    <Select
                      id="trip-route"
                      size="sm"
                      value={routeId}
                      disabled={savingRoute || routesQuery.isPending}
                      onChange={(next) => setRouteId(next)}
                      options={[
                        { value: '', label: t('drawer.route.none') },
                        ...routes.map((route) => ({ value: route.id, label: `${route.code} — ${route.name}` })),
                      ]}
                    />
                  </div>
                  <div className="d-flex gap-2">
                    <button
                      type="button"
                      className="btn btn-sm btn-primary"
                      disabled={savingRoute || routeId === '' || !routeDirty}
                      onClick={() => void saveRoute(routeId)}
                    >
                      {savingRoute ? t('drawer.saving') : t('drawer.route.apply')}
                    </button>
                    <button
                      type="button"
                      className="btn btn-sm btn-outline-secondary"
                      disabled={savingRoute || serverRouteId === ''}
                      onClick={() => void saveRoute(null)}
                    >
                      {t('drawer.route.clear')}
                    </button>
                  </div>
                </div>
                <div className="form-check small mb-3">
                  <input
                    className="form-check-input"
                    type="checkbox"
                    id="trip-route-sequence"
                    checked={applyRouteSequence}
                    onChange={(event) => setApplyRouteSequence(event.target.checked)}
                  />
                  <label className="form-check-label" htmlFor="trip-route-sequence">
                    {t('drawer.route.applySequence')}
                  </label>
                  <p className="text-body-secondary mb-0">{t('drawer.route.applySequenceHelp')}</p>
                </div>
              </>
            )}

            <h3 className="tms-section-title mt-4 mb-2">{t('drawer.stopSequence')}</h3>
            {stopOrder.length === 0 && <p className="small text-body-secondary">{t('drawer.noStops')}</p>}
            {stopOrder.length > 0 && (
              <>
                {/* Below md a map and a full stop list cannot both fit usefully, so a phone gets
                    tabs instead of one or the other being squeezed unreadably small. */}
                <ul className="nav nav-pills nav-fill mb-2 d-md-none" role="tablist">
                  <li className="nav-item" role="presentation">
                    <button
                      type="button"
                      className={`nav-link ${mobileStopTab === 'map' ? 'active' : ''}`}
                      onClick={() => setMobileStopTab('map')}
                    >
                      {t('drawer.map.tabMap')}
                    </button>
                  </li>
                  <li className="nav-item" role="presentation">
                    <button
                      type="button"
                      className={`nav-link ${mobileStopTab === 'list' ? 'active' : ''}`}
                      onClick={() => setMobileStopTab('list')}
                    >
                      {t('drawer.map.tabList')}
                    </button>
                  </li>
                </ul>

                <div className="tms-trip-stop-layout mb-2">
                  <div className={`${mobileStopTab === 'map' ? 'd-block' : 'd-none'} d-md-block`}>
                    <TripStopMap
                      origin={mapOrigin}
                      stops={mapStops}
                      selectedStopId={selectedStopId}
                      onSelectStop={setSelectedStopId}
                    />
                    {selectedStop ? (
                      <div className="border rounded p-2 small">
                        <p className="fw-semibold mb-1 tms-truncate">
                          {selectedStop.destinationName ?? selectedStop.destinationCode ?? selectedStop.destinationId}
                        </p>
                        <p className="text-body-secondary mb-2">
                          {t('drawer.map.sequenceLabel', { position: selectedStopPosition, total: stopOrder.length })}
                        </p>
                        <dl className="row g-1 mb-0">
                          <dt className="col-4 fw-normal text-body-secondary">{t('drawer.map.address')}</dt>
                          <dd className="col-8 mb-0">{selectedStop.address ?? t('drawer.map.noAddress')}</dd>
                          <dt className="col-4 fw-normal text-body-secondary">{t('drawer.map.serviceWindow')}</dt>
                          <dd className="col-8 mb-0">
                            {formatServiceWindow(selectedStop.serviceWindowStart, selectedStop.serviceWindowEnd) ??
                              t('drawer.map.noServiceWindow')}
                          </dd>
                          <dt className="col-4 fw-normal text-body-secondary">{t('drawer.map.orders')}</dt>
                          <dd className="col-8 mb-0">{t('drawer.stopOrders', { count: selectedStop.orderCount })}</dd>
                        </dl>
                      </div>
                    ) : (
                      <p className="small text-body-secondary mb-0">{t('drawer.map.selectHint')}</p>
                    )}
                  </div>

                  <div className={`${mobileStopTab === 'list' ? 'd-block' : 'd-none'} d-md-block`}>
                    <ol className="list-group list-group-numbered mb-2">
                      {stopOrder.map((destinationId, index) => {
                        const stop = detail.stops.find((s) => s.destinationId === destinationId)
                        const mappable = stop?.latitude !== null && stop?.longitude !== null
                        return (
                          <li
                            key={destinationId}
                            className={`list-group-item d-flex justify-content-between align-items-center gap-2 ${
                              destinationId === selectedStopId ? 'active' : ''
                            }`}
                          >
                            <button
                              type="button"
                              className="btn btn-link p-0 text-start text-decoration-none text-reset tms-min-w-0 flex-grow-1"
                              aria-pressed={destinationId === selectedStopId}
                              onClick={() => setSelectedStopId(destinationId)}
                            >
                              <span className="tms-truncate d-block">
                                {stop?.destinationName ?? stop?.destinationCode ?? destinationId}
                              </span>
                              <span className="small">
                                {t('drawer.stopOrders', { count: stop?.orderCount ?? 0 })}
                                {/* A stop with no coordinate is not an error - it simply cannot be put on
                                    a map, and saying so beats a marker at (0, 0) in the Atlantic. */}
                                {!mappable && (
                                  <>
                                    {' · '}
                                    <span title={t('drawer.coordinatesMissingHint')}>{t('drawer.coordinatesMissing')}</span>
                                  </>
                                )}
                              </span>
                            </button>
                            {editable && (
                              <div className="btn-group btn-group-sm flex-shrink-0">
                                <button
                                  type="button"
                                  className="btn btn-outline-secondary"
                                  aria-label={t('drawer.moveStopUp', { position: index + 1 })}
                                  disabled={index === 0}
                                  onClick={() => setStopOrder(moveItem(stopOrder, index, index - 1))}
                                >
                                  ↑
                                </button>
                                <button
                                  type="button"
                                  className="btn btn-outline-secondary"
                                  aria-label={t('drawer.moveStopDown', { position: index + 1 })}
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
                  </div>
                </div>
              </>
            )}
            {editable && stopOrder.length > 0 && (
              <button
                type="button"
                className="btn btn-sm btn-primary"
                disabled={!stopsDirty || savingStops}
                onClick={() => void saveStopOrder()}
              >
                {savingStops ? t('drawer.saving') : t('drawer.saveStopOrder')}
              </button>
            )}
          </>
        )}
      </div>

      {showVehicleModal && detail && (
        <TripVehicleDrawer
          companyId={companyId}
          trip={detail.trip}
          onClose={() => setShowVehicleModal(false)}
          onUpdated={(next) => {
            setShowVehicleModal(false)
            applyDetail(next)
            notifySuccess(t('drawer.vehicleSaved'))
          }}
        />
      )}
    </TmsDrawer>
  )
}

/**
 * One labelled fact of the shipment header. A value the backend could not resolve renders as an
 * em dash rather than being hidden: "this shipment has no carrier yet" is information a planner
 * needs, and a row that silently disappears makes the header a different shape every time.
 */
function ShipmentFact({ label, value, code = false }: { label: string; value: string | null; code?: boolean }) {
  return (
    <>
      <dt className="col-5 col-sm-4 fw-normal text-body-secondary">{label}</dt>
      <dd className={`col-7 col-sm-8 mb-0 tms-truncate${code ? ' tms-code' : ''}`}>{value ?? '—'}</dd>
    </>
  )
}
