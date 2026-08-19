import { useEnumLabels } from '../../shared/i18n/enums'
import { keepPreviousData, useQuery, useQueryClient } from '@tanstack/react-query'
import { useState } from 'react'
import { useTranslation } from 'react-i18next'
import type { ApiError } from '../../shared/api/httpClient'
import { fetchDestinations } from '../../shared/api/destinationsApi'
import { fetchOrigins } from '../../shared/api/originsApi'
import {
  ORDER_PRIORITIES,
  ORDER_STATUSES,
  cancelOrder,
  fetchOrders,
  markOrderReadyForPlanning,
  type OrderPriority,
  type OrderStatus,
  type OrderView,
} from '../../shared/api/ordersApi'
import { describeApiError } from '../../shared/api/problemMessages'
import { useCompany } from '../../shared/company/CompanyContext'
import {
  confirmDialog,
  DataTable,
  FilterBar,
  PageHeader,
  Pagination,
  StatusBadge,
  type DataTableColumn,
  type StatusTone,
} from '../../shared/ui/components'
import { notifyError, notifySuccess } from '../../shared/ui/alerts'
import { OrderFormModal } from './OrderFormModal'

const PAGE_SIZE = 25

const STATUS_TONE: Record<OrderStatus, StatusTone> = {
  NOT_READY: 'neutral',
  READY_FOR_PLANNING: 'info',
  PLANNED: 'success',
  CANCELLED: 'danger',
}

const PRIORITY_TONE: Record<OrderPriority, StatusTone> = {
  LOW: 'neutral',
  NORMAL: 'info',
  HIGH: 'warning',
  URGENT: 'danger',
}

interface AppliedFilters {
  orderNumber: string
  originId: string
  destinationId: string
  serviceDateFrom: string
  serviceDateTo: string
  status: OrderStatus | ''
  priority: OrderPriority | ''
}

const DEFAULT_FILTERS: AppliedFilters = {
  orderNumber: '', originId: '', destinationId: '', serviceDateFrom: '', serviceDateTo: '', status: '', priority: '',
}

type ModalState = { mode: 'create' } | { mode: 'edit'; orderId: string } | null

