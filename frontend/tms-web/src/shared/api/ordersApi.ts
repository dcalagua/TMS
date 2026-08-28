import { apiDownload, apiRequest, apiUpload, type DownloadedFile } from './httpClient'
import type { PageResponse } from './pageResponse'

/** Mirrors the backend's `OrderStatus` enum (`orders/domain/OrderStatus.java`). See
 * `docs/domain/ORDER_LIFECYCLE_V2.md` for the full lifecycle, planning and execution. */
export type OrderStatus =
  | 'NOT_READY'
  | 'READY_FOR_PLANNING'
  | 'PLANNED'
  | 'IN_EXECUTION'
  | 'DELIVERED'
  | 'PARTIALLY_DELIVERED'
  | 'DELIVERY_FAILED'
  | 'CANCELLED'

/** In lifecycle order, which is the order a filter should offer them in. */
export const ORDER_STATUSES: OrderStatus[] = [
  'NOT_READY',
  'READY_FOR_PLANNING',
  'PLANNED',
  'IN_EXECUTION',
  'DELIVERED',
  'PARTIALLY_DELIVERED',
  'DELIVERY_FAILED',
  'CANCELLED',
]

/**
 * The statuses an order can be reopened from: it came back short and the customer is still owed
 * something. Mirrors `OrderStatus.isReopenable()` - the rule is the backend's and it refuses
 * anything else with a 409; this only decides whether the button is worth rendering.
 */
export const REOPENABLE_ORDER_STATUSES: OrderStatus[] = ['PARTIALLY_DELIVERED', 'DELIVERY_FAILED']

/**
 * What happened to the goods at the dock, per stop and correctable - the live view that
 * `OrderStatus`'s closed-out states are the lifecycle consequence of. Derived by the backend from
 * the delivery rows (migration V28) rather than stored - see `OrderFulfillmentStatus` there.
 */
export type OrderFulfillmentStatus =
  | 'PENDING'
  | 'DELIVERED'
  | 'PARTIALLY_DELIVERED'
  | 'REJECTED'
  | 'FAILED'
  | 'NOT_ATTEMPTED'

/** In the order a filter should offer them: nothing yet, then best to worst outcome. */
export const ORDER_FULFILLMENT_STATUSES: OrderFulfillmentStatus[] = [
  'PENDING', 'DELIVERED', 'PARTIALLY_DELIVERED', 'REJECTED', 'FAILED', 'NOT_ATTEMPTED',
]

/** Mirrors the backend's `TotalsSource` enum (`orders/domain/TotalsSource.java`).
 *
 * `CALCULATED` means the effective totals were summed from the lines; `DECLARED` means the order
 * has no lines and the figures are the ones its sender asserted. See
 * `docs/domain/ORDER_TOTALS_V1.md` - and note that the browser never sends the effective totals
 * under either strategy, only `declared*`. */
export type TotalsSource = 'CALCULATED' | 'DECLARED'

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
  fulfillmentStatus: OrderFulfillmentStatus
  cancelReason: string | null
  totalWeightKg: number
  totalVolumeM3: number
  totalPallets: number
  declaredWeightKg: number | null
  declaredVolumeM3: number | null
  declaredPallets: number | null
  totalsSource: TotalsSource
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
  /** What the operator asserts the order weighs/occupies, independent of the lines. Where the
   * lines also state a measure the two must agree within 1% or the backend answers 400. */
  declaredWeightKg?: number | null
  declaredVolumeM3?: number | null
  declaredPallets?: number | null
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

/**
 * Puts an order that came back short into the plannable pool for another delivery attempt
 * (migration V36). Only `PARTIALLY_DELIVERED` and `DELIVERY_FAILED` are accepted; anything else
 * comes back 409 with a sentence explaining why.
 *
 * The reason is optional to the API and asked for in the UI: a redelivery costs a truck, and the
 * audit row is where "why did we go twice" gets answered.
 */
export function reopenOrderForPlanning(
  companyId: string, id: string, reason?: string,
): Promise<OrderDetailView> {
  return apiRequest<OrderDetailView>(`/orders/${id}/reopen`, {
    method: 'POST', companyId, query: reason ? { reason } : undefined,
  })
}

// --- bulk import -----------------------------------------------------------------------

/** Mirrors the backend's `OrderImportFormat` enum. */
export type OrderImportFormat = 'XLSX' | 'CSV'

/** Mirrors `OrderImportReport.Outcome` - what the import decided about one order. */
export type OrderImportOutcome = 'CREATE' | 'SKIPPED_DUPLICATE' | 'REJECTED'

/** Mirrors `OrderImportReport.OrderPreview`. */
export interface OrderImportPreview {
  externalReference: string
  outcome: OrderImportOutcome
  firstRowNumber: number
  originCode: string | null
  originName: string | null
  destinationCode: string | null
  destinationName: string | null
  customerName: string | null
  serviceDate: string | null
  priority: OrderPriority
  lineCount: number
  totalWeightKg: number | null
  totalVolumeM3: number | null
  totalPallets: number | null
  totalsSource: TotalsSource
  /** Present only on an applied import. */
  orderNumber: string | null
}

/** Mirrors `OrderImportReport.Issue` - one reason a row cannot be accepted. */
export interface OrderImportIssue {
  rowNumber: number
  column: string | null
  externalReference: string | null
  message: string
}

/**
 * Mirrors the backend's `OrderImportReport`. One shape for the preview and for the applied
 * result, so the table an operator approves and the confirmation they get are the same
 * rendering - `applied` is the only thing that differs.
 *
 * A file with issues comes back as HTTP 200 with `applied: false` and nothing written, so a
 * caller must branch on `applied` rather than on the request having succeeded.
 */
export interface OrderImportReport {
  dryRun: boolean
  applied: boolean
  batchId: string | null
  fileName: string | null
  format: OrderImportFormat
  externalSource: string
  rowCount: number
  orderCount: number
  createdCount: number
  skippedCount: number
  rejectedCount: number
  issueCount: number
  issuesTruncated: boolean
  orders: OrderImportPreview[]
  issues: OrderImportIssue[]
}

export function downloadOrderImportTemplate(
  companyId: string,
  format: OrderImportFormat,
  signal?: AbortSignal,
): Promise<DownloadedFile> {
  return apiDownload('/orders/import/template', { companyId, query: { format }, signal })
}

function importForm(externalSource: string, file: File): FormData {
  const form = new FormData()
  form.append('externalSource', externalSource)
  form.append('file', file)
  return form
}

/** Validates the file and reports what applying it would do. Writes nothing. */
export function previewOrderImport(
  companyId: string,
  externalSource: string,
  file: File,
  signal?: AbortSignal,
): Promise<OrderImportReport> {
  return apiUpload<OrderImportReport>('/orders/import/preview', {
    companyId, formData: importForm(externalSource, file), signal,
  })
}

/** Applies the file in one transaction, or writes nothing at all if any row has an issue. */
export function applyOrderImport(
  companyId: string,
  externalSource: string,
  file: File,
): Promise<OrderImportReport> {
  return apiUpload<OrderImportReport>('/orders/import', {
    companyId, formData: importForm(externalSource, file),
  })
}
