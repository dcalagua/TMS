import { apiRequest } from './httpClient'
import type { PageResponse } from './pageResponse'

/**
 * Mirrors `InvoiceStatus` (migración V46), en orden de vida.
 *
 * Fase 65: tras `EXPORTED` vienen los estados de pago - lo que el ERP pagó, registrado en TMS.
 * `EXPORTED` ya no es terminal. `ON_HOLD` es una retención con motivo, no un rechazo.
 */
export const INVOICE_STATUSES = [
  'RECEIVED', 'MATCHING', 'MATCHED', 'DISCREPANCY', 'UNDER_REVIEW',
  'APPROVED', 'REJECTED', 'EXPORTED', 'SCHEDULED', 'PARTIALLY_PAID', 'PAID', 'ON_HOLD',
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
  /** V66: la deducción del cargador; el aprobador la acepta en su propia fila. */
  | 'PENALTY'

/**
 * La clase interna del comprobante (ADR-027). El código que le pone cada país (01/07/08 en Perú,
 * 01/04/05 en Ecuador, ninguno en el régimen genérico) llega en `TaxRegimeView.documentKinds`.
 */
export const DOCUMENT_KINDS = ['INVOICE', 'CREDIT_NOTE', 'DEBIT_NOTE'] as const
export type DocumentKind = (typeof DOCUMENT_KINDS)[number]

/** El régimen fiscal con que la empresa calcula sus impuestos (ADR-027). */
export type TaxRegimeCode = 'PE' | 'EC' | 'GENERIC'

export const WITHHOLDING_KINDS = [
  'PE_DETRACTION', 'PE_RETENTION', 'EC_INCOME_TAX_WITHHOLDING', 'EC_VAT_WITHHOLDING',
] as const
export type WithholdingKind = (typeof WITHHOLDING_KINDS)[number]

/** A qué se aplica la tasa de una retención. */
export type WithholdingBase = 'TOTAL' | 'SUBTOTAL' | 'VAT'

export type TaxIdRule = 'PE_RUC' | 'EC_RUC' | 'FREE_TEXT'

export const PENALTY_REFERENCE_TYPES = ['TRIP_EXCEPTION', 'DELIVERY_FAILED', 'LATE_ARRIVAL', 'OTHER', 'FREIGHT_CLAIM'] as const
export type PenaltyReferenceType = (typeof PENALTY_REFERENCE_TYPES)[number]

export type DiscrepancyStatus = 'OPEN' | 'ACCEPTED' | 'REJECTED'

/**
 * Mirrors `ChargeCode`: de qué componente del flete habla una línea.
 *
 * `FREIGHT` es el flete todo incluido y el valor por defecto - las líneas anteriores a esta fase lo
 * llevan. Una línea con un código de componente se compara con **ese** componente de la estimación,
 * no con el viaje entero. `PENALTY` queda fuera de la comparación: TMS no estima penalidades.
 */
export const CHARGE_CODES = [
  'FREIGHT', 'BASE', 'DISTANCE', 'WEIGHT', 'VOLUME', 'PALLETS', 'LOADING_METERS', 'STOP_OFF',
  'FUEL_SURCHARGE', 'WAITING_TIME', 'TOLL', 'OTHER_ACCESSORIAL', 'PENALTY',
] as const
export type ChargeCode = (typeof CHARGE_CODES)[number]

/**
 * Contra qué cifra se comparó: el costo real cerrado del viaje o su estimación (el costo aún no se
 * cerró). `MIXED` sólo existe en la cabecera: unos viajes contra real y otros contra estimado.
 */
export type LineExpectedBasis = 'ACTUAL' | 'ESTIMATED'
export type ExpectedBasis = LineExpectedBasis | 'MIXED'

export type ExportFormat = 'JSON' | 'CSV'

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
  /** ADR-027. Ausente en un servidor anterior: se lee como factura. */
  documentKind?: DocumentKind
  /** El código del país (01, 04…); null en el régimen genérico. */
  fiscalDocumentCode?: string | null
  /** Fase 65. Ausentes en un servidor anterior; null = sin vencimiento calculable. */
  dueDate?: string | null
  paidAmount?: number
  onHoldReason?: string | null
}

