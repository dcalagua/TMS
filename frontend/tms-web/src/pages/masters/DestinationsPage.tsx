import { keepPreviousData, useQuery, useQueryClient } from '@tanstack/react-query'
import { useState } from 'react'
import type { ApiError } from '../../shared/api/httpClient'
import {
  activateDestination,
  deactivateDestination,
  DESTINATION_TYPE_LABELS,
  DESTINATION_TYPES,
  fetchDestinations,
  type DestinationType,
  type DestinationView,
} from '../../shared/api/destinationsApi'
import { fetchZones } from '../../shared/api/zonesApi'
import { describeApiError } from '../../shared/api/problemMessages'
import { useCompany } from '../../shared/company/CompanyContext'
import {
  confirmDialog,
  DataTable,
  FilterBar,
  PageHeader,
  Pagination,
  StatusBadge,
  type DataTableColumn,
} from '../../shared/ui/components'
import { notifyError, notifySuccess } from '../../shared/ui/alerts'
import { DestinationFormModal } from './DestinationFormModal'

const PAGE_SIZE = 25

type ActiveFilter = 'active' | 'inactive' | 'all'

interface AppliedFilters {
  code: string
  name: string
  type: DestinationType | ''
  zoneId: string
  active: ActiveFilter
}

const DEFAULT_FILTERS: AppliedFilters = { code: '', name: '', type: '', zoneId: '', active: 'active' }

type ModalState = { mode: 'create' } | { mode: 'edit'; destination: DestinationView } | null

