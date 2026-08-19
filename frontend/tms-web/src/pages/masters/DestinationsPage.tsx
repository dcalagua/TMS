import { keepPreviousData, useQuery, useQueryClient } from '@tanstack/react-query'
import { useState } from 'react'
import { useTranslation } from 'react-i18next'
import type { ApiError } from '../../shared/api/httpClient'
import {
  activateDestination,
  deactivateDestination,
  DESTINATION_TYPES,
  fetchDestinations,
  type DestinationType,
  type DestinationView,
} from '../../shared/api/destinationsApi'
import { fetchZones } from '../../shared/api/zonesApi'
import { describeApiError } from '../../shared/api/problemMessages'
import { useEnumLabels } from '../../shared/i18n/enums'
import { useCompany } from '../../shared/company/CompanyContext'
import {
  confirmDialog,
  DataTable,
  FilterBar,
  PageHeader,
  Pagination,
  ActionMenu,
  ActiveBadge,
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
  const { t } = useTranslation('masters')
  const { t: tc } = useTranslation('common')
  const { t: td } = useTranslation('dialogs')
  const enumLabels = useEnumLabels()
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
      title: destination.active
        ? td('deactivate.title', { name: destination.name })
        : td('activate.title', { name: destination.name }),
      text: destination.active ? td('deactivate.text') : td('activate.text'),
      confirmLabel: destination.active ? tc('actions.deactivate') : tc('actions.activate'),
      dangerous: destination.active,
    })
    if (!confirmed) return

    try {
      if (destination.active) {
        await deactivateDestination(companyId, destination.id)
        notifySuccess(td('deactivated'), destination.name)
      } else {
        await activateDestination(companyId, destination.id)
        notifySuccess(td('activated'), destination.name)
      }
      refresh()
    } catch (error) {
      notifyError(td('errorTitle'), describeApiError(error as ApiError))
    }
  }

  const columns: DataTableColumn<DestinationView>[] = [
    { key: 'code', header: tc('columns.code'), render: (destination) => <span className="fw-semibold">{destination.code}</span> },
    { key: 'name', header: tc('columns.name'), render: (destination) => destination.name },
    { key: 'type', header: tc('columns.type'), render: (destination) => enumLabels.destinationType(destination.type) },
    { key: 'zone', header: tc('columns.zone'), render: (destination) => destination.zoneName ?? '—' },
    {
      key: 'locality',
      header: tc('columns.districtProvince'),
      render: (destination) => [destination.district, destination.province].filter(Boolean).join(' / ') || '—',
    },
    { key: 'serviceTime', header: tc('columns.serviceMinutes'), render: (destination) => destination.serviceTimeMinutes },
    {
      key: 'active',
      header: tc('columns.status'),
      render: (destination) => (
        <ActiveBadge active={destination.active} />
      ),
    },
  ]

  if (canManage) {
    columns.push({
      key: 'actions',
      header: tc('columns.actions'),
      actions: true,
      render: (destination) => (
        <ActionMenu
          items={[
            {
              key: 'edit',
              label: tc('actions.edit'),
              icon: 'bi-pencil',
              onSelect: () => setModal({ mode: 'edit', destination }),
            },
            {
              key: 'active',
              label: destination.active ? tc('actions.deactivate') : tc('actions.activate'),
              icon: destination.active ? 'bi-slash-circle' : 'bi-check-circle',
              dangerous: destination.active,
              onSelect: () => void toggleActive(destination),
            },
          ]}
        />
      ),
    })
  }

  const pageData = destinationsQuery.data

  return (
    <div>
      <PageHeader
        title={t('destinations.title')}
        description={t('destinations.description')}
        actions={
          canManage && (
            <button type="button" className="btn btn-primary btn-sm" onClick={() => setModal({ mode: 'create' })}>
              {t('destinations.new')}
            </button>
          )
        }
      />

      <FilterBar onSubmit={applyFilters} onReset={resetFilters}>
        <div>
          <label htmlFor="filter-code" className="form-label small mb-1">
            {tc('columns.code')}
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
            {tc('columns.name')}
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
            {tc('columns.type')}
          </label>
          <select
            id="filter-type"
            className="form-select form-select-sm"
            value={draftFilters.type}
            onChange={(event) => setDraftFilters({ ...draftFilters, type: event.target.value as DestinationType | '' })}
          >
            <option value="">{tc('filters.allTypes')}</option>
            {DESTINATION_TYPES.map((type) => (
              <option key={type} value={type}>
                {enumLabels.destinationType(type)}
              </option>
            ))}
          </select>
        </div>
        <div>
          <label htmlFor="filter-zone" className="form-label small mb-1">
            {tc('columns.zone')}
          </label>
          <select
            id="filter-zone"
            className="form-select form-select-sm"
            value={draftFilters.zoneId}
            onChange={(event) => setDraftFilters({ ...draftFilters, zoneId: event.target.value })}
          >
            <option value="">{tc('filters.allZones')}</option>
            {zones.map((zone) => (
              <option key={zone.id} value={zone.id}>
                {zone.name}
              </option>
            ))}
          </select>
        </div>
        <div>
          <label htmlFor="filter-active" className="form-label small mb-1">
            {tc('columns.status')}
          </label>
          <select
            id="filter-active"
            className="form-select form-select-sm"
            value={draftFilters.active}
            onChange={(event) => setDraftFilters({ ...draftFilters, active: event.target.value as ActiveFilter })}
          >
            <option value="active">{tc('filters.statusActive')}</option>
            <option value="inactive">{tc('filters.statusInactive')}</option>
            <option value="all">{tc('filters.statusAll')}</option>
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
            emptyTitle={t('destinations.empty.title')}
            emptyMessage={t('destinations.empty.message')}
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
            notifySuccess(modal.mode === 'edit' ? td('updated') : td('created'))
            refresh()
          }}
        />
      )}
    </div>
  )
}
