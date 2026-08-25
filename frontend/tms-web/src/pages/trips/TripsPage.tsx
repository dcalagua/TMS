import { keepPreviousData, useQuery } from '@tanstack/react-query'
import { useState } from 'react'
import { useTranslation } from 'react-i18next'
import { useNavigate } from 'react-router-dom'
import { fetchCarriers } from '../../shared/api/carriersApi'
import { fetchDrivers } from '../../shared/api/driversApi'
import type { ApiError } from '../../shared/api/httpClient'
import { fetchOrigins } from '../../shared/api/originsApi'
import { fetchTrips, TRIP_STATUSES, type TripStatus, type TripView } from '../../shared/api/planningApi'
import { describeApiError } from '../../shared/api/problemMessages'
import { useCompany } from '../../shared/company/CompanyContext'
import { useEnumLabels } from '../../shared/i18n/enums'
import { useFormat } from '../../shared/i18n/format'
import {
  DataTable,
  FilterBar,
  PageHeader,
  Pagination,
  Select,
  StatusBadge,
  type DataTableColumn,
} from '../../shared/ui/components'
import { TRIP_STATUS_TONE } from '../../shared/ui/statusTones'

const PAGE_SIZE = 20

interface AppliedFilters {
  shipmentNumber: string
  status: TripStatus | ''
  originId: string
  carrierId: string
  driverId: string
  planningDateFrom: string
  planningDateTo: string
}

const DEFAULT_FILTERS: AppliedFilters = {
  shipmentNumber: '', status: '', originId: '', carrierId: '', driverId: '',
  planningDateFrom: '', planningDateTo: '',
}

/**
 * The execution board (`docs/domain/TRIP_EXECUTION_V1.md`): every trip of the company, indexed by
 * day rather than by planning run.
 *
 * A dispatcher's question is "what is leaving today", which spans every run that produced a trip
 * for that date - and `PlanningBoardPage` can only ever show one run. That is why this screen
 * exists next to it instead of inside it, and why the row is the shipment number rather than
 * "trip 2 of PL-17", which means nothing outside its own board.
 */
