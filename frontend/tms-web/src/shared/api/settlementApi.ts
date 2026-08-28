import { apiRequest } from './httpClient'
import type { PageResponse } from './pageResponse'

/** Mirrors `InvoiceStatus` (migración V46), en orden de vida. */
export const INVOICE_STATUSES = [
  'RECEIVED', 'MATCHING', 'MATCHED', 'DISCREPANCY', 'UNDER_REVIEW',
  'APPROVED', 'REJECTED', 'EXPORTED',
] as const
export type InvoiceStatus = (typeof INVOICE_STATUSES)[number]

/**
 * Mirrors `MatchStatus`.
 *
 * `UNMATCHABLE` no es un problema con la factura: es que TMS **no tiene opinión**, porque ningún
 * envío de la factura tiene coste estimado. Decirle a un auditor que una factura correcta está en
 * disputa desperdicia justo la atención que este módulo existe para dirigir.
 */
export type MatchStatus = 'MATCHED' | 'DISCREPANCY' | 'UNMATCHABLE'

export type DiscrepancyType =
  | 'TOTAL_AMOUNT' | 'LINE_AMOUNT' | 'UNMATCHED_TRIP'
  | 'DUPLICATE_INVOICE' | 'CURRENCY_MISMATCH' | 'MISSING_EXPECTED_COST'

export type DiscrepancyStatus = 'OPEN' | 'ACCEPTED' | 'REJECTED'

/**
 * Mirrors `CarrierInvoiceSummaryView` - una fila de la cola de auditoría.
 *
 * `expectedAmount` **null significa que no había nada con qué comparar**, nunca cero. La pantalla
 * pinta un guion: pintar 0,00 reportaría cada envío sin tarificar como un sobrecoste total.
 */
export interface CarrierInvoiceSummaryView {
  id: string
  carrierId: string
  carrierName: string | null
  invoiceNumber: string
  invoiceDate: string
  currency: string
  totalAmount: number
  status: InvoiceStatus
  matchStatus: MatchStatus | null
  expectedAmount: number | null
  differenceAmount: number | null
  openDiscrepancyCount: number
}

/** Mirrors `CarrierInvoiceView.LineView`. */
export interface InvoiceLineView {
  id: string
  lineNumber: number
  tripId: string | null
  shipmentNumber: string | null
  description: string
  quantity: number | null
  unitAmount: number | null
  lineAmount: number
  /** Null cuando el envío nunca se tarificó, o no es de este transportista. Nunca cero. */
  expectedAmount: number | null
  actualAmount: number | null
  differenceAmount: number | null
}

/** Mirrors `CarrierInvoiceView.MatchView`: el veredicto y con qué se comparó. */
export interface MatchView {
  status: MatchStatus
  expectedAmount: number | null
  actualAmount: number | null
  invoicedAmount: number
  differenceAmount: number | null
  /** La tolerancia congelada: ampliarla el mes que viene no reescribe por qué esta factura cuadró. */
  toleranceAbsolute: number | null
  tolerancePercentage: number | null
  matchedTripCount: number
  unmatchedLineCount: number
  computedAt: string
}

export interface FreightDiscrepancyView {
  id: string
  type: DiscrepancyType
  expectedAmount: number | null
  invoicedAmount: number | null
  differenceAmount: number | null
  /** La frase que explica la diferencia, compuesta en el servidor a partir de las cifras. */
  detail: string
  status: DiscrepancyStatus
  resolutionNotes: string | null
  resolvedAt: string | null
}

export interface SettlementApprovalView {
  id: string
  decision: 'APPROVED' | 'REJECTED'
  decidedBy: string
  decidedAt: string
  comment: string | null
}

export interface PayableExportView {
  id: string
  exportReference: string
  format: 'JSON' | 'CSV'
  payload: string
  exportedAt: string
  /** `true` cuando ya existía: dos clics no crean dos obligaciones. */
  alreadyExported: boolean
}

