import { apiRequest } from './httpClient'

/**
 * Mirrors the backend's `TenderStatus`, in lifecycle order.
 *
 * The value the API sends is always the **effective** one: a sent offer past its deadline arrives
 * as `EXPIRED` even before TMS has written the lapse down, so the UI never has to compare
 * `expiresAt` against the browser's clock to decide whether an offer is still live. See
 * `docs/domain/CARRIER_TENDERING_V1.md` §6.
 */
export const TENDER_STATUSES = ['DRAFT', 'SENT', 'ACCEPTED', 'REJECTED', 'EXPIRED', 'CANCELLED'] as const
export type TenderStatus = (typeof TENDER_STATUSES)[number]

/** Mirrors the backend's `TenderResponseSource` - who answered, which is evidence and not decoration. */
export const TENDER_RESPONSE_SOURCES = ['OPERATOR', 'INTEGRATION'] as const
export type TenderResponseSource = (typeof TENDER_RESPONSE_SOURCES)[number]

/**
 * Mirrors the backend's `TripTenderView` record - one attempt to place a shipment with a carrier.
 *
 * `allowedTransitions` is the server's answer to "which buttons work", computed after the deadline
 * has been applied. The card renders from it rather than keeping a second copy of the lifecycle in
 * TypeScript, exactly as `TripView.allowedTransitions` is used.
 */
export interface TripTenderView {
  id: string
  tripId: string
  attempt: number
  status: TenderStatus
  carrierId: string
  carrierName: string | null
  offeredAmount: number | null
  currency: string | null
  notes: string | null
  expiresAt: string | null
  sentAt: string | null
  respondedAt: string | null
  responseSource: TenderResponseSource | null
  respondedByClient: string | null
  responseNotes: string | null
  expiredAt: string | null
  cancelledAt: string | null
  cancelReason: string | null
  allowedTransitions: TenderStatus[]
  createdAt: string
  updatedAt: string
}

/** Mirrors the backend's `TenderRequest` - the terms of an offer. Every field is optional. */
export interface TenderRequest {
  offeredAmount?: number | null
  currency?: string | null
  notes?: string | null
  /** ISO-8601 instant. Must still be in the future when the offer is sent, not when it is drafted. */
  expiresAt?: string | null
}

/** Mirrors the backend's `TenderResponseRequest`. Required on a rejection, optional on an acceptance. */
export interface TenderResponseRequest {
  notes?: string | null
}

/** Mirrors the backend's `TenderWithdrawRequest`. */
export interface TenderWithdrawRequest {
  reason: string
}

/**
 * Every mutation answers with the shipment's whole tender history, newest attempt first - not the
 * one attempt it touched. One round trip, and the planner who withdraws attempt 2 immediately sees
 * attempt 1's rejection above it, which is usually why they are looking.
 */
export function fetchTripTenders(
  companyId: string,
  tripId: string,
  signal?: AbortSignal,
): Promise<TripTenderView[]> {
  return apiRequest<TripTenderView[]>(`/planning/trips/${tripId}/tenders`, { companyId, signal })
}

export function createTender(
  companyId: string,
  tripId: string,
  request: TenderRequest,
): Promise<TripTenderView[]> {
  return apiRequest<TripTenderView[]>(`/planning/trips/${tripId}/tenders`, {
    method: 'POST',
    companyId,
    body: request,
  })
}

export function updateTenderTerms(
  companyId: string,
  tripId: string,
  tenderId: string,
  request: TenderRequest,
): Promise<TripTenderView[]> {
  return apiRequest<TripTenderView[]>(`/planning/trips/${tripId}/tenders/${tenderId}`, {
    method: 'PUT',
    companyId,
    body: request,
  })
}

export function sendTender(companyId: string, tripId: string, tenderId: string): Promise<TripTenderView[]> {
  return apiRequest<TripTenderView[]>(`/planning/trips/${tripId}/tenders/${tenderId}/send`, {
    method: 'POST',
    companyId,
  })
}

export function acceptTender(
  companyId: string,
  tripId: string,
  tenderId: string,
  request: TenderResponseRequest,
): Promise<TripTenderView[]> {
  return apiRequest<TripTenderView[]>(`/planning/trips/${tripId}/tenders/${tenderId}/accept`, {
    method: 'POST',
    companyId,
    body: request,
  })
}

export function rejectTender(
  companyId: string,
  tripId: string,
  tenderId: string,
  request: TenderResponseRequest,
): Promise<TripTenderView[]> {
  return apiRequest<TripTenderView[]>(`/planning/trips/${tripId}/tenders/${tenderId}/reject`, {
    method: 'POST',
    companyId,
    body: request,
  })
}