export function TripsPage() {
  const { t } = useTranslation('trips')
  const enumLabels = useEnumLabels()
  const format = useFormat()
  const { selected } = useCompany()
  const companyId = selected?.id ?? ''
  const navigate = useNavigate()

  const [page, setPage] = useState(0)
  const [draftFilters, setDraftFilters] = useState<AppliedFilters>(DEFAULT_FILTERS)
  const [filters, setFilters] = useState<AppliedFilters>(DEFAULT_FILTERS)

  const tripsQuery = useQuery({
    queryKey: ['trips', companyId, page, filters],
    queryFn: ({ signal }) =>
      fetchTrips({
        companyId,
        page,
        size: PAGE_SIZE,
        shipmentNumber: filters.shipmentNumber || undefined,
        status: filters.status || undefined,
        originId: filters.originId || undefined,
        carrierId: filters.carrierId || undefined,
        driverId: filters.driverId || undefined,
        planningDateFrom: filters.planningDateFrom || undefined,
        planningDateTo: filters.planningDateTo || undefined,
        signal,
      }),
    enabled: companyId !== '',
    placeholderData: keepPreviousData,
  })

  const originsQuery = useQuery({
    queryKey: ['origins-for-trip-filter', companyId],
    queryFn: ({ signal }) => fetchOrigins({ companyId, size: 200, active: true, sort: 'code,asc', signal }),
    enabled: companyId !== '',
  })
  const carriersQuery = useQuery({
    queryKey: ['carriers-for-trip-filter', companyId],
    queryFn: ({ signal }) => fetchCarriers({ companyId, size: 200, active: true, sort: 'code,asc', signal }),
    enabled: companyId !== '',
  })
  // Active drivers only: this is the "where is Ana today" filter, and a retired driver's past
  // trips are reached from the driver master or from the shipment number, not from a picker whose
  // list would grow forever.
  const driversQuery = useQuery({
    queryKey: ['drivers-for-trip-filter', companyId],
    queryFn: ({ signal }) => fetchDrivers({ companyId, size: 200, active: true, signal }),
    enabled: companyId !== '',
  })

  function applyFilters() {
    setFilters(draftFilters)
    setPage(0)
  }

  function resetFilters() {
    setDraftFilters(DEFAULT_FILTERS)
    setFilters(DEFAULT_FILTERS)
    setPage(0)
  }

  const columns: DataTableColumn<TripView>[] = [
    {
      key: 'shipment',
      header: t('columns.shipment'),
      render: (trip) => <span className="fw-semibold">{trip.shipmentNumber}</span>,
    },
    { key: 'date', header: t('columns.date'), render: (trip) => format.date(trip.planningDate) },
    { key: 'origin', header: t('columns.origin'), render: (trip) => trip.originName ?? trip.originCode ?? '—' },
    { key: 'carrier', header: t('columns.carrier'), render: (trip) => trip.carrierName ?? '—' },
    { key: 'plate', header: t('columns.plate'), render: (trip) => trip.vehicleLicensePlate ?? '—' },
    {
      key: 'driver',
      header: t('columns.driver'),
      render: (trip) =>
        trip.driverName === null ? (
          '—'
        ) : (
          <div>
            <div>{trip.driverName}</div>
            {/* Only the two statuses that mean something on a board are shown: a valid licence is
                the normal case and a badge for it would be noise on every row. */}
            {(trip.driverLicenseStatus === 'EXPIRED' || trip.driverLicenseStatus === 'EXPIRING_SOON') && (
              <StatusBadge
                label={enumLabels.driverLicenseStatus(trip.driverLicenseStatus)}
                tone={trip.driverLicenseStatus === 'EXPIRED' ? 'danger' : 'warning'}
              />
            )}
          </div>
        ),
    },
    { key: 'orders', header: t('columns.orders'), className: 'text-end', render: (trip) => trip.orderCount },
    { key: 'stops', header: t('columns.stops'), className: 'text-end', render: (trip) => trip.stopCount },
    {
      key: 'capacity',
      header: t('columns.capacity'),
      className: 'text-end',
      // The heaviest of the three dimensions, which is the one that decides whether the truck is
      // full. Never recomputed here - the backend already said which numbers are trustworthy
      // (`docs/domain/CAPACITY_MODEL.md`), and a null percentage means "unknown", not zero.
      render: (trip) => {
        const worst = [trip.capacity.weight, trip.capacity.volume, trip.capacity.pallets]
          .map((dimension) => dimension.percentUsed)
          .filter((value): value is number => value !== null)
          .reduce<number | null>((highest, value) => (highest === null || value > highest ? value : highest), null)
        return worst === null ? '—' : format.percent(worst, 0)
      },
    },
    {
      key: 'status',
      header: t('columns.status'),
      render: (trip) => (
        <StatusBadge label={enumLabels.tripStatus(trip.status)} tone={TRIP_STATUS_TONE[trip.status]} />
      ),
    },
    {
      key: 'actions',
      header: '',
      className: 'text-end',
      render: (trip) => (
        <button type="button" className="btn btn-sm btn-outline-primary" onClick={() => navigate(`/trips/${trip.id}`)}>
          {t('open')}
        </button>
      ),
    },
  ]

  const pageData = tripsQuery.data

  return (
    <div>
      <PageHeader icon="truck" title={t('title')} description={t('description')} />

      <FilterBar onSubmit={applyFilters} onReset={resetFilters}>
        <div>
          <label htmlFor="filter-shipment-number" className="form-label small mb-1">
            {t('filters.shipmentNumber')}
          </label>
          <input
            id="filter-shipment-number"
            className="form-control form-control-sm"
            value={draftFilters.shipmentNumber}
            onChange={(event) => setDraftFilters({ ...draftFilters, shipmentNumber: event.target.value })}
          />
        </div>
        <div>
          <label htmlFor="filter-trip-status" className="form-label small mb-1">
            {t('filters.status')}
          </label>
          <Select
            id="filter-trip-status"
            size="sm"
            value={draftFilters.status}
            onChange={(next) => setDraftFilters({ ...draftFilters, status: next as TripStatus | '' })}
            options={[
              { value: '', label: t('filters.allStatuses') },
              ...TRIP_STATUSES.map((status) => ({ value: status, label: enumLabels.tripStatus(status) })),
            ]}
          />
        </div>
        <div>
          <label htmlFor="filter-trip-origin" className="form-label small mb-1">
            {t('filters.origin')}
          </label>
          <Select
            id="filter-trip-origin"
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
          <label htmlFor="filter-trip-carrier" className="form-label small mb-1">
            {t('filters.carrier')}
          </label>
          <Select
            id="filter-trip-carrier"
            size="sm"
            value={draftFilters.carrierId}
            onChange={(next) => setDraftFilters({ ...draftFilters, carrierId: next })}
            options={[
              { value: '', label: t('filters.allCarriers') },
              ...(carriersQuery.data?.content ?? []).map((carrier) => ({
                value: carrier.id, label: carrier.businessName,
              })),
            ]}
          />
        </div>
        <div>
          <label htmlFor="filter-trip-driver" className="form-label small mb-1">
            {t('filters.driver')}
          </label>
          <Select
            id="filter-trip-driver"
            size="sm"
            value={draftFilters.driverId}
            onChange={(next) => setDraftFilters({ ...draftFilters, driverId: next })}
            options={[
              { value: '', label: t('filters.allDrivers') },
              ...(driversQuery.data?.content ?? []).map((driver) => ({
                value: driver.id, label: driver.fullName,
              })),
            ]}
          />
        </div>
        <div>
          <label htmlFor="filter-trip-date-from" className="form-label small mb-1">
            {t('filters.dateFrom')}
          </label>
          <input
            id="filter-trip-date-from"
            type="date"
            className="form-control form-control-sm"
            value={draftFilters.planningDateFrom}
            onChange={(event) => setDraftFilters({ ...draftFilters, planningDateFrom: event.target.value })}
          />
        </div>
        <div>
          <label htmlFor="filter-trip-date-to" className="form-label small mb-1">
            {t('filters.dateTo')}
          </label>
          <input
            id="filter-trip-date-to"
            type="date"
            className="form-control form-control-sm"
            value={draftFilters.planningDateTo}
            onChange={(event) => setDraftFilters({ ...draftFilters, planningDateTo: event.target.value })}
          />
        </div>
      </FilterBar>

      <DataTable
        columns={columns}
        rows={pageData?.content ?? []}
        total={pageData?.totalElements}
        rowKey={(trip) => trip.id}
        isLoading={tripsQuery.isPending}
        error={tripsQuery.isError ? describeApiError(tripsQuery.error as ApiError) : null}
        onRetry={() => void tripsQuery.refetch()}
        emptyTitle={t('empty.title')}
        emptyMessage={t('empty.message')}
        footer={pageData ? <Pagination page={pageData} onPageChange={setPage} /> : undefined}
      />
    </div>
  )
}