/** Mirrors `CarrierInvoiceView.LineView`. */
export interface InvoiceLineView {
  id: string
  lineNumber: number
  tripId: string | null
  shipmentNumber: string | null
  /** Nunca null en una respuesta: una línea sin código es `FREIGHT`. */
  chargeCode: ChargeCode
  description: string
  quantity: number | null
  unitAmount: number | null
  lineAmount: number
  /**
   * Con qué se comparó **esta** línea. Para un código de componente, ese componente de la
   * estimación (BASE incluye los ajustes de mínimo y máximo); para `FREIGHT`, la cifra del viaje
   * menos lo esperado de las otras líneas de componente del mismo viaje.
   *
   * Null cuando el envío nunca se tarificó, no es de este transportista, o la línea es `PENALTY`
   * (fuera de la comparación). Nunca cero.
   */
  expectedAmount: number | null
  /** Contra qué cifra del viaje: costo real cerrado o estimado. Null cuando no hubo comparación. */
  expectedBasis: LineExpectedBasis | null
  actualAmount: number | null
  differenceAmount: number | null
  penaltyReason?: string | null
  penaltyReferenceType?: PenaltyReferenceType | null
  penaltyReferenceId?: string | null
}

/** Una nota que corrige la factura, tal como la factura la lista. */
export interface NoteView {
  id: string
  documentKind: DocumentKind
  fiscalDocumentCode: string | null
  invoiceNumber: string
  invoiceDate: string
  totalAmount: number
  status: InvoiceStatus
}

/**
 * Una retención del documento (ADR-027): la detracción o la retención del IGV en Perú, la retención
 * en la fuente o la de IVA en Ecuador. TMS la calcula y la informa; no la paga.
 */
export interface WithholdingView {
  kind: WithholdingKind
  /** El nombre oficial del país, del servidor. */
  label?: string | null
  code: string | null
  base: WithholdingBase | null
  baseAmount: number | null
  rate: number | null
  /** Lo que calculó TMS. Null en un servidor que no lo informa (portal). */
  computedAmount?: number | null
  amount: number
  /** Por qué alguien cambió lo calculado. */
  overrideReason?: string | null
  /** Constancia de depósito o comprobante de retención. */
  certificateNumber: string | null
  certificateDate: string | null
}

/** Una retención tal como la informó un pago: lo depositado o retenido y su comprobante. */
export interface ReportedWithholding {
  kind: WithholdingKind
  amount: number | null
  certificateNumber: string | null
  certificateDate: string | null
}

/**
 * La parte fiscal del documento (ADR-027). `electronicValidation` es hoy siempre
 * `EXTERNAL_DEPENDENCY`: nadie consultó a la autoridad tributaria, y la pantalla no lo presenta
 * como «válido».
 */
export interface FiscalView {
  /** El país del régimen con que se recibió: congelado, aunque la empresa cambie después. */
  fiscalCountry: string | null
  regime: TaxRegimeCode | null
  vatLabel: string | null
  documentKind: DocumentKind
  fiscalDocumentCode: string | null
  effectiveSign: number
  documentNumber: string | null
  issuerTaxId: string | null
  subtotalAmount: number | null
  taxRate: number | null
  taxAmount: number | null
  referencedInvoiceId: string | null
  referencedInvoiceNumber: string | null
  netAmount: number | null
  notes: NoteView[]
  withholdings: WithholdingView[]
  withholdingTotalAmount: number | null
  electronicValidation: 'EXTERNAL_DEPENDENCY' | 'ACCEPTED' | 'NOT_ACCEPTED' | null
}

export interface DocumentKindOption {
  kind: DocumentKind
  /** El código del país; null en el régimen genérico. */
  code: string | null
  label: string
}

