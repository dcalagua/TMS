import { apiDownload, apiRequest, apiUpload, type DownloadedFile } from './httpClient'
import type {
  DocumentDeliveryReason, DocumentDeliveryStatus, DocumentEvidenceType,
} from './documentDeliveriesApi'

//
// El portal de transportistas (ADR-018 §6). Lo usa un usuario con membresía de transportista y
// rol `CARRIER_OPERATOR`: ve los viajes de su transportista que el almacén ya despachó y reporta
// documento a documento. Va por el mismo cliente HTTP que el resto (bearer + `X-Company-Id`).
//

export const IDEMPOTENCY_KEY_HEADER = 'Idempotency-Key'

/** At most this many photos per report; the server enforces the same bound. */
export const MAX_REPORT_PHOTOS = 5

export interface PortalTripSummary {
  tripId: string
  shipmentNumber: string
  status: string
  planningDate: string
  dispatchedAt: string | null
  originName: string | null
  vehicleLicensePlate: string | null
  driverName: string | null
  stopCount: number
  documentCount: number
  pendingCount: number
  deliveredCount: number
  rejectedCount: number
  notDeliveredCount: number
  returnedCount: number
}

export interface EvidenceRef {
  id: string
  evidenceType: DocumentEvidenceType
  contentType: string
  uploadedAt: string
}

export interface PortalDocument {
  /** The tracking row (`document_delivery.id`). */
  id: string
  /** The logistics document (ADR-015). The report endpoint addresses the document by this id. */
  documentId: string
  documentType: string
  documentNumber: string
  orderNumbers: string[]
  customerName: string | null
  status: DocumentDeliveryStatus
  reasonCode: DocumentDeliveryReason | null
  remark: string | null
  reportedAt: string | null
  recordedAt: string | null
  returnedAt: string | null
  /** False once the office confirmed the return: the report is then final. */
  editable: boolean
  evidence: EvidenceRef[]
}

export interface PortalStop {
  /** `null` for the trailing group of documents whose order matched no stop. */
  stopId: string | null
  sequence: number | null
  destinationName: string | null
  address: string | null
  /** WGS84 of the stop's destination (ADR-019 §2, additive): opens navigation. Absent on older servers. */
  latitude?: number | null
  longitude?: number | null
  documents: PortalDocument[]
}

export interface PortalTripDetail {
  trip: PortalTripSummary
  /** False when the deployment has no evidence store: `DELIVERED` (photo mandatory) cannot be reported. */
  evidenceEnabled: boolean
  stops: PortalStop[]
}

export interface PortalReportInput {
  status: DocumentDeliveryStatus
  reasonCode?: DocumentDeliveryReason | null
  remark?: string | null
  /** ISO-8601. When it happened at the customer; the server uses its own clock when absent. */
  reportedAt?: string | null
  photos: Blob[]
  signature?: Blob | null
}

/** `portal.trip:read`. */
export function fetchPortalTrips(
  companyId: string,
  params: { days: number; pendingOnly: boolean },
  signal?: AbortSignal,
): Promise<PortalTripSummary[]> {
  return apiRequest<PortalTripSummary[]>('/portal/trips', { companyId, signal, query: params })
}

/** `portal.trip:read`. */
export function fetchPortalTrip(companyId: string, tripId: string, signal?: AbortSignal): Promise<PortalTripDetail> {
  return apiRequest<PortalTripDetail>(`/portal/trips/${tripId}`, { companyId, signal })
}

/** Builds the multipart body of a report. Exported for tests: the part names are the contract. */
export function buildReportFormData(input: PortalReportInput): FormData {
  const formData = new FormData()
  formData.append('status', input.status)
  if (input.reasonCode) formData.append('reasonCode', input.reasonCode)
  if (input.remark) formData.append('remark', input.remark)
  if (input.reportedAt) formData.append('reportedAt', input.reportedAt)
  input.photos.forEach((photo, index) => {
    formData.append('photos', photo, fileNameOf(photo, `foto-${index + 1}.jpg`))
  })
  if (input.signature) formData.append('signature', input.signature, 'firma.png')
  return formData
}

function fileNameOf(blob: Blob, fallback: string): string {
  return blob instanceof File && blob.name ? blob.name : fallback
}

/**
 * `portal.document:report`. The `Idempotency-Key` is the caller's: one per report attempt the
 * person started, reused on every retry of it, so a report that reached the server before the
 * network dropped is not recorded twice (ADR-018 §5).
 */
export function reportPortalDocument(
  companyId: string,
  tripId: string,
  documentId: string,
  input: PortalReportInput,
  idempotencyKey: string,
): Promise<PortalDocument> {
  return apiUpload<PortalDocument>(`/portal/trips/${tripId}/documents/${documentId}/report`, {
    companyId,
    formData: buildReportFormData(input),
    headers: { [IDEMPOTENCY_KEY_HEADER]: idempotencyKey },
  })
}

/** `portal.trip:read`. */
export function downloadPortalEvidence(
  companyId: string, tripId: string, documentId: string, evidenceId: string, signal?: AbortSignal,
): Promise<DownloadedFile> {
  return apiDownload(`/portal/trips/${tripId}/documents/${documentId}/evidence/${evidenceId}/content`,
    { companyId, signal })
}

// --- Mis facturas (fase 65) ----------------------------------------------------------------
//
// Lo que el transportista ve de sus facturas: un estado simplificado (no ve la auditoría por
// dentro), el vencimiento, lo pagado y la constancia de detracción. Sin copia en el teléfono: una
// factura no se reporta desde la calle, así que sin señal la pantalla sólo lo dice.

/** El estado que ve el transportista, no el `InvoiceStatus` de la oficina. */
export const PORTAL_INVOICE_STATUSES = [
  'IN_REVIEW', 'APPROVED', 'SCHEDULED', 'PARTIALLY_PAID', 'PAID', 'ON_HOLD', 'REJECTED',
] as const
export type PortalInvoiceStatus = (typeof PORTAL_INVOICE_STATUSES)[number]

export interface PortalInvoiceSummary {
  id: string
  invoiceNumber: string
  documentType: string
  invoiceDate: string
  currency: string
  totalAmount: number
  status: PortalInvoiceStatus
  dueDate: string | null
  paidAmount: number
  /** Null mientras la factura no está aprobada: aún no hay un saldo que pagar. */
  openBalance: number | null
  detractionAmount: number | null
  detractionConstancyNumber: string | null
}

export interface PortalInvoiceLine {
  lineNumber: number
  description: string | null
  lineAmount: number
  chargeCode: string
}

export interface PortalInvoicePayment {
  paymentReference: string
  paidOn: string
  amount: number
  detractionConstancyNumber: string | null
  detractionConstancyDate: string | null
  detractionAmount: number | null
  retentionAmount: number | null
}

export interface PortalInvoiceDetail extends PortalInvoiceSummary {
  netAmount: number | null
  retentionAmount: number | null
  payableAmount: number | null
  rejectionReason: string | null
  lines: PortalInvoiceLine[]
  payments: PortalInvoicePayment[]
}

/** `portal.invoice:read`. */
export function fetchPortalInvoices(companyId: string, signal?: AbortSignal): Promise<PortalInvoiceSummary[]> {
  return apiRequest<PortalInvoiceSummary[]>('/portal/invoices', { companyId, signal })
}

/** `portal.invoice:read`. */
export function fetchPortalInvoice(companyId: string, invoiceId: string, signal?: AbortSignal): Promise<PortalInvoiceDetail> {
  return apiRequest<PortalInvoiceDetail>(`/portal/invoices/${invoiceId}`, { companyId, signal })
}
