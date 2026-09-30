import { ApiError, apiRequest } from './httpClient'
import type { PageResponse } from './pageResponse'
import type { OrderDetailView, OrderPriority, OrderStatus } from './ordersApi'

/**
 * Programación y liberación (ADR-014). Todo lo que devuelve este módulo se **deriva al leer** en el
 * backend: la elegibilidad, la fecha límite de liberación y la ruta resuelta no se guardan en el
 * pedido. Liberar sigue siendo la transición `NOT_READY -> READY_FOR_PLANNING`.
 */

/** Mirrors `orders.domain.Eligibility`. No es un estado del pedido. */
export type Eligibility = 'ELIGIBLE' | 'WARNING' | 'BLOCKED'

export const ELIGIBILITIES: Eligibility[] = ['ELIGIBLE', 'WARNING', 'BLOCKED']

/** Mirrors `orders.domain.SchedulingReasonCode`. */
export type SchedulingReasonCode =
  | 'MISSING_ORIGIN'
  | 'MISSING_DESTINATION'
  | 'MISSING_CAPACITY'
  | 'ROUTE_NOT_FOUND'
  | 'ROUTE_AMBIGUOUS'
  | 'ACTIVE_BLOCKING_HOLD'
  | 'CUTOFF_MISSED'
  | 'FREQUENCY_OVERRIDE'
  | 'ROUTE_NOT_CONFIGURED'

export const SCHEDULING_REASON_CODES: SchedulingReasonCode[] = [
  'MISSING_ORIGIN', 'MISSING_DESTINATION', 'MISSING_CAPACITY', 'ROUTE_NOT_FOUND', 'ROUTE_AMBIGUOUS',
  'ACTIVE_BLOCKING_HOLD', 'CUTOFF_MISSED', 'FREQUENCY_OVERRIDE', 'ROUTE_NOT_CONFIGURED',
]

/** Mirrors `RouteResolution.Status`. */
export type RouteResolutionStatus = 'RESOLVED' | 'NOT_FOUND' | 'AMBIGUOUS' | 'NOT_CONFIGURED'

/** Mirrors `orders.domain.SchedulingReason`. `detail` es prosa del backend, para el panel de detalle. */
export interface SchedulingReason {
  code: SchedulingReasonCode
  severity: Exclude<Eligibility, 'ELIGIBLE'>
  requiresOverride: boolean
  detail: string
}

/** Mirrors `SchedulingRowView`: el pedido y su elegibilidad derivada. */
export interface SchedulingRow {
  orderId: string
  orderNumber: string
  externalReference: string | null
  customerName: string | null
  customerReference: string | null
  originId: string
  originCode: string | null
  originName: string | null
  destinationId: string
  destinationCode: string | null
  destinationName: string | null
  scheduledDispatchDate: string
  priority: OrderPriority
  status: OrderStatus
  totalWeightKg: number
  totalVolumeM3: number
  totalPallets: number
  version: number
  eligibility: Eligibility
  requiresOverride: boolean
  reasons: SchedulingReason[]
  releaseDeadline: string | null
  releaseDeadlineEndOfDay: boolean
  routeResolution: RouteResolutionStatus
  routeCode: string | null
  routeName: string | null
  routeCandidates: string[]
  locationFrequencyCode: string | null
  routeFrequencyCode: string | null
  activeHolds: number
  activeBlockingHolds: number
}

export interface SchedulingCounts {
  total: number
  eligible: number
  warning: number
  blocked: number
  withHolds: number
  released: number
}

export interface SchedulingGroup {
  originId: string
  originCode: string | null
  originName: string | null
  /** Null: pedidos que no resuelven a una única ruta. */
  routeCode: string | null
  routeName: string | null
  scheduledDispatchDate: string
  counts: SchedulingCounts
}

export interface SchedulingSummary {
  totals: SchedulingCounts
  groups: SchedulingGroup[]
}

