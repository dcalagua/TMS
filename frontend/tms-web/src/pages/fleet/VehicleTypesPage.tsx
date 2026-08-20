import { keepPreviousData, useQuery, useQueryClient } from '@tanstack/react-query'
import { useState } from 'react'
import { useTranslation } from 'react-i18next'
import type { ApiError } from '../../shared/api/httpClient'
import { describeApiError } from '../../shared/api/problemMessages'
import { useCompany } from '../../shared/company/CompanyContext'
import {
  activateVehicleType,
  deactivateVehicleType,
  fetchVehicleTypes,
  VEHICLE_TYPE_IMPORT_BASE_PATH,
  type VehicleTypeImportPreview,
  type VehicleTypeView,
} from '../../shared/api/vehicleTypesApi'
import {
  confirmDialog,
  DataTable,
  FilterBar,
  ImportDrawer,
  PageHeader,
  Pagination,
  ActionMenu,
  ActiveBadge,
  StatusBadge,
  Select,
  type DataTableColumn,
} from '../../shared/ui/components'
import { notifyError, notifySuccess } from '../../shared/ui/alerts'
import { VehicleTypeFormDrawer } from './VehicleTypeFormDrawer'

const PAGE_SIZE = 25

type ActiveFilter = 'active' | 'inactive' | 'all'

interface AppliedFilters {
  code: string
  name: string
  active: ActiveFilter
}

const DEFAULT_FILTERS: AppliedFilters = { code: '', name: '', active: 'active' }

type ModalState = { mode: 'create' } | { mode: 'edit'; vehicleType: VehicleTypeView } | null