export function OrdersPage() {
  const { t } = useTranslation('orders')
  const enumLabels = useEnumLabels()
  const { selected, hasPermission } = useCompany()
  const companyId = selected?.id ?? ''
  const canManage = hasPermission('orders.order:manage')
  const queryClient = useQueryClient()

  const [page, setPage] = useState(0)
  const [draftFilters, setDraftFilters] = useState<AppliedFilters>(DEFAULT_FILTERS)
  const [filters, setFilters] = useState<AppliedFilters>(DEFAULT_FILTERS)
  const [modal, setModal] = useState<ModalState>(null)

  const ordersQuery = useQuery({
    queryKey: ['orders', companyId, page, filters],
    queryFn: ({ signal }) =>
      fetchOrders({
        companyId,
        page,
        size: PAGE_SIZE,
        sort: 'serviceDate,desc',
        orderNumber: filters.orderNumber || undefined,
        originId: filters.originId || undefined,
        destinationId: filters.destinationId || undefined,
        serviceDateFrom: filters.serviceDateFrom || undefined,
        serviceDateTo: filters.serviceDateTo || undefined,
        status: filters.status || undefined,
        priority: filters.priority || undefined,
        signal,
      }),
    placeholderData: keepPreviousData,
  })

  const originsQuery = useQuery({
    queryKey: ['origins-for-order-filter', companyId],
    queryFn: ({ signal }) => fetchOrigins({ companyId, size: 200, active: true, sort: 'code,asc', signal }),
    enabled: companyId !== '',
  })
  const origins = originsQuery.data?.content ?? []

  const destinationsQuery = useQuery({
    queryKey: ['destinations-for-order-filter', companyId],
    queryFn: ({ signal }) => fetchDestinations({ companyId, size: 200, active: true, sort: 'code,asc', signal }),
    enabled: companyId !== '',
  })
  const destinations = destinationsQuery.data?.content ?? []

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
    void queryClient.invalidateQueries({ queryKey: ['orders', companyId] })
  }

  async function markReady(order: OrderView) {
    const confirmed = await confirmDialog({
      title: 'Mark order ready for planning?',
      text: `${order.orderNumber} will become visible to planning once at least one line and a known weight, volume or pallet count are on file.`,
      confirmLabel: 'Mark ready',
    })
    if (!confirmed) return

    try {
      await markOrderReadyForPlanning(companyId, order.id)
      notifySuccess('Order marked ready for planning', order.orderNumber)
      refresh()
    } catch (error) {
      notifyError('Could not mark the order ready', describeApiError(error as ApiError))
    }
  }

  async function cancel(order: OrderView) {
    const confirmed = await confirmDialog({
      title: 'Cancel order?',
      text: `${order.orderNumber} will be cancelled and can no longer be edited or planned.`,
      confirmLabel: 'Cancel order',
      dangerous: true,
    })
    if (!confirmed) return

    try {
      await cancelOrder(companyId, order.id)
      notifySuccess('Order cancelled', order.orderNumber)
      refresh()
    } catch (error) {
      notifyError('Could not cancel the order', describeApiError(error as ApiError))
    }
  }

  const columns: DataTableColumn<OrderView>[] = [
    { key: 'orderNumber', header: 'Order #', render: (order) => <span className="fw-semibold">{order.orderNumber}</span> },
    {
      key: 'lane',
      header: 'Lane',
      render: (order) => (
        <span>
          {order.originName ?? order.originCode ?? '—'} → {order.destinationName ?? order.destinationCode ?? '—'}
        </span>
      ),
    },
    { key: 'serviceDate', header: 'Service date', render: (order) => order.serviceDate },
    {
      key: 'priority',
      header: 'Priority',
      render: (order) => <StatusBadge label={enumLabels.orderPriority(order.priority)} tone={PRIORITY_TONE[order.priority]} />,
    },
    {
      key: 'totals',
      header: 'Totals',
      render: (order) => (
        <span className="small text-body-secondary">
          {order.totalWeightKg} kg · {order.totalVolumeM3} m³ · {order.totalPallets} plt
        </span>
      ),
    },
    { key: 'lines', header: 'Lines', className: 'text-end', render: (order) => order.lineCount },
    {
      key: 'status',
      header: 'Status',
      render: (order) => <StatusBadge label={enumLabels.orderStatus(order.status)} tone={STATUS_TONE[order.status]} />,
    },
  ]

  if (canManage) {
    columns.push({
      key: 'actions',
      header: '',
      className: 'text-end',
      render: (order) => {
        const editable = order.status === 'NOT_READY' || order.status === 'READY_FOR_PLANNING'
        const cancellable = order.status !== 'CANCELLED' && order.status !== 'PLANNED'
        return (
          <div className="btn-group btn-group-sm">
            <button
              type="button"
              className="btn btn-outline-secondary"
              onClick={() => setModal({ mode: 'edit', orderId: order.id })}
            >
              {editable ? 'Edit' : 'View'}
            </button>
            {order.status === 'NOT_READY' && (
              <button type="button" className="btn btn-outline-primary" onClick={() => void markReady(order)}>
                Mark ready
              </button>
            )}
            {cancellable && (
              <button type="button" className="btn btn-outline-danger" onClick={() => void cancel(order)}>
                Cancel
              </button>
            )}
          </div>
        )
      },
    })
  }

  const pageData = ordersQuery.data

  return (
    <div>
      <PageHeader
        title={t('title')}
        description={t('description')}
        actions={
          canManage && (
            <button type="button" className="btn btn-primary btn-sm" onClick={() => setModal({ mode: 'create' })}>
              {t('new')}
            </button>
          )
        }
      />

      <FilterBar onSubmit={applyFilters} onReset={resetFilters}>
        <div>
          <label htmlFor="filter-order-number" className="form-label small mb-1">
            Order #
          </label>
          <input
            id="filter-order-number"
            className="form-control form-control-sm"
            value={draftFilters.orderNumber}
            onChange={(event) => setDraftFilters({ ...draftFilters, orderNumber: event.target.value })}
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
          <label htmlFor="filter-destination" className="form-label small mb-1">
            Destination
          </label>
          <select
            id="filter-destination"
            className="form-select form-select-sm"
            value={draftFilters.destinationId}
            onChange={(event) => setDraftFilters({ ...draftFilters, destinationId: event.target.value })}
          >
            <option value="">All destinations</option>
            {destinations.map((destination) => (
              <option key={destination.id} value={destination.id}>
                {destination.name}
              </option>
            ))}
          </select>
        </div>
        <div>
          <label htmlFor="filter-date-from" className="form-label small mb-1">
            Service date from
          </label>
          <input
            id="filter-date-from"
            type="date"
            className="form-control form-control-sm"
            value={draftFilters.serviceDateFrom}
            onChange={(event) => setDraftFilters({ ...draftFilters, serviceDateFrom: event.target.value })}
          />
        </div>
        <div>
          <label htmlFor="filter-date-to" className="form-label small mb-1">
            Service date to
          </label>
          <input
            id="filter-date-to"
            type="date"
            className="form-control form-control-sm"
            value={draftFilters.serviceDateTo}
            onChange={(event) => setDraftFilters({ ...draftFilters, serviceDateTo: event.target.value })}
          />
        </div>
        <div>
          <label htmlFor="filter-status" className="form-label small mb-1">
            Status
          </label>
          <select
            id="filter-status"
            className="form-select form-select-sm"
            value={draftFilters.status}
            onChange={(event) => setDraftFilters({ ...draftFilters, status: event.target.value as OrderStatus | '' })}
          >
            <option value="">All statuses</option>
            {ORDER_STATUSES.map((status) => (
              <option key={status} value={status}>
                {enumLabels.orderStatus(status)}
              </option>
            ))}
          </select>
        </div>
        <div>
          <label htmlFor="filter-priority" className="form-label small mb-1">
            Priority
          </label>
          <select
            id="filter-priority"
            className="form-select form-select-sm"
            value={draftFilters.priority}
            onChange={(event) => setDraftFilters({ ...draftFilters, priority: event.target.value as OrderPriority | '' })}
          >
            <option value="">All priorities</option>
            {ORDER_PRIORITIES.map((priority) => (
              <option key={priority} value={priority}>
                {enumLabels.orderPriority(priority)}
              </option>
            ))}
          </select>
        </div>
      </FilterBar>

      <div className="card shadow-sm">
        <div className="card-body p-0">
          <DataTable
            columns={columns}
            rows={pageData?.content ?? []}
            rowKey={(order) => order.id}
            isLoading={ordersQuery.isPending}
            error={ordersQuery.isError ? describeApiError(ordersQuery.error as ApiError) : null}
            onRetry={() => void ordersQuery.refetch()}
            emptyTitle="No orders found"
            emptyMessage="Create an order or adjust your filters."
          />
        </div>
        {pageData && (
          <div className="card-footer">
            <Pagination page={pageData} onPageChange={setPage} />
          </div>
        )}
      </div>

      {modal && (
        <OrderFormModal
          companyId={companyId}
          orderId={modal.mode === 'edit' ? modal.orderId : null}
          onClose={() => setModal(null)}
          onSaved={() => {
            setModal(null)
            notifySuccess(modal.mode === 'edit' ? 'Order updated' : 'Order created')
            refresh()
          }}
        />
      )}
    </div>
  )
}
