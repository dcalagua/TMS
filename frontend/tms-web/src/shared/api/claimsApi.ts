import { apiRequest, type DownloadedFile } from './httpClient'
import type { PageResponse } from './pageResponse'

//
// Reclamos de carga y centro de incidencias (V71). Un reclamo se abre contra el transportista de un
// viaje, pasa a revisión y lo decide **otra** persona (cuatro ojos). Si se acepta, se recupera con lo
// que la auditoría de flete ya tiene: una línea PENALTY en la factura del transportista o una nota de
// crédito que él envía. TMS no mueve dinero.
//

export const CLAIM_TYPES = ['DAMAGE', 'SHORTAGE', 'LOSS', 'DELAY', 'WRONG_DELIVERY', 'OTHER'] as const
export type ClaimType = (typeof CLAIM_TYPES)[number]

export const CLAIM_STATUSES = [
  'OPEN', 'UNDER_REVIEW', 'ACCEPTED', 'PARTIALLY_ACCEPTED', 'REJECTED', 'CLOSED', 'CANCELLED',
] as const
export type ClaimStatus = (typeof CLAIM_STATUSES)[number]

export type ClaimResolution = 'ACCEPTED' | 'PARTIALLY_ACCEPTED' | 'REJECTED'
export const RECOVERY_METHODS = ['PENALTY', 'CREDIT_NOTE'] as const
export type RecoveryMethod = (typeof RECOVERY_METHODS)[number]

/** Lo que el estado deja hacer. La pantalla lo pinta; el permiso y los cuatro ojos los revisa el servidor. */
export type ClaimAction =
  | 'UPDATE' | 'SUBMIT' | 'ACCEPT' | 'PARTIALLY_ACCEPT' | 'REJECT' | 'CLOSE' | 'CANCEL' | 'ATTACH_EVIDENCE'

/** Los estados que siguen pidiendo que alguien haga algo. */
export const ACTIVE_CLAIM_STATUSES: ClaimStatus[] = ['OPEN', 'UNDER_REVIEW', 'ACCEPTED', 'PARTIALLY_ACCEPTED', 'REJECTED']

export interface ClaimSummary {
  id: string
  claimNumber: string
  claimType: ClaimType
  status: ClaimStatus
  resolution: ClaimResolution | null
  carrierId: string
  carrierName: string | null
  tripId: string
  shipmentNumber: string | null
  orderId: string | null
  orderNumber: string | null
  incidentDate: string
  openedAt: string
  /** Días de calendario de la empresa: hasta hoy si sigue activo, hasta el cierre si no. */
  ageDays: number
  claimedAmount: number
  acceptedAmount: number | null
  currency: string
  recoveryMethod: RecoveryMethod | null
}

export interface ClaimLineView {
  id: string
  lineNumber: number
  orderLineId: string | null
  orderLineNumber: number | null
  materialCode: string | null
  description: string
  quantity: number | null
  uom: string | null
  amount: number
}

export interface ClaimEvidenceView {
  id: string
  contentType: string
  sizeBytes: number
  originalFilename: string | null
  description: string | null
  uploadedBy: string
  createdAt: string
}

/** Una línea PENALTY de un documento no rechazado que recupera el reclamo. `amount` es negativo. */
export interface PenaltyLineView {
  invoiceId: string
  invoiceNumber: string
  invoiceStatus: string
  lineId: string
  amount: number
}

export interface ClaimDetail {
  summary: ClaimSummary
  description: string
  orderDeliveryId: string | null
  tripExceptionId: string | null
  openedBy: string
  /** Quien lo abrió no puede aceptarlo: la pantalla lo dice antes de que se pulse el botón. */
  openedByMe: boolean
  submittedBy: string | null
  submittedAt: string | null
  decisionReason: string | null
  decidedBy: string | null
  decidedAt: string | null
  closedBy: string | null
  closedAt: string | null
  closeNotes: string | null
  settlementInvoiceId: string | null
  settlementInvoiceNumber: string | null
  cancellationReason: string | null
  lines: ClaimLineView[]
  evidence: ClaimEvidenceView[]
  penalties: PenaltyLineView[]
  allowedActions: ClaimAction[]
  version: number
}

export interface DraftLine {
  orderLineId: string
  orderLineNumber: number | null
  materialCode: string | null
  quantity: number
  uom: string
  notes: string | null
}

