import { useEnumLabels } from '../../shared/i18n/enums'
import { keepPreviousData, useQuery } from '@tanstack/react-query'
import { useState } from 'react'
import { useTranslation } from 'react-i18next'
import { useNavigate } from 'react-router-dom'
import type { ApiError } from '../../shared/api/httpClient'
import { fetchOrigins } from '../../shared/api/originsApi'
import {
  fetchPlanningRuns,
  PLANNING_RUN_STATUSES,
  type PlanningRunStatus,
  type PlanningRunView,
} from '../../shared/api/planningApi'
import { describeApiError } from '../../shared/api/problemMessages'
import { useCompany } from '../../shared/company/CompanyContext'
import {
  DataTable,
  FilterBar,
  PageHeader,
  Pagination,
  StatusBadge,
  type DataTableColumn,
  type StatusTone,
} from '../../shared/ui/components'
import { PlanningRunFormModal } from './PlanningRunFormModal'

const PAGE_SIZE = 20

const STATUS_TONE: Record<PlanningRunStatus, StatusTone> = {
  DRAFT: 'info',
  CONFIRMED: 'success',
  CANCELLED: 'danger',
}

interface AppliedFilters {
  planNumber: string
  originId: string
  planningDateFrom: string
  planningDateTo: string
  status: PlanningRunStatus | ''
}

const DEFAULT_FILTERS: AppliedFilters = { planNumber: '', originId: '', planningDateFrom: '', planningDateTo: '', status: '' }

/** Entry point for manual planning: find or open a run, then hand off to `PlanningBoardPage` for
 * everything that happens inside it (`docs/domain/PLANNING_MANUAL_V1.md`, "The flow"). Runs are
 * never edited from here - only listed, filtered and created. */
