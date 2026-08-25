import { keepPreviousData, useQuery, useQueryClient } from '@tanstack/react-query'
import { useState } from 'react'
import { useTranslation } from 'react-i18next'
import type { ApiError } from '../../shared/api/httpClient'
import {
  activateLocation,
  deactivateLocation,
  fetchLocations,
  LOCATION_IMPORT_BASE_PATH,
  LOCATION_ROLES,
  LOCATION_TYPES,
  type LocationImportPreview,
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
  ImportDrawer,
  PageHeader,
  Pagination,
  Select,
  StatusBadge,
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

type ModalState = { mode: 'create' } | { mode: 'edit'; location: LocationView } | null

interface LocationsPageProps {
  /**
   * Which screen this is. Absent, it is Ubicaciones: the full master. Set, it is Orígenes or
   * Destinos - the same master with the operational-use filter pinned, the filter control
   * hidden because it is the identity of the screen rather than a choice, and the drawer
   * opening with that use already ticked.
   */
  view?: LocationRole
}

/**
 * The master-data screen for physical places, and - with `view` set - the Origins and
 * Destinations screens too.
 *
 * Those three menu entries are one component on purpose. A store that receives deliveries and
 * ships its own returns is one place, one address, one pair of coordinates; "origins" and
 * "destinations" are two questions asked of that master, not two masters. Building them as
 * separate screens is what produced the duplicate records this domain change removed, and a
 * second copy of this file would grow the two apart again within a release.
 *
 * One search box rather than separate code and name filters: this is the master an operator
 * looks something up in, and they look it up by whichever of code, name or external reference
 * they happen to remember - which is exactly what the backend's `search` parameter spans.
 */
export function LocationsPage({ view }: LocationsPageProps = {}) {
  const { t } = useTranslation('masters')
  const { t: tc } = useTranslation('common')
  const { t: td } = useTranslation('dialogs')
  const enumLabels = useEnumLabels()
  const { selected, hasPermission } = useCompany()
  const companyId = selected?.id ?? ''
  const canManage = hasPermission('masterdata.location:manage')
  const queryClient = useQueryClient()

  // The view's role is part of the resting state, not something the operator applied, so it is
  // never offered as a clearable chip and reset() puts it back rather than clearing it.
  const defaultFilters: AppliedFilters = {
    search: '', type: '', role: view ?? '', zoneId: '', active: 'active',
  }

  const [page, setPage] = useState(0)
  const [pageSize, setPageSize] = useState(DEFAULT_PAGE_SIZE)
  const [draftFilters, setDraftFilters] = useState<AppliedFilters>(defaultFilters)
  const [filters, setFilters] = useState<AppliedFilters>(defaultFilters)
  const [modal, setModal] = useState<ModalState>(null)
  const [showImport, setShowImport] = useState(false)

  /** `masters` keys for this screen: `locations`, `origins` or `destinations`. */
  const scope = view === 'ORIGIN' ? 'origins' : view === 'DESTINATION' ? 'destinations' : 'locations'

  const locationsQuery = useQuery({
    // companyId stays second: refresh() invalidates the ['locations', companyId] prefix, and a
    // key that put the view before it would no longer match that prefix - the list would keep
    // showing what it showed before the save.
    queryKey: ['locations', companyId, view ?? 'all', page, pageSize, filters],
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
    setDraftFilters(defaultFilters)
    setFilters(defaultFilters)
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
      // One column, not two. The old screen showed "Roles" next to "Utilizable como", which were
      // the same fact told twice because five of the seven roles described the type instead.
      key: 'use',
      header: t('locations.columns.use'),
      render: (location) =>
        location.roles.length === 0 ? (
          <span className="tms-cell-sub">{t('locations.use.none')}</span>
        ) : (
          <span className="d-inline-flex flex-wrap gap-1">
            {location.roles.map((role) => (
              <span key={role} className="badge text-bg-light border fw-normal">
                {enumLabels.locationRole(role)}
              </span>
            ))}
          </span>
        ),
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
    !view && filters.role && {
      key: 'role', label: t('locations.columns.use'), value: enumLabels.locationRole(filters.role),
      onClear: () => clearOne({ role: '' }),
    },
    filters.zoneId && {
      key: 'zone', label: tc('columns.zone'),
      value: zones.find((zone) => zone.id === filters.zoneId)?.name ?? filters.zoneId,
      onClear: () => clearOne({ zoneId: '' }),
    },
    filters.active !== defaultFilters.active && {
      key: 'active', label: tc('columns.status'),
      value: filters.active === 'all' ? tc('filters.statusAll') : tc('filters.statusInactive'),
      onClear: () => clearOne({ active: defaultFilters.active }),
    },
  ].filter(Boolean) as FilterChip[]

  const isFiltered = activeChips.length > 0

  return (
    <div>
      <PageHeader
        icon={view === 'ORIGIN' ? 'box-arrow-up-right' : view === 'DESTINATION' ? 'geo-fill' : 'geo-alt-fill'}
        title={t(`${scope}.title`)}
        description={t(`${scope}.description`)}
        actions={
          canManage && (
            <>
              <button
                type="button"
                className="btn btn-outline-secondary btn-sm d-inline-flex align-items-center gap-2"
                onClick={() => setShowImport(true)}
              >
                <i className="bi bi-upload" aria-hidden="true" />
                {tc('actions.import')}
              </button>
              <button
                type="button"
                className="btn btn-primary btn-sm d-inline-flex align-items-center gap-2"
                onClick={() => setModal({ mode: 'create' })}
              >
                <i className="bi bi-plus-lg" aria-hidden="true" />
                {t(`${scope}.new`)}
              </button>
            </>
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
        {!view && (
          <FilterField label={t('locations.columns.use')}>
            {(id) => (
              <Select
                id={id}
                size="sm"
                value={draftFilters.role}
                onChange={(next) => setDraftFilters({ ...draftFilters, role: next as LocationRole | '' })}
                options={[
                  { value: '', label: t('locations.filters.anyUse') },
                  ...LOCATION_ROLES.map((role) => ({ value: role, label: enumLabels.locationRole(role) })),
                ]}
              />
            )}
          </FilterField>
        )}
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
        emptyTitle={isFiltered ? tc('filters.noResultsTitle') : t(`${scope}.empty.title`)}
        emptyMessage={isFiltered ? tc('filters.noResultsMessage') : t(`${scope}.empty.message`)}
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
              {t(`${scope}.new`)}
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
          presetRole={view}
          onClose={() => setModal(null)}
          onSaved={() => {
            setModal(null)
            notifySuccess(modal.mode === 'edit' ? td('updated') : td('created'))
            refresh()
          }}
        />
      )}

      {showImport && (
        <ImportDrawer<LocationImportPreview>
          apiBasePath={LOCATION_IMPORT_BASE_PATH}
          companyId={companyId}
          onClose={() => setShowImport(false)}
          onImported={refresh}
          strings={{
            title: t('locations.import.title'),
            subtitle: t('locations.import.subtitle'),
            templateSection: t('locations.import.templateSection'),
            templateHelp: t('locations.import.templateHelp'),
            downloadXlsx: t('locations.import.downloadXlsx'),
            downloadCsv: t('locations.import.downloadCsv'),
            downloadError: t('locations.import.downloadError'),
            fileSection: t('locations.import.fileSection'),
            file: t('locations.import.file'),
            fileHelp: (mb, rows) => t('locations.import.fileHelp', { mb, rows }),
            previewSection: t('locations.import.previewSection'),
            validate: t('locations.import.validate'),
            previewing: t('locations.import.previewing'),
            apply: t('locations.import.apply'),
            applying: t('locations.import.applying'),
            applied: (created, skipped) => `${t('locations.import.applied')}: ${t('locations.import.appliedText', { created, skipped })}`,
            confirmTitle: t('locations.import.confirmTitle'),
            confirmText: (count) => t('locations.import.confirmText', { count }),
            blocked: t('locations.import.blocked'),
            readyToApply: t('locations.import.readyToApply'),
            nothingToCreate: t('locations.import.nothingToCreate'),
            reset: t('locations.import.reset'),
            issuesTitle: t('locations.import.issuesTitle'),
            issuesTruncated: (shown, total) => t('locations.import.issuesTruncated', { shown, total }),
            downloadIssuesReport: t('locations.import.downloadIssuesReport'),
            itemsTitle: t('locations.import.itemsTitle'),
            columnRow: t('locations.import.columnRow'),
            columnColumn: t('locations.import.columnColumn'),
            columnIdentifier: t('locations.import.columnIdentifier'),
            columnMessage: t('locations.import.columnMessage'),
            countRows: t('locations.import.countRows'),
            countItems: t('locations.import.countItems'),
            countCreate: t('locations.import.countCreate'),
            countDuplicates: t('locations.import.countDuplicates'),
            countRejected: t('locations.import.countRejected'),
            countIssues: t('locations.import.countIssues'),
            outcomeCreate: t('locations.import.outcomeCreate'),
            outcomeSkipped: t('locations.import.outcomeSkipped'),
            outcomeRejected: t('locations.import.outcomeRejected'),
            cancel: t('locations.import.cancel'),
            close: t('locations.import.close'),
          }}
          renderItems={(items, outcomeLabel) => (
            <div className="tms-table-scroll">
              <table className="table table-sm align-middle">
                <caption className="visually-hidden">{t('locations.import.itemsTitle')}</caption>
                <thead>
                  <tr>
                    <th scope="col">{tc('columns.status')}</th>
                    <th scope="col">{tc('columns.code')}</th>
                    <th scope="col">{tc('columns.name')}</th>
                    <th scope="col">{t('locations.import.columns.type')}</th>
                    <th scope="col">{t('locations.import.columns.roles')}</th>
                    <th scope="col">{t('locations.import.columns.zone')}</th>
                  </tr>
                </thead>
                <tbody>
                  {items.map((item, index) => (
                    <tr key={`${item.code}-${index}`}>
                      <td>
                        <StatusBadge
                          label={outcomeLabel(item.outcome)}
                          tone={item.outcome === 'CREATE' ? 'success' : item.outcome === 'REJECTED' ? 'danger' : 'neutral'}
                        />
                      </td>
                      <td className="tms-code">{item.code}</td>
                      <td>{item.name}</td>
                      <td>{enumLabels.locationType(item.type)}</td>
                      <td>{item.roles.map((role) => enumLabels.locationRole(role)).join(', ')}</td>
                      <td className="tms-code">{item.zoneCode ?? '—'}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          )}
        />
      )}
    </div>
  )
}
