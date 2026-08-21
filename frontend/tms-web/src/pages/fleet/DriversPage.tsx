import { keepPreviousData, useQuery, useQueryClient } from '@tanstack/react-query'
import { useState } from 'react'
import { useTranslation } from 'react-i18next'
import { fetchCarriers } from '../../shared/api/carriersApi'
import {
  activateDriver,
  deactivateDriver,
  DRIVER_LICENSE_STATUSES,
  fetchDrivers,
  type DriverLicenseStatus,
  type DriverView,
} from '../../shared/api/driversApi'
import type { ApiError } from '../../shared/api/httpClient'
import { describeApiError } from '../../shared/api/problemMessages'
import { useCompany } from '../../shared/company/CompanyContext'
import { useEnumLabels } from '../../shared/i18n/enums'
import { useFormat } from '../../shared/i18n/format'
import { notifyError, notifySuccess } from '../../shared/ui/alerts'
import {
  ActionMenu,
  ActiveBadge,
  confirmDialog,
  DataTable,
  FilterBar,
  PageHeader,
  Pagination,
  Select,
  StatusBadge,
  type DataTableColumn,
} from '../../shared/ui/components'
import { DriverFormDrawer } from './DriverFormDrawer'

const PAGE_SIZE = 25

type ActiveFilter = 'active' | 'inactive' | 'all'

interface AppliedFilters {
  code: string
  name: string
  carrierId: string
  licenseStatus: DriverLicenseStatus | ''
  active: ActiveFilter
}

const DEFAULT_FILTERS: AppliedFilters = {
  code: '', name: '', carrierId: '', licenseStatus: '', active: 'active',
}

type ModalState = { mode: 'create' } | { mode: 'edit'; driver: DriverView } | null

/**
 * The licence badge's colour, and the only place the four statuses turn into a tone.
 *
 * `EXPIRED` is danger because it is the one that actually stops a dispatch; `EXPIRING_SOON` is a
 * warning a planner acts on by choice; `UNRECORDED` is neutral rather than a warning, because a
 * missing date blocks nothing and colouring it amber would train people to ignore amber.
 */
const LICENSE_TONE: Record<DriverLicenseStatus, 'success' | 'warning' | 'danger' | 'neutral'> = {
  VALID: 'success',
  EXPIRING_SOON: 'warning',
  EXPIRED: 'danger',
  UNRECORDED: 'neutral',
}

