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
