import { apiDownload, apiRequest, type DownloadedFile } from './httpClient'
import type { PageResponse } from './pageResponse'

//
// Entrega de documentos (ADR-018 §4 y §6): qué pasó con cada factura, guía o GRE que viajó en un
// envío, y si el cargo firmado volvió a la oficina. El monitor de oficina lee aquí; el portal de
// transportistas reporta por `portalApi`. Nada de esto mueve un pedido, una parada o un viaje.
//

/** Mirrors the backend's `DocumentDeliveryStatus`. No partial state: partial is a property of the goods. */
export type DocumentDeliveryStatus = 'PENDING' | 'DELIVERED' | 'REJECTED' | 'NOT_DELIVERED'

export const DOCUMENT_DELIVERY_STATUSES: DocumentDeliveryStatus[] = ['PENDING', 'DELIVERED', 'REJECTED', 'NOT_DELIVERED']

/** Why a document was `REJECTED` or `NOT_DELIVERED`. `OTHER` requires a remark. */
export type DocumentDeliveryReason =
  | 'CUSTOMER_ABSENT'
  | 'CUSTOMER_REFUSED'
  | 'CUSTOMER_CLOSED'
  | 'ADDRESS_NOT_FOUND'
  | 'DOCUMENT_ERROR'
  | 'GOODS_REJECTED'
  | 'OTHER'

export const DOCUMENT_DELIVERY_REASONS: DocumentDeliveryReason[] = [
  'CUSTOMER_ABSENT', 'CUSTOMER_REFUSED', 'CUSTOMER_CLOSED', 'ADDRESS_NOT_FOUND', 'DOCUMENT_ERROR',
  'GOODS_REJECTED', 'OTHER',
]

/** Where the document's state disagrees with the delivery result of its order on the trip (ADR-009). Flagged, never resolved. */
export type DocumentInconsistency = 'DOCUMENT_DELIVERED_GOODS_FAILED' | 'GOODS_DELIVERED_DOCUMENT_NOT'

export type DocumentEvidenceType = 'PHOTO' | 'SIGNATURE'

export interface DocumentDeliveryRow {
  id: string
  tripId: string
  shipmentNumber: string
  tripStatus: string
  dispatchedAt: string | null
  carrierId: string | null
  carrierName: string | null
  vehicleLicensePlate: string | null
  driverName: string | null
  documentId: string
  documentType: string
  documentNumber: string
  orderNumbers: string[]
  customerName: string | null
  status: DocumentDeliveryStatus
  reasonCode: DocumentDeliveryReason | null
  remark: string | null
  /** The carrier's time: when it happened at the customer, not when it reached TMS. */
  reportedAt: string | null
  recordedAt: string | null
  recordedByName: string | null
  /** When the office confirmed the signed copy came back. `null` = not yet. */
  returnedAt: string | null
  returnedByName: string | null
  evidenceCount: number
  inconsistency: DocumentInconsistency | null
}

export interface CarrierDocumentTotals {
  carrierId: string | null
  carrierName: string | null
  total: number
  pending: number
  delivered: number
  rejected: number
  notDelivered: number
  awaitingReturn: number
}

export interface DocumentDeliverySummary {
  total: number
  pending: number
  delivered: number
  rejected: number
  notDelivered: number
  returned: number
  /** Reported (not `PENDING`) and not yet returned. */
  awaitingReturn: number
  inconsistent: number
  byCarrier: CarrierDocumentTotals[]
}

export interface DocumentDeliveryEvent {
  id: string
  eventType: 'REPORTED' | 'RETURNED'
  status: DocumentDeliveryStatus | null
  reasonCode: DocumentDeliveryReason | null
  remark: string | null
  reportedAt: string | null
  actorName: string | null
  recordedAt: string
}

export interface DocumentEvidence {
  id: string
  evidenceType: DocumentEvidenceType
  contentType: string
  sizeBytes: number
  originalFilename: string | null
  uploadedAt: string
  uploadedByName: string | null
}

export interface DocumentDeliveryDetail {
  delivery: DocumentDeliveryRow
  events: DocumentDeliveryEvent[]
  evidence: DocumentEvidence[]
}

export interface DocumentDeliveryListParams {
  companyId: string
  /** `yyyy-mm-dd`, on the dispatch date. */
  dateFrom?: string
  dateTo?: string
  carrierId?: string
  status?: DocumentDeliveryStatus
  returned?: boolean
  q?: string
  page?: number
  size?: number
  signal?: AbortSignal
}

/** A return can be confirmed once the document was reported and only once. Mirrors the server rule. */
export function canConfirmReturn(row: Pick<DocumentDeliveryRow, 'status' | 'returnedAt'>): boolean {
  return row.status !== 'PENDING' && row.returnedAt === null
}

/** `planning.document_delivery:read`. */
export function fetchDocumentDeliveries(params: DocumentDeliveryListParams): Promise<PageResponse<DocumentDeliveryRow>> {
  const { companyId, signal, ...query } = params
  return apiRequest<PageResponse<DocumentDeliveryRow>>('/document-deliveries', { companyId, signal, query })
}

/** `planning.document_delivery:read`. Same date and carrier filters as the list, nothing else. */
export function fetchDocumentDeliverySummary(params: {
  companyId: string; dateFrom?: string; dateTo?: string; carrierId?: string; signal?: AbortSignal
}): Promise<DocumentDeliverySummary> {
  const { companyId, signal, ...query } = params
  return apiRequest<DocumentDeliverySummary>('/document-deliveries/summary', { companyId, signal, query })
}

/** `planning.document_delivery:read`. */
export function fetchDocumentDelivery(companyId: string, id: string, signal?: AbortSignal): Promise<DocumentDeliveryDetail> {
  return apiRequest<DocumentDeliveryDetail>(`/document-deliveries/${id}`, { companyId, signal })
}

/** `planning.document_delivery:return`. The office confirms the signed copy is back; never the carrier. */
export function confirmDocumentReturn(companyId: string, id: string, remark?: string | null): Promise<DocumentDeliveryDetail> {
  return apiRequest<DocumentDeliveryDetail>(`/document-deliveries/${id}/return`, {
    method: 'POST',
    companyId,
    body: remark ? { remark } : {},
  })
}

/** The bytes of one photo or signature, through the API: they have no public address. */
export function downloadDocumentDeliveryEvidence(
  companyId: string, id: string, evidenceId: string, signal?: AbortSignal,
): Promise<DownloadedFile> {
  return apiDownload(`/document-deliveries/${id}/evidence/${evidenceId}/content`, { companyId, signal })
}