export function DestinationsPage() {
  const { selected, hasPermission } = useCompany()
  const companyId = selected?.id ?? ''
  const canManage = hasPermission('masterdata.destination:manage')
  const queryClient = useQueryClient()

  const [page, setPage] = useState(0)
  const [draftFilters, setDraftFilters] = useState<AppliedFilters>(DEFAULT_FILTERS)
  const [filters, setFilters] = useState<AppliedFilters>(DEFAULT_FILTERS)
  const [modal, setModal] = useState<ModalState>(null)

  const destinationsQuery = useQuery({
    queryKey: ['destinations', companyId, page, filters],
    queryFn: ({ signal }) =>
      fetchDestinations({
        companyId,
        page,
        size: PAGE_SIZE,
        sort: 'code,asc',
        code: filters.code || undefined,
        name: filters.name || undefined,
        type: filters.type || undefined,
        zoneId: filters.zoneId || undefined,
        active: filters.active === 'all' ? undefined : filters.active === 'active',
        signal,
      }),
    placeholderData: keepPreviousData,
  })

  const zonesQuery = useQuery({
    queryKey: ['zones-for-filter', companyId],
    queryFn: ({ signal }) => fetchZones({ companyId, size: 200, active: true, sort: 'code,asc', signal }),
    enabled: companyId !== '',
  })
  const zones = zonesQuery.data?.content ?? []

  function applyFilters() {
    setFilters(draftFilters)
    setPage(0)
  }

  function resetFilters() {
    setDraftFilters(DEFAULT_FILTERS)
    setFilters(DEFAULT_FILTERS)
    setPage(0)
  }

  function refresh() {
    void queryClient.invalidateQueries({ queryKey: ['destinations', companyId] })
  }

  async function toggleActive(destination: DestinationView) {
    const confirmed = await confirmDialog({
      title: destination.active ? 'Deactivate destination?' : 'Activate destination?',
      text: destination.active
        ? `${destination.name} will no longer be selectable for new planning.`
        : `${destination.name} will become selectable again.`,
      confirmLabel: destination.active ? 'Deactivate' : 'Activate',
      dangerous: destination.active,
    })
    if (!confirmed) return

    try {
      if (destination.active) {
        await deactivateDestination(companyId, destination.id)
        notifySuccess('Destination deactivated', destination.name)
      } else {
        await activateDestination(companyId, destination.id)
        notifySuccess('Destination activated', destination.name)
      }
      refresh()
    } catch (error) {
      notifyError('Could not update the destination', describeApiError(error as ApiError))
    }
  }

  const columns: DataTableColumn<DestinationView>[] = [
    { key: 'code', header: 'Code', render: (destination) => <span className="fw-semibold">{destination.code}</span> },
    { key: 'name', header: 'Name', render: (destination) => destination.name },
    { key: 'type', header: 'Type', render: (destination) => DESTINATION_TYPE_LABELS[destination.type] },
    { key: 'zone', header: 'Zone', render: (destination) => destination.zoneName ?? '—' },
    {
      key: 'locality',
      header: 'District / Province',
      render: (destination) => [destination.district, destination.province].filter(Boolean).join(' / ') || '—',
    },
    { key: 'serviceTime', header: 'Service (min)', render: (destination) => destination.serviceTimeMinutes },
    {
      key: 'active',
      header: 'Status',
      render: (destination) => (
        <StatusBadge label={destination.active ? 'Active' : 'Inactive'} tone={destination.active ? 'success' : 'neutral'} />
      ),
    },
  ]

  if (canManage) {
    columns.push({
      key: 'actions',
      header: '',
      className: 'text-end',
      render: (destination) => (
        <div className="btn-group btn-group-sm">
          <button
            type="button"
            className="btn btn-outline-secondary"
            onClick={() => setModal({ mode: 'edit', destination })}
          >
            Edit
          </button>
          <button
            type="button"
            className={`btn btn-outline-${destination.active ? 'danger' : 'success'}`}
            onClick={() => void toggleActive(destination)}
          >
            {destination.active ? 'Deactivate' : 'Activate'}
          </button>
        </div>
      ),
    })
  }

  const pageData = destinationsQuery.data

  return (
    <div>
      <PageHeader
        title="Destinations"
        description="Delivery and service points used to plan and build trips."
        actions={
          canManage && (
            <button type="button" className="btn btn-primary btn-sm" onClick={() => setModal({ mode: 'create' })}>
              New destination
            </button>
          )
        }
      />

      <FilterBar onSubmit={applyFilters} onReset={resetFilters}>
        <div>
          <label htmlFor="filter-code" className="form-label small mb-1">
            Code
          </label>
          <input
            id="filter-code"
            className="form-control form-control-sm"
            value={draftFilters.code}
            onChange={(event) => setDraftFilters({ ...draftFilters, code: event.target.value })}
          />
        </div>
        <div>
          <label htmlFor="filter-name" className="form-label small mb-1">
            Name
          </label>
          <input
            id="filter-name"
            className="form-control form-control-sm"
            value={draftFilters.name}
            onChange={(event) => setDraftFilters({ ...draftFilters, name: event.target.value })}
          />
        </div>
        <div>
          <label htmlFor="filter-type" className="form-label small mb-1">
            Type
          </label>
          <select
            id="filter-type"
            className="form-select form-select-sm"
            value={draftFilters.type}
            onChange={(event) => setDraftFilters({ ...draftFilters, type: event.target.value as DestinationType | '' })}
          >
            <option value="">All types</option>
            {DESTINATION_TYPES.map((type) => (
              <option key={type} value={type}>
                {DESTINATION_TYPE_LABELS[type]}
              </option>
            ))}
          </select>
        </div>
        <div>
          <label htmlFor="filter-zone" className="form-label small mb-1">
            Zone
          </label>
          <select
            id="filter-zone"
            className="form-select form-select-sm"
            value={draftFilters.zoneId}
            onChange={(event) => setDraftFilters({ ...draftFilters, zoneId: event.target.value })}
          >
            <option value="">All zones</option>
            {zones.map((zone) => (
              <option key={zone.id} value={zone.id}>
                {zone.name}
              </option>
            ))}
          </select>
        </div>
        <div>
          <label htmlFor="filter-active" className="form-label small mb-1">
            Status
          </label>
          <select
            id="filter-active"
            className="form-select form-select-sm"
            value={draftFilters.active}
            onChange={(event) => setDraftFilters({ ...draftFilters, active: event.target.value as ActiveFilter })}
          >
            <option value="active">Active</option>
            <option value="inactive">Inactive</option>
            <option value="all">All</option>
          </select>
        </div>
      </FilterBar>

      <div className="card shadow-sm">
        <div className="card-body p-0">
          <DataTable
            columns={columns}
            rows={pageData?.content ?? []}
            rowKey={(destination) => destination.id}
            isLoading={destinationsQuery.isPending}
            error={destinationsQuery.isError ? describeApiError(destinationsQuery.error as ApiError) : null}
            onRetry={() => void destinationsQuery.refetch()}
            emptyTitle="No destinations found"
            emptyMessage="Create a destination or adjust your filters."
          />
        </div>
        {pageData && (
          <div className="card-footer">
            <Pagination page={pageData} onPageChange={setPage} />
          </div>
        )}
      </div>

      {modal && (
        <DestinationFormModal
          companyId={companyId}
          destination={modal.mode === 'edit' ? modal.destination : null}
          onClose={() => setModal(null)}
          onSaved={() => {
            setModal(null)
            notifySuccess(modal.mode === 'edit' ? 'Destination updated' : 'Destination created')
            refresh()
          }}
        />
      )}
    </div>
  )
}
