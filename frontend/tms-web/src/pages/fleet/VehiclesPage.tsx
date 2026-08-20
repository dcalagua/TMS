import { keepPreviousData, useQuery, useQueryClient } from '@tanstack/react-query'
import { useState } from 'react'
import { useTranslation } from 'react-i18next'
import type { ApiError } from '../../shared/api/httpClient'
import { describeApiError } from '../../shared/api/problemMessages'
import { useEnumLabels } from '../../shared/i18n/enums'
import { useFormat } from '../../shared/i18n/format'
import { useCompany } from '../../shared/company/CompanyContext'
import { fetchCarriers } from '../../shared/api/carriersApi'
import { fetchVehicleTypes } from '../../shared/api/vehicleTypesApi'
import {
  activateVehicle,
  deactivateVehicle,
  fetchVehicles,
  VEHICLE_AVAILABILITY_STATUSES,
  VEHICLE_IMPORT_BASE_PATH,
  type VehicleAvailabilityStatus,
  type VehicleImportPreview,
  type VehicleView,
} from '../../shared/api/vehiclesApi'
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
import { VehicleFormDrawer } from './VehicleFormDrawer'

const PAGE_SIZE = 25

type ActiveFilter = 'active' | 'inactive' | 'all'

interface AppliedFilters {
  code: string
  licensePlate: string
  carrierId: string
  vehicleTypeId: string
  availabilityStatus: VehicleAvailabilityStatus | ''
  active: ActiveFilter
}

const DEFAULT_FILTERS: AppliedFilters = {
  code: '', licensePlate: '', carrierId: '', vehicleTypeId: '', availabilityStatus: '', active: 'active',
}

type ModalState = { mode: 'create' } | { mode: 'edit'; vehicle: VehicleView } | null

const AVAILABILITY_TONE: Record<VehicleAvailabilityStatus, 'success' | 'warning' | 'neutral'> = {
  AVAILABLE: 'success',
  IN_MAINTENANCE: 'warning',
  OUT_OF_SERVICE: 'neutral',
}

