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
  ActionMenu,
  ActiveBadge,
  type DataTableColumn,
} from '../../shared/ui/components'
import { notifyError, notifySuccess } from '../../shared/ui/alerts'
import { RouteFormDrawer } from './RouteFormDrawer'

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
  const { t: tc } = useTranslation('common')
  const { t: td } = useTranslation('dialogs')
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
      title: route.active
        ? td('deactivate.title', { name: route.name })
        : td('activate.title', { name: route.name }),
      text: route.active ? td('deactivate.text') : td('activate.text'),
      confirmLabel: route.active ? tc('actions.deactivate') : tc('actions.activate'),
      dangerous: route.active,
    })
    if (!confirmed) return

    try {
      if (route.active) {
        await deactivateRoute(companyId, route.id)
        notifySuccess(td('deactivated'), route.name)
      } else {
        await activateRoute(companyId, route.id)
        notifySuccess(td('activated'), route.name)
      }
      refresh()
    } catch (error) {
      notifyError(td('errorTitle'), describeApiError(error as ApiError))
    }
  }

  const columns: DataTableColumn<RouteView>[] = [
    { key: 'code', header: tc('columns.code'), render: (route) => <span className="fw-semibold">{route.code}</span> },
    { key: 'name', header: tc('columns.name'), render: (route) => route.name },
    { key: 'origin', header: tc('columns.origin'), render: (route) => route.originName ?? route.originCode ?? '—' },
    { key: 'zone', header: tc('columns.zone'), render: (route) => route.zoneName ?? '—' },
    { key: 'stops', header: tc('columns.stops'), className: 'text-end', render: (route) => route.stopCount },
    {
      key: 'active',
      header: tc('columns.status'),
      render: (route) => <ActiveBadge active={route.active} />,
    },
  ]

  if (canManage) {
    columns.push({
      key: 'actions',
      header: tc('columns.actions'),
      actions: true,
      render: (route) => (
        <ActionMenu
          items={[
            {
              key: 'edit',
              label: tc('actions.edit'),
              icon: 'bi-pencil',
              onSelect: () => setModal({ mode: 'edit', routeId: route.id }),
            },
            {
              key: 'active',
              label: route.active ? tc('actions.deactivate') : tc('actions.activate'),
              icon: route.active ? 'bi-slash-circle' : 'bi-check-circle',
              dangerous: route.active,
              onSelect: () => void toggleActive(route),
            },
          ]}
        />
      ),
    })
  }

  const pageData = routesQuery.data

  return (
    <div>
      <PageHeader
        icon="signpost-split"
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
          <label htmlFor="filter-origin" className="form-label small mb-1">
            {tc('columns.origin')}
          </label>
          <select
            id="filter-origin"
            className="form-select form-select-sm"
            value={draftFilters.originId}
            onChange={(event) => setDraftFilters({ ...draftFilters, originId: event.target.value })}
          >
            <option value="">{tc('filters.allOrigins')}</option>
            {origins.map((origin) => (
              <option key={origin.id} value={origin.id}>
                {origin.name}
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
            total={pageData?.totalElements}
            rowKey={(route) => route.id}
            isLoading={routesQuery.isPending}
            error={routesQuery.isError ? describeApiError(routesQuery.error as ApiError) : null}
            onRetry={() => void routesQuery.refetch()}
            emptyTitle={t('routes.empty.title')}
            emptyMessage={t('routes.empty.message')}
          />
        </div>
        {pageData && (
          <div className="card-footer">
            <Pagination page={pageData} onPageChange={setPage} />
          </div>
        )}
      </div>

      {modal && (
        <RouteFormDrawer
          companyId={companyId}
          routeId={modal.mode === 'edit' ? modal.routeId : null}
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
