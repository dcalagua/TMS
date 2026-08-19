import { keepPreviousData, useQuery, useQueryClient } from '@tanstack/react-query'
import { useState } from 'react'
import type { ApiError } from '../../shared/api/httpClient'
import { fetchDestinations } from '../../shared/api/destinationsApi'
import {
  assignOrderToTrip,
  fetchEligibleOrders,
  type EligibleOrderView,
  type PlanningRunView,
  type TripDetailView,
  type TripView,
} from '../../shared/api/planningApi'
import { describePlanningError } from '../../shared/api/problemMessages'
import { DataTable, FilterBar, Pagination, type DataTableColumn } from '../../shared/ui/components'
import { notifyError, notifySuccess } from '../../shared/ui/alerts'

const PAGE_SIZE = 10

interface EligibleOrdersPanelProps {
  companyId: string
  run: PlanningRunView
  trips: TripView[]
  canManage: boolean
  onAssigned: (detail: TripDetailView) => void
}

/**
 * The left pane of the planning board: orders in `READY_FOR_PLANNING` for this run's origin and
 * date, paginated - never loaded all at once (the step brief's explicit rule). Origin and service
 * date are not editable filters here: eligibility requires an exact match on both
 * (`docs/domain/PLANNING_MANUAL_V1.md` section 5), so widening them would only list orders an
 * assign call would then refuse.
 */
