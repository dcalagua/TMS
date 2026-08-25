import { keepPreviousData, useQuery } from '@tanstack/react-query'
import { useEffect, useState } from 'react'
import { useTranslation } from 'react-i18next'
import { useNavigate } from 'react-router-dom'
import { fetchCarriers } from '../../shared/api/carriersApi'
import {
  fetchControlTower,
  fetchControlTowerTrips,
  type ControlTowerTripView,
} from '../../shared/api/controlTowerApi'
import type { ApiError } from '../../shared/api/httpClient'
import { fetchOrigins } from '../../shared/api/originsApi'
import { TRIP_STATUSES, type TripStatus } from '../../shared/api/planningApi'
import { describeApiError } from '../../shared/api/problemMessages'
import { useCompany } from '../../shared/company/CompanyContext'
import { useEnumLabels } from '../../shared/i18n/enums'
import { useFormat } from '../../shared/i18n/format'
import {
  DataTable,
  FilterBar,
  KpiCard,
  PageHeader,
  Pagination,
  SectionHeader,
  Select,
  StatusBadge,
  type DataTableColumn,
} from '../../shared/ui/components'
import { TRIP_STATUS_TONE } from '../../shared/ui/statusTones'
import { ExceptionsPanel, OutstandingStopsPanel, WorkloadPanel } from './ControlTowerPanels'
import { DEPARTURE_TONE } from './departureTone'

const PAGE_SIZE = 25

/**
 * How often the overview re-reads itself. A control tower is left open on a second monitor, and
 * two of its numbers - overdue departures and stops past their window - change with the clock
 * rather than with anything anybody did. A minute is short enough that the screen is never
 * meaningfully behind the room and long enough that a wallboard costs the API one request a
 * minute per viewer.
 *
 * The table is deliberately not on a timer: it is what somebody is reading and paging through,
 * and rows shifting under a cursor is worse than a row being sixty seconds old.
 */
const OVERVIEW_REFRESH_MS = 60_000

interface AppliedFilters {
  /** Empty means "today", decided by the server in the company's zone - never by this browser. */
  date: string
  originId: string
  carrierId: string
  status: TripStatus | ''
}

const DEFAULT_FILTERS: AppliedFilters = { date: '', originId: '', carrierId: '', status: '' }

/**
 * The control tower (`docs/domain/CONTROL_TOWER_V1.md`).
 *
 * One screen, one day, and the seven questions a transport supervisor asks between 06:00 and
 * 18:00: what is leaving, what is late, what has an open problem, what is on the road, which
 * deliveries have run past their window, what is still unplanned, and which trucks are carrying
 * the most.
 *
 * <b>Nothing on it is computed here.</b> Every counter is a server-side aggregate, every "late"
 * is the backend's verdict (`DepartureDelay`, `StopServiceWindow`), and every percentage is the
 * one the capacity model produced. The browser's job is to lay the answers out and to link each
 * one to the shipment it belongs to - a number an operator cannot click through to is a poster.
 *
 * <b>Two scopes, said out loud.</b> The counters and the panels cover the whole day; only the
 * table narrows to the origin, carrier and status picked in the filter bar. That is deliberate -
 * a filter must not be able to hide a problem from the summary - and the screen states it rather
 * than leaving the operator to discover it, which is what `scopeNote` is for.
 */