/** Lo que diría un reclamo nuevo, leído de lo que se registró del viaje. No escribe nada. */
export interface ClaimDraft {
  tripId: string
  shipmentNumber: string
  carrierId: string | null
  carrierName: string | null
  claimable: boolean
  notClaimableReason: string | null
  orderId: string | null
  orderNumber: string | null
  orderDeliveryId: string | null
  deliveryResult: string | null
  deliveryNotes: string | null
  refusedWeightKg: number | null
  refusedPallets: number | null
  tripExceptionId: string | null
  exceptionType: string | null
  exceptionNotes: string | null
  suggestedType: ClaimType | null
  incidentDate: string
  refusedLines: DraftLine[]
  existingClaims: { id: string; claimNumber: string; status: ClaimStatus; claimType: ClaimType }[]
}

export interface ClaimLineInput {
  orderLineId?: string | null
  description: string
  quantity?: number | null
  uom?: string | null
  amount: number
}

export interface OpenClaimInput {
  tripId: string
  orderId?: string | null
  orderDeliveryId?: string | null
  tripExceptionId?: string | null
  claimType: ClaimType
  incidentDate: string
  description: string
  /** Con líneas, puede omitirse: es su suma. */
  claimedAmount?: number | null
  currency: string
  lines: ClaimLineInput[]
}

export type UpdateClaimInput = Omit<OpenClaimInput, 'tripId' | 'orderId' | 'orderDeliveryId' | 'tripExceptionId'>

export interface DecisionInput {
  decision: ClaimResolution
  acceptedAmount?: number | null
  recoveryMethod?: RecoveryMethod | null
  reason?: string | null
}

export interface AgeingBuckets {
  upTo7: number
  from8To30: number
  from31To60: number
  over60: number
}

export interface ClaimsOverview {
  activeCount: number
  openCount: number
  underReviewCount: number
  awaitingRecovery: number
  currencies: { currency: string; count: number; claimedAmount: number; acceptedPending: number }[]
  ageing: AgeingBuckets
}

/** Un reclamo aceptado que la factura que se registra podría descontar. `penaltyAmount` es negativo. */
export interface PenaltySuggestion {
  claimId: string
  claimNumber: string
  claimType: ClaimType
  tripId: string
  shipmentNumber: string | null
  acceptedAmount: number
  currency: string
  penaltyAmount: number
  penaltyReason: string
}

export interface ClaimListQuery {
  status?: ClaimStatus[]
  carrierId?: string
  claimType?: ClaimType
  tripId?: string
  from?: string
  to?: string
  search?: string
  page?: number
  size?: number
}

const BASE = '/settlement/claims'

/** `settlement.claim:read`. */
export function fetchClaims(companyId: string, query: ClaimListQuery, signal?: AbortSignal): Promise<PageResponse<ClaimSummary>> {
  return apiRequest<PageResponse<ClaimSummary>>(BASE, {
    companyId,
    signal,
    query: {
      status: query.status && query.status.length > 0 ? query.status.join(',') : undefined,
      carrierId: query.carrierId || undefined,
      claimType: query.claimType || undefined,
      tripId: query.tripId || undefined,
      from: query.from || undefined,
      to: query.to || undefined,
      search: query.search || undefined,
      page: query.page,
      size: query.size,
    },
  })
}

export function fetchClaimsOverview(companyId: string, signal?: AbortSignal): Promise<ClaimsOverview> {
  return apiRequest<ClaimsOverview>(`${BASE}/overview`, { companyId, signal })
}

export function fetchClaim(companyId: string, claimId: string, signal?: AbortSignal): Promise<ClaimDetail> {
  return apiRequest<ClaimDetail>(`${BASE}/${claimId}`, { companyId, signal })
}

/** `settlement.claim:read` + `planning.trip:read`. Desde un viaje, una de sus entregas o una incidencia. */
export function fetchClaimDraft(
  companyId: string,
  source: { tripId?: string; orderDeliveryId?: string; tripExceptionId?: string },
  signal?: AbortSignal,
): Promise<ClaimDraft> {
  return apiRequest<ClaimDraft>(`${BASE}/draft`, { companyId, signal, query: source })
}

