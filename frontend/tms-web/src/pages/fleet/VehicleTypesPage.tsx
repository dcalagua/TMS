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
  type VehicleTypeView,
} from '../../shared/api/vehicleTypesApi'
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
import { VehicleTypeFormModal } from './VehicleTypeFormModal'

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
  const { selected, hasPermission } = useCompany()
  const companyId = selected?.id ?? ''
  const canManage = hasPermission('fleet.vehicle_type:manage')
  const queryClient = useQueryClient()

  const [page, setPage] = useState(0)
  const [draftFilters, setDraftFilters] = useState<AppliedFilters>(DEFAULT_FILTERS)
  const [filters, setFilters] = useState<AppliedFilters>(DEFAULT_FILTERS)
  const [modal, setModal] = useState<ModalState>(null)

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
      title: vehicleType.active ? 'Deactivate vehicle type?' : 'Activate vehicle type?',
      text: vehicleType.active
        ? `${vehicleType.name} will no longer be selectable for new vehicles.`
        : `${vehicleType.name} will become selectable again.`,
      confirmLabel: vehicleType.active ? 'Deactivate' : 'Activate',
      dangerous: vehicleType.active,
    })
    if (!confirmed) return

    try {
      if (vehicleType.active) {
        await deactivateVehicleType(companyId, vehicleType.id)
        notifySuccess('Vehicle type deactivated', vehicleType.name)
      } else {
        await activateVehicleType(companyId, vehicleType.id)
        notifySuccess('Vehicle type activated', vehicleType.name)
      }
      refresh()
    } catch (error) {
      notifyError('Could not update the vehicle type', describeApiError(error as ApiError))
    }
  }

  const columns: DataTableColumn<VehicleTypeView>[] = [
    { key: 'code', header: 'Code', render: (type) => <span className="fw-semibold">{type.code}</span> },
    { key: 'name', header: 'Name', render: (type) => type.name },
    { key: 'maxWeight', header: 'Max weight (kg)', render: (type) => type.maxWeightKg },
    { key: 'maxVolume', header: 'Max volume (m³)', render: (type) => type.maxVolumeM3 },
    { key: 'maxPallets', header: 'Max pallets', render: (type) => type.maxPallets },
    {
      key: 'active',
      header: 'Status',
      render: (type) => (
        <ActiveBadge active={type.active} />
      ),
    },
  ]

  if (canManage) {
    columns.push({
      key: 'actions',
      header: '',
      className: 'text-end',
      render: (type) => (
        <div className="btn-group btn-group-sm">
          <button
            type="button"
            className="btn btn-outline-secondary"
            onClick={() => setModal({ mode: 'edit', vehicleType: type })}
          >
            Edit
          </button>
          <button
            type="button"
            className={`btn btn-outline-${type.active ? 'danger' : 'success'}`}
            onClick={() => void toggleActive(type)}
          >
            {type.active ? 'Deactivate' : 'Activate'}
          </button>
        </div>
      ),
    })
  }

  const pageData = vehicleTypesQuery.data

  return (
    <div>
      <PageHeader
        title={t('vehicleTypes.title')}
        description={t('vehicleTypes.description')}
        actions={
          canManage && (
            <button type="button" className="btn btn-primary btn-sm" onClick={() => setModal({ mode: 'create' })}>
              {t('vehicleTypes.new')}
            </button>
          )
        }
      />

      <FilterBar onSubmit={applyFilters} onReset={resetFilters}>
        <div>
          <label htmlFor="vehicle-type-filter-code" className="form-label small mb-1">
            Code
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
            Name
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
            Status
          </label>
          <select
            id="vehicle-type-filter-active"
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
            rowKey={(type) => type.id}
            isLoading={vehicleTypesQuery.isPending}
            error={vehicleTypesQuery.isError ? describeApiError(vehicleTypesQuery.error as ApiError) : null}
            onRetry={() => void vehicleTypesQuery.refetch()}
            emptyTitle="No vehicle types found"
            emptyMessage="Create a vehicle type or adjust your filters."
          />
        </div>
        {pageData && (
          <div className="card-footer">
            <Pagination page={pageData} onPageChange={setPage} />
          </div>
        )}
      </div>

      {modal && (
        <VehicleTypeFormModal
          companyId={companyId}
          vehicleType={modal.mode === 'edit' ? modal.vehicleType : null}
          onClose={() => setModal(null)}
          onSaved={() => {
            setModal(null)
            notifySuccess(modal.mode === 'edit' ? 'Vehicle type updated' : 'Vehicle type created')
            refresh()
          }}
        />
      )}
    </div>
  )
}
