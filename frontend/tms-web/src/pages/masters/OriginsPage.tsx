import { keepPreviousData, useQuery, useQueryClient } from '@tanstack/react-query'
import { useState } from 'react'
import { useTranslation } from 'react-i18next'
import type { ApiError } from '../../shared/api/httpClient'
import {
  activateOrigin,
  deactivateOrigin,
  fetchOrigins,
  ORIGIN_TYPES,
  ORIGIN_TYPE_LABELS,
  type OriginType,
  type OriginView,
} from '../../shared/api/originsApi'
import { describeApiError } from '../../shared/api/problemMessages'
import { useCompany } from '../../shared/company/CompanyContext'
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
import { OriginFormModal } from './OriginFormModal'

const PAGE_SIZE = 25

type ActiveFilter = 'active' | 'inactive' | 'all'

interface AppliedFilters {
  code: string
  name: string
  type: OriginType | ''
  active: ActiveFilter
}

const DEFAULT_FILTERS: AppliedFilters = { code: '', name: '', type: '', active: 'active' }

type ModalState = { mode: 'create' } | { mode: 'edit'; origin: OriginView } | null

export function OriginsPage() {
  const { t } = useTranslation('masters')
  const { selected, hasPermission } = useCompany()
  const companyId = selected?.id ?? ''
  const canManage = hasPermission('masterdata.origin:manage')
  const queryClient = useQueryClient()

  const [page, setPage] = useState(0)
  const [draftFilters, setDraftFilters] = useState<AppliedFilters>(DEFAULT_FILTERS)
  const [filters, setFilters] = useState<AppliedFilters>(DEFAULT_FILTERS)
  const [modal, setModal] = useState<ModalState>(null)

  const originsQuery = useQuery({
    queryKey: ['origins', companyId, page, filters],
    queryFn: ({ signal }) =>
      fetchOrigins({
        companyId,
        page,
        size: PAGE_SIZE,
        sort: 'code,asc',
        code: filters.code || undefined,
        name: filters.name || undefined,
        type: filters.type || undefined,
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
    void queryClient.invalidateQueries({ queryKey: ['origins', companyId] })
  }

  async function toggleActive(origin: OriginView) {
    const confirmed = await confirmDialog({
      title: origin.active ? 'Deactivate origin?' : 'Activate origin?',
      text: origin.active
        ? `${origin.name} will no longer be selectable for new planning.`
        : `${origin.name} will become selectable again.`,
      confirmLabel: origin.active ? 'Deactivate' : 'Activate',
      dangerous: origin.active,
    })
    if (!confirmed) return

    try {
      if (origin.active) {
        await deactivateOrigin(companyId, origin.id)
        notifySuccess('Origin deactivated', origin.name)
      } else {
        await activateOrigin(companyId, origin.id)
        notifySuccess('Origin activated', origin.name)
      }
      refresh()
    } catch (error) {
      notifyError('Could not update the origin', describeApiError(error as ApiError))
    }
  }

  const columns: DataTableColumn<OriginView>[] = [
    { key: 'code', header: 'Code', render: (origin) => <span className="fw-semibold">{origin.code}</span> },
    { key: 'name', header: 'Name', render: (origin) => origin.name },
    { key: 'type', header: 'Type', render: (origin) => ORIGIN_TYPE_LABELS[origin.type] },
    { key: 'address', header: 'Address', render: (origin) => origin.address ?? '—' },
    { key: 'timeZone', header: 'Time zone', render: (origin) => origin.timeZone },
    {
      key: 'active',
      header: 'Status',
      render: (origin) => (
        <ActiveBadge active={origin.active} />
      ),
    },
  ]

  if (canManage) {
    columns.push({
      key: 'actions',
      header: '',
      className: 'text-end',
      render: (origin) => (
        <div className="btn-group btn-group-sm">
          <button type="button" className="btn btn-outline-secondary" onClick={() => setModal({ mode: 'edit', origin })}>
            Edit
          </button>
          <button
            type="button"
            className={`btn btn-outline-${origin.active ? 'danger' : 'success'}`}
            onClick={() => void toggleActive(origin)}
          >
            {origin.active ? 'Deactivate' : 'Activate'}
          </button>
        </div>
      ),
    })
  }

  const pageData = originsQuery.data

  return (
    <div>
      <PageHeader
        title={t('origins.title')}
        description={t('origins.description')}
        actions={
          canManage && (
            <button type="button" className="btn btn-primary btn-sm" onClick={() => setModal({ mode: 'create' })}>
              {t('origins.new')}
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
            onChange={(event) => setDraftFilters({ ...draftFilters, type: event.target.value as OriginType | '' })}
          >
            <option value="">All types</option>
            {ORIGIN_TYPES.map((type) => (
              <option key={type} value={type}>
                {ORIGIN_TYPE_LABELS[type]}
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
            rowKey={(origin) => origin.id}
            isLoading={originsQuery.isPending}
            error={originsQuery.isError ? describeApiError(originsQuery.error as ApiError) : null}
            onRetry={() => void originsQuery.refetch()}
            emptyTitle="No origins found"
            emptyMessage="Create an origin or adjust your filters."
          />
        </div>
        {pageData && (
          <div className="card-footer">
            <Pagination page={pageData} onPageChange={setPage} />
          </div>
        )}
      </div>

      {modal && (
        <OriginFormModal
          companyId={companyId}
          origin={modal.mode === 'edit' ? modal.origin : null}
          onClose={() => setModal(null)}
          onSaved={() => {
            setModal(null)
            notifySuccess(modal.mode === 'edit' ? 'Origin updated' : 'Origin created')
            refresh()
          }}
        />
      )}
    </div>
  )
}