export function withdrawTender(
  companyId: string,
  tripId: string,
  tenderId: string,
  request: TenderWithdrawRequest,
): Promise<TripTenderView[]> {
  return apiRequest<TripTenderView[]>(`/planning/trips/${tripId}/tenders/${tenderId}/withdraw`, {
    method: 'POST',
    companyId,
    body: request,
  })
}

// --- the tender waterfall (migración V40) --------------------------------------------

/** Mirrors `WaterfallStatus`. Solo `ACTIVE` está corriendo; las otras tres son cómo termina. */
export const WATERFALL_STATUSES = ['ACTIVE', 'ACCEPTED', 'EXHAUSTED', 'CANCELLED'] as const
export type WaterfallStatus = (typeof WATERFALL_STATUSES)[number]

/** Mirrors `WaterfallCandidateStatus`. `SKIPPED` es el único sin oferta detrás. */
export const WATERFALL_CANDIDATE_STATUSES = [
  'PENDING', 'OFFERED', 'ACCEPTED', 'REJECTED', 'EXPIRED', 'SKIPPED',
] as const
export type WaterfallCandidateStatus = (typeof WATERFALL_CANDIDATE_STATUSES)[number]

/** Mirrors `TenderWaterfallView.CandidateView` - un transportista y su lugar en la lista. */
export interface WaterfallCandidateView {
  rank: number
  carrierId: string
  carrierCode: string | null
  carrierName: string | null
  status: WaterfallCandidateStatus
  /**
   * Con qué precio se ordenó, o `null` si no había tarifa aplicable. Null y cero no son lo mismo:
   * "sin tarifa" no es "gratis", y por eso un transportista sin precio va al final y no al
   * principio.
   */
  quotedAmount: number | null
  quotedCurrency: string | null
  rateCardId: string | null
  tenderId: string | null
  decidedAt: string | null
}

/**
 * Mirrors the backend's `TenderWaterfallView` (migración V40).
 *
 * La lista de candidatos es el punto: "ofrecido a tres transportistas" es un número, mientras que
 * la lista en orden — con lo cotizado y lo que contestó cada uno — responde la pregunta que un
 * despachador hace de verdad: *quién ya dijo que no, y quién queda*.
 */
export interface TenderWaterfallView {
  id: string
  tripId: string
  status: WaterfallStatus
  maxAttempts: number
  responseMinutes: number
  attemptsUsed: number
  /** La oferta que está fuera pasó su plazo. Se calcula al leer, no se guarda. */
  currentOfferLapsed: boolean
  outcomeNote: string | null
  startedAt: string
  completedAt: string | null
  candidates: WaterfallCandidateView[]
}

/** La cascada del viaje, o 404 si nunca se tendereó por cascada. */
export function fetchTenderWaterfall(companyId: string, tripId: string): Promise<TenderWaterfallView> {
  return apiRequest<TenderWaterfallView>(`/planning/trips/${tripId}/tenders/waterfall`, { companyId })
}

/** Ordena los transportistas por lo que cobrarían y ofrece al primero. Nunca acepta ni despacha. */
export function startTenderWaterfall(
  companyId: string, tripId: string, options?: { maxAttempts?: number; responseMinutes?: number },
): Promise<TenderWaterfallView> {
  return apiRequest<TenderWaterfallView>(`/planning/trips/${tripId}/tenders/waterfall`, {
    method: 'POST',
    companyId,
    query: {
      ...(options?.maxAttempts ? { maxAttempts: options.maxAttempts } : {}),
      ...(options?.responseMinutes ? { responseMinutes: options.responseMinutes } : {}),
    },
  })
}

/** Pasa al siguiente transportista cuando la oferta actual venció. Se rechaza si sigue viva. */
export function advanceTenderWaterfall(companyId: string, tripId: string): Promise<TenderWaterfallView> {
  return apiRequest<TenderWaterfallView>(`/planning/trips/${tripId}/tenders/waterfall/advance`, {
    method: 'POST', companyId,
  })
}

/** El override manual: detiene la cascada y retira la oferta que esté fuera. */
export function stopTenderWaterfall(
  companyId: string, tripId: string, reason?: string,
): Promise<TenderWaterfallView> {
  return apiRequest<TenderWaterfallView>(`/planning/trips/${tripId}/tenders/waterfall/stop`, {
    method: 'POST', companyId, query: reason ? { reason } : undefined,
  })
}
