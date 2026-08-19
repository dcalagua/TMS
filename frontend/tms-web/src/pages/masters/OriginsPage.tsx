import { keepPreviousData, useQuery, useQueryClient } from '@tanstack/react-query'
import { useState } from 'react'
import { useTranslation } from 'react-i18next'
import type { ApiError } from '../../shared/api/httpClient'
import {
  activateOrigin,
  deactivateOrigin,
  fetchOrigins,
  ORIGIN_TYPES,
  type OriginType,
  type OriginView,
} from '../../shared/api/originsApi'
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
import { OriginFormDrawer } from './OriginFormDrawer'

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
  const enumLabels = useEnumLabels()
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
    { key: 'type', header: tc('columns.type'), render: (origin) => enumLabels.originType(origin.type) },
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
        icon="box-seam"
        title={t('origins.title')}
        description={t('origins.description')}
        actions={
          canManage && (
            <button type="button" className="btn btn-primary btn-sm d-inline-flex align-items-center gap-2" onClick={() => setModal({ mode: 'create' })}>
              <i className="bi bi-plus-lg" aria-hidden="true" />
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
                {enumLabels.originType(type)}
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

      <DataTable
        columns={columns}
        rows={pageData?.content ?? []}
        total={pageData?.totalElements}
        rowKey={(origin) => origin.id}
        isLoading={originsQuery.isPending}
        error={originsQuery.isError ? describeApiError(originsQuery.error as ApiError) : null}
        onRetry={() => void originsQuery.refetch()}
        emptyTitle={t('origins.empty.title')}
        emptyMessage={t('origins.empty.message')}
        footer={pageData ? <Pagination page={pageData} onPageChange={setPage} /> : undefined}
      />

      {modal && (
        <OriginFormDrawer
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
