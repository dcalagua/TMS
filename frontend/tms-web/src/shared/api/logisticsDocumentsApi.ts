import { apiRequest } from './httpClient'

//
// The documents that travel with orders (ADR-015): invoices, delivery notes and remission guides
// recorded from the ERP. Read-only in TMS - nothing here moves an order, a trip or a delivery.
// ADR-028: any country's catalogue. TMS receives them; it never issues, signs or validates one with
// the SRI or SUNAT.
//

/** Mirrors the backend's document types. Kept open to a string: a type added later still renders. */
export type LogisticsDocumentType =
  | 'INVOICE' | 'DELIVERY_NOTE' | 'REMISSION_GUIDE' | 'GRE_REMITENTE' | 'GRE_TRANSPORTISTA' | 'OTHER'

/**
 * Mirrors `LogisticsDocumentPort.Fiscal` (ADR-028, V69). Every field is null on a document received
 * without it - including every document received before V69.
 */
export interface LogisticsDocumentFiscal {
  /** ISO 3166-1 alpha-2 of the issuing authority (EC, PE, ...). */
  fiscalCountry: string | null
  /** The authority's code: '06' SRI guía de remisión, '09' / '31' SUNAT GRE. As sent. */
  fiscalDocumentCode: string | null
  /** SRI clave de acceso (49 digits) or SUNAT reference. Shown and copied, never verified. */
  authorizationNumber: string | null
  transportStartDate: string | null
  transportEndDate: string | null
  carrierTaxId: string | null
  carrierName: string | null
  vehiclePlate: string | null
  originAddress: string | null
  transferReason: string | null
}

/** Mirrors `LogisticsDocumentPort.Item`: a line of the guide, as issued. Never reconciled with the order. */
export interface LogisticsDocumentItem {
  code: string | null
  additionalCode: string | null
  description: string
  quantity: number | null
  unit: string | null
}

/** Mirrors `LogisticsDocumentPort.Recipient`: a destinatario of the guide. */
export interface LogisticsDocumentRecipient {
  taxId: string | null
  name: string | null
  destinationAddress: string | null
  transferReason: string | null
  destinationEstablishmentCode: string | null
  route: string | null
  relatedDocumentCode: string | null
  relatedDocumentNumber: string | null
  relatedDocumentAuthorizationNumber: string | null
  relatedDocumentDate: string | null
  items: LogisticsDocumentItem[]
}

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
  /** ADR-028 (V69). Optional so that a response of an older backend still renders. */
  issuerTaxId?: string | null
  recipientTaxId?: string | null
  fiscal?: LogisticsDocumentFiscal | null
  recipients?: LogisticsDocumentRecipient[]
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
