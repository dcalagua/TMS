import { keepPreviousData, useQuery, useQueryClient } from '@tanstack/react-query'
import { useState } from 'react'
import { useTranslation } from 'react-i18next'
import type { ApiError } from '../../shared/api/httpClient'
import {
  activateLocation,
  deactivateLocation,
  fetchLocations,
  LOCATION_ROLES,
  LOCATION_TYPES,
  type LocationRole,
  type LocationType,
  type LocationView,
} from '../../shared/api/locationsApi'
import { fetchZones } from '../../shared/api/zonesApi'
import { describeApiError } from '../../shared/api/problemMessages'
import { useEnumLabels } from '../../shared/i18n/enums'
import { useCompany } from '../../shared/company/CompanyContext'
import {
  ActionMenu,
  ActiveBadge,
  confirmDialog,
  DataTable,
  FilterBar,
  FilterChips,
  FilterField,
  PageHeader,
  Pagination,
  Select,
  type DataTableColumn,
  type FilterChip,
} from '../../shared/ui/components'
import { notifyError, notifySuccess } from '../../shared/ui/alerts'
import { LocationFormDrawer } from './LocationFormDrawer'

const DEFAULT_PAGE_SIZE = 25

type ActiveFilter = 'active' | 'inactive' | 'all'

interface AppliedFilters {
  search: string
  type: LocationType | ''
  role: LocationRole | ''
  zoneId: string
  active: ActiveFilter
}

const DEFAULT_FILTERS: AppliedFilters = { search: '', type: '', role: '', zoneId: '', active: 'active' }

type ModalState = { mode: 'create' } | { mode: 'edit'; location: LocationView } | null

/**
 * The canonical master-data screen for physical places (migration V14).
 *
 * One search box rather than separate code and name filters: this is the master an operator
 * looks something up in, and they look it up by whichever of code, name or external reference
 * they happen to remember - which is exactly what the backend's `search` parameter spans.
 */
