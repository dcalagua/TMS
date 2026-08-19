import { keepPreviousData, useQuery, useQueryClient } from '@tanstack/react-query'
import { useState } from 'react'
import { useTranslation } from 'react-i18next'
import type { ApiError } from '../../shared/api/httpClient'
import {
  activateFrequency,
  deactivateFrequency,
  fetchFrequencies,
  type FrequencyView,
} from '../../shared/api/frequenciesApi'
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
import { FrequencyFormDrawer } from './FrequencyFormDrawer'

const PAGE_SIZE = 25

const DAY_ABBREVIATIONS: Record<number, string> = { 1: 'Mon', 2: 'Tue', 3: 'Wed', 4: 'Thu', 5: 'Fri', 6: 'Sat', 7: 'Sun' }

function summarizeDays(frequency: FrequencyView): string {
  const enabledDays = frequency.weeklyRules.filter((rule) => rule.enabled).map((rule) => DAY_ABBREVIATIONS[rule.dayOfWeek])
  return enabledDays.length > 0 ? enabledDays.join(', ') : '—'
}

type ActiveFilter = 'active' | 'inactive' | 'all'

interface AppliedFilters {
  code: string
  name: string
  active: ActiveFilter
}

const DEFAULT_FILTERS: AppliedFilters = { code: '', name: '', active: 'active' }

type ModalState = { mode: 'create' } | { mode: 'edit'; frequency: FrequencyView } | null

export function FrequenciesPage() {
  const { t } = useTranslation('masters')
  const { t: tc } = useTranslation('common')
  const { t: td } = useTranslation('dialogs')
  const { selected, hasPermission } = useCompany()
  const companyId = selected?.id ?? ''
  const canManage = hasPermission('masterdata.frequency:manage')
  const queryClient = useQueryClient()

  const [page, setPage] = useState(0)
  const [draftFilters, setDraftFilters] = useState<AppliedFilters>(DEFAULT_FILTERS)
  const [filters, setFilters] = useState<AppliedFilters>(DEFAULT_FILTERS)
  const [modal, setModal] = useState<ModalState>(null)

  const frequenciesQuery = useQuery({
    queryKey: ['frequencies', companyId, page, filters],
    queryFn: ({ signal }) =>
      fetchFrequencies({
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
    void queryClient.invalidateQueries({ queryKey: ['frequencies', companyId] })
  }

  async function toggleActive(frequency: FrequencyView) {
    const confirmed = await confirmDialog({
      title: frequency.active
        ? td('deactivate.title', { name: frequency.name })
        : td('activate.title', { name: frequency.name }),
      text: frequency.active ? td('deactivate.text') : td('activate.text'),
      confirmLabel: frequency.active ? tc('actions.deactivate') : tc('actions.activate'),
      dangerous: frequency.active,
    })
    if (!confirmed) return

    try {
      if (frequency.active) {
        await deactivateFrequency(companyId, frequency.id)
        notifySuccess(td('deactivated'), frequency.name)
      } else {
        await activateFrequency(companyId, frequency.id)
        notifySuccess(td('activated'), frequency.name)
      }
      refresh()
    } catch (error) {
      notifyError(td('errorTitle'), describeApiError(error as ApiError))
    }
  }

  const columns: DataTableColumn<FrequencyView>[] = [
    { key: 'code', header: tc('columns.code'), render: (frequency) => <span className="fw-semibold">{frequency.code}</span> },
    { key: 'name', header: tc('columns.name'), render: (frequency) => frequency.name },
    { key: 'days', header: tc('columns.days'), render: (frequency) => summarizeDays(frequency) },
    { key: 'description', header: tc('columns.description'), render: (frequency) => frequency.description ?? '—' },
    {
      key: 'active',
      header: tc('columns.status'),
      render: (frequency) => (
        <ActiveBadge active={frequency.active} />
      ),
    },
  ]

  if (canManage) {
    columns.push({
      key: 'actions',
      header: tc('columns.actions'),
      actions: true,
      render: (frequency) => (
        <ActionMenu
          items={[
            {
              key: 'edit',
              label: tc('actions.edit'),
              icon: 'bi-pencil',
              onSelect: () => setModal({ mode: 'edit', frequency }),
            },
            {
              key: 'active',
              label: frequency.active ? tc('actions.deactivate') : tc('actions.activate'),
              icon: frequency.active ? 'bi-slash-circle' : 'bi-check-circle',
              dangerous: frequency.active,
              onSelect: () => void toggleActive(frequency),
            },
          ]}
        />
      ),
    })
  }

  const pageData = frequenciesQuery.data

  return (
    <div>
      <PageHeader
        icon="calendar-week"
        title={t('frequencies.title')}
        description={t('frequencies.description')}
        actions={
          canManage && (
            <button type="button" className="btn btn-primary btn-sm d-inline-flex align-items-center gap-2" onClick={() => setModal({ mode: 'create' })}>
              <i className="bi bi-plus-lg" aria-hidden="true" />
              {t('frequencies.new')}
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
        rowKey={(frequency) => frequency.id}
        isLoading={frequenciesQuery.isPending}
        error={frequenciesQuery.isError ? describeApiError(frequenciesQuery.error as ApiError) : null}
        onRetry={() => void frequenciesQuery.refetch()}
        emptyTitle={t('frequencies.empty.title')}
        emptyMessage={t('frequencies.empty.message')}
        footer={pageData ? <Pagination page={pageData} onPageChange={setPage} /> : undefined}
      />

      {modal && (
        <FrequencyFormDrawer
          companyId={companyId}
          frequency={modal.mode === 'edit' ? modal.frequency : null}
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
