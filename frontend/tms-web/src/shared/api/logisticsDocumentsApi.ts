import { apiRequest } from './httpClient'

//
// The documents that travel with orders (ADR-015): invoices, delivery notes and remission guides
// recorded from the ERP. Read-only in TMS - nothing here moves an order, a trip or a delivery.
//

/** Mirrors the backend's document types. Kept open to a string: a type added later still renders. */
export type LogisticsDocumentType = 'INVOICE' | 'DELIVERY_NOTE' | 'GRE_REMITENTE' | 'GRE_TRANSPORTISTA' | 'OTHER'

/** Mirrors `LogisticsDocumentPort.Document`. `documentNumber` is exactly as issued, never parsed. */
export interface LogisticsDocumentView {
  id: string
  sourceSystem: string
  documentType: LogisticsDocumentType | (string & {})
  documentNumber: string
  issueDate: string | null
  recipientName: string | null
  /** Informational only; null when the ERP did not send it - never a zero standing in for "unknown". */
  amount: number | null
  currency: string | null
  externalStatus: string | null
  receivedAt: string
}

/** Mirrors `TripDocumentController.OrderDocuments`: one entry per order the trip carries. */
export interface TripOrderDocumentsView {
  orderId: string
  documents: LogisticsDocumentView[]
}

/** `orders.order:read`. */
export function fetchOrderDocuments(
  companyId: string, orderId: string, signal?: AbortSignal,
): Promise<LogisticsDocumentView[]> {
  return apiRequest<LogisticsDocumentView[]>(`/orders/${orderId}/documents`, { companyId, signal })
}

/** `planning.trip:read` **and** `orders.order:read`: a trip's documents are its orders' documents. */
export function fetchTripDocuments(
  companyId: string, tripId: string, signal?: AbortSignal,
): Promise<TripOrderDocumentsView[]> {
  return apiRequest<TripOrderDocumentsView[]>(`/planning/trips/${tripId}/documents`, { companyId, signal })
}
