import { apiRequest } from './httpClient'
import type { DocumentTypeCode, InvoiceStatus } from './settlementApi'

//
// Cuentas por pagar (fase 65): el estado de pago de cada factura aprobada y exportada. TMS no
// mueve dinero: registra lo que el ERP pagó (por integración) o lo que una persona transcribe, y
// en ese caso el pago manual es una solicitud que **otra** persona confirma (cuatro ojos).
//

/** Las acciones que el servidor deja hacer sobre la factura. La pantalla las pinta, no las deduce. */
export type PayableAction = 'SCHEDULE' | 'HOLD' | 'RELEASE' | 'REQUEST_PAYMENT'

export type PaymentSource = 'INTEGRATION' | 'OPERATOR'

export type PaymentRequestStatus = 'PENDING' | 'CONFIRMED' | 'REJECTED'

/** Mirrors the aging buckets, en el orden en que el servidor los devuelve. */
export const AGING_BUCKETS = ['NOT_DUE', 'DAYS_1_30', 'DAYS_31_60', 'DAYS_61_90', 'DAYS_OVER_90', 'NO_DUE_DATE'] as const
export type AgingBucket = (typeof AGING_BUCKETS)[number]

/** Un pago aplicado. `recordedBy`/`confirmedBy` son ids de usuario; null cuando lo trajo la integración. */
export interface PaymentView {
  id: string
  paymentReference: string
  paidOn: string
  amount: number
  currency: string
  detractionConstancyNumber: string | null
  detractionConstancyDate: string | null
  detractionAmount: number | null
  retentionAmount: number | null
  source: PaymentSource
  recordedBy: string | null
  confirmedBy: string | null
  createdAt: string
}

/** Un pago manual a la espera del segundo par de ojos, o ya decidido. */
export interface PaymentRequestView {
  id: string
  paymentReference: string
  paidOn: string
  amount: number
  currency: string
  detractionConstancyNumber: string | null
  detractionConstancyDate: string | null
  detractionAmount: number | null
  retentionAmount: number | null
  status: PaymentRequestStatus
  requestedBy: string
  requestedAt: string
  decidedBy: string | null
  decidedAt: string | null
  decisionComment: string | null
}

/**
 * Mirrors `PayableView`. `openBalance` es lo que queda por pagar de `payableAmount` (neto menos
 * detracción y retención). `daysOverdue` null = no vencida o sin vencimiento.
 */
export interface PayableView {
  invoiceId: string
  invoiceNumber: string
  documentType: DocumentTypeCode
  carrierId: string
  carrierName: string | null
  currency: string
  status: InvoiceStatus
  invoiceDate: string
  dueDate: string | null
  paymentTermsDays: number | null
  scheduledPaymentDate: string | null
  onHoldReason: string | null
  netAmount: number
  detractionAmount: number | null
  detractionConstancyNumber: string | null
  detractionConstancyDate: string | null
  retentionAmount: number | null
  payableAmount: number
  paidAmount: number
  openBalance: number
  daysOverdue: number | null
  allowedActions: PayableAction[]
  payments: PaymentView[]
  requests: PaymentRequestView[]
}

/** El cuerpo de una solicitud de pago manual. La moneda es la de la factura. */
export interface PaymentRequestInput {
  paymentReference: string
  paidOn: string
  amount: number
  currency: string
  detractionConstancyNumber?: string | null
  detractionConstancyDate?: string | null
  detractionAmount?: number | null
  retentionAmount?: number | null
}

/** Una solicitud pendiente en la bandeja de quien confirma. */
export interface PendingPaymentRequestRow {
  id: string
  invoiceId: string
  invoiceNumber: string
  carrierName: string | null
  currency: string
  amount: number
  paymentReference: string
  paidOn: string
  requestedBy: string
  requestedAt: string
  openBalance: number
}

/** Una fila de vencimientos. `daysToDue` negativo = vencida hace tantos días. */
export interface DuePayableRow {
  invoiceId: string
  invoiceNumber: string
  carrierId: string
  carrierName: string | null
  currency: string
  status: InvoiceStatus
  invoiceDate: string
  dueDate: string
  daysToDue: number
  openBalance: number
  paidAmount: number
  payableAmount: number
}

export type AgingAmounts = Partial<Record<AgingBucket, number>>

export interface PayableAgingTotal {
  currency: string
  amounts: AgingAmounts
  total: number
  count: number
}

