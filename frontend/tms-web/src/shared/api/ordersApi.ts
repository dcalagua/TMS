import { apiRequest } from './httpClient'
import type { PageResponse } from './pageResponse'

/** Mirrors the backend's `OrderStatus` enum (`orders/domain/OrderStatus.java`). See
 * `docs/domain/ORDER_LIFECYCLE_V1.md` for the full lifecycle. */
export type OrderStatus = 'NOT_READY' | 'READY_FOR_PLANNING' | 'PLANNED' | 'CANCELLED'

export const ORDER_STATUSES: OrderStatus[] = ['NOT_READY', 'READY_FOR_PLANNING', 'PLANNED', 'CANCELLED']

/** Mirrors the backend's `OrderPriority` enum (`orders/domain/OrderPriority.java`). */
export type OrderPriority = 'LOW' | 'NORMAL' | 'HIGH' | 'URGENT'

export const ORDER_PRIORITIES: OrderPriority[] = ['LOW', 'NORMAL', 'HIGH', 'URGENT']

/** Mirrors the backend's `OrderView` record - the list-row shape (a line count, not the lines). */
export interface OrderView {
  id: string
  orderNumber: string
  externalSource: string | null
  externalReference: string | null
  originId: string
  originCode: string | null
  originName: string | null
  destinationId: string
  destinationCode: string | null
  destinationName: string | null
  customerName: string | null
  customerReference: string | null
  serviceDate: string
  priority: OrderPriority
  requestedWindowStart: string | null
  requestedWindowEnd: string | null
  status: OrderStatus
  cancelReason: string | null
  totalWeightKg: number
  totalVolumeM3: number
  totalPallets: number
  lineCount: number
  version: number
  createdAt: string
  updatedAt: string
}

/** Mirrors the backend's `OrderDetailView.OrderLineView` record. */
export interface OrderLineView {
  id: string
  lineNumber: number
  materialCode: string
  materialDescription: string
  quantity: number
  uom: string
  unitWeightKg: number | null
  unitVolumeM3: number | null
  lineWeightKg: number | null
  lineVolumeM3: number | null
  palletQuantity: number | null
}

/** Mirrors the backend's `OrderDetailView` record - returned by get/create/update/mark-ready/cancel. */
export interface OrderDetailView extends Omit<OrderView, 'lineCount'> {
  lines: OrderLineView[]
}

/** Mirrors the backend's `OrderRequest.OrderLineRequest` record. */
export interface OrderLineRequest {
  materialCode: string
  materialDescription: string
  quantity: number
  uom: string
  unitWeightKg?: number | null
  unitVolumeM3?: number | null
  palletQuantity?: number | null
}

/** Mirrors the backend's `OrderRequest` record - shared shape for create and update.
 * `version` is ignored by create and required by update (`OrderService.requireCurrentVersion`). */
export interface OrderRequest {
  externalSource?: string | null
  externalReference?: string | null
  originId: string
  destinationId: string
  customerName?: string | null
  customerReference?: string | null
  serviceDate: string
  priority: OrderPriority
  requestedWindowStart?: string | null
  requestedWindowEnd?: string | null
  version?: number | null
  lines: OrderLineRequest[]
}

export interface OrderListParams {
  companyId: string
  page?: number
  size?: number
  sort?: string
  orderNumber?: string
  originId?: string
  destinationId?: string
  serviceDateFrom?: string
  serviceDateTo?: string
  status?: OrderStatus
  priority?: OrderPriority
  signal?: AbortSignal
}

export function fetchOrders(params: OrderListParams): Promise<PageResponse<OrderView>> {
  const { companyId, signal, ...query } = params
  return apiRequest<PageResponse<OrderView>>('/orders', { companyId, signal, query })
}

export function fetchOrder(companyId: string, id: string, signal?: AbortSignal): Promise<OrderDetailView> {
  return apiRequest<OrderDetailView>(`/orders/${id}`, { companyId, signal })
}

export function createOrder(companyId: string, request: OrderRequest): Promise<OrderDetailView> {
  return apiRequest<OrderDetailView>('/orders', { method: 'POST', companyId, body: request })
}

export function updateOrder(companyId: string, id: string, request: OrderRequest): Promise<OrderDetailView> {
  return apiRequest<OrderDetailView>(`/orders/${id}`, { method: 'PUT', companyId, body: request })
}

export function markOrderReadyForPlanning(companyId: string, id: string): Promise<OrderDetailView> {
  return apiRequest<OrderDetailView>(`/orders/${id}/mark-ready`, { method: 'POST', companyId })
}

export function cancelOrder(companyId: string, id: string, reason?: string): Promise<OrderDetailView> {
  return apiRequest<OrderDetailView>(`/orders/${id}/cancel`, {
    method: 'POST', companyId, query: reason ? { reason } : undefined,
  })
}
