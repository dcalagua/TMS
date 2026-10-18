import { apiDownload, apiRequest, type DownloadedFile } from './httpClient'
import type { PageResponse } from './pageResponse'
import type { WithholdingKind } from './settlementApi'

//
// Provisiones de flete (migración V65, ADR-023 §1): la obligación con el transportista entre el
// servicio y la factura. Nace al despachar (o al entregar, según la empresa), se ajusta al costo
// real cerrado, se consume al aprobar la factura y la revierte una persona. TMS no mueve dinero:
// el asiento es un archivo para el ERP.
//

/** Mirrors `AccrualStatus`. */
export const ACCRUAL_STATUSES = ['OPEN', 'PARTIALLY_SETTLED', 'SETTLED', 'REVERSED'] as const
export type AccrualStatus = (typeof ACCRUAL_STATUSES)[number]

export type AccrualBasis = 'ESTIMATED' | 'ACTUAL'
export type AccrualEntryType = 'ACCRUE' | 'ADJUST' | 'CONSUME' | 'REVERSE'
export type UnaccruedReason = 'NO_CARRIER' | 'NO_TRIP_COST' | 'NOT_CALCULABLE' | 'PENDING_SWEEP'

/**
 * Mirrors `GlConcept` (ADR-027: sin conceptos de un país). `WITHHOLDING_PAYABLE` lleva además la
 * clase de retención: una cuenta para la detracción y otra para la retención, o una para todas.
 */
export const GL_CONCEPTS = [
  'FREIGHT_EXPENSE', 'FREIGHT_ACCRUAL', 'CARRIER_PAYABLE', 'VAT_CREDIT', 'WITHHOLDING_PAYABLE',
] as const
export type GlConcept = (typeof GL_CONCEPTS)[number]
export type GlScopeType = 'COMPANY' | 'ORIGIN' | 'ROUTE'

/** Mirrors `AccrualSummaryView`. `balance` es negativo cuando se facturó por encima de lo provisionado. */
export interface AccrualSummaryView {
  id: string
  tripId: string
  shipmentNumber: string | null
  carrierId: string | null
  carrierName: string | null
  currency: string
  status: AccrualStatus
  basis: AccrualBasis
  serviceDate: string
  accruedAmount: number
  consumedAmount: number
  reversedAmount: number
  balance: number
  createdAt: string
}

/** Mirrors `AccrualEntryView`: `amount` con signo, lo que el movimiento hizo al saldo. */
export interface AccrualEntryView {
  id: string
  entryType: AccrualEntryType
  amount: number
  period: string
  sourceType: string
  sourceKey: string
  carrierInvoiceId: string | null
  reason: string | null
  createdBy: string | null
  createdAt: string
}

export interface AccrualView {
  summary: AccrualSummaryView
  entries: AccrualEntryView[]
}

export interface AccrualAgingRow {
  carrierId: string | null
  carrierName: string | null
  currency: string
  accruals: number
  days0To30: number
  days31To60: number
  days61To90: number
  over90: number
  total: number
}

export interface AccrualAgingView {
  asOf: string
  rows: AccrualAgingRow[]
}

export interface UnaccruedTripRow {
  tripId: string
  shipmentNumber: string | null
  carrierId: string | null
  carrierName: string | null
  executedAt: string
  reason: UnaccruedReason
}

export interface UnaccruedTripsView {
  from: string
  to: string
  trigger: 'DISPATCHED' | 'DELIVERED'
  rows: UnaccruedTripRow[]
  truncated: boolean
}

export interface AccrualJournalRow {
  entryId: string
  entryDate: string
  entryType: AccrualEntryType
  sourceType: string
  sourceKey: string
  tripId: string
  shipmentNumber: string | null
  carrierCode: string | null
  carrierName: string | null
  invoiceNumber: string | null
  currency: string
  amount: number
  debitConcept: GlConcept
  debitAccount: string | null
  debitCostCenter: string | null
  creditConcept: GlConcept
  creditAccount: string | null
  creditCostCenter: string | null
  reason: string | null
}

/** Mirrors `AccrualJournalView`. `warnings` nombra cada concepto sin cuenta: la cuenta queda vacía, nunca inventada. */
export interface AccrualJournalView {
  period: string
  rows: AccrualJournalRow[]
  warnings: string[]
}

