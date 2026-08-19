import { keepPreviousData, useQuery, useQueryClient } from '@tanstack/react-query'
import { useState } from 'react'
import { useTranslation } from 'react-i18next'
import { fetchDestinations } from '../../shared/api/destinationsApi'
import type { ApiError } from '../../shared/api/httpClient'
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
import { fetchOrigins } from '../../shared/api/originsApi'
import { describeApiError } from '../../shared/api/problemMessages'
import { useEnumLabels } from '../../shared/i18n/enums'
import { useFormat } from '../../shared/i18n/format'
import { useCompany } from '../../shared/company/CompanyContext'
import { notifyError, notifySuccess } from '../../shared/ui/alerts'
import {
  ActionMenu,
  DataTable,
  PageHeader,
  Pagination,
  StatusBadge,
  Toolbar,
  confirmDialog,
  type DataTableColumn,
  type StatusTone,
} from '../../shared/ui/components'
import { OrderFormDrawer } from './OrderFormDrawer'

const PAGE_SIZE = 25

const STATUS_TONE: Record<OrderStatus, StatusTone> = {
  NOT_READY: 'neutral',
  READY_FOR_PLANNING: 'info',
  PLANNED: 'success',
  CANCELLED: 'danger',
}

const PRIORITY_TONE: Record<OrderPriority, StatusTone> = {
  LOW: 'neutral',
  NORMAL: 'neutral',
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

/** Totals for the rows currently on screen. Deliberately not presented as a company-wide
 * figure: the backend paginates, so anything beyond this page is simply not known here. */
function pageTotals(rows: OrderView[]) {
  return rows.reduce(
    (running, order) => ({
      weight: running.weight + order.totalWeightKg,
      volume: running.volume + order.totalVolumeM3,
      pallets: running.pallets + order.totalPallets,
    }),
    { weight: 0, volume: 0, pallets: 0 },
  )
}

export function OrdersPage() {
  const { t } = useTranslation('orders')
  const { t: tc } = useTranslation('common')
  const enumLabels = useEnumLabels()
  const format = useFormat()
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
      title: t('dialogs.markReadyTitle'),
      text: t('dialogs.markReadyText', { number: order.orderNumber }),
      confirmLabel: t('actions.markReady'),
    })
    if (!confirmed) return

    try {
      await markOrderReadyForPlanning(companyId, order.id)
      notifySuccess(t('dialogs.markedReady'), order.orderNumber)
      refresh()
    } catch (error) {
      notifyError(t('dialogs.markReadyError'), describeApiError(error as ApiError))
    }
  }

  async function cancel(order: OrderView) {
    const confirmed = await confirmDialog({
      title: t('dialogs.cancelTitle'),
      text: t('dialogs.cancelText', { number: order.orderNumber }),
      confirmLabel: t('actions.cancelOrder'),
      dangerous: true,
    })
    if (!confirmed) return

    try {
      await cancelOrder(companyId, order.id)
      notifySuccess(t('dialogs.cancelled'), order.orderNumber)
      refresh()
    } catch (error) {
      notifyError(t('dialogs.cancelError'), describeApiError(error as ApiError))
    }
  }

  const columns: DataTableColumn<OrderView>[] = [
    {
      key: 'orderNumber',
      header: tc('columns.orderNumber'),
      render: (order) => <span className="tms-code tms-cell-strong">{order.orderNumber}</span>,
    },
    {
      key: 'origin',
      header: tc('columns.origin'),
      render: (order) => (
        <span className="tms-truncate d-block" style={{ maxWidth: '14rem' }} title={order.originName ?? undefined}>
          {order.originName ?? order.originCode ?? '—'}
        </span>
      ),
    },
    {
      key: 'destination',
      header: tc('columns.destination'),
      render: (order) => (
        <span
          className="tms-truncate d-block"
          style={{ maxWidth: '14rem' }}
          title={order.destinationName ?? undefined}
        >
          {order.destinationName ?? order.destinationCode ?? '—'}
        </span>
      ),
    },
    { key: 'serviceDate', header: tc('columns.requiredDate'), render: (order) => format.date(order.serviceDate) },
    {
      key: 'priority',
      header: tc('columns.priority'),
      render: (order) => (
        <StatusBadge label={enumLabels.orderPriority(order.priority)} tone={PRIORITY_TONE[order.priority]} />
      ),
    },
    { key: 'weight', header: tc('columns.weight'), numeric: true, render: (order) => format.weight(order.totalWeightKg) },
    { key: 'volume', header: tc('columns.volume'), numeric: true, render: (order) => format.volume(order.totalVolumeM3) },
    {
      key: 'pallets',
      header: tc('columns.pallets'),
      numeric: true,
      render: (order) => format.decimal(order.totalPallets),
    },
    {
      key: 'lines',
      header: tc('columns.lines'),
      numeric: true,
      render: (order) => format.quantity(order.lineCount),
    },
    {
      key: 'status',
      header: tc('columns.status'),
      render: (order) => <StatusBadge label={enumLabels.orderStatus(order.status)} tone={STATUS_TONE[order.status]} />,
    },
  ]

  if (canManage) {
    columns.push({
      key: 'actions',
      header: tc('columns.actions'),
      actions: true,
      render: (order) => {
        const editable = order.status === 'NOT_READY' || order.status === 'READY_FOR_PLANNING'
        const cancellable = order.status !== 'CANCELLED' && order.status !== 'PLANNED'
        return (
          <ActionMenu
            items={[
              {
                key: 'open',
                label: editable ? tc('actions.edit') : t('actions.view'),
                icon: editable ? 'bi-pencil' : 'bi-eye',
                onSelect: () => setModal({ mode: 'edit', orderId: order.id }),
              },
              ...(order.status === 'NOT_READY'
                ? [
                    {
                      key: 'ready',
                      label: t('actions.markReady'),
                      icon: 'bi-check2-circle',
                      onSelect: () => void markReady(order),
                    },
                  ]
                : []),
              ...(cancellable
                ? [
                    {
                      key: 'cancel',
                      label: t('actions.cancelOrder'),
                      icon: 'bi-x-circle',
                      dangerous: true,
                      onSelect: () => void cancel(order),
                    },
                  ]
                : []),
            ]}
          />
        )
      },
    })
  }

  const pageData = ordersQuery.data
  const rows = pageData?.content ?? []
  const totals = pageTotals(rows)
  const activeFilterCount = Object.values(filters).filter((value) => value !== '').length

  return (
    <div>
      <PageHeader
        icon="card-checklist"
        title={t('title')}
        description={t('description')}
        meta={
          pageData && (
            <span className="tms-badge tms-badge-neutral">
              {t('summary.found', { count: pageData.totalElements })}
            </span>
          )
        }
        actions={
          canManage && (
            <button
              type="button"
              className="btn btn-primary btn-sm d-inline-flex align-items-center gap-2"
              onClick={() => setModal({ mode: 'create' })}
            >
              <i className="bi bi-plus-lg" aria-hidden="true" />
              {t('new')}
            </button>
          )
        }
      />

      {rows.length > 0 && (
        <div className="row g-2 mb-3">
          {[
            { key: 'weight', label: t('summary.pageWeight'), value: format.weight(totals.weight), icon: 'bi-box-seam' },
            { key: 'volume', label: t('summary.pageVolume'), value: format.volume(totals.volume), icon: 'bi-bounding-box' },
            { key: 'pallets', label: t('summary.pagePallets'), value: format.decimal(totals.pallets), icon: 'bi-stack' },
          ].map((tile) => (
            <div className="col-12 col-sm-4" key={tile.key}>
              <div className="tms-card h-100 px-3 py-2 d-flex align-items-center gap-3">
                <i className={`bi ${tile.icon} fs-5 text-body-secondary`} aria-hidden="true" />
                <div className="tms-min-w-0">
                  <p className="tms-section-title mb-0">{tile.label}</p>
                  <p className="mb-0 fw-semibold">{tile.value}</p>
                </div>
              </div>
            </div>
          ))}
          <div className="col-12">
            <p className="text-body-secondary small mb-0">{t('summary.pageNote')}</p>
          </div>
        </div>
      )}

      <Toolbar
        onApply={applyFilters}
        onReset={resetFilters}
        activeFilterCount={activeFilterCount}
        primary={
          <div className="tms-filter-field flex-grow-1" style={{ maxWidth: '18rem' }}>
            <label htmlFor="filter-order-number" className="tms-filter-label">
              {tc('columns.orderNumber')}
            </label>
            <input
              id="filter-order-number"
              className="form-control form-control-sm"
              value={draftFilters.orderNumber}
              onChange={(event) => setDraftFilters({ ...draftFilters, orderNumber: event.target.value })}
            />
          </div>
        }
        filters={
          <>
            <div className="tms-filter-field">
              <label htmlFor="filter-origin" className="tms-filter-label">
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
            <div className="tms-filter-field">
              <label htmlFor="filter-destination" className="tms-filter-label">
                {tc('columns.destination')}
              </label>
              <select
                id="filter-destination"
                className="form-select form-select-sm"
                value={draftFilters.destinationId}
                onChange={(event) => setDraftFilters({ ...draftFilters, destinationId: event.target.value })}
              >
                <option value="">{t('filters.allDestinations')}</option>
                {destinations.map((destination) => (
                  <option key={destination.id} value={destination.id}>
                    {destination.name}
                  </option>
                ))}
              </select>
            </div>
            <div className="tms-filter-field">
              <label htmlFor="filter-date-from" className="tms-filter-label">
                {t('filters.dateFrom')}
              </label>
              <input
                id="filter-date-from"
                type="date"
                className="form-control form-control-sm"
                value={draftFilters.serviceDateFrom}
                onChange={(event) => setDraftFilters({ ...draftFilters, serviceDateFrom: event.target.value })}
              />
            </div>
            <div className="tms-filter-field">
              <label htmlFor="filter-date-to" className="tms-filter-label">
                {t('filters.dateTo')}
              </label>
              <input
                id="filter-date-to"
                type="date"
                className="form-control form-control-sm"
                value={draftFilters.serviceDateTo}
                onChange={(event) => setDraftFilters({ ...draftFilters, serviceDateTo: event.target.value })}
              />
            </div>
            <div className="tms-filter-field">
              <label htmlFor="filter-status" className="tms-filter-label">
                {tc('columns.status')}
              </label>
              <select
                id="filter-status"
                className="form-select form-select-sm"
                value={draftFilters.status}
                onChange={(event) => setDraftFilters({ ...draftFilters, status: event.target.value as OrderStatus | '' })}
              >
                <option value="">{t('filters.allStatuses')}</option>
                {ORDER_STATUSES.map((status) => (
                  <option key={status} value={status}>
                    {enumLabels.orderStatus(status)}
                  </option>
                ))}
              </select>
            </div>
            <div className="tms-filter-field">
              <label htmlFor="filter-priority" className="tms-filter-label">
                {tc('columns.priority')}
              </label>
              <select
                id="filter-priority"
                className="form-select form-select-sm"
                value={draftFilters.priority}
                onChange={(event) =>
                  setDraftFilters({ ...draftFilters, priority: event.target.value as OrderPriority | '' })
                }
              >
                <option value="">{t('filters.allPriorities')}</option>
                {ORDER_PRIORITIES.map((priority) => (
                  <option key={priority} value={priority}>
                    {enumLabels.orderPriority(priority)}
                  </option>
                ))}
              </select>
            </div>
          </>
        }
      />

      <DataTable
        columns={columns}
        rows={rows}
        total={pageData?.totalElements}
        rowKey={(order) => order.id}
        isLoading={ordersQuery.isPending}
        error={ordersQuery.isError ? describeApiError(ordersQuery.error as ApiError) : null}
        onRetry={() => void ordersQuery.refetch()}
        emptyTitle={t('empty.title')}
        emptyMessage={t('empty.message')}
        caption={t('title')}
      />

      {pageData && (
        <div className="mt-3">
          <Pagination page={pageData} onPageChange={setPage} />
        </div>
      )}

      {modal && (
        <OrderFormDrawer
          companyId={companyId}
          orderId={modal.mode === 'edit' ? modal.orderId : null}
          onClose={() => setModal(null)}
          onSaved={() => {
            setModal(null)
            refresh()
          }}
        />
      )}
    </div>
  )
}