export function LocationsPage() {
  const { t } = useTranslation('masters')
  const { t: tc } = useTranslation('common')
  const { t: td } = useTranslation('dialogs')
  const enumLabels = useEnumLabels()
  const { selected, hasPermission } = useCompany()
  const companyId = selected?.id ?? ''
  const canManage = hasPermission('masterdata.location:manage')
  const queryClient = useQueryClient()

  const [page, setPage] = useState(0)
  const [pageSize, setPageSize] = useState(DEFAULT_PAGE_SIZE)
  const [draftFilters, setDraftFilters] = useState<AppliedFilters>(DEFAULT_FILTERS)
  const [filters, setFilters] = useState<AppliedFilters>(DEFAULT_FILTERS)
  const [modal, setModal] = useState<ModalState>(null)

  const locationsQuery = useQuery({
    queryKey: ['locations', companyId, page, pageSize, filters],
    queryFn: ({ signal }) =>
      fetchLocations({
        companyId,
        page,
        size: pageSize,
        sort: 'code,asc',
        search: filters.search || undefined,
        type: filters.type || undefined,
        role: filters.role || undefined,
        zoneId: filters.zoneId || undefined,
        active: filters.active === 'all' ? undefined : filters.active === 'active',
        signal,
      }),
    placeholderData: keepPreviousData,
  })

  const zonesQuery = useQuery({
    queryKey: ['zones-for-filter', companyId],
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

  function clearOne(patch: Partial<AppliedFilters>) {
    const next = { ...filters, ...patch }
    setDraftFilters(next)
    setFilters(next)
    setPage(0)
  }

  function refresh() {
    void queryClient.invalidateQueries({ queryKey: ['locations', companyId] })
    // The projections changed too, so any Origins/Destinations list already in the cache is
    // stale. Invalidating them here is what stops the three screens disagreeing after an edit.
    void queryClient.invalidateQueries({ queryKey: ['origins', companyId] })
    void queryClient.invalidateQueries({ queryKey: ['destinations', companyId] })
  }

  async function toggleActive(location: LocationView) {
    const confirmed = await confirmDialog({
      title: location.active
        ? td('deactivate.title', { name: location.name })
        : td('activate.title', { name: location.name }),
      text: location.active ? td('deactivate.text') : td('activate.text'),
      confirmLabel: location.active ? tc('actions.deactivate') : tc('actions.activate'),
      dangerous: location.active,
    })
    if (!confirmed) return

    try {
      if (location.active) {
        await deactivateLocation(companyId, location.id)
        notifySuccess(td('deactivated'), location.name)
      } else {
        await activateLocation(companyId, location.id)
        notifySuccess(td('activated'), location.name)
      }
      refresh()
    } catch (error) {
      notifyError(td('errorTitle'), describeApiError(error as ApiError))
    }
  }

  /** What the location can actually be used for, which is not the same as the roles it lists. */
  function usableAs(location: LocationView): string {
    const uses = [
      location.originId ? t('locations.usableAs.origin') : null,
      location.destinationId ? t('locations.usableAs.shipTo') : null,
    ].filter(Boolean)
    return uses.length > 0 ? uses.join(' / ') : t('locations.usableAs.none')
  }

  const columns: DataTableColumn<LocationView>[] = [
    { key: 'code', header: tc('columns.code'), render: (location) => <span className="tms-code">{location.code}</span> },
    {
      key: 'name',
      header: tc('columns.name'),
      // Name and address in one cell, the same decision OriginsPage documents: they are one
      // fact - which place this is - and the address column would sit mostly empty on its own.
      render: (location) => (
        <span className="tms-cell-stack">
          <span className="tms-cell-primary">{location.name}</span>
          {location.address && <span className="tms-cell-sub">{location.address}</span>}
        </span>
      ),
    },
    { key: 'type', header: tc('columns.type'), render: (location) => enumLabels.locationType(location.type) },
    {
      key: 'roles',
      header: t('locations.columns.roles'),
      render: (location) => (
        <span className="d-inline-flex flex-wrap gap-1">
          {location.roles.map((role) => (
            <span key={role} className="badge text-bg-light border">
              {enumLabels.locationRole(role)}
            </span>
          ))}
        </span>
      ),
    },
    {
      key: 'usableAs',
      header: t('locations.columns.usableAs'),
      render: (location) => <span className="tms-cell-sub">{usableAs(location)}</span>,
    },
    { key: 'zone', header: tc('columns.zone'), render: (location) => location.zoneName ?? '—' },
    { key: 'active', header: tc('columns.status'), render: (location) => <ActiveBadge active={location.active} /> },
  ]

  if (canManage) {
    columns.push({
      key: 'actions',
      header: tc('columns.actions'),
      actions: true,
      render: (location) => (
        <ActionMenu
          items={[
            {
              key: 'edit',
              label: tc('actions.edit'),
              icon: 'bi-pencil',
              onSelect: () => setModal({ mode: 'edit', location }),
            },
            {
              key: 'active',
              label: location.active ? tc('actions.deactivate') : tc('actions.activate'),
              icon: location.active ? 'bi-slash-circle' : 'bi-check-circle',
              dangerous: location.active,
              onSelect: () => void toggleActive(location),
            },
          ]}
        />
      ),
    })
  }

  const pageData = locationsQuery.data

  /* What is narrowing the list right now. `active` only counts when it is not the default:
     "Activos" is the resting state of the screen, not a filter the user chose. */
  const activeChips: FilterChip[] = [
    filters.search && {
      key: 'search', label: t('locations.filters.searchLabel'), value: filters.search,
      onClear: () => clearOne({ search: '' }),
    },
    filters.type && {
      key: 'type', label: tc('columns.type'), value: enumLabels.locationType(filters.type),
      onClear: () => clearOne({ type: '' }),
    },
    filters.role && {
      key: 'role', label: t('locations.columns.roles'), value: enumLabels.locationRole(filters.role),
      onClear: () => clearOne({ role: '' }),
    },
    filters.zoneId && {
      key: 'zone', label: tc('columns.zone'),
      value: zones.find((zone) => zone.id === filters.zoneId)?.name ?? filters.zoneId,
      onClear: () => clearOne({ zoneId: '' }),
    },
    filters.active !== DEFAULT_FILTERS.active && {
      key: 'active', label: tc('columns.status'),
      value: filters.active === 'all' ? tc('filters.statusAll') : tc('filters.statusInactive'),
      onClear: () => clearOne({ active: DEFAULT_FILTERS.active }),
    },
  ].filter(Boolean) as FilterChip[]

  const isFiltered = activeChips.length > 0

  return (
    <div>
      <PageHeader
        icon="geo-alt-fill"
        title={t('locations.title')}
        description={t('locations.description')}
        actions={
          canManage && (
            <button
              type="button"
              className="btn btn-primary btn-sm d-inline-flex align-items-center gap-2"
              onClick={() => setModal({ mode: 'create' })}
            >
              <i className="bi bi-plus-lg" aria-hidden="true" />
              {t('locations.new')}
            </button>
          )
        }
      />

      <FilterBar onSubmit={applyFilters} onReset={resetFilters}>
        <FilterField label={t('locations.filters.searchLabel')}>
          {(id) => (
            <input
              id={id}
              type="search"
              className="form-control form-control-sm"
              placeholder={t('locations.filters.searchPlaceholder')}
              value={draftFilters.search}
              onChange={(event) => setDraftFilters({ ...draftFilters, search: event.target.value })}
            />
          )}
        </FilterField>
        <FilterField label={tc('columns.type')}>
          {(id) => (
            <Select
              id={id}
              size="sm"
              value={draftFilters.type}
              onChange={(next) => setDraftFilters({ ...draftFilters, type: next as LocationType | '' })}
              options={[
                { value: '', label: tc('filters.allTypes') },
                ...LOCATION_TYPES.map((type) => ({ value: type, label: enumLabels.locationType(type) })),
              ]}
            />
          )}
        </FilterField>
        <FilterField label={t('locations.columns.roles')}>
          {(id) => (
            <Select
              id={id}
              size="sm"
              value={draftFilters.role}
              onChange={(next) => setDraftFilters({ ...draftFilters, role: next as LocationRole | '' })}
              options={[
                { value: '', label: t('locations.filters.allRoles') },
                ...LOCATION_ROLES.map((role) => ({ value: role, label: enumLabels.locationRole(role) })),
              ]}
            />
          )}
        </FilterField>
        <FilterField label={tc('columns.zone')}>
          {(id) => (
            <Select
              id={id}
              size="sm"
              value={draftFilters.zoneId}
              onChange={(next) => setDraftFilters({ ...draftFilters, zoneId: next })}
              options={[
                { value: '', label: tc('filters.allZones') },
                ...zones.map((zone) => ({ value: zone.id, label: zone.name })),
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
        rowKey={(location) => location.id}
        isLoading={locationsQuery.isPending}
        error={locationsQuery.isError ? describeApiError(locationsQuery.error as ApiError) : null}
        onRetry={() => void locationsQuery.refetch()}
        /* Two different situations, two different exits: nothing has ever been created here, or
           the filters have narrowed everything away. */
        emptyTitle={isFiltered ? tc('filters.noResultsTitle') : t('locations.empty.title')}
        emptyMessage={isFiltered ? tc('filters.noResultsMessage') : t('locations.empty.message')}
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
              {t('locations.new')}
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
        <LocationFormDrawer
          companyId={companyId}
          location={modal.mode === 'edit' ? modal.location : null}
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
