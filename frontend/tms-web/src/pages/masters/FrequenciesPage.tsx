import { keepPreviousData, useQuery, useQueryClient } from '@tanstack/react-query'
import { useState } from 'react'
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
  StatusBadge,
  type DataTableColumn,
} from '../../shared/ui/components'
import { notifyError, notifySuccess } from '../../shared/ui/alerts'
import { FrequencyFormModal } from './FrequencyFormModal'

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
      title: frequency.active ? 'Deactivate frequency?' : 'Activate frequency?',
      text: frequency.active
        ? `${frequency.name} will no longer be selectable for new planning.`
        : `${frequency.name} will become selectable again.`,
      confirmLabel: frequency.active ? 'Deactivate' : 'Activate',
      dangerous: frequency.active,
    })
    if (!confirmed) return

    try {
      if (frequency.active) {
        await deactivateFrequency(companyId, frequency.id)
        notifySuccess('Frequency deactivated', frequency.name)
      } else {
        await activateFrequency(companyId, frequency.id)
        notifySuccess('Frequency activated', frequency.name)
      }
      refresh()
    } catch (error) {
      notifyError('Could not update the frequency', describeApiError(error as ApiError))
    }
  }

  const columns: DataTableColumn<FrequencyView>[] = [
    { key: 'code', header: 'Code', render: (frequency) => <span className="fw-semibold">{frequency.code}</span> },
    { key: 'name', header: 'Name', render: (frequency) => frequency.name },
    { key: 'days', header: 'Days', render: (frequency) => summarizeDays(frequency) },
    { key: 'description', header: 'Description', render: (frequency) => frequency.description ?? '—' },
    {
      key: 'active',
      header: 'Status',
      render: (frequency) => (
        <StatusBadge label={frequency.active ? 'Active' : 'Inactive'} tone={frequency.active ? 'success' : 'neutral'} />
      ),
    },
  ]

  if (canManage) {
    columns.push({
      key: 'actions',
      header: '',
      className: 'text-end',
      render: (frequency) => (
        <div className="btn-group btn-group-sm">
          <button
            type="button"
            className="btn btn-outline-secondary"
            onClick={() => setModal({ mode: 'edit', frequency })}
          >
            Edit
          </button>
          <button
            type="button"
            className={`btn btn-outline-${frequency.active ? 'danger' : 'success'}`}
            onClick={() => void toggleActive(frequency)}
          >
            {frequency.active ? 'Deactivate' : 'Activate'}
          </button>
        </div>
      ),
    })
  }

  const pageData = frequenciesQuery.data

  return (
    <div>
      <PageHeader
        title="Frequencies"
        description="Delivery schedules: weekly cadence, cutoff and lead-time rules."
        actions={
          canManage && (
            <button type="button" className="btn btn-primary btn-sm" onClick={() => setModal({ mode: 'create' })}>
              New frequency
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
            rowKey={(frequency) => frequency.id}
            isLoading={frequenciesQuery.isPending}
            error={frequenciesQuery.isError ? describeApiError(frequenciesQuery.error as ApiError) : null}
            onRetry={() => void frequenciesQuery.refetch()}
            emptyTitle="No frequencies found"
            emptyMessage="Create a frequency or adjust your filters."
          />
        </div>
        {pageData && (
          <div className="card-footer">
            <Pagination page={pageData} onPageChange={setPage} />
          </div>
        )}
      </div>

      {modal && (
        <FrequencyFormModal
          companyId={companyId}
          frequency={modal.mode === 'edit' ? modal.frequency : null}
          onClose={() => setModal(null)}
          onSaved={() => {
            setModal(null)
            notifySuccess(modal.mode === 'edit' ? 'Frequency updated' : 'Frequency created')
            refresh()
          }}
        />
      )}
    </div>
  )
}