/** El seudocódigo de ruta para "sin ruta resuelta" (`SchedulingFilter.NO_ROUTE`). */
export const NO_ROUTE = 'NONE'

export interface SchedulingFilterParams {
  originId?: string
  routeCode?: string
  serviceDateFrom?: string
  serviceDateTo?: string
  customer?: string
  priority?: OrderPriority
  eligibility?: Eligibility
  hasHold?: boolean
  status?: OrderStatus
  frequency?: string
  orderNumber?: string
}

export interface SchedulingListParams extends SchedulingFilterParams {
  companyId: string
  page?: number
  size?: number
  sort?: string
  signal?: AbortSignal
}

export function fetchSchedulingBoard(params: SchedulingListParams): Promise<PageResponse<SchedulingRow>> {
  const { companyId, signal, ...query } = params
  return apiRequest<PageResponse<SchedulingRow>>('/orders/scheduling', { companyId, signal, query })
}

export function fetchSchedulingSummary(
  companyId: string, filter: SchedulingFilterParams, signal?: AbortSignal,
): Promise<SchedulingSummary> {
  return apiRequest<SchedulingSummary>('/orders/scheduling/summary', { companyId, signal, query: { ...filter } })
}

export function fetchOrderScheduling(companyId: string, orderId: string, signal?: AbortSignal): Promise<SchedulingRow> {
  return apiRequest<SchedulingRow>(`/orders/${orderId}/scheduling`, { companyId, signal })
}

/**
 * Libera un pedido (`POST /orders/{id}/mark-ready`). Sin motivo, el cuerpo no se envía: así se
 * comportan todos los llamadores de antes de ADR-014. Un 409 trae los motivos - ver
 * {@link releaseRefusalOf}.
 */
export function releaseOrder(companyId: string, orderId: string, overrideReason?: string): Promise<OrderDetailView> {
  return apiRequest<OrderDetailView>(`/orders/${orderId}/mark-ready`, {
    method: 'POST', companyId, body: overrideReason ? { overrideReason } : undefined,
  })
}

/** Mirrors `BulkReleaseResult.Item`. */
export interface BulkReleaseItem {
  index: number
  orderId: string
  orderNumber: string | null
  released: boolean
  status: OrderStatus | null
  eligibility: Eligibility | null
  overrideRequired: boolean
  reasons: SchedulingReason[]
  message: string | null
}

/** Mirrors `BulkReleaseResult`. HTTP 200 si todos se liberaron, 207 si alguno no. */
export interface BulkReleaseResult {
  submitted: number
  released: number
  refused: number
  results: BulkReleaseItem[]
}

export function bulkReleaseOrders(
  companyId: string, orderIds: string[], overrideReason?: string,
): Promise<BulkReleaseResult> {
  return apiRequest<BulkReleaseResult>('/orders/release', {
    method: 'POST', companyId, body: { orderIds, overrideReason: overrideReason || undefined },
  })
}

// --- retenciones (V54) ---------------------------------------------------------------------

/** Mirrors `orders.domain.HoldType`. */
export type HoldType =
  | 'COMMERCIAL' | 'INVENTORY' | 'ADDRESS' | 'CUSTOMER' | 'TRANSPORT' | 'INTEGRATION' | 'MANUAL' | 'OTHER'

export const HOLD_TYPES: HoldType[] = [
  'COMMERCIAL', 'INVENTORY', 'ADDRESS', 'CUSTOMER', 'TRANSPORT', 'INTEGRATION', 'MANUAL', 'OTHER',
]

/** Mirrors `OrderHoldView`. */
export interface OrderHold {
  id: string
  orderId: string
  holdType: HoldType
  reasonCode: string | null
  reason: string
  source: 'OPERATOR' | 'INTEGRATION'
  blocking: boolean
  active: boolean
  createdBy: string | null
  createdByClient: string | null
  createdAt: string
  releasedAt: string | null
  releasedBy: string | null
  releasedByClient: string | null
  releaseReason: string | null
  version: number
}