export function DriversPage() {
  const { t } = useTranslation('fleet')
  const { t: tc } = useTranslation('common')
  const { t: td } = useTranslation('dialogs')
  const enumLabels = useEnumLabels()
  const format = useFormat()
  const { selected, hasPermission } = useCompany()
  const companyId = selected?.id ?? ''
  const canManage = hasPermission('fleet.driver:manage')
  const queryClient = useQueryClient()

  const [page, setPage] = useState(0)
  const [draftFilters, setDraftFilters] = useState<AppliedFilters>(DEFAULT_FILTERS)
  const [filters, setFilters] = useState<AppliedFilters>(DEFAULT_FILTERS)
  const [modal, setModal] = useState<ModalState>(null)

  const driversQuery = useQuery({
    queryKey: ['drivers', companyId, page, filters],
    queryFn: ({ signal }) =>
      fetchDrivers({
        companyId,
        page,
        size: PAGE_SIZE,
        code: filters.code || undefined,
        name: filters.name || undefined,
        carrierId: filters.carrierId || undefined,
        licenseStatus: filters.licenseStatus || undefined,
        active: filters.active === 'all' ? undefined : filters.active === 'active',
        signal,
      }),
    placeholderData: keepPreviousData,
  })

  const carriersQuery = useQuery({
    queryKey: ['carriers-for-driver-filter', companyId],
    queryFn: ({ signal }) => fetchCarriers({ companyId, size: 200, active: true, sort: 'code,asc', signal }),
    enabled: companyId !== '',
  })
  const carriers = carriersQuery.data?.content ?? []

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
    void queryClient.invalidateQueries({ queryKey: ['drivers', companyId] })
  }

  async function toggleActive(driver: DriverView) {
    const confirmed = await confirmDialog({
      title: driver.active
        ? td('deactivate.title', { name: driver.fullName })
        : td('activate.title', { name: driver.fullName }),
      text: driver.active ? td('deactivate.text') : td('activate.text'),
      confirmLabel: driver.active ? tc('actions.deactivate') : tc('actions.activate'),
      dangerous: driver.active,
    })
    if (!confirmed) return

    try {
      if (driver.active) {
        await deactivateDriver(companyId, driver.id)
        notifySuccess(td('deactivated'), driver.fullName)
      } else {
        await activateDriver(companyId, driver.id)
        notifySuccess(td('activated'), driver.fullName)
      }
      refresh()
    } catch (error) {
      notifyError(td('errorTitle'), describeApiError(error as ApiError))
    }
  }

  const columns: DataTableColumn<DriverView>[] = [
    {
      key: 'name',
      header: t('drivers.columns.name'),
      render: (driver) => (
        <div>
          <div className="fw-semibold">{driver.fullName}</div>
          <div className="text-muted small">{driver.code}</div>
        </div>
      ),
    },
    {
      key: 'document',
      header: t('drivers.columns.document'),
      render: (driver) => `${driver.documentType} ${driver.documentNumber}`,
    },
    {
      key: 'carrier',
      header: tc('columns.carrier'),
      render: (driver) => driver.carrierBusinessName ?? t('drivers.ownStaff'),
    },
    {
      key: 'license',
      header: t('drivers.columns.license'),
      render: (driver) => (
        <div>
          <div className="tms-code">
            {driver.licenseNumber}
            {driver.licenseCategory !== null && <span className="text-muted"> · {driver.licenseCategory}</span>}
          </div>
          {/* The date sits under the badge rather than in its own column: on its own a date says
              nothing, and the badge without it cannot be acted on. */}
          <div className="small">
            <StatusBadge
              label={enumLabels.driverLicenseStatus(driver.licenseStatus)}
              tone={LICENSE_TONE[driver.licenseStatus]}
            />{' '}
            <span className="text-muted">
              {driver.licenseExpiresOn === null
                ? t('drivers.licenseNoExpiry')
                : format.date(driver.licenseExpiresOn)}
            </span>
          </div>
        </div>
      ),
    },
    { key: 'phone', header: tc('columns.phone'), render: (driver) => driver.phone ?? '—' },
    { key: 'active', header: tc('columns.status'), render: (driver) => <ActiveBadge active={driver.active} /> },
  ]

  if (canManage) {
    columns.push({
      key: 'actions',
      header: tc('columns.actions'),
      actions: true,
      render: (driver) => (
        <ActionMenu
          items={[
            {
              key: 'edit',
              label: tc('actions.edit'),
              icon: 'bi-pencil',
              onSelect: () => setModal({ mode: 'edit', driver }),
            },
            {
              key: 'active',
              label: driver.active ? tc('actions.deactivate') : tc('actions.activate'),
              icon: driver.active ? 'bi-slash-circle' : 'bi-check-circle',
              dangerous: driver.active,
              onSelect: () => void toggleActive(driver),
            },
          ]}
        />
      ),
    })
  }

  const pageData = driversQuery.data

  return (
    <div>
      <PageHeader
        icon="truck"
        title={t('drivers.title')}
        description={t('drivers.description')}
        actions={
          canManage && (
            <button
              type="button"
              className="btn btn-primary btn-sm d-inline-flex align-items-center gap-2"
              onClick={() => setModal({ mode: 'create' })}
            >
              <i className="bi bi-plus-lg" aria-hidden="true" />
              {t('drivers.new')}
            </button>
          )
        }
      />

      <FilterBar onSubmit={applyFilters} onReset={resetFilters}>
        <div>
          <label htmlFor="driver-filter-code" className="form-label small mb-1">
            {tc('columns.code')}
          </label>
          <input
            id="driver-filter-code"
            className="form-control form-control-sm"
            value={draftFilters.code}
            onChange={(event) => setDraftFilters({ ...draftFilters, code: event.target.value })}
          />
        </div>
        <div>
          <label htmlFor="driver-filter-name" className="form-label small mb-1">
            {t('drivers.filters.name')}
          </label>
          <input
            id="driver-filter-name"
            className="form-control form-control-sm"
            value={draftFilters.name}
            onChange={(event) => setDraftFilters({ ...draftFilters, name: event.target.value })}
          />
        </div>
        <div>
          <label htmlFor="driver-filter-carrier" className="form-label small mb-1">
            {tc('columns.carrier')}
          </label>
          <Select
            id="driver-filter-carrier"
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
          <label htmlFor="driver-filter-license" className="form-label small mb-1">
            {t('drivers.filters.license')}
          </label>
          <Select
            id="driver-filter-license"
            size="sm"
            value={draftFilters.licenseStatus}
            onChange={(next) =>
              setDraftFilters({ ...draftFilters, licenseStatus: next as DriverLicenseStatus | '' })
            }
            options={[
              { value: '', label: t('drivers.filters.allLicenseStatuses') },
              ...DRIVER_LICENSE_STATUSES.map((status) => ({
                value: status,
                label: enumLabels.driverLicenseStatus(status),
              })),
            ]}
          />
        </div>
        <div>
          <label htmlFor="driver-filter-active" className="form-label small mb-1">
            {tc('columns.status')}
          </label>
          <Select
            id="driver-filter-active"
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
        rowKey={(driver) => driver.id}
        isLoading={driversQuery.isPending}
        error={driversQuery.isError ? describeApiError(driversQuery.error as ApiError) : null}
        onRetry={() => void driversQuery.refetch()}
        emptyTitle={t('drivers.empty.title')}
        emptyMessage={t('drivers.empty.message')}
        footer={pageData ? <Pagination page={pageData} onPageChange={setPage} /> : undefined}
      />

      {modal && (
        <DriverFormDrawer
          companyId={companyId}
          driver={modal.mode === 'edit' ? modal.driver : null}
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