export function VehiclesPage() {
  const { t } = useTranslation('fleet')
  const { t: tc } = useTranslation('common')
  const { t: td } = useTranslation('dialogs')
  const enumLabels = useEnumLabels()
  const format = useFormat()
  const { selected, hasPermission } = useCompany()
  const companyId = selected?.id ?? ''
  const canManage = hasPermission('fleet.vehicle:manage')
  const queryClient = useQueryClient()

  const [page, setPage] = useState(0)
  const [draftFilters, setDraftFilters] = useState<AppliedFilters>(DEFAULT_FILTERS)
  const [filters, setFilters] = useState<AppliedFilters>(DEFAULT_FILTERS)
  const [modal, setModal] = useState<ModalState>(null)
  const [showImport, setShowImport] = useState(false)

  const vehiclesQuery = useQuery({
    queryKey: ['vehicles', companyId, page, filters],
    queryFn: ({ signal }) =>
      fetchVehicles({
        companyId,
        page,
        size: PAGE_SIZE,
        sort: 'code,asc',
        code: filters.code || undefined,
        licensePlate: filters.licensePlate || undefined,
        carrierId: filters.carrierId || undefined,
        vehicleTypeId: filters.vehicleTypeId || undefined,
        availabilityStatus: filters.availabilityStatus || undefined,
        active: filters.active === 'all' ? undefined : filters.active === 'active',
        signal,
      }),
    placeholderData: keepPreviousData,
  })

  const carriersQuery = useQuery({
    queryKey: ['carriers-for-vehicle-filter', companyId],
    queryFn: ({ signal }) => fetchCarriers({ companyId, size: 200, active: true, sort: 'code,asc', signal }),
    enabled: companyId !== '',
  })
  const carriers = carriersQuery.data?.content ?? []

  const vehicleTypesQuery = useQuery({
    queryKey: ['vehicle-types-for-vehicle-filter', companyId],
    queryFn: ({ signal }) => fetchVehicleTypes({ companyId, size: 200, active: true, sort: 'code,asc', signal }),
    enabled: companyId !== '',
  })
  const vehicleTypes = vehicleTypesQuery.data?.content ?? []

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
    void queryClient.invalidateQueries({ queryKey: ['vehicles', companyId] })
  }

  async function toggleActive(vehicle: VehicleView) {
    const confirmed = await confirmDialog({
      title: vehicle.active
        ? td('deactivate.title', { name: vehicle.licensePlate })
        : td('activate.title', { name: vehicle.licensePlate }),
      text: vehicle.active ? td('deactivate.text') : td('activate.text'),
      confirmLabel: vehicle.active ? tc('actions.deactivate') : tc('actions.activate'),
      dangerous: vehicle.active,
    })
    if (!confirmed) return

    try {
      if (vehicle.active) {
        await deactivateVehicle(companyId, vehicle.id)
        notifySuccess(td('deactivated'), vehicle.licensePlate)
      } else {
        await activateVehicle(companyId, vehicle.id)
        notifySuccess(td('activated'), vehicle.licensePlate)
      }
      refresh()
    } catch (error) {
      notifyError(td('errorTitle'), describeApiError(error as ApiError))
    }
  }

  const columns: DataTableColumn<VehicleView>[] = [
    {
      key: 'plate',
      header: tc('columns.plateCode'),
      render: (vehicle) => (
        <div>
          <div className="fw-semibold">{vehicle.licensePlate}</div>
          <div className="text-muted small">{vehicle.code}</div>
        </div>
      ),
    },
    { key: 'carrier', header: tc('columns.carrier'), render: (vehicle) => vehicle.carrierBusinessName ?? t('vehicles.ownedFleet') },
    { key: 'type', header: tc('columns.type'), render: (vehicle) => vehicle.vehicleTypeName ?? '—' },
    {
      key: 'capacity',
      header: t('vehicles.columns.effectiveCapacity'),
      render: (vehicle) => (
        <span>
          {format.weight(vehicle.effectiveMaxWeightKg)} &middot; {format.volume(vehicle.effectiveMaxVolumeM3)}{' '}
          &middot; {format.quantity(vehicle.effectiveMaxPallets)} {t('vehicles.palletsUnit')}
        </span>
      ),
    },
    {
      key: 'availability',
      header: tc('columns.availability'),
      render: (vehicle) => (
        <StatusBadge
          label={enumLabels.vehicleAvailability(vehicle.availabilityStatus)}
          tone={AVAILABILITY_TONE[vehicle.availabilityStatus]}
        />
      ),
    },
    {
      key: 'active',
      header: tc('columns.status'),
      render: (vehicle) => (
        <ActiveBadge active={vehicle.active} />
      ),
    },
  ]

  if (canManage) {
    columns.push({
      key: 'actions',
      header: tc('columns.actions'),
      actions: true,
      render: (vehicle) => (
        <ActionMenu
          items={[
            {
              key: 'edit',
              label: tc('actions.edit'),
              icon: 'bi-pencil',
              onSelect: () => setModal({ mode: 'edit', vehicle }),
            },
            {
              key: 'active',
              label: vehicle.active ? tc('actions.deactivate') : tc('actions.activate'),
              icon: vehicle.active ? 'bi-slash-circle' : 'bi-check-circle',
              dangerous: vehicle.active,
              onSelect: () => void toggleActive(vehicle),
            },
          ]}
        />
      ),
    })
  }

  const pageData = vehiclesQuery.data

  return (
    <div>
      <PageHeader
        icon="truck"
        title={t('vehicles.title')}
        description={t('vehicles.description')}
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
                {t('vehicles.new')}
              </button>
            </>
          )
        }
      />

      <FilterBar onSubmit={applyFilters} onReset={resetFilters}>
        <div>
          <label htmlFor="vehicle-filter-code" className="form-label small mb-1">
            {tc('columns.code')}
          </label>
          <input
            id="vehicle-filter-code"
            className="form-control form-control-sm"
            value={draftFilters.code}
            onChange={(event) => setDraftFilters({ ...draftFilters, code: event.target.value })}
          />
        </div>
        <div>
          <label htmlFor="vehicle-filter-plate" className="form-label small mb-1">
            {tc('fields.licensePlate')}
          </label>
          <input
            id="vehicle-filter-plate"
            className="form-control form-control-sm"
            value={draftFilters.licensePlate}
            onChange={(event) => setDraftFilters({ ...draftFilters, licensePlate: event.target.value })}
          />
        </div>
        <div>
          <label htmlFor="vehicle-filter-carrier" className="form-label small mb-1">
            {tc('columns.carrier')}
          </label>
          <Select
            id="vehicle-filter-carrier"
            size="sm"
            value={draftFilters.carrierId}
            onChange={(next) => setDraftFilters({ ...draftFilters, carrierId: next })}
            options={[
              { value: '', label: tc('filters.allCarriers') },
              ...carriers.map((carrier) => ({ value: carrier.id, label: carrier.businessName })),
            ]}
          />
        </div>
        <div>
          <label htmlFor="vehicle-filter-type" className="form-label small mb-1">
            {tc('columns.type')}
          </label>
          <Select
            id="vehicle-filter-type"
            size="sm"
            value={draftFilters.vehicleTypeId}
            onChange={(next) => setDraftFilters({ ...draftFilters, vehicleTypeId: next })}
            options={[
              { value: '', label: tc('filters.allTypes') },
              ...vehicleTypes.map((type) => ({ value: type.id, label: type.name })),
            ]}
          />
        </div>
        <div>
          <label htmlFor="vehicle-filter-availability" className="form-label small mb-1">
            {tc('columns.availability')}
          </label>
          <Select
            id="vehicle-filter-availability"
            size="sm"
            value={draftFilters.availabilityStatus}
            onChange={(next) =>
              setDraftFilters({ ...draftFilters, availabilityStatus: next as VehicleAvailabilityStatus | '' })
            }
            options={[
              { value: '', label: tc('filters.allAvailability') },
              ...VEHICLE_AVAILABILITY_STATUSES.map((status) => ({ value: status, label: enumLabels.vehicleAvailability(status) })),
            ]}
          />
        </div>
        <div>
          <label htmlFor="vehicle-filter-active" className="form-label small mb-1">
            {tc('columns.status')}
          </label>
          <Select
            id="vehicle-filter-active"
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
        rowKey={(vehicle) => vehicle.id}
        isLoading={vehiclesQuery.isPending}
        error={vehiclesQuery.isError ? describeApiError(vehiclesQuery.error as ApiError) : null}
        onRetry={() => void vehiclesQuery.refetch()}
        emptyTitle={t('vehicles.empty.title')}
        emptyMessage={t('vehicles.empty.message')}
        footer={pageData ? <Pagination page={pageData} onPageChange={setPage} /> : undefined}
      />

      {modal && (
        <VehicleFormDrawer
          companyId={companyId}
          vehicle={modal.mode === 'edit' ? modal.vehicle : null}
          onClose={() => setModal(null)}
          onSaved={() => {
            setModal(null)
            notifySuccess(modal.mode === 'edit' ? td('updated') : td('created'))
            refresh()
          }}
        />
      )}

      {showImport && (
        <ImportDrawer<VehicleImportPreview>
          apiBasePath={VEHICLE_IMPORT_BASE_PATH}
          companyId={companyId}
          onClose={() => setShowImport(false)}
          onImported={refresh}
          strings={{
            title: t('vehicles.import.title'),
            subtitle: t('vehicles.import.subtitle'),
            templateSection: t('vehicles.import.templateSection'),
            templateHelp: t('vehicles.import.templateHelp'),
            downloadXlsx: t('vehicles.import.downloadXlsx'),
            downloadCsv: t('vehicles.import.downloadCsv'),
            downloadError: t('vehicles.import.downloadError'),
            fileSection: t('vehicles.import.fileSection'),
            file: t('vehicles.import.file'),
            fileHelp: (mb, rows) => t('vehicles.import.fileHelp', { mb, rows }),
            previewSection: t('vehicles.import.previewSection'),
            validate: t('vehicles.import.validate'),
            previewing: t('vehicles.import.previewing'),
            apply: t('vehicles.import.apply'),
            applying: t('vehicles.import.applying'),
            applied: (created, skipped) => `${t('vehicles.import.applied')}: ${t('vehicles.import.appliedText', { created, skipped })}`,
            confirmTitle: t('vehicles.import.confirmTitle'),
            confirmText: (count) => t('vehicles.import.confirmText', { count }),
            blocked: t('vehicles.import.blocked'),
            readyToApply: t('vehicles.import.readyToApply'),
            nothingToCreate: t('vehicles.import.nothingToCreate'),
            reset: t('vehicles.import.reset'),
            issuesTitle: t('vehicles.import.issuesTitle'),
            issuesTruncated: (shown, total) => t('vehicles.import.issuesTruncated', { shown, total }),
            downloadIssuesReport: t('vehicles.import.downloadIssuesReport'),
            itemsTitle: t('vehicles.import.itemsTitle'),
            columnRow: t('vehicles.import.columnRow'),
            columnColumn: t('vehicles.import.columnColumn'),
            columnIdentifier: t('vehicles.import.columnIdentifier'),
            columnMessage: t('vehicles.import.columnMessage'),
            countRows: t('vehicles.import.countRows'),
            countItems: t('vehicles.import.countItems'),
            countCreate: t('vehicles.import.countCreate'),
            countDuplicates: t('vehicles.import.countDuplicates'),
            countRejected: t('vehicles.import.countRejected'),
            countIssues: t('vehicles.import.countIssues'),
            outcomeCreate: t('vehicles.import.outcomeCreate'),
            outcomeSkipped: t('vehicles.import.outcomeSkipped'),
            outcomeRejected: t('vehicles.import.outcomeRejected'),
            cancel: t('vehicles.import.cancel'),
            close: t('vehicles.import.close'),
          }}
          renderItems={(items, outcomeLabel) => (
            <div className="tms-table-scroll">
              <table className="table table-sm align-middle">
                <caption className="visually-hidden">{t('vehicles.import.itemsTitle')}</caption>
                <thead>
                  <tr>
                    <th scope="col">{tc('columns.status')}</th>
                    <th scope="col">{tc('columns.code')}</th>
                    <th scope="col">{t('vehicles.import.columns.plate')}</th>
                    <th scope="col">{t('vehicles.import.columns.carrier')}</th>
                    <th scope="col">{t('vehicles.import.columns.type')}</th>
                    <th scope="col">{t('vehicles.import.columns.status')}</th>
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
                      <td className="tms-code">{item.licensePlate}</td>
                      <td className="tms-code">{item.carrierCode ?? '—'}</td>
                      <td className="tms-code">{item.vehicleTypeCode ?? '—'}</td>
                      <td>{enumLabels.vehicleAvailability(item.availabilityStatus)}</td>
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