export interface PlaceHoldRequest {
  holdType: HoldType
  reasonCode?: string | null
  reason: string
  blocking?: boolean
}

export function fetchOrderHolds(companyId: string, orderId: string, signal?: AbortSignal): Promise<OrderHold[]> {
  return apiRequest<OrderHold[]>(`/orders/${orderId}/holds`, { companyId, signal })
}

export function placeOrderHold(companyId: string, orderId: string, request: PlaceHoldRequest): Promise<OrderHold> {
  return apiRequest<OrderHold>(`/orders/${orderId}/holds`, { method: 'POST', companyId, body: request })
}

export function releaseOrderHold(
  companyId: string, orderId: string, holdId: string, releaseReason: string, version?: number,
): Promise<OrderHold> {
  return apiRequest<OrderHold>(`/orders/${orderId}/holds/${holdId}/release`, {
    method: 'POST', companyId, body: { releaseReason, version },
  })
}

// --- reglas de presentación (puras, probadas en scheduling.test.ts) -------------------------

/** Lo que el 409 de la liberación trae además de la frase (`ReleaseRefusal` en el backend). */
export interface ReleaseRefusal {
  eligibility: Eligibility
  overrideRequired: boolean
  reasons: SchedulingReason[]
}

/**
 * Los motivos de un rechazo de liberación, o `null` si el error no es uno. Se ramifica por los
 * campos máquina del problem detail, nunca por `detail`.
 */
export function releaseRefusalOf(error: unknown): ReleaseRefusal | null {
  if (!(error instanceof ApiError) || error.status !== 409 || !error.problem) return null
  const problem = error.problem as Record<string, unknown>
  if (!Array.isArray(problem.reasons) || typeof problem.eligibility !== 'string') return null
  return {
    eligibility: problem.eligibility as Eligibility,
    overrideRequired: problem.overrideRequired === true,
    reasons: problem.reasons as SchedulingReason[],
  }
}

/** Si liberar esta fila exige un motivo: algún aviso que lo pida (corte vencido, fuera de frecuencia). */
export function needsOverride(row: Pick<SchedulingRow, 'reasons'>): boolean {
  return row.reasons.some((reason) => reason.requiresOverride)
}

/** Si la fila se puede intentar liberar desde la pantalla: sin liberar aún y no bloqueada. */
export function isReleasable(row: Pick<SchedulingRow, 'status' | 'eligibility'>): boolean {
  return row.status === 'NOT_READY' && row.eligibility !== 'BLOCKED'
}

/**
 * Qué hacer con una selección antes de liberarla en bloque: cuáles se envían, cuáles se descartan
 * de entrada por estar bloqueadas o ya liberadas, y si hace falta pedir un motivo porque alguna de
 * las enviadas lo necesita. El backend vuelve a juzgar cada una; esto solo evita un viaje inútil.
 */
export function planBulkRelease(rows: SchedulingRow[]): {
  toSend: SchedulingRow[]
  skipped: SchedulingRow[]
  reasonNeeded: boolean
} {
  const toSend = rows.filter(isReleasable)
  const skipped = rows.filter((row) => !isReleasable(row))
  return { toSend, skipped, reasonNeeded: toSend.some(needsOverride) }
}

/** El motivo más grave de una fila, para la columna "Motivo": primero lo que bloquea. */
export function primaryReason(row: Pick<SchedulingRow, 'reasons'>): SchedulingReason | null {
  return row.reasons.find((reason) => reason.severity === 'BLOCKED')
    ?? row.reasons.find((reason) => reason.requiresOverride)
    ?? row.reasons[0]
    ?? null
}

/** Cuántos filtros de la pantalla están puestos, para el contador del desplegable. */
export function activeFilterCount(filter: SchedulingFilterParams): number {
  return Object.values(filter).filter((value) => value !== undefined && value !== '').length
}
