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
  ActionMenu,
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
  const { t: tc } = useTranslation('common')
  const { t: td } = useTranslation('dialogs')
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
      title: origin.active
        ? td('deactivate.title', { name: origin.name })
        : td('activate.title', { name: origin.name }),
      text: origin.active ? td('deactivate.text') : td('activate.text'),
      confirmLabel: origin.active ? tc('actions.deactivate') : tc('actions.activate'),
      dangerous: origin.active,
    })
    if (!confirmed) return

    try {
      if (origin.active) {
        await deactivateOrigin(companyId, origin.id)
        notifySuccess(td('deactivated'), origin.name)
      } else {
        await activateOrigin(companyId, origin.id)
        notifySuccess(td('activated'), origin.name)
      }
      refresh()
    } catch (error) {
      notifyError(td('errorTitle'), describeApiError(error as ApiError))
    }
  }

  const columns: DataTableColumn<OriginView>[] = [
    { key: 'code', header: tc('columns.code'), render: (origin) => <span className="fw-semibold">{origin.code}</span> },
    { key: 'name', header: tc('columns.name'), render: (origin) => origin.name },
    { key: 'type', header: tc('columns.type'), render: (origin) => ORIGIN_TYPE_LABELS[origin.type] },
    { key: 'address', header: tc('columns.address'), render: (origin) => origin.address ?? '—' },
    { key: 'timeZone', header: tc('columns.timeZone'), render: (origin) => origin.timeZone },
    {
      key: 'active',
      header: tc('columns.status'),
      render: (origin) => (
        <ActiveBadge active={origin.active} />
      ),
    },
  ]

  if (canManage) {
    columns.push({
      key: 'actions',
      header: tc('columns.actions'),
      actions: true,
      render: (origin) => (
        <ActionMenu
          items={[
            {
              key: 'edit',
              label: tc('actions.edit'),
              icon: 'bi-pencil',
              onSelect: () => setModal({ mode: 'edit', origin }),
            },
            {
              key: 'active',
              label: origin.active ? tc('actions.deactivate') : tc('actions.activate'),
              icon: origin.active ? 'bi-slash-circle' : 'bi-check-circle',
              dangerous: origin.active,
              onSelect: () => void toggleActive(origin),
            },
          ]}
        />
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
            onChange={(event) => setDraftFilters({ ...draftFilters, type: event.target.value as OriginType | '' })}
          >
            <option value="">{tc('filters.allTypes')}</option>
            {ORIGIN_TYPES.map((type) => (
              <option key={type} value={type}>
                {ORIGIN_TYPE_LABELS[type]}
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
            rowKey={(origin) => origin.id}
            isLoading={originsQuery.isPending}
            error={originsQuery.isError ? describeApiError(originsQuery.error as ApiError) : null}
            onRetry={() => void originsQuery.refetch()}
            emptyTitle={t('origins.empty.title')}
            emptyMessage={t('origins.empty.message')}
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
            notifySuccess(modal.mode === 'edit' ? td('updated') : td('created'))
            refresh()
          }}
        />
      )}
    </div>
  )
}