/** Una retención del régimen, con lo que la empresa haya cambiado ya resuelto. */
export interface WithholdingRuleView {
  kind: WithholdingKind
  label: string
  enabled: boolean
  code: string | null
  rate: number | null
  base: WithholdingBase
  /** Sólo aplica si el TOTAL supera este importe. Null = sin umbral. */
  thresholdAmount: number | null
  strictlyAboveThreshold: boolean
  /** Decimales del redondeo HALF_UP (0 = unidades enteras, como la detracción). */
  scale: number
  overridable: boolean
  /** No aplica si ya aplicó alguna de éstas (la retención del IGV no va con detracción). */
  requiresNoOther: WithholdingKind[]
}

export interface StoredWithholdingSetting {
  kind: WithholdingKind
  enabled: boolean | null
  code: string | null
  rate: number | null
  thresholdAmount: number | null
}

/** Lo que la empresa guardó; null = hereda el valor por defecto. */
export interface StoredFiscalSettings {
  fiscalCountry: string | null
  vatRate: number | null
  freightVatRate: number | null
  withholdings: StoredWithholdingSetting[]
}

/**
 * El régimen fiscal de la empresa, con los valores por defecto ya resueltos por el servidor
 * (ADR-027). La pantalla no trae ninguna tasa, código ni formato propio: todo sale de aquí.
 */
export interface TaxRegimeView {
  regime: TaxRegimeCode
  country: string
  /** Null en el régimen genérico: ninguna moneda se impone. */
  fiscalCurrency: string | null
  vatLabel: string
  vatRate: number | null
  /** La tasa con que se adelanta un flete (Ecuador 0 %, Perú 18 %). */
  freightVatRate: number | null
  /** Una tasa declarada debe ser una de éstas; vacío = cualquiera entre 0 y 100. */
  allowedVatRates: number[]
  breakdownRequired: boolean
  breakdownTolerance: number
  documentKinds: DocumentKindOption[]
  documentNumber: { pattern: string | null; placeholder: string | null; hint: string | null }
  taxId: { label: string; rule: TaxIdRule; length: number | null; pattern: string | null; hint: string | null }
  electronicValidation: string | null
  withholdings: WithholdingRuleView[]
  stored: StoredFiscalSettings
  defaultCountry: string | null
}

/** Compatibilidad de nombre: el régimen es la configuración fiscal. */
export type FiscalSettingsView = TaxRegimeView

export function fetchFiscalSettings(companyId: string, signal?: AbortSignal): Promise<TaxRegimeView> {
  return apiRequest<TaxRegimeView>('/settlement/fiscal-settings', { companyId, signal })
}

/** Reemplaza lo guardado por la empresa (`settlement.tax:manage`); los null heredan. */
export interface FiscalSettingsUpdateRequest {
  fiscalCountry: string | null
  vatRate: number | null
  freightVatRate: number | null
  withholdings: StoredWithholdingSetting[]
}

export function updateFiscalSettings(companyId: string, request: FiscalSettingsUpdateRequest): Promise<TaxRegimeView> {
  return apiRequest<TaxRegimeView>('/settlement/fiscal-settings', { method: 'PUT', companyId, body: request })
}

/** Mirrors `CarrierInvoiceView.MatchView`: el veredicto y con qué se comparó. */
export interface MatchView {
  status: MatchStatus
  expectedAmount: number | null
  /** Contra qué se comparó la factura entera. Null cuando no hubo nada con qué comparar. */
  expectedBasis: ExpectedBasis | null
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
  /** La línea de la que habla. Null = de la cabecera o del viaje entero. */
  invoiceLineId: string | null
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
  format: ExportFormat
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
  /** La parte fiscal (ADR-027). Ausente en un servidor anterior. */
  fiscal?: FiscalView | null
  /** Fase 65: el estado de pago, tal como el ERP lo informó. Ausentes en un servidor anterior. */
  dueDate?: string | null
  paidAmount?: number
  onHoldReason?: string | null
}

