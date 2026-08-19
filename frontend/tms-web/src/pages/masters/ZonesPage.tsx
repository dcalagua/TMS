import { keepPreviousData, useQuery, useQueryClient } from '@tanstack/react-query'
import { useState } from 'react'
import { useTranslation } from 'react-i18next'
import type { ApiError } from '../../shared/api/httpClient'
import { describeApiError } from '../../shared/api/problemMessages'
import { useCompany } from '../../shared/company/CompanyContext'
import { activateZone, deactivateZone, fetchZones, type ZoneView } from '../../shared/api/zonesApi'
import {
  confirmDialog,
  DataTable,
  FilterBar,
  PageHeader,
  Pagination,
  ActiveBadge,
  type DataTableColumn,
} from '../../shared/ui/components'
import { notifyError, notifySuccess } from '../../shared/ui/alerts'
import { ZoneFormModal } from './ZoneFormModal'

const PAGE_SIZE = 25

type ActiveFilter = 'active' | 'inactive' | 'all'

interface AppliedFilters {
  code: string
  name: string
  active: ActiveFilter
}

const DEFAULT_FILTERS: AppliedFilters = { code: '', name: '', active: 'active' }

type ModalState = { mode: 'create' } | { mode: 'edit'; zone: ZoneView } | null

export function ZonesPage() {
  const { t } = useTranslation('masters')
  const { selected, hasPermission } = useCompany()
  const companyId = selected?.id ?? ''
  const canManage = hasPermission('masterdata.zone:manage')
  const queryClient = useQueryClient()

  const [page, setPage] = useState(0)
  const [draftFilters, setDraftFilters] = useState<AppliedFilters>(DEFAULT_FILTERS)
  const [filters, setFilters] = useState<AppliedFilters>(DEFAULT_FILTERS)
  const [modal, setModal] = useState<ModalState>(null)

  const zonesQuery = useQuery({
    queryKey: ['zones', companyId, page, filters],
    queryFn: ({ signal }) =>
      fetchZones({
        companyId,
        page,
        size: PAGE_SIZE,
        sort: 'code,asc',
        code: filters.code || undefined,
        name: filters.name || undefined,
        active: filters.active === 'all' ? undefined : filters.active === 'active',
        signal,
      }),
    placeholderData: keepPreviousData,
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

  function refresh() {
    void queryClient.invalidateQueries({ queryKey: ['zones', companyId] })
  }

  async function toggleActive(zone: ZoneView) {
    const confirmed = await confirmDialog({
      title: zone.active ? 'Deactivate zone?' : 'Activate zone?',
      text: zone.active
        ? `${zone.name} will no longer be selectable for new planning.`
        : `${zone.name} will become selectable again.`,
      confirmLabel: zone.active ? 'Deactivate' : 'Activate',
      dangerous: zone.active,
    })
    if (!confirmed) return

    try {
      if (zone.active) {
        await deactivateZone(companyId, zone.id)
        notifySuccess('Zone deactivated', zone.name)
      } else {
        await activateZone(companyId, zone.id)
        notifySuccess('Zone activated', zone.name)
      }
      refresh()
    } catch (error) {
      notifyError('Could not update the zone', describeApiError(error as ApiError))
    }
  }

  const columns: DataTableColumn<ZoneView>[] = [
    { key: 'code', header: 'Code', render: (zone) => <span className="fw-semibold">{zone.code}</span> },
    { key: 'name', header: 'Name', render: (zone) => zone.name },
    { key: 'description', header: 'Description', render: (zone) => zone.description ?? '—' },
    {
      key: 'active',
      header: 'Status',
      render: (zone) => <ActiveBadge active={zone.active} />,
    },
  ]

  if (canManage) {
    columns.push({
      key: 'actions',
      header: '',
      className: 'text-end',
      render: (zone) => (
        <div className="btn-group btn-group-sm">
          <button type="button" className="btn btn-outline-secondary" onClick={() => setModal({ mode: 'edit', zone })}>
            Edit
          </button>
          <button
            type="button"
            className={`btn btn-outline-${zone.active ? 'danger' : 'success'}`}
            onClick={() => void toggleActive(zone)}
          >
            {zone.active ? 'Deactivate' : 'Activate'}
          </button>
        </div>
      ),
    })
  }

  const pageData = zonesQuery.data

  return (
    <div>
      <PageHeader
        title={t('zones.title')}
        description={t('zones.description')}
        actions={
          canManage && (
            <button type="button" className="btn btn-primary btn-sm" onClick={() => setModal({ mode: 'create' })}>
              {t('zones.new')}
            </button>
          )
        }
      />

      <FilterBar onSubmit={applyFilters} onReset={resetFilters}>
        <div>
          <label htmlFor="zone-filter-code" className="form-label small mb-1">
            Code
          </label>
          <input
            id="zone-filter-code"
            className="form-control form-control-sm"
            value={draftFilters.code}
            onChange={(event) => setDraftFilters({ ...draftFilters, code: event.target.value })}
          />
        </div>
        <div>
          <label htmlFor="zone-filter-name" className="form-label small mb-1">
            Name
          </label>
          <input
            id="zone-filter-name"
            className="form-control form-control-sm"
            value={draftFilters.name}
            onChange={(event) => setDraftFilters({ ...draftFilters, name: event.target.value })}
          />
        </div>
        <div>
          <label htmlFor="zone-filter-active" className="form-label small mb-1">
            Status
          </label>
          <select
            id="zone-filter-active"
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
            rowKey={(zone) => zone.id}
            isLoading={zonesQuery.isPending}
            error={zonesQuery.isError ? describeApiError(zonesQuery.error as ApiError) : null}
            onRetry={() => void zonesQuery.refetch()}
            emptyTitle="No zones found"
            emptyMessage="Create a zone or adjust your filters."
          />
        </div>
        {pageData && (
          <div className="card-footer">
            <Pagination page={pageData} onPageChange={setPage} />
          </div>
        )}
      </div>

      {modal && (
        <ZoneFormModal
          companyId={companyId}
          zone={modal.mode === 'edit' ? modal.zone : null}
          onClose={() => setModal(null)}
          onSaved={() => {
            setModal(null)
            notifySuccess(modal.mode === 'edit' ? 'Zone updated' : 'Zone created')
            refresh()
          }}
        />
      )}
    </div>
  )
}
