import { keepPreviousData, useQuery, useQueryClient } from '@tanstack/react-query'
import { useState } from 'react'
import { useTranslation } from 'react-i18next'
import type { ApiError } from '../../shared/api/httpClient'
import { describeApiError } from '../../shared/api/problemMessages'
import { useEnumLabels } from '../../shared/i18n/enums'
import { useCompany } from '../../shared/company/CompanyContext'
import { fetchCarriers } from '../../shared/api/carriersApi'
import { fetchVehicleTypes } from '../../shared/api/vehicleTypesApi'
import {
  activateVehicle,
  deactivateVehicle,
  fetchVehicles,
  VEHICLE_AVAILABILITY_STATUSES,
  type VehicleAvailabilityStatus,
  type VehicleView,
} from '../../shared/api/vehiclesApi'
import {
  confirmDialog,
  DataTable,
  FilterBar,
  PageHeader,
  Pagination,
  ActionMenu,
  ActiveBadge,
  StatusBadge,
  type DataTableColumn,
} from '../../shared/ui/components'
import { notifyError, notifySuccess } from '../../shared/ui/alerts'
import { VehicleFormModal } from './VehicleFormModal'

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
  const { selected, hasPermission } = useCompany()
  const companyId = selected?.id ?? ''
  const canManage = hasPermission('fleet.vehicle:manage')
  const queryClient = useQueryClient()

  const [page, setPage] = useState(0)
  const [draftFilters, setDraftFilters] = useState<AppliedFilters>(DEFAULT_FILTERS)
  const [filters, setFilters] = useState<AppliedFilters>(DEFAULT_FILTERS)
  const [modal, setModal] = useState<ModalState>(null)

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
    { key: 'carrier', header: tc('columns.carrier'), render: (vehicle) => vehicle.carrierBusinessName ?? 'Owned fleet' },
    { key: 'type', header: tc('columns.type'), render: (vehicle) => vehicle.vehicleTypeName ?? '—' },
    {
      key: 'capacity',
      header: 'Effective capacity',
      render: (vehicle) => (
        <span>
          {vehicle.effectiveMaxWeightKg} kg &middot; {vehicle.effectiveMaxVolumeM3} m³ &middot;{' '}
          {vehicle.effectiveMaxPallets} pallets
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
        title={t('vehicles.title')}
        description={t('vehicles.description')}
        actions={
          canManage && (
            <button type="button" className="btn btn-primary btn-sm" onClick={() => setModal({ mode: 'create' })}>
              {t('vehicles.new')}
            </button>
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
          <select
            id="vehicle-filter-carrier"
            className="form-select form-select-sm"
            value={draftFilters.carrierId}
            onChange={(event) => setDraftFilters({ ...draftFilters, carrierId: event.target.value })}
          >
            <option value="">{tc('filters.allCarriers')}</option>
            {carriers.map((carrier) => (
              <option key={carrier.id} value={carrier.id}>
                {carrier.businessName}
              </option>
            ))}
          </select>
        </div>
        <div>
          <label htmlFor="vehicle-filter-type" className="form-label small mb-1">
            {tc('columns.type')}
          </label>
          <select
            id="vehicle-filter-type"
            className="form-select form-select-sm"
            value={draftFilters.vehicleTypeId}
            onChange={(event) => setDraftFilters({ ...draftFilters, vehicleTypeId: event.target.value })}
          >
            <option value="">{tc('filters.allTypes')}</option>
            {vehicleTypes.map((type) => (
              <option key={type.id} value={type.id}>
                {type.name}
              </option>
            ))}
          </select>
        </div>
        <div>
          <label htmlFor="vehicle-filter-availability" className="form-label small mb-1">
            {tc('columns.availability')}
          </label>
          <select
            id="vehicle-filter-availability"
            className="form-select form-select-sm"
            value={draftFilters.availabilityStatus}
            onChange={(event) =>
              setDraftFilters({ ...draftFilters, availabilityStatus: event.target.value as VehicleAvailabilityStatus | '' })
            }
          >
            <option value="">All</option>
            {VEHICLE_AVAILABILITY_STATUSES.map((status) => (
              <option key={status} value={status}>
                {enumLabels.vehicleAvailability(status)}
              </option>
            ))}
          </select>
        </div>
        <div>
          <label htmlFor="vehicle-filter-active" className="form-label small mb-1">
            {tc('columns.status')}
          </label>
          <select
            id="vehicle-filter-active"
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
            rowKey={(vehicle) => vehicle.id}
            isLoading={vehiclesQuery.isPending}
            error={vehiclesQuery.isError ? describeApiError(vehiclesQuery.error as ApiError) : null}
            onRetry={() => void vehiclesQuery.refetch()}
            emptyTitle={t('vehicles.empty.title')}
            emptyMessage={t('vehicles.empty.message')}
          />
        </div>
        {pageData && (
          <div className="card-footer">
            <Pagination page={pageData} onPageChange={setPage} />
          </div>
        )}
      </div>

      {modal && (
        <VehicleFormModal
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
    </div>
  )
}