export interface CarrierInvoiceRequest {
  carrierId: string
  /** Opcional con el número fiscal (ADR-027): el servidor lo compone. */
  invoiceNumber?: string | null
  invoiceDate: string
  currency: string
  totalAmount: number
  externalReference?: string | null
  notes?: string | null
  lines: {
    tripId?: string | null
    /** Null o ausente = `FREIGHT`. */
    chargeCode?: ChargeCode | null
    description: string
    quantity?: number | null
    unitAmount?: number | null
    /** Negativo sólo en una línea `PENALTY`, que dice por qué. */
    lineAmount: number
    penaltyReason?: string | null
    penaltyReferenceType?: PenaltyReferenceType | null
    penaltyReferenceId?: string | null
  }[]
  documentKind?: DocumentKind | null
  /** El número fiscal en el formato del régimen; con `issuerTaxId`, o ninguno. */
  documentNumber?: string | null
  issuerTaxId?: string | null
  subtotalAmount?: number | null
  taxRate?: number | null
  taxAmount?: number | null
  referencedInvoiceId?: string | null
  withholdingCertificates?: { kind: WithholdingKind; certificateNumber: string; certificateDate?: string | null }[]
  withholdingOverrides?: { kind: WithholdingKind; amount: number; reason: string }[]
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

/**
 * Repetirlo devuelve la misma exportación: dos clics no crean dos obligaciones. Si ya estaba
 * exportada, el servidor la devuelve **con su formato original**, pida el que se pida ahora.
 */
export function exportInvoice(
  companyId: string, id: string, format: ExportFormat = 'JSON',
): Promise<PayableExportView> {
  return apiRequest<PayableExportView>(`/settlement/invoices/${id}/export`, {
    method: 'POST', companyId, query: { format },
  })
}

// --- políticas de tolerancia -------------------------------------------------------------

/**
 * Mirrors `TolerancePolicyView`.
 *
 * `carrierId` null es la política de **toda la empresa**; la de un transportista la desplaza para
 * sus facturas. Una diferencia se acepta si cabe en el importe absoluto **o** en el porcentaje.
 */
export interface TolerancePolicyView {
  id: string
  carrierId: string | null
  carrierName: string | null
  absoluteAmount: number | null
  percentage: number | null
  /** Obligatoria cuando hay importe absoluto: 5 USD y 5 EUR no son la misma tolerancia. */
  currency: string | null
  active: boolean
  updatedAt: string
}

/** Crear: el ámbito (empresa o transportista) se elige aquí y no cambia después. */
export interface TolerancePolicyCreateRequest {
  carrierId?: string | null
  absoluteAmount?: number | null
  percentage?: number | null
  currency?: string | null
}

/** Editar: sólo los límites. */
export interface TolerancePolicyUpdateRequest {
  absoluteAmount: number | null
  percentage: number | null
  currency: string | null
}

export function fetchTolerancePolicies(
  companyId: string, includeInactive = false, signal?: AbortSignal,
): Promise<TolerancePolicyView[]> {
  return apiRequest<TolerancePolicyView[]>('/settlement/tolerance-policies', {
    companyId, signal, query: { includeInactive },
  })
}

/** 409 cuando ya hay una política activa para el mismo ámbito. */
export function createTolerancePolicy(
  companyId: string, request: TolerancePolicyCreateRequest,
): Promise<TolerancePolicyView> {
  return apiRequest<TolerancePolicyView>('/settlement/tolerance-policies', {
    method: 'POST', companyId, body: request,
  })
}

export function updateTolerancePolicy(
  companyId: string, id: string, request: TolerancePolicyUpdateRequest,
): Promise<TolerancePolicyView> {
  return apiRequest<TolerancePolicyView>(`/settlement/tolerance-policies/${id}`, {
    method: 'PUT', companyId, body: request,
  })
}

export function deactivateTolerancePolicy(companyId: string, id: string): Promise<TolerancePolicyView> {
  return apiRequest<TolerancePolicyView>(`/settlement/tolerance-policies/${id}/deactivate`, {
    method: 'POST', companyId,
  })
}
