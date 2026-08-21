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
  FilterField,
  FilterChips,
  Select,
  type FilterChip,
  type DataTableColumn,
} from '../../shared/ui/components'
import { notifyError, notifySuccess } from '../../shared/ui/alerts'
import { OriginFormDrawer } from './OriginFormDrawer'

const DEFAULT_PAGE_SIZE = 25

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
  const [pageSize, setPageSize] = useState(DEFAULT_PAGE_SIZE)
  const [draftFilters, setDraftFilters] = useState<AppliedFilters>(DEFAULT_FILTERS)
  const [filters, setFilters] = useState<AppliedFilters>(DEFAULT_FILTERS)
  const [modal, setModal] = useState<ModalState>(null)

  const originsQuery = useQuery({
    queryKey: ['origins', companyId, page, pageSize, filters],
    queryFn: ({ signal }) =>
      fetchOrigins({
        companyId,
        page,
        size: pageSize,
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
    { key: 'code', header: tc('columns.code'), render: (origin) => <span className="tms-code">{origin.code}</span> },
    {
      key: 'name',
      header: tc('columns.name'),
      // Name and address in one cell rather than two columns. They are one fact - which place
      // this is - and splitting them made the eye cross the table to assemble it, while the
      // address column sat mostly empty because it is optional.
      render: (origin) => (
        <span className="tms-cell-stack">
          <span className="tms-cell-primary">{origin.name}</span>
          {origin.address && <span className="tms-cell-sub">{origin.address}</span>}
        </span>
      ),
    },
    { key: 'type', header: tc('columns.type'), render: (origin) => enumLabels.originType(origin.type) },
    {
      key: 'timeZone',
      header: tc('columns.timeZone'),
      render: (origin) => <span className="tms-cell-sub">{origin.timeZone}</span>,
    },
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

  /* What is actually narrowing the list right now. `active` only counts when it is not the
     default: "Activos" is the resting state of the screen, not a filter the user chose. */
  const activeChips: FilterChip[] = [
    filters.code && { key: 'code', label: tc('columns.code'), value: filters.code,
      onClear: () => { const next = { ...filters, code: '' }; setDraftFilters(next); setFilters(next); setPage(0) } },
    filters.name && { key: 'name', label: tc('columns.name'), value: filters.name,
      onClear: () => { const next = { ...filters, name: '' }; setDraftFilters(next); setFilters(next); setPage(0) } },
    filters.type && { key: 'type', label: tc('columns.type'), value: enumLabels.originType(filters.type),
      onClear: () => { const next = { ...filters, type: '' as const }; setDraftFilters(next); setFilters(next); setPage(0) } },
    filters.active !== DEFAULT_FILTERS.active && {
      key: 'active', label: tc('columns.status'),
      value: filters.active === 'all' ? tc('filters.statusAll') : tc('filters.statusInactive'),
      onClear: () => { const next = { ...filters, active: DEFAULT_FILTERS.active }; setDraftFilters(next); setFilters(next); setPage(0) } },
  ].filter(Boolean) as FilterChip[]

  const isFiltered = activeChips.length > 0

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
        <FilterField label={tc('columns.code')}>
          {(id) => (
            <input
              id={id}
              className="form-control form-control-sm"
              value={draftFilters.code}
              onChange={(event) => setDraftFilters({ ...draftFilters, code: event.target.value })}
            />
          )}
        </FilterField>
        <FilterField label={tc('columns.name')}>
          {(id) => (
            <input
              id={id}
              className="form-control form-control-sm"
              value={draftFilters.name}
              onChange={(event) => setDraftFilters({ ...draftFilters, name: event.target.value })}
            />
          )}
        </FilterField>
        <FilterField label={tc('columns.type')}>
          {(id) => (
            <Select
              id={id}
              size="sm"
              value={draftFilters.type}
              onChange={(next) => setDraftFilters({ ...draftFilters, type: next as OriginType | '' })}
              options={[
                { value: '', label: tc('filters.allTypes') },
                ...ORIGIN_TYPES.map((type) => ({ value: type, label: enumLabels.originType(type) })),
              ]}
            />
          )}
        </FilterField>
        <FilterField label={tc('columns.status')}>
          {(id) => (
            <Select
              id={id}
              size="sm"
              value={draftFilters.active}
              onChange={(next) => setDraftFilters({ ...draftFilters, active: next as ActiveFilter })}
              options={[
                { value: 'active', label: tc('filters.statusActive') },
                { value: 'inactive', label: tc('filters.statusInactive') },
                { value: 'all', label: tc('filters.statusAll') },
              ]}
            />
          )}
        </FilterField>
      </FilterBar>

      <FilterChips chips={activeChips} onClearAll={resetFilters} />

      <DataTable
        columns={columns}
        rows={pageData?.content ?? []}
        total={pageData?.totalElements}
        rowKey={(origin) => origin.id}
        isLoading={originsQuery.isPending}
        error={originsQuery.isError ? describeApiError(originsQuery.error as ApiError) : null}
        onRetry={() => void originsQuery.refetch()}
        /* Two different situations, two different exits: nothing has ever been created here, or
           the filters have narrowed everything away. Offering "create" to someone whose filter
           is simply too tight sends them to the wrong place. */
        emptyTitle={isFiltered ? tc('filters.noResultsTitle') : t('origins.empty.title')}
        emptyMessage={isFiltered ? tc('filters.noResultsMessage') : t('origins.empty.message')}
        emptyAction={
          isFiltered ? (
            <button type="button" className="btn btn-sm btn-outline-secondary" onClick={resetFilters}>
              {tc('actions.clear')}
            </button>
          ) : canManage ? (
            <button
              type="button"
              className="btn btn-sm btn-primary d-inline-flex align-items-center gap-2"
              onClick={() => setModal({ mode: 'create' })}
            >
              <i className="bi bi-plus-lg" aria-hidden="true" />
              {t('origins.new')}
            </button>
          ) : undefined
        }
        footer={
          pageData ? (
            <Pagination
              page={pageData}
              onPageChange={setPage}
              onPageSizeChange={(size) => {
                setPageSize(size)
                // Back to the first page: page 4 of the old size may not exist under the new one.
                setPage(0)
              }}
            />
          ) : undefined
        }
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