/** `settlement.claim:manage`. El transportista no se envía: es el del viaje. */
export function openClaim(companyId: string, input: OpenClaimInput): Promise<ClaimDetail> {
  return apiRequest<ClaimDetail>(BASE, { method: 'POST', companyId, body: input })
}

export function updateClaim(companyId: string, claimId: string, input: UpdateClaimInput): Promise<ClaimDetail> {
  return apiRequest<ClaimDetail>(`${BASE}/${claimId}`, { method: 'PUT', companyId, body: input })
}

export function submitClaim(companyId: string, claimId: string): Promise<ClaimDetail> {
  return apiRequest<ClaimDetail>(`${BASE}/${claimId}/submit`, { method: 'POST', companyId })
}

/** `settlement.claim:decide`. 409 si quien decide aceptar es quien lo abrió. */
export function decideClaim(companyId: string, claimId: string, input: DecisionInput): Promise<ClaimDetail> {
  return apiRequest<ClaimDetail>(`${BASE}/${claimId}/decision`, { method: 'POST', companyId, body: input })
}

export function closeClaim(
  companyId: string, claimId: string, input: { settlementInvoiceId?: string | null; notes?: string | null },
): Promise<ClaimDetail> {
  return apiRequest<ClaimDetail>(`${BASE}/${claimId}/close`, { method: 'POST', companyId, body: input })
}

export function cancelClaim(companyId: string, claimId: string, reason: string): Promise<ClaimDetail> {
  return apiRequest<ClaimDetail>(`${BASE}/${claimId}/cancel`, { method: 'POST', companyId, body: { reason } })
}

/** Necesita el almacén de evidencias configurado; si no, 503 `storage-unavailable`. */
export function attachClaimEvidence(
  companyId: string, claimId: string, file: File, description?: string,
): Promise<ClaimDetail> {
  const form = new FormData()
  form.append('file', file)
  if (description && description.trim() !== '') form.append('description', description.trim())
  return apiRequest<ClaimDetail>(`${BASE}/${claimId}/evidence`, { method: 'POST', companyId, formData: form })
}

export function downloadClaimEvidence(companyId: string, claimId: string, evidenceId: string): Promise<DownloadedFile> {
  return apiRequest<DownloadedFile>(`${BASE}/${claimId}/evidence/${evidenceId}/content`, {
    companyId, responseType: 'blob', accept: '*/*',
  })
}

/** `settlement.invoice:manage`: quien registra la factura. Una propuesta, nada se aplica solo. */
export function fetchPenaltySuggestions(
  companyId: string, carrierId: string, currency: string, signal?: AbortSignal,
): Promise<PenaltySuggestion[]> {
  return apiRequest<PenaltySuggestion[]>(`${BASE}/penalty-suggestions`, { companyId, signal, query: { carrierId, currency } })
}

// ── Centro de incidencias ──

export type IncidentStatusGroup = 'OPEN' | 'CLOSED' | 'ALL'
export type IncidentKind = 'EXCEPTION' | 'CLAIM'

export interface IncidentRow {
  kind: IncidentKind
  id: string
  /** El número del reclamo; para una incidencia, el número del envío. */
  reference: string
  type: string
  status: string
  open: boolean
  tripId: string
  shipmentNumber: string | null
  carrierId: string | null
  carrierName: string | null
  occurredAt: string
  closedAt: string | null
  ageDays: number
  amount: number | null
  currency: string | null
  summary: string | null
  /** De una incidencia: los reclamos abiertos desde ella. */
  claims: string[]
}

export interface IncidentCentreView {
  rows: IncidentRow[]
  totals: { openExceptions: number; activeClaims: number; ageing: AgeingBuckets }
  truncated: boolean
  limit: number
}

export interface IncidentQuery {
  status?: IncidentStatusGroup
  kind?: IncidentKind | ''
  carrierId?: string
  type?: string
  from?: string
  to?: string
}

/** `settlement.claim:read` + `planning.trip:read`. */
export function fetchIncidents(companyId: string, query: IncidentQuery, signal?: AbortSignal): Promise<IncidentCentreView> {
  return apiRequest<IncidentCentreView>('/incidents', {
    companyId,
    signal,
    query: {
      status: query.status,
      kind: query.kind || undefined,
      carrierId: query.carrierId || undefined,
      type: query.type || undefined,
      from: query.from || undefined,
      to: query.to || undefined,
    },
  })
}