export function ControlTowerPage() {
  const { t } = useTranslation('controlTower')
  const enumLabels = useEnumLabels()
  const format = useFormat()
  const { selected } = useCompany()
  const companyId = selected?.id ?? ''
  const navigate = useNavigate()

  const [page, setPage] = useState(0)
  const [draftFilters, setDraftFilters] = useState<AppliedFilters>(DEFAULT_FILTERS)
  const [filters, setFilters] = useState<AppliedFilters>(DEFAULT_FILTERS)

  const overview = useQuery({
    queryKey: ['control-tower', companyId, filters.date, filters.originId, filters.carrierId],
    queryFn: ({ signal }) =>
      fetchControlTower({
        companyId,
        date: filters.date || undefined,
        originId: filters.originId || undefined,
        carrierId: filters.carrierId || undefined,
        signal,
      }),
    enabled: companyId !== '',
    placeholderData: keepPreviousData,
    refetchInterval: OVERVIEW_REFRESH_MS,
  })

  const board = useQuery({
    queryKey: ['control-tower-trips', companyId, page, filters],
    queryFn: ({ signal }) =>
      fetchControlTowerTrips({
        companyId,
        page,
        size: PAGE_SIZE,
        date: filters.date || undefined,
        originId: filters.originId || undefined,
        carrierId: filters.carrierId || undefined,
        status: filters.status || undefined,
        signal,
      }),
    enabled: companyId !== '',
    placeholderData: keepPreviousData,
  })

  const originsQuery = useQuery({
    queryKey: ['origins-for-control-tower', companyId],
    queryFn: ({ signal }) => fetchOrigins({ companyId, size: 200, active: true, sort: 'code,asc', signal }),
    enabled: companyId !== '',
  })
  const carriersQuery = useQuery({
    queryKey: ['carriers-for-control-tower', companyId],
    queryFn: ({ signal }) => fetchCarriers({ companyId, size: 200, active: true, sort: 'code,asc', signal }),
    enabled: companyId !== '',
  })

  // The date box starts empty, which means "today in the company's zone" - a decision the server
  // makes, because a browser one time zone east would otherwise open the screen on tomorrow. Once
  // the server has answered, the box is filled in with the day it actually chose, so the operator
  // can see and step off it. Only the draft is touched, so this never triggers a refetch.
  const resolvedDate = overview.data?.date
  useEffect(() => {
    if (!resolvedDate) {
      return
    }
    setDraftFilters((current) => (current.date === '' ? { ...current, date: resolvedDate } : current))
  }, [resolvedDate])

  function applyFilters() {
    setFilters(draftFilters)
    setPage(0)
  }

  function resetFilters() {
    setDraftFilters(DEFAULT_FILTERS)
    setFilters(DEFAULT_FILTERS)
    setPage(0)
  }

  const summary = overview.data?.summary
  const count = (value: number | null | undefined) =>
    value === null || value === undefined ? undefined : format.quantity(value)
  const tone = (value: number | undefined, warm: 'danger' | 'warning') =>
    value !== undefined && value > 0 ? warm : 'neutral'

  const delayed =
    summary === undefined ? undefined : summary.tripsDepartedLate + summary.tripsOverdue

  const columns: DataTableColumn<ControlTowerTripView>[] = [
    {
      key: 'shipment',
      header: t('columns.shipment'),
      render: (row) => (
        <div>
          <div className="fw-semibold">{row.trip.shipmentNumber}</div>
          <div className="small text-body-secondary tms-truncate">
            {row.trip.originName ?? row.trip.originCode ?? '—'}
          </div>
        </div>
      ),
    },
    {
      key: 'status',
      header: t('columns.status'),
      render: (row) => (
        <StatusBadge label={enumLabels.tripStatus(row.trip.status)} tone={TRIP_STATUS_TONE[row.trip.status]} />
      ),
    },
    {
      key: 'departure',
      header: t('columns.departure'),
      // The verdict is the server's; the minutes are shown beside it so three and ninety-five do
      // not read the same, which is the whole reason V1 declares no grace period of its own.
      render: (row) => (
        <div>
          <StatusBadge
            label={enumLabels.departureTimeliness(row.departureTimeliness)}
            tone={DEPARTURE_TONE[row.departureTimeliness]}
          />
          <div className="small text-body-secondary">
            {row.departureDelayMinutes === null
              ? format.time(row.trip.actualDepartureAt ?? row.trip.plannedDepartureAt)
              : t('delayMinutes', { minutes: format.quantity(row.departureDelayMinutes) })}
          </div>
        </div>
      ),
    },
    {
      key: 'progress',
      header: t('columns.progress'),
      render: (row) => (
        <div>
          <div>{t('stopProgress', { done: row.stopsResolved, total: row.stopsTotal })}</div>
          {row.stopsPastWindow > 0 && (
            <StatusBadge label={t('stopsLate', { value: row.stopsPastWindow })} tone="danger" />
          )}
        </div>
      ),
    },
    {
      key: 'nextStop',
      header: t('columns.nextStop'),
      render: (row) =>
        row.nextStopSequence === null ? (
          '—'
        ) : (
          <div>
            <div>{row.nextStopSequence}</div>
            <div className="small text-body-secondary">
              {row.nextStopDueAt ? t('dueBy', { time: format.time(row.nextStopDueAt) }) : t('noWindow')}
            </div>
          </div>
        ),
    },
    {
      key: 'exceptions',
      header: t('columns.exceptions'),
      numeric: true,
      render: (row) =>
        row.openExceptions === 0 ? (
          '—'
        ) : (
          <StatusBadge label={format.quantity(row.openExceptions)} tone="danger" />
        ),
    },
    {
      key: 'vehicle',
      header: t('columns.vehicle'),
      render: (row) => (
        <div>
          <div>{row.trip.vehicleLicensePlate ?? row.trip.vehicleCode ?? '—'}</div>
          <div className="small text-body-secondary tms-truncate">{row.trip.driverName ?? '—'}</div>
        </div>
      ),
    },
    {
      key: 'carrier',
      header: t('columns.carrier'),
      render: (row) => row.trip.carrierName ?? '—',
    },
    {
      key: 'capacity',
      header: t('columns.capacity'),
      numeric: true,
      // The three dimensions and the verdict are the backend's (`docs/domain/CAPACITY_MODEL.md`);
      // this shows the weight bar's percentage only, and a null reads as unknown, not as empty.
      render: (row) => format.percent(row.trip.capacity.weight.percentUsed, 0),
    },
    {
      key: 'actions',
      header: '',
      actions: true,
      render: (row) => (
        <button
          type="button"
          className="btn btn-sm btn-outline-primary"
          onClick={() => navigate(`/trips/${row.trip.id}`)}
        >
          {t('open')}
        </button>
      ),
    },
  ]

  const pageData = board.data

  return (
    <div>
      <PageHeader
        icon="broadcast-pin"
        title={t('title')}
        description={t('description')}
        meta={
          overview.data ? (
            <StatusBadge label={format.date(overview.data.date)} tone="info" />
          ) : undefined
        }
        actions={
          <div className="d-flex align-items-center gap-2">
            {/* The instant the server judged "overdue" and "past window" against. Shown because
                both change on their own: a tab left open for an hour must be able to say its
                verdict is an hour old rather than looking current. */}
            {overview.data && (
              <span className="small text-body-secondary">
                {t('generatedAt', { time: format.time(overview.data.generatedAt) })}
              </span>
            )}
            <button
              type="button"
              className="btn btn-sm btn-outline-secondary d-flex align-items-center gap-2"
              onClick={() => {
                void overview.refetch()
                void board.refetch()
              }}
            >
              <i className="bi bi-arrow-clockwise" aria-hidden="true" />
              {t('refresh')}
            </button>
          </div>
        }
      />

      <FilterBar onSubmit={applyFilters} onReset={resetFilters}>
        <div>
          <label htmlFor="ct-filter-date" className="form-label small mb-1">
            {t('filters.date')}
          </label>
          <input
            id="ct-filter-date"
            type="date"
            className="form-control form-control-sm"
            value={draftFilters.date}
            onChange={(event) => setDraftFilters({ ...draftFilters, date: event.target.value })}
          />
        </div>
        <div>
          <label htmlFor="ct-filter-origin" className="form-label small mb-1">
            {t('filters.origin')}
          </label>
          <Select
            id="ct-filter-origin"
            size="sm"
            value={draftFilters.originId}
            onChange={(next) => setDraftFilters({ ...draftFilters, originId: next })}
            options={[
              { value: '', label: t('filters.allOrigins') },
              ...(originsQuery.data?.content ?? []).map((origin) => ({ value: origin.id, label: origin.name })),
            ]}
          />
        </div>
        <div>
          <label htmlFor="ct-filter-carrier" className="form-label small mb-1">
            {t('filters.carrier')}
          </label>
          <Select
            id="ct-filter-carrier"
            size="sm"
            value={draftFilters.carrierId}
            onChange={(next) => setDraftFilters({ ...draftFilters, carrierId: next })}
            options={[
              { value: '', label: t('filters.allCarriers') },
              ...(carriersQuery.data?.content ?? []).map((carrier) => ({
                value: carrier.id,
                label: carrier.businessName,
              })),
            ]}
          />
        </div>
        <div>
          <label htmlFor="ct-filter-status" className="form-label small mb-1">
            {t('filters.status')}
          </label>
          <Select
            id="ct-filter-status"
            size="sm"
            value={draftFilters.status}
            onChange={(next) => setDraftFilters({ ...draftFilters, status: next as TripStatus | '' })}
            options={[
              { value: '', label: t('filters.allStatuses') },
              ...TRIP_STATUSES.map((status) => ({ value: status, label: enumLabels.tripStatus(status) })),
            ]}
          />
        </div>
      </FilterBar>

      <p className="text-body-secondary small mt-2 mb-3">{t('scopeNote')}</p>

      {/* The counters and the panels share one request, so one alert covers all of them. Said out
          loud rather than left to the dashes the cards would otherwise show: on a screen that is
          left open, "nothing is wrong" and "I could not ask" have to look different. */}
      {overview.isError && (
        <div className="alert alert-warning d-flex align-items-start gap-2 py-2 small" role="alert">
          <i className="bi bi-exclamation-triangle-fill mt-1" aria-hidden="true" />
          <span>{describeApiError(overview.error as ApiError)}</span>
        </div>
      )}

      {/* Named, not implied: the strip below covers the day whatever the filter bar says, and a
          heading that says so is cheaper than an operator discovering it during an incident. */}
      <SectionHeader level={2} title={t('wholeDay')} />
      <div className="tms-kpi-grid">
        <KpiCard
          icon="bi-box-arrow-right"
          label={t('kpi.scheduled')}
          hint={t('kpi.scheduledHint')}
          value={count(summary?.tripsScheduled)}
          isLoading={overview.isPending}
        />
        <KpiCard
          icon="bi-truck"
          tone="info"
          label={t('kpi.inTransit')}
          hint={t('kpi.inTransitHint')}
          value={count(summary?.tripsInTransit)}
          isLoading={overview.isPending}
        />
        <KpiCard
          icon="bi-clock-history"
          tone={tone(delayed, 'danger')}
          label={t('kpi.delayed')}
          hint={t('kpi.delayedHint', {
            late: summary?.tripsDepartedLate ?? 0,
            overdue: summary?.tripsOverdue ?? 0,
          })}
          value={count(delayed)}
          isLoading={overview.isPending}
        />
        <KpiCard
          icon="bi-exclamation-octagon"
          tone={tone(summary?.openExceptions, 'danger')}
          label={t('kpi.exceptions')}
          hint={t('kpi.exceptionsHint')}
          value={count(summary?.openExceptions)}
          isLoading={overview.isPending}
        />
        <KpiCard
          icon="bi-geo"
          tone={tone(summary?.stopsPastWindow, 'warning')}
          label={t('kpi.lateStops')}
          hint={t('kpi.lateStopsHint', { outstanding: summary?.outstandingStops ?? 0 })}
          value={count(summary?.stopsPastWindow)}
          isLoading={overview.isPending}
        />
        <KpiCard
          icon="bi-inbox"
          tone={tone(summary?.ordersUnplanned ?? undefined, 'warning')}
          label={t('kpi.unplanned')}
          // Null is not zero: the caller was not allowed to look at the backlog, and the hint says
          // so instead of the card implying an empty queue.
          hint={
            summary !== undefined && summary.ordersUnplanned === null
              ? t('kpi.unplannedDenied')
              : t('kpi.unplannedHint')
          }
          value={count(summary?.ordersUnplanned)}
          isLoading={overview.isPending}
          to="/orders"
        />
        <KpiCard
          icon="bi-hourglass-split"
          tone={tone(summary?.tripsDraft, 'warning')}
          label={t('kpi.draft')}
          hint={t('kpi.draftHint')}
          value={count(summary?.tripsDraft)}
          isLoading={overview.isPending}
        />
        <KpiCard
          icon="bi-check2-circle"
          tone="success"
          label={t('kpi.completed')}
          hint={t('kpi.completedHint')}
          value={count(summary?.tripsCompleted)}
          isLoading={overview.isPending}
        />
      </div>

      <div className="row g-3 mt-0 mb-3">
        <div className="col-12 col-xl-4">
          <WorkloadPanel rows={overview.data?.workload ?? []} />
        </div>
        <div className="col-12 col-xl-4">
          <ExceptionsPanel
            rows={overview.data?.openExceptions ?? []}
            total={summary?.openExceptions ?? 0}
          />
        </div>
        <div className="col-12 col-xl-4">
          <OutstandingStopsPanel
            rows={overview.data?.outstandingStops ?? []}
            total={summary?.outstandingStops ?? 0}
          />
        </div>
      </div>

      <SectionHeader level={2} title={t('board.title')} />
      <DataTable
        columns={columns}
        rows={pageData?.content ?? []}
        total={pageData?.totalElements}
        rowKey={(row) => row.trip.id}
        isLoading={board.isPending}
        error={board.isError ? describeApiError(board.error as ApiError) : null}
        onRetry={() => void board.refetch()}
        emptyTitle={t('board.emptyTitle')}
        emptyMessage={t('board.emptyMessage')}
        caption={t('board.title')}
        footer={pageData ? <Pagination page={pageData} onPageChange={setPage} /> : undefined}
      />
    </div>
  )
}
