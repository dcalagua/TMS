import { keepPreviousData, useQuery, useQueryClient } from '@tanstack/react-query'
import { useState } from 'react'
import { useTranslation } from 'react-i18next'
import type { ApiError } from '../../shared/api/httpClient'
import { activateRoute, deactivateRoute, fetchRoutes, type RouteView } from '../../shared/api/routesApi'
import { fetchOrigins } from '../../shared/api/originsApi'
import { fetchZones } from '../../shared/api/zonesApi'
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
import { RouteFormModal } from './RouteFormModal'

const PAGE_SIZE = 25

type ActiveFilter = 'active' | 'inactive' | 'all'

interface AppliedFilters {
  code: string
  name: string
  originId: string
  zoneId: string
  active: ActiveFilter
}

const DEFAULT_FILTERS: AppliedFilters = { code: '', name: '', originId: '', zoneId: '', active: 'active' }

type ModalState = { mode: 'create' } | { mode: 'edit'; routeId: string } | null

export function RoutesPage() {
  const { t } = useTranslation('masters')
  const { selected, hasPermission } = useCompany()
  const companyId = selected?.id ?? ''
  const canManage = hasPermission('masterdata.route:manage')
  const queryClient = useQueryClient()

  const [page, setPage] = useState(0)
  const [draftFilters, setDraftFilters] = useState<AppliedFilters>(DEFAULT_FILTERS)
  const [filters, setFilters] = useState<AppliedFilters>(DEFAULT_FILTERS)
  const [modal, setModal] = useState<ModalState>(null)

  const routesQuery = useQuery({
    queryKey: ['routes', companyId, page, filters],
    queryFn: ({ signal }) =>
      fetchRoutes({
        companyId,
        page,
        size: PAGE_SIZE,
        sort: 'code,asc',
        code: filters.code || undefined,
        name: filters.name || undefined,
        originId: filters.originId || undefined,
        zoneId: filters.zoneId || undefined,
        active: filters.active === 'all' ? undefined : filters.active === 'active',
        signal,
      }),
    placeholderData: keepPreviousData,
  })

  const originsQuery = useQuery({
    queryKey: ['origins-for-route-filter', companyId],
    queryFn: ({ signal }) => fetchOrigins({ companyId, size: 200, active: true, sort: 'code,asc', signal }),
    enabled: companyId !== '',
  })
  const origins = originsQuery.data?.content ?? []

  const zonesQuery = useQuery({
    queryKey: ['zones-for-route-filter', companyId],
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
    void queryClient.invalidateQueries({ queryKey: ['routes', companyId] })
  }

  async function toggleActive(route: RouteView) {
    const confirmed = await confirmDialog({
      title: route.active ? 'Deactivate route?' : 'Activate route?',
      text: route.active
        ? `${route.name} will no longer be selectable for new planning. Existing trip history that referenced it is not affected.`
        : `${route.name} will become selectable again.`,
      confirmLabel: route.active ? 'Deactivate' : 'Activate',
      dangerous: route.active,
    })
    if (!confirmed) return

    try {
      if (route.active) {
        await deactivateRoute(companyId, route.id)
        notifySuccess('Route deactivated', route.name)
      } else {
        await activateRoute(companyId, route.id)
        notifySuccess('Route activated', route.name)
      }
      refresh()
    } catch (error) {
      notifyError('Could not update the route', describeApiError(error as ApiError))
    }
  }

  const columns: DataTableColumn<RouteView>[] = [
    { key: 'code', header: 'Code', render: (route) => <span className="fw-semibold">{route.code}</span> },
    { key: 'name', header: 'Name', render: (route) => route.name },
    { key: 'origin', header: 'Origin', render: (route) => route.originName ?? route.originCode ?? '—' },
    { key: 'zone', header: 'Zone', render: (route) => route.zoneName ?? '—' },
    { key: 'stops', header: 'Stops', className: 'text-end', render: (route) => route.stopCount },
    {
      key: 'active',
      header: 'Status',
      render: (route) => <ActiveBadge active={route.active} />,
    },
  ]

  if (canManage) {
    columns.push({
      key: 'actions',
      header: '',
      className: 'text-end',
      render: (route) => (
        <div className="btn-group btn-group-sm">
          <button
            type="button"
            className="btn btn-outline-secondary"
            onClick={() => setModal({ mode: 'edit', routeId: route.id })}
          >
            Edit
          </button>
          <button
            type="button"
            className={`btn btn-outline-${route.active ? 'danger' : 'success'}`}
            onClick={() => void toggleActive(route)}
          >
            {route.active ? 'Deactivate' : 'Activate'}
          </button>
        </div>
      ),
    })
  }

  const pageData = routesQuery.data

  return (
    <div>
      <PageHeader
        title={t('routes.title')}
        description={t('routes.description')}
        actions={
          canManage && (
            <button type="button" className="btn btn-primary btn-sm" onClick={() => setModal({ mode: 'create' })}>
              {t('routes.new')}
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
            rowKey={(route) => route.id}
            isLoading={routesQuery.isPending}
            error={routesQuery.isError ? describeApiError(routesQuery.error as ApiError) : null}
            onRetry={() => void routesQuery.refetch()}
            emptyTitle="No routes found"
            emptyMessage="Create a route or adjust your filters."
          />
        </div>
        {pageData && (
          <div className="card-footer">
            <Pagination page={pageData} onPageChange={setPage} />
          </div>
        )}
      </div>

      {modal && (
        <RouteFormModal
          companyId={companyId}
          routeId={modal.mode === 'edit' ? modal.routeId : null}
          onClose={() => setModal(null)}
          onSaved={() => {
            setModal(null)
            notifySuccess(modal.mode === 'edit' ? 'Route updated' : 'Route created')
            refresh()
          }}
        />
      )}
    </div>
  )
}