export function VehicleTypesPage() {
  const { t } = useTranslation('fleet')
  const { t: tc } = useTranslation('common')
  const { t: td } = useTranslation('dialogs')
  const { selected, hasPermission } = useCompany()
  const companyId = selected?.id ?? ''
  const canManage = hasPermission('fleet.vehicle_type:manage')
  const queryClient = useQueryClient()

  const [page, setPage] = useState(0)
  const [draftFilters, setDraftFilters] = useState<AppliedFilters>(DEFAULT_FILTERS)
  const [filters, setFilters] = useState<AppliedFilters>(DEFAULT_FILTERS)
  const [modal, setModal] = useState<ModalState>(null)
  const [showImport, setShowImport] = useState(false)

  const vehicleTypesQuery = useQuery({
    queryKey: ['vehicle-types', companyId, page, filters],
    queryFn: ({ signal }) =>
      fetchVehicleTypes({
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
    void queryClient.invalidateQueries({ queryKey: ['vehicle-types', companyId] })
  }

  async function toggleActive(vehicleType: VehicleTypeView) {
    const confirmed = await confirmDialog({
      title: vehicleType.active
        ? td('deactivate.title', { name: vehicleType.name })
        : td('activate.title', { name: vehicleType.name }),
      text: vehicleType.active ? td('deactivate.text') : td('activate.text'),
      confirmLabel: vehicleType.active ? tc('actions.deactivate') : tc('actions.activate'),
      dangerous: vehicleType.active,
    })
    if (!confirmed) return

    try {
      if (vehicleType.active) {
        await deactivateVehicleType(companyId, vehicleType.id)
        notifySuccess(td('deactivated'), vehicleType.name)
      } else {
        await activateVehicleType(companyId, vehicleType.id)
        notifySuccess(td('activated'), vehicleType.name)
      }
      refresh()
    } catch (error) {
      notifyError(td('errorTitle'), describeApiError(error as ApiError))
    }
  }

  const columns: DataTableColumn<VehicleTypeView>[] = [
    { key: 'code', header: tc('columns.code'), render: (type) => <span className="fw-semibold">{type.code}</span> },
    { key: 'name', header: tc('columns.name'), render: (type) => type.name },
    { key: 'maxWeight', header: tc('columns.maxWeight'), render: (type) => type.maxWeightKg },
    { key: 'maxVolume', header: tc('columns.maxVolume'), render: (type) => type.maxVolumeM3 },
    { key: 'maxPallets', header: tc('columns.maxPallets'), render: (type) => type.maxPallets },
    {
      key: 'active',
      header: tc('columns.status'),
      render: (type) => (
        <ActiveBadge active={type.active} />
      ),
    },
  ]

  if (canManage) {
    columns.push({
      key: 'actions',
      header: tc('columns.actions'),
      actions: true,
      render: (type) => (
        <ActionMenu
          items={[
            {
              key: 'edit',
              label: tc('actions.edit'),
              icon: 'bi-pencil',
              onSelect: () => setModal({ mode: 'edit', vehicleType: type }),
            },
            {
              key: 'active',
              label: type.active ? tc('actions.deactivate') : tc('actions.activate'),
              icon: type.active ? 'bi-slash-circle' : 'bi-check-circle',
              dangerous: type.active,
              onSelect: () => void toggleActive(type),
            },
          ]}
        />
      ),
    })
  }

  const pageData = vehicleTypesQuery.data

  return (
    <div>
      <PageHeader
        icon="diagram-3"
        title={t('vehicleTypes.title')}
        description={t('vehicleTypes.description')}
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
              <button type="button" className="btn btn-primary btn-sm d-inline-flex align-items-center gap-2" onClick={() => setModal({ mode: 'create' })}>
                <i className="bi bi-plus-lg" aria-hidden="true" />
                {t('vehicleTypes.new')}
              </button>
            </>
          )
        }
      />

      <FilterBar onSubmit={applyFilters} onReset={resetFilters}>
        <div>
          <label htmlFor="vehicle-type-filter-code" className="form-label small mb-1">
            {tc('columns.code')}
          </label>
          <input
            id="vehicle-type-filter-code"
            className="form-control form-control-sm"
            value={draftFilters.code}
            onChange={(event) => setDraftFilters({ ...draftFilters, code: event.target.value })}
          />
        </div>
        <div>
          <label htmlFor="vehicle-type-filter-name" className="form-label small mb-1">
            {tc('columns.name')}
          </label>
          <input
            id="vehicle-type-filter-name"
            className="form-control form-control-sm"
            value={draftFilters.name}
            onChange={(event) => setDraftFilters({ ...draftFilters, name: event.target.value })}
          />
        </div>
        <div>
          <label htmlFor="vehicle-type-filter-active" className="form-label small mb-1">
            {tc('columns.status')}
          </label>
          <Select
            id="vehicle-type-filter-active"
            size="sm"
            value={draftFilters.active}
            onChange={(next) => setDraftFilters({ ...draftFilters, active: next as ActiveFilter })}
            options={[
              { value: 'active', label: tc('filters.statusActive') },
              { value: 'inactive', label: tc('filters.statusInactive') },
              { value: 'all', label: tc('filters.statusAll') },
            ]}
          />
        </div>
      </FilterBar>

      <DataTable
        columns={columns}
        rows={pageData?.content ?? []}
        total={pageData?.totalElements}
        rowKey={(type) => type.id}
        isLoading={vehicleTypesQuery.isPending}
        error={vehicleTypesQuery.isError ? describeApiError(vehicleTypesQuery.error as ApiError) : null}
        onRetry={() => void vehicleTypesQuery.refetch()}
        emptyTitle={t('vehicleTypes.empty.title')}
        emptyMessage={t('vehicleTypes.empty.message')}
        footer={pageData ? <Pagination page={pageData} onPageChange={setPage} /> : undefined}
      />

      {modal && (
        <VehicleTypeFormDrawer
          companyId={companyId}
          vehicleType={modal.mode === 'edit' ? modal.vehicleType : null}
          onClose={() => setModal(null)}
          onSaved={() => {
            setModal(null)
            notifySuccess(modal.mode === 'edit' ? td('updated') : td('created'))
            refresh()
          }}
        />
      )}

      {showImport && (
        <ImportDrawer<VehicleTypeImportPreview>
          apiBasePath={VEHICLE_TYPE_IMPORT_BASE_PATH}
          companyId={companyId}
          onClose={() => setShowImport(false)}
          onImported={refresh}
          strings={{
            title: t('vehicleTypes.import.title'),
            subtitle: t('vehicleTypes.import.subtitle'),
            templateSection: t('vehicleTypes.import.templateSection'),
            templateHelp: t('vehicleTypes.import.templateHelp'),
            downloadXlsx: t('vehicleTypes.import.downloadXlsx'),
            downloadCsv: t('vehicleTypes.import.downloadCsv'),
            downloadError: t('vehicleTypes.import.downloadError'),
            fileSection: t('vehicleTypes.import.fileSection'),
            file: t('vehicleTypes.import.file'),
            fileHelp: (mb, rows) => t('vehicleTypes.import.fileHelp', { mb, rows }),
            previewSection: t('vehicleTypes.import.previewSection'),
            validate: t('vehicleTypes.import.validate'),
            previewing: t('vehicleTypes.import.previewing'),
            apply: t('vehicleTypes.import.apply'),
            applying: t('vehicleTypes.import.applying'),
            applied: (created, skipped) =>
              `${t('vehicleTypes.import.applied')}: ${t('vehicleTypes.import.appliedText', { created, skipped })}`,
            confirmTitle: t('vehicleTypes.import.confirmTitle'),
            confirmText: (count) => t('vehicleTypes.import.confirmText', { count }),
            blocked: t('vehicleTypes.import.blocked'),
            readyToApply: t('vehicleTypes.import.readyToApply'),
            nothingToCreate: t('vehicleTypes.import.nothingToCreate'),
            reset: t('vehicleTypes.import.reset'),
            issuesTitle: t('vehicleTypes.import.issuesTitle'),
            issuesTruncated: (shown, total) => t('vehicleTypes.import.issuesTruncated', { shown, total }),
            downloadIssuesReport: t('vehicleTypes.import.downloadIssuesReport'),
            itemsTitle: t('vehicleTypes.import.itemsTitle'),
            columnRow: t('vehicleTypes.import.columnRow'),
            columnColumn: t('vehicleTypes.import.columnColumn'),
            columnIdentifier: t('vehicleTypes.import.columnIdentifier'),
            columnMessage: t('vehicleTypes.import.columnMessage'),
            countRows: t('vehicleTypes.import.countRows'),
            countItems: t('vehicleTypes.import.countItems'),
            countCreate: t('vehicleTypes.import.countCreate'),
            countDuplicates: t('vehicleTypes.import.countDuplicates'),
            countRejected: t('vehicleTypes.import.countRejected'),
            countIssues: t('vehicleTypes.import.countIssues'),
            outcomeCreate: t('vehicleTypes.import.outcomeCreate'),
            outcomeSkipped: t('vehicleTypes.import.outcomeSkipped'),
            outcomeRejected: t('vehicleTypes.import.outcomeRejected'),
            cancel: t('vehicleTypes.import.cancel'),
            close: t('vehicleTypes.import.close'),
          }}
          renderItems={(items, outcomeLabel) => (
            <div className="tms-table-scroll">
              <table className="table table-sm align-middle">
                <caption className="visually-hidden">{t('vehicleTypes.import.itemsTitle')}</caption>
                <thead>
                  <tr>
                    <th scope="col">{tc('columns.status')}</th>
                    <th scope="col">{tc('columns.code')}</th>
                    <th scope="col">{tc('columns.name')}</th>
                    <th scope="col">{t('vehicleTypes.import.columns.capacity')}</th>
                    <th scope="col">{t('vehicleTypes.import.columns.temperature')}</th>
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
                      <td>
                        {item.maxWeightKg} kg &middot; {item.maxVolumeM3} m³ &middot; {item.maxPallets} pallets
                      </td>
                      <td>
                        {item.temperatureControlled
                          ? `${item.minTemperatureCelsius ?? '—'} / ${item.maxTemperatureCelsius ?? '—'} °C`
                          : '—'}
                      </td>
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