export interface CarrierInvoiceView {
  id: string
  carrierId: string
  carrierName: string | null
  invoiceNumber: string
  invoiceDate: string
  currency: string
  totalAmount: number
  status: InvoiceStatus
  /** Los estados a los que el servidor deja moverse. La pantalla los pinta, no los deduce. */
  allowedTransitions: InvoiceStatus[]
  externalReference: string | null
  receivedAt: string
  notes: string | null
  version: number
  lines: InvoiceLineView[]
  match: MatchView | null
  discrepancies: FreightDiscrepancyView[]
  approvals: SettlementApprovalView[]
  export: PayableExportView | null
}

export interface CarrierInvoiceRequest {
  carrierId: string
  invoiceNumber: string
  invoiceDate: string
  currency: string
  totalAmount: number
  externalReference?: string | null
  notes?: string | null
  lines: {
    tripId?: string | null
    description: string
    quantity?: number | null
    unitAmount?: number | null
    lineAmount: number
  }[]
}

export function fetchInvoices(
  companyId: string,
  params: { page?: number; size?: number; carrierId?: string; status?: InvoiceStatus[] } = {},
  signal?: AbortSignal,
): Promise<PageResponse<CarrierInvoiceSummaryView>> {
  return apiRequest<PageResponse<CarrierInvoiceSummaryView>>('/settlement/invoices', {
    companyId,
    signal,
    query: {
      page: params.page ?? 0,
      size: params.size ?? 25,
      ...(params.carrierId ? { carrierId: params.carrierId } : {}),
      ...(params.status?.length ? { status: params.status.join(',') } : {}),
    },
  })
}

export function fetchInvoice(companyId: string, id: string, signal?: AbortSignal): Promise<CarrierInvoiceView> {
  return apiRequest<CarrierInvoiceView>(`/settlement/invoices/${id}`, { companyId, signal })
}

export function receiveInvoice(companyId: string, request: CarrierInvoiceRequest): Promise<CarrierInvoiceView> {
  return apiRequest<CarrierInvoiceView>('/settlement/invoices', { method: 'POST', companyId, body: request })
}

export function matchInvoice(companyId: string, id: string): Promise<CarrierInvoiceView> {
  return apiRequest<CarrierInvoiceView>(`/settlement/invoices/${id}/match`, { method: 'POST', companyId })
}

export function beginInvoiceReview(companyId: string, id: string): Promise<CarrierInvoiceView> {
  return apiRequest<CarrierInvoiceView>(`/settlement/invoices/${id}/review`, { method: 'POST', companyId })
}

export function resolveDiscrepancy(
  companyId: string, id: string, discrepancyId: string,
  decision: Exclude<DiscrepancyStatus, 'OPEN'>, notes?: string | null,
): Promise<FreightDiscrepancyView> {
  return apiRequest<FreightDiscrepancyView>(
    `/settlement/invoices/${id}/discrepancies/${discrepancyId}/resolve`,
    { method: 'POST', companyId, body: { decision, notes } },
  )
}

export function approveInvoice(companyId: string, id: string, comment?: string | null): Promise<CarrierInvoiceView> {
  return apiRequest<CarrierInvoiceView>(`/settlement/invoices/${id}/approve`, {
    method: 'POST', companyId, body: { comment },
  })
}

export function rejectInvoice(companyId: string, id: string, comment: string): Promise<CarrierInvoiceView> {
  return apiRequest<CarrierInvoiceView>(`/settlement/invoices/${id}/reject`, {
    method: 'POST', companyId, body: { comment },
  })
}

/** Repetirlo devuelve la misma exportación: dos clics no crean dos obligaciones. */
export function exportInvoice(companyId: string, id: string): Promise<PayableExportView> {
  return apiRequest<PayableExportView>(`/settlement/invoices/${id}/export`, { method: 'POST', companyId })
}