export interface PayableAgingCarrierRow extends PayableAgingTotal {
  carrierId: string
  carrierName: string | null
}

/** Saldo abierto por tramo de vencimiento. Cada moneda va aparte: TMS no convierte. */
export interface PayableAgingView {
  asOf: string
  buckets: AgingBucket[]
  totals: PayableAgingTotal[]
  carriers: PayableAgingCarrierRow[]
}

export interface PayableSummaryCurrency {
  currency: string
  overdueCount: number
  overdueAmount: number
  dueSoonCount: number
  dueSoonAmount: number
}

/** Lo que la torre de control pinta: vencido, por vencer en `dueSoonDays` y pagos por confirmar. */
export interface PayableSummaryView {
  asOf: string
  dueSoonDays: number
  pendingPaymentRequests: number
  currencies: PayableSummaryCurrency[]
}

const BASE = '/settlement/payables'

/** `settlement.invoice:read`. */
export function fetchPayable(companyId: string, invoiceId: string, signal?: AbortSignal): Promise<PayableView> {
  return apiRequest<PayableView>(`${BASE}/${invoiceId}`, { companyId, signal })
}

/** `settlement.payment:record`. */
export function schedulePayable(companyId: string, invoiceId: string, scheduledPaymentDate: string): Promise<PayableView> {
  return apiRequest<PayableView>(`${BASE}/${invoiceId}/schedule`, {
    method: 'POST', companyId, body: { scheduledPaymentDate },
  })
}

/** `settlement.payment:record`. Retener exige motivo. */
export function holdPayable(companyId: string, invoiceId: string, reason: string): Promise<PayableView> {
  return apiRequest<PayableView>(`${BASE}/${invoiceId}/hold`, { method: 'POST', companyId, body: { reason } })
}

/** `settlement.payment:record`. Liberar también exige motivo. */
export function releasePayable(companyId: string, invoiceId: string, reason: string): Promise<PayableView> {
  return apiRequest<PayableView>(`${BASE}/${invoiceId}/release`, { method: 'POST', companyId, body: { reason } })
}

/**
 * `settlement.payment:record`. Sólo propone el pago: no cuenta hasta que otra persona lo confirme.
 * 422 `payment-exceeds-balance` / `payment-currency-mismatch` traen su `detail`.
 */
export function requestPayment(companyId: string, invoiceId: string, input: PaymentRequestInput): Promise<PayableView> {
  return apiRequest<PayableView>(`${BASE}/${invoiceId}/payment-requests`, { method: 'POST', companyId, body: input })
}

/** `settlement.payment:confirm`. 409 si quien confirma es quien lo pidió. */
export function confirmPaymentRequest(companyId: string, invoiceId: string, requestId: string): Promise<PayableView> {
  return apiRequest<PayableView>(`${BASE}/${invoiceId}/payment-requests/${requestId}/confirm`, { method: 'POST', companyId })
}

/** `settlement.payment:confirm`. */
export function rejectPaymentRequest(
  companyId: string, invoiceId: string, requestId: string, comment: string,
): Promise<PayableView> {
  return apiRequest<PayableView>(`${BASE}/${invoiceId}/payment-requests/${requestId}/reject`, {
    method: 'POST', companyId, body: { comment },
  })
}

export function fetchPendingPaymentRequests(companyId: string, signal?: AbortSignal): Promise<PendingPaymentRequestRow[]> {
  return apiRequest<PendingPaymentRequestRow[]>(`${BASE}/payment-requests`, { companyId, signal })
}

/** Lo que vence en los próximos `within` días, más lo ya vencido, por fecha de vencimiento. */
export function fetchDuePayables(
  companyId: string, params: { within: number; carrierId?: string }, signal?: AbortSignal,
): Promise<DuePayableRow[]> {
  return apiRequest<DuePayableRow[]>(`${BASE}/due`, {
    companyId,
    signal,
    query: { within: params.within, ...(params.carrierId ? { carrierId: params.carrierId } : {}) },
  })
}

export function fetchPayableAging(companyId: string, asOf?: string, signal?: AbortSignal): Promise<PayableAgingView> {
  return apiRequest<PayableAgingView>(`${BASE}/aging`, { companyId, signal, query: asOf ? { asOf } : {} })
}

export function fetchPayableSummary(companyId: string, signal?: AbortSignal): Promise<PayableSummaryView> {
  return apiRequest<PayableSummaryView>(`${BASE}/summary`, { companyId, signal })
}