export function EligibleOrdersPanel({ companyId, run, trips, canManage, onAssigned }: EligibleOrdersPanelProps) {
  const queryClient = useQueryClient()
  const [page, setPage] = useState(0)
  const [draftFilters, setDraftFilters] = useState({ destinationId: '', orderNumber: '' })
  const [filters, setFilters] = useState({ destinationId: '', orderNumber: '' })
  const [assignTargets, setAssignTargets] = useState<Record<string, string>>({})
  const [assigningOrderId, setAssigningOrderId] = useState<string | null>(null)

  const draftTrips = trips.filter((trip) => trip.status === 'DRAFT')

  const eligibleQuery = useQuery({
    queryKey: ['eligible-orders', companyId, run.id, page, filters],
    queryFn: ({ signal }) =>
      fetchEligibleOrders({
        companyId,
        originId: run.originId,
        serviceDate: run.planningDate,
        destinationId: filters.destinationId || undefined,
        orderNumber: filters.orderNumber || undefined,
        page,
        size: PAGE_SIZE,
        sort: 'orderNumber,asc',
        signal,
      }),
    placeholderData: keepPreviousData,
  })

  const destinationsQuery = useQuery({
    queryKey: ['destinations-for-eligible-orders', companyId],
    queryFn: ({ signal }) => fetchDestinations({ companyId, size: 200, active: true, sort: 'code,asc', signal }),
  })
  const destinations = destinationsQuery.data?.content ?? []

  function applyFilters() {
    setFilters(draftFilters)
    setPage(0)
  }

  function resetFilters() {
    setDraftFilters({ destinationId: '', orderNumber: '' })
    setFilters({ destinationId: '', orderNumber: '' })
    setPage(0)
  }

  function refreshEligible() {
    void queryClient.invalidateQueries({ queryKey: ['eligible-orders', companyId, run.id] })
  }

  async function assign(order: EligibleOrderView) {
    const targetTripId = assignTargets[order.id] ?? draftTrips[0]?.id
    if (!targetTripId) return

    setAssigningOrderId(order.id)
    try {
      const detail = await assignOrderToTrip(companyId, targetTripId, { orderId: order.id })
      notifySuccess('Order assigned', `${order.orderNumber} added to trip ${detail.trip.tripNumber}`)
      onAssigned(detail)
      refreshEligible()
    } catch (error) {
      notifyError('Could not assign order', describePlanningError(error as ApiError))
    } finally {
      setAssigningOrderId(null)
    }
  }

  const columns: DataTableColumn<EligibleOrderView>[] = [
    { key: 'orderNumber', header: 'Order #', render: (order) => <span className="fw-semibold">{order.orderNumber}</span> },
    {
      key: 'destination',
      header: 'Destination',
      render: (order) => order.destinationName ?? order.destinationCode ?? '—',
    },
    { key: 'customer', header: 'Customer', render: (order) => order.customerName ?? '—' },
    {
      key: 'window',
      header: 'Window',
      render: (order) =>
        order.requestedWindowStart
          ? `${order.requestedWindowStart.slice(0, 5)}–${order.requestedWindowEnd?.slice(0, 5) ?? '?'}`
          : '—',
    },
    {
      key: 'totals',
      header: 'Totals',
      render: (order) => (
        <span className="small text-body-secondary">
          {order.totalWeightKg} kg · {order.totalVolumeM3} m³ · {order.totalPallets} plt
        </span>
      ),
    },
  ]

  if (canManage) {
    columns.push({
      key: 'assign',
      header: '',
      className: 'text-end',
      render: (order) => (
        <div className="input-group input-group-sm" style={{ minWidth: '14rem' }}>
          <select
            className="form-select"
            aria-label={`Assign ${order.orderNumber} to trip`}
            value={assignTargets[order.id] ?? draftTrips[0]?.id ?? ''}
            disabled={draftTrips.length === 0}
            onChange={(event) => setAssignTargets({ ...assignTargets, [order.id]: event.target.value })}
          >
            {draftTrips.length === 0 && <option value="">No open trips</option>}
            {draftTrips.map((trip) => (
              <option key={trip.id} value={trip.id}>
                Trip {trip.tripNumber} — {trip.vehicleCode ?? 'no vehicle'}
              </option>
            ))}
          </select>
          <button
            type="button"
            className="btn btn-outline-primary"
            disabled={draftTrips.length === 0 || assigningOrderId === order.id}
            onClick={() => void assign(order)}
          >
            {assigningOrderId === order.id ? 'Assigning...' : 'Assign'}
          </button>
        </div>
      ),
    })
  }

  const pageData = eligibleQuery.data

  return (
    <div className="card shadow-sm h-100">
      <div className="card-header bg-white">
        <h2 className="h6 mb-0">Eligible orders</h2>
        <p className="small text-body-secondary mb-0">
          Ready for planning at {run.originName ?? run.originCode} on {run.planningDate}.
        </p>
      </div>
      <div className="card-body p-2">
        <FilterBar onSubmit={applyFilters} onReset={resetFilters}>
          <div>
            <label htmlFor="eligible-order-number" className="form-label small mb-1">
              Order #
            </label>
            <input
              id="eligible-order-number"
              className="form-control form-control-sm"
              value={draftFilters.orderNumber}
              onChange={(event) => setDraftFilters({ ...draftFilters, orderNumber: event.target.value })}
            />
          </div>
          <div>
            <label htmlFor="eligible-destination" className="form-label small mb-1">
              Destination
            </label>
            <select
              id="eligible-destination"
              className="form-select form-select-sm"
              value={draftFilters.destinationId}
              onChange={(event) => setDraftFilters({ ...draftFilters, destinationId: event.target.value })}
            >
              <option value="">All destinations</option>
              {destinations.map((destination) => (
                <option key={destination.id} value={destination.id}>
                  {destination.name}
                </option>
              ))}
            </select>
          </div>
        </FilterBar>

        {canManage && draftTrips.length === 0 && (
          <div className="alert alert-secondary small py-2 mb-2" role="note">
            Create a trip before assigning orders.
          </div>
        )}

        <DataTable
          columns={columns}
          rows={pageData?.content ?? []}
          rowKey={(order) => order.id}
          isLoading={eligibleQuery.isPending}
          error={eligibleQuery.isError ? describePlanningError(eligibleQuery.error as ApiError) : null}
          onRetry={() => void eligibleQuery.refetch()}
          emptyTitle="No eligible orders"
          emptyMessage="Every ready order for this origin and date is already on a trip."
        />
      </div>
      {pageData && (
        <div className="card-footer">
          <Pagination page={pageData} onPageChange={setPage} />
        </div>
      )}
    </div>
  )
}