export function PlanningRunsPage() {
  const { t } = useTranslation('planning')
  const enumLabels = useEnumLabels()
  const { selected, hasPermission } = useCompany()
  const companyId = selected?.id ?? ''
  const canManage = hasPermission('planning.plan:manage')
  const navigate = useNavigate()

  const [page, setPage] = useState(0)
  const [draftFilters, setDraftFilters] = useState<AppliedFilters>(DEFAULT_FILTERS)
  const [filters, setFilters] = useState<AppliedFilters>(DEFAULT_FILTERS)
  const [showCreate, setShowCreate] = useState(false)

  const runsQuery = useQuery({
    queryKey: ['planning-runs', companyId, page, filters],
    queryFn: ({ signal }) =>
      fetchPlanningRuns({
        companyId,
        page,
        size: PAGE_SIZE,
        sort: 'planningDate,desc',
        planNumber: filters.planNumber || undefined,
        originId: filters.originId || undefined,
        planningDateFrom: filters.planningDateFrom || undefined,
        planningDateTo: filters.planningDateTo || undefined,
        status: filters.status || undefined,
        signal,
      }),
    placeholderData: keepPreviousData,
  })

  const originsQuery = useQuery({
    queryKey: ['origins-for-planning-run-filter', companyId],
    queryFn: ({ signal }) => fetchOrigins({ companyId, size: 200, active: true, sort: 'code,asc', signal }),
    enabled: companyId !== '',
  })
  const origins = originsQuery.data?.content ?? []

  function applyFilters() {
    setFilters(draftFilters)
    setPage(0)
  }

  function resetFilters() {
    setDraftFilters(DEFAULT_FILTERS)
    setFilters(DEFAULT_FILTERS)
    setPage(0)
  }

  const columns: DataTableColumn<PlanningRunView>[] = [
    { key: 'planNumber', header: 'Plan #', render: (run) => <span className="fw-semibold">{run.planNumber}</span> },
    { key: 'origin', header: 'Origin', render: (run) => run.originName ?? run.originCode ?? '—' },
    { key: 'planningDate', header: 'Planning date', render: (run) => run.planningDate },
    {
      key: 'status',
      header: 'Status',
      render: (run) => <StatusBadge label={enumLabels.planningRunStatus(run.status)} tone={STATUS_TONE[run.status]} />,
    },
    { key: 'trips', header: 'Trips', className: 'text-end', render: (run) => run.tripCount },
    { key: 'orders', header: 'Orders assigned', className: 'text-end', render: (run) => run.assignedOrderCount },
    {
      key: 'actions',
      header: '',
      className: 'text-end',
      render: (run) => (
        <button type="button" className="btn btn-sm btn-outline-primary" onClick={() => navigate(`/planning/${run.id}`)}>
          Open
        </button>
      ),
    },
  ]

  const pageData = runsQuery.data

  return (
    <div>
      <PageHeader
        title={t('title')}
        description={t('description')}
        actions={
          canManage && (
            <button type="button" className="btn btn-primary btn-sm" onClick={() => setShowCreate(true)}>
              {t('new')}
            </button>
          )
        }
      />

      <FilterBar onSubmit={applyFilters} onReset={resetFilters}>
        <div>
          <label htmlFor="filter-plan-number" className="form-label small mb-1">
            Plan #
          </label>
          <input
            id="filter-plan-number"
            className="form-control form-control-sm"
            value={draftFilters.planNumber}
            onChange={(event) => setDraftFilters({ ...draftFilters, planNumber: event.target.value })}
          />
        </div>
        <div>
          <label htmlFor="filter-origin" className="form-label small mb-1">
            Origin
          </label>
          <select
            id="filter-origin"
            className="form-select form-select-sm"
            value={draftFilters.originId}
            onChange={(event) => setDraftFilters({ ...draftFilters, originId: event.target.value })}
          >
            <option value="">All origins</option>
            {origins.map((origin) => (
              <option key={origin.id} value={origin.id}>
                {origin.name}
              </option>
            ))}
          </select>
        </div>
        <div>
          <label htmlFor="filter-date-from" className="form-label small mb-1">
            Planning date from
          </label>
          <input
            id="filter-date-from"
            type="date"
            className="form-control form-control-sm"
            value={draftFilters.planningDateFrom}
            onChange={(event) => setDraftFilters({ ...draftFilters, planningDateFrom: event.target.value })}
          />
        </div>
        <div>
          <label htmlFor="filter-date-to" className="form-label small mb-1">
            Planning date to
          </label>
          <input
            id="filter-date-to"
            type="date"
            className="form-control form-control-sm"
            value={draftFilters.planningDateTo}
            onChange={(event) => setDraftFilters({ ...draftFilters, planningDateTo: event.target.value })}
          />
        </div>
        <div>
          <label htmlFor="filter-status" className="form-label small mb-1">
            Status
          </label>
          <select
            id="filter-status"
            className="form-select form-select-sm"
            value={draftFilters.status}
            onChange={(event) => setDraftFilters({ ...draftFilters, status: event.target.value as PlanningRunStatus | '' })}
          >
            <option value="">All statuses</option>
            {PLANNING_RUN_STATUSES.map((status) => (
              <option key={status} value={status}>
                {enumLabels.planningRunStatus(status)}
              </option>
            ))}
          </select>
        </div>
      </FilterBar>

      <div className="card shadow-sm">
        <div className="card-body p-0">
          <DataTable
            columns={columns}
            rows={pageData?.content ?? []}
            rowKey={(run) => run.id}
            isLoading={runsQuery.isPending}
            error={runsQuery.isError ? describeApiError(runsQuery.error as ApiError) : null}
            onRetry={() => void runsQuery.refetch()}
            emptyTitle="No planning runs found"
            emptyMessage="Open a run for an origin and a planning date to start assigning orders to trips."
          />
        </div>
        {pageData && (
          <div className="card-footer">
            <Pagination page={pageData} onPageChange={setPage} />
          </div>
        )}
      </div>

      {showCreate && (
        <PlanningRunFormModal
          companyId={companyId}
          onClose={() => setShowCreate(false)}
          onCreated={(run) => navigate(`/planning/${run.run.id}`)}
        />
      )}
    </div>
  )
}
