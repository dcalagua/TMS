import { keepPreviousData, useQuery, useQueryClient } from '@tanstack/react-query'
import { useState } from 'react'
import { useTranslation } from 'react-i18next'
import type { ApiError } from '../../shared/api/httpClient'
import type { OrderPriority } from '../../shared/api/ordersApi'
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
import { useEnumLabels } from '../../shared/i18n/enums'
import { useFormat } from '../../shared/i18n/format'
import {
  EmptyState,
  ErrorState,
  LoadingState,
  Pagination,
  Select,
  StatusBadge,
  type StatusTone,
} from '../../shared/ui/components'
import { notifyError, notifySuccess } from '../../shared/ui/alerts'

const PAGE_SIZE = 10

const PRIORITY_TONE: Record<OrderPriority, StatusTone> = {
  LOW: 'neutral',
  NORMAL: 'neutral',
  HIGH: 'warning',
  URGENT: 'danger',
}

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
 *
 * Rendered as a compact list rather than a table. This panel is a third of the board on desktop
 * and a full-width tab on a phone; a seven-column table in that width scrolls sideways and hides
 * exactly the two things a planner scans for - the order number and its destination. Each row
 * still carries everything the brief asks for: number, destination, priority, weight, volume and
 * pallets, on two dense lines rather than in a card the size of a trip.
 */
export function EligibleOrdersPanel({ companyId, run, trips, canManage, onAssigned }: EligibleOrdersPanelProps) {
  const { t } = useTranslation('planning')
  const { t: tc } = useTranslation('common')
  const enumLabels = useEnumLabels()
  const format = useFormat()
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
      notifySuccess(
        t('eligible.assigned'),
        t('eligible.assignedDetail', { number: order.orderNumber, trip: detail.trip.tripNumber }),
      )
      refreshEligible()
      onAssigned(detail)
    } catch (error) {
      notifyError(t('eligible.assignError'), describePlanningError(error as ApiError))
    } finally {
      setAssigningOrderId(null)
    }
  }

  const pageData = eligibleQuery.data
  const rows = pageData?.content ?? []

  return (
    <div className="tms-card h-100 d-flex flex-column">
      {/* No heading here: the board already labels this column, and repeating it would put the
          same words on screen twice. The subtitle is the part that adds information. */}
      <div className="p-3 border-bottom">
        <p className="small text-body-secondary mb-0">
          {t('eligible.subtitle', {
            origin: run.originName ?? run.originCode,
            date: format.date(run.planningDate),
          })}
        </p>
      </div>

      <form
        className="p-3 border-bottom d-flex flex-wrap align-items-end gap-2"
        onSubmit={(event) => {
          event.preventDefault()
          applyFilters()
        }}
      >
        <div className="tms-filter-field flex-grow-1">
          <label htmlFor="eligible-order-number" className="tms-filter-label">
            {tc('columns.orderNumber')}
          </label>
          <input
            id="eligible-order-number"
            className="form-control form-control-sm"
            value={draftFilters.orderNumber}
            onChange={(event) => setDraftFilters({ ...draftFilters, orderNumber: event.target.value })}
          />
        </div>
        <div className="tms-filter-field flex-grow-1">
          <label htmlFor="eligible-destination" className="tms-filter-label">
            {tc('columns.destination')}
          </label>
          <Select
            id="eligible-destination"
            size="sm"
            value={draftFilters.destinationId}
            onChange={(next) => setDraftFilters({ ...draftFilters, destinationId: next })}
            options={[
              { value: '', label: t('eligible.allDestinations') },
              ...destinations.map((destination) => ({ value: destination.id, label: destination.name })),
            ]}
          />
        </div>
        <div className="d-flex gap-2 ms-auto">
          <button type="button" className="btn btn-sm btn-outline-secondary" onClick={resetFilters}>
            {tc('actions.clear')}
          </button>
          <button type="submit" className="btn btn-sm btn-primary">
            {tc('actions.applyFilters')}
          </button>
        </div>
      </form>

      <div className="flex-grow-1">
        {canManage && draftTrips.length === 0 && (
          <p className="alert alert-secondary small py-2 m-3 mb-0">{t('eligible.createTripFirst')}</p>
        )}

        {eligibleQuery.isPending && <LoadingState label={tc('states.loadingRecords')} />}

        {eligibleQuery.isError && (
          <div className="p-3">
            <ErrorState
              message={describePlanningError(eligibleQuery.error as ApiError)}
              onRetry={() => void eligibleQuery.refetch()}
            />
          </div>
        )}

        {!eligibleQuery.isPending && !eligibleQuery.isError && rows.length === 0 && (
          <EmptyState icon="bi-inbox" title={t('eligible.emptyTitle')} message={t('eligible.emptyMessage')} />
        )}

        {rows.length > 0 && (
          <ul className="list-unstyled mb-0">
            {rows.map((order) => (
              <li key={order.id} className="border-bottom px-3 py-2">
                <div className="d-flex align-items-baseline justify-content-between gap-2">
                  <span className="tms-code tms-cell-strong">{order.orderNumber}</span>
                  <StatusBadge
                    label={enumLabels.orderPriority(order.priority as OrderPriority)}
                    tone={PRIORITY_TONE[order.priority as OrderPriority] ?? 'neutral'}
                  />
                </div>

                <p className="mb-1 small tms-truncate" title={order.destinationName ?? undefined}>
                  {order.destinationName ?? order.destinationCode ?? '—'}
                  {order.customerName && <span className="text-body-secondary"> · {order.customerName}</span>}
                </p>

                <p className="mb-2 small text-body-secondary tms-code">
                  {format.weight(order.totalWeightKg)} · {format.volume(order.totalVolumeM3)} ·{' '}
                  {format.decimal(order.totalPallets)} {t('capacity.palletsUnit')}
                </p>

                {canManage && (
                  // Not an `input-group`: `.tms-select` draws its own border and radius, unlike
                  // `.form-select`, so it does not need (or want) Bootstrap's input-group merging.
                  <div className="d-flex gap-2 align-items-start">
                    <div className="flex-grow-1">
                      {/* `aria-label`, not a visible `<label>`: this text repeats the order
                          number already on screen in the row, and a real DOM label (even
                          visually hidden) would make it match twice wherever a test looks that
                          text up. */}
                      <Select
                        aria-label={t('eligible.assignAria', { number: order.orderNumber })}
                        size="sm"
                        value={assignTargets[order.id] ?? draftTrips[0]?.id ?? ''}
                        onChange={(next) => setAssignTargets({ ...assignTargets, [order.id]: next })}
                        disabled={draftTrips.length === 0}
                        options={
                          draftTrips.length === 0
                            ? [{ value: '', label: t('eligible.noOpenTrips') }]
                            : draftTrips.map((trip) => ({
                                value: trip.id,
                                label: t('eligible.tripOption', {
                                  number: trip.tripNumber,
                                  vehicle: trip.vehicleCode ?? t('eligible.noVehicle'),
                                }),
                              }))
                        }
                      />
                    </div>
                    <button
                      type="button"
                      className="btn btn-outline-primary btn-sm"
                      disabled={draftTrips.length === 0 || assigningOrderId === order.id}
                      onClick={() => void assign(order)}
                    >
                      {assigningOrderId === order.id ? t('eligible.assigning') : t('eligible.assign')}
                    </button>
                  </div>
                )}
              </li>
            ))}
          </ul>
        )}
      </div>

      {pageData && rows.length > 0 && (
        <div className="p-3 border-top">
          <Pagination page={pageData} onPageChange={setPage} />
        </div>
      )}
    </div>
  )
}