export interface AccrualSweepResult {
  created: number
  adjusted: number
  consumed: number
  unaccrued: number
}

export interface GlAccountMappingView {
  id: string
  concept: GlConcept
  /** Sólo en `WITHHOLDING_PAYABLE`; null = toda retención sin cuenta propia. */
  withholdingKind?: WithholdingKind | null
  scopeType: GlScopeType
  scopeId: string | null
  scopeName: string | null
  accountCode: string
  costCenterCode: string | null
  active: boolean
  updatedAt: string
}

export interface GlAccountMappingRequest {
  concept: GlConcept
  withholdingKind?: WithholdingKind | null
  scopeType?: GlScopeType
  scopeId?: string | null
  accountCode: string
  costCenterCode?: string | null
}

export interface AccrualListParams {
  page?: number
  size?: number
  /** Mes del servicio, `YYYY-MM`. */
  period?: string
  carrierId?: string
  currency?: string
  status?: AccrualStatus
}

export function fetchAccruals(
  companyId: string, params: AccrualListParams = {}, signal?: AbortSignal,
): Promise<PageResponse<AccrualSummaryView>> {
  return apiRequest<PageResponse<AccrualSummaryView>>('/settlement/accruals', {
    companyId,
    signal,
    query: {
      page: params.page ?? 0,
      size: params.size ?? 25,
      ...(params.period ? { period: params.period } : {}),
      ...(params.carrierId ? { carrierId: params.carrierId } : {}),
      ...(params.currency ? { currency: params.currency } : {}),
      ...(params.status ? { status: params.status } : {}),
    },
  })
}

export function fetchAccrual(companyId: string, id: string, signal?: AbortSignal): Promise<AccrualView> {
  return apiRequest<AccrualView>(`/settlement/accruals/${id}`, { companyId, signal })
}

export function reverseAccrual(companyId: string, id: string, reason: string): Promise<AccrualView> {
  return apiRequest<AccrualView>(`/settlement/accruals/${id}/reverse`, {
    method: 'POST', companyId, body: { reason },
  })
}

export function fetchAccrualAging(companyId: string, asOf?: string, signal?: AbortSignal): Promise<AccrualAgingView> {
  return apiRequest<AccrualAgingView>('/settlement/accruals/aging', {
    companyId, signal, query: asOf ? { asOf } : {},
  })
}

export function fetchUnaccruedTrips(
  companyId: string, from: string, to: string, signal?: AbortSignal,
): Promise<UnaccruedTripsView> {
  return apiRequest<UnaccruedTripsView>('/settlement/accruals/unaccrued', { companyId, signal, query: { from, to } })
}

export function sweepAccruals(companyId: string, from: string, to: string): Promise<AccrualSweepResult> {
  return apiRequest<AccrualSweepResult>('/settlement/accruals/sweep', { method: 'POST', companyId, query: { from, to } })
}

export function fetchAccrualJournal(companyId: string, period: string, signal?: AbortSignal): Promise<AccrualJournalView> {
  return apiRequest<AccrualJournalView>('/settlement/accruals/journal', {
    companyId, signal, query: { period, format: 'JSON' },
  })
}

export function downloadAccrualJournalCsv(companyId: string, period: string): Promise<DownloadedFile> {
  return apiDownload('/settlement/accruals/journal', { companyId, query: { period, format: 'CSV' } })
}

export function fetchGlMappings(
  companyId: string, includeInactive = false, signal?: AbortSignal,
): Promise<GlAccountMappingView[]> {
  return apiRequest<GlAccountMappingView[]>('/settlement/gl-mappings', { companyId, signal, query: { includeInactive } })
}

export function createGlMapping(companyId: string, request: GlAccountMappingRequest): Promise<GlAccountMappingView> {
  return apiRequest<GlAccountMappingView>('/settlement/gl-mappings', { method: 'POST', companyId, body: request })
}

export function updateGlMapping(
  companyId: string, id: string, request: GlAccountMappingRequest,
): Promise<GlAccountMappingView> {
  return apiRequest<GlAccountMappingView>(`/settlement/gl-mappings/${id}`, { method: 'PUT', companyId, body: request })
}

export function deactivateGlMapping(companyId: string, id: string): Promise<GlAccountMappingView> {
  return apiRequest<GlAccountMappingView>(`/settlement/gl-mappings/${id}/deactivate`, { method: 'POST', companyId })
}
