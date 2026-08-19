import { keepPreviousData, useQuery, useQueryClient } from '@tanstack/react-query'
import { useState } from 'react'
import { useTranslation } from 'react-i18next'
import type { ApiError } from '../../shared/api/httpClient'
import { activateCarrier, deactivateCarrier, fetchCarriers, type CarrierView } from '../../shared/api/carriersApi'
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
import { CarrierFormModal } from './CarrierFormModal'

const PAGE_SIZE = 25

type ActiveFilter = 'active' | 'inactive' | 'all'

interface AppliedFilters {
  code: string
  businessName: string
  active: ActiveFilter
}

const DEFAULT_FILTERS: AppliedFilters = { code: '', businessName: '', active: 'active' }

type ModalState = { mode: 'create' } | { mode: 'edit'; carrier: CarrierView } | null

export function CarriersPage() {
  const { t } = useTranslation('fleet')
  const { selected, hasPermission } = useCompany()
  const companyId = selected?.id ?? ''
  const canManage = hasPermission('fleet.carrier:manage')
  const queryClient = useQueryClient()

  const [page, setPage] = useState(0)
  const [draftFilters, setDraftFilters] = useState<AppliedFilters>(DEFAULT_FILTERS)
  const [filters, setFilters] = useState<AppliedFilters>(DEFAULT_FILTERS)
  const [modal, setModal] = useState<ModalState>(null)

  const carriersQuery = useQuery({
    queryKey: ['carriers', companyId, page, filters],
    queryFn: ({ signal }) =>
      fetchCarriers({
        companyId,
        page,
        size: PAGE_SIZE,
        sort: 'code,asc',
        code: filters.code || undefined,
        businessName: filters.businessName || undefined,
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
    void queryClient.invalidateQueries({ queryKey: ['carriers', companyId] })
  }

  async function toggleActive(carrier: CarrierView) {
    const confirmed = await confirmDialog({
      title: carrier.active ? 'Deactivate carrier?' : 'Activate carrier?',
      text: carrier.active
        ? `${carrier.businessName} will no longer be assignable to new vehicles.`
        : `${carrier.businessName} will become assignable again.`,
      confirmLabel: carrier.active ? 'Deactivate' : 'Activate',
      dangerous: carrier.active,
    })
    if (!confirmed) return

    try {
      if (carrier.active) {
        await deactivateCarrier(companyId, carrier.id)
        notifySuccess('Carrier deactivated', carrier.businessName)
      } else {
        await activateCarrier(companyId, carrier.id)
        notifySuccess('Carrier activated', carrier.businessName)
      }
      refresh()
    } catch (error) {
      notifyError('Could not update the carrier', describeApiError(error as ApiError))
    }
  }

  const columns: DataTableColumn<CarrierView>[] = [
    { key: 'code', header: 'Code', render: (carrier) => <span className="fw-semibold">{carrier.code}</span> },
    { key: 'businessName', header: 'Business name', render: (carrier) => carrier.businessName },
    { key: 'taxId', header: 'Tax id', render: (carrier) => `${carrier.taxIdType} ${carrier.taxIdValue}` },
    { key: 'contactName', header: 'Contact', render: (carrier) => carrier.contactName ?? '—' },
    { key: 'phone', header: 'Phone', render: (carrier) => carrier.phone ?? '—' },
    {
      key: 'active',
      header: 'Status',
      render: (carrier) => (
        <ActiveBadge active={carrier.active} />
      ),
    },
  ]

  if (canManage) {
    columns.push({
      key: 'actions',
      header: '',
      className: 'text-end',
      render: (carrier) => (
        <div className="btn-group btn-group-sm">
          <button
            type="button"
            className="btn btn-outline-secondary"
            onClick={() => setModal({ mode: 'edit', carrier })}
          >
            Edit
          </button>
          <button
            type="button"
            className={`btn btn-outline-${carrier.active ? 'danger' : 'success'}`}
            onClick={() => void toggleActive(carrier)}
          >
            {carrier.active ? 'Deactivate' : 'Activate'}
          </button>
        </div>
      ),
    })
  }

  const pageData = carriersQuery.data

  return (
    <div>
      <PageHeader
        title={t('carriers.title')}
        description={t('carriers.description')}
        actions={
          canManage && (
            <button type="button" className="btn btn-primary btn-sm" onClick={() => setModal({ mode: 'create' })}>
              {t('carriers.new')}
            </button>
          )
        }
      />

      <FilterBar onSubmit={applyFilters} onReset={resetFilters}>
        <div>
          <label htmlFor="carrier-filter-code" className="form-label small mb-1">
            Code
          </label>
          <input
            id="carrier-filter-code"
            className="form-control form-control-sm"
            value={draftFilters.code}
            onChange={(event) => setDraftFilters({ ...draftFilters, code: event.target.value })}
          />
        </div>
        <div>
          <label htmlFor="carrier-filter-business-name" className="form-label small mb-1">
            Business name
          </label>
          <input
            id="carrier-filter-business-name"
            className="form-control form-control-sm"
            value={draftFilters.businessName}
            onChange={(event) => setDraftFilters({ ...draftFilters, businessName: event.target.value })}
          />
        </div>
        <div>
          <label htmlFor="carrier-filter-active" className="form-label small mb-1">
            Status
          </label>
          <select
            id="carrier-filter-active"
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
            rowKey={(carrier) => carrier.id}
            isLoading={carriersQuery.isPending}
            error={carriersQuery.isError ? describeApiError(carriersQuery.error as ApiError) : null}
            onRetry={() => void carriersQuery.refetch()}
            emptyTitle="No carriers found"
            emptyMessage="Create a carrier or adjust your filters."
          />
        </div>
        {pageData && (
          <div className="card-footer">
            <Pagination page={pageData} onPageChange={setPage} />
          </div>
        )}
      </div>

      {modal && (
        <CarrierFormModal
          companyId={companyId}
          carrier={modal.mode === 'edit' ? modal.carrier : null}
          onClose={() => setModal(null)}
          onSaved={() => {
            setModal(null)
            notifySuccess(modal.mode === 'edit' ? 'Carrier updated' : 'Carrier created')
            refresh()
          }}
        />
      )}
    </div>
  )
}
