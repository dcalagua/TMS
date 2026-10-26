import { apiDownload, apiRequest, apiUpload, type DownloadedFile } from './httpClient'
import type {
  DocumentDeliveryReason, DocumentDeliveryStatus, DocumentEvidenceType,
} from './documentDeliveriesApi'
import type { DocumentKind, ReportedWithholding, WithholdingView } from './settlementApi'
import type {
  DeliveryResult, StopExecutionStatus, TransportEventSource, TripExceptionType,
} from './planningApi'

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
  /**
   * ADR-028: the authority's identity of the document (an Ecuadorian guide's clave de acceso), or
   * null when the ERP sent none. Optional: a trip cached offline before V69 has no such field.
   */
  fiscal?: PortalDocumentFiscal | null
}

/** Mirrors `LogisticsDocumentPort.FiscalIdentity`. */
export interface PortalDocumentFiscal {
  fiscalCountry: string | null
  fiscalDocumentCode: string | null
  authorizationNumber: string | null
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
  /**
   * V70: where the vehicle is in this stop, and the moves the server allows next (empty unless the
   * trip is on the road). Absent on a trip cached by an older version of the portal; null on the
   * trailing group without a stop.
   */
  executionStatus?: StopExecutionStatus | null
  allowedExecutionTransitions?: StopExecutionStatus[] | null
  actualArrivalAt?: string | null
  serviceStartedAt?: string | null
  actualDepartureAt?: string | null
  /** V70: the orders delivered here, each with what was recorded for it. */
  orders?: PortalStopOrder[]
}

/** An order delivered at a portal stop (V70). The assigned amounts bound a partial delivery. */
export interface PortalStopOrder {
  orderId: string
  orderNumber: string
  customerName: string | null
  assignedWeightKg: number | null
  assignedVolumeM3: number | null
  assignedPallets: number | null
  /** Null while nothing was recorded for this order at this stop. */
  delivery: PortalDelivery | null
}

/** The delivery the carrier (or the office, correcting it) recorded - `source` says who. */
export interface PortalDelivery {
  id: string
  result: DeliveryResult
  deliveredAt: string | null
  receiverName: string | null
  receiverDocument: string | null
  notes: string | null
  source: TransportEventSource
  recordedAt: string | null
  evidenceCount: number
  refusedWeightKg: number | null
  refusedVolumeM3: number | null
  refusedPallets: number | null
}

/** A problem on the trip (V70), reported by the carrier or by the office. */
export interface PortalIncident {
  id: string
  tripStopId: string | null
  stopSequence: number | null
  exceptionType: TripExceptionType
  status: 'OPEN' | 'RESOLVED'
  reportedAt: string
  notes: string | null
  resolvedAt: string | null
}

export interface PortalTripDetail {
  trip: PortalTripSummary
  /**
   * False when the deployment has no evidence store: `DELIVERED` (photo mandatory) cannot be
   * reported for a document, and a delivery result is recorded without photo or signature (V70).
   */
  evidenceEnabled: boolean
  stops: PortalStop[]
  /** V70. Absent on a trip cached by an older version of the portal. */
  incidents?: PortalIncident[]
}

/** The stop moves a carrier makes from the portal (V70). Skipping a stop is the office's decision. */
export type PortalStopTransition = 'arrive' | 'service' | 'complete' | 'fail'

/** What a stop move produces, so the screen can show it before the server answers. */
export const STOP_TRANSITION_TARGET: Record<PortalStopTransition, StopExecutionStatus> = {
  arrive: 'ARRIVED',
  service: 'IN_SERVICE',
  complete: 'COMPLETED',
  fail: 'FAILED',
}

/** The fields of the delivery form (V70). Refused amounts only for `PARTIAL`. */
export interface PortalDeliveryInput {
  result: DeliveryResult
  /** ISO-8601. When it happened at the door; omitted for `NOT_ATTEMPTED`. */
  deliveredAt?: string | null
  receiverName?: string | null
  receiverDocument?: string | null
  notes?: string | null
  refusedWeightKg?: number | null
  refusedVolumeM3?: number | null
  refusedPallets?: number | null
  photos: Blob[]
  signature?: Blob | null
}

/**
 * One execution action of the portal (V70), as the offline queue keeps it and sends it. Each has
 * its `Idempotency-Key` on the queue entry, so a resend records nothing twice.
 */
export type PortalExecutionAction =
  | { type: 'STOP'; stopId: string; transition: PortalStopTransition; occurredAt: string;
      notes?: string | null; exceptionType?: TripExceptionType | null }
  | { type: 'DELIVERY'; stopId: string; orderId: string; input: PortalDeliveryInput }
  | { type: 'INCIDENT'; tripStopId: string | null; exceptionType: TripExceptionType; occurredAt: string;
      notes?: string | null }

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

/** Builds the multipart body of a delivery (V70). Exported for tests: the part names are the contract. */
export function buildDeliveryFormData(input: PortalDeliveryInput): FormData {
  const formData = new FormData()
  formData.append('result', input.result)
  const fields: [string, string | number | null | undefined][] = [
    ['deliveredAt', input.deliveredAt], ['receiverName', input.receiverName],
    ['receiverDocument', input.receiverDocument], ['notes', input.notes],
    ['refusedWeightKg', input.refusedWeightKg], ['refusedVolumeM3', input.refusedVolumeM3],
    ['refusedPallets', input.refusedPallets],
  ]
  for (const [name, value] of fields) {
    if (value !== null && value !== undefined && value !== '') formData.append(name, String(value))
  }
  input.photos.forEach((photo, index) => {
    formData.append('photos', photo, fileNameOf(photo, `foto-${index + 1}.jpg`))
  })
  if (input.signature) formData.append('signature', input.signature, 'firma.png')
  return formData
}

/**
 * Sends one execution action (V70): `portal.stop:execute` for a stop move or an incident,
 * `portal.delivery:report` for a delivery. Answers with the whole trip, as the screen shows it.
 */
export function sendPortalExecution(
  companyId: string,
  tripId: string,
  action: PortalExecutionAction,
  idempotencyKey: string,
): Promise<PortalTripDetail> {
  const headers = { [IDEMPOTENCY_KEY_HEADER]: idempotencyKey }
  switch (action.type) {
    case 'STOP':
      return apiRequest<PortalTripDetail>(`/portal/trips/${tripId}/stops/${action.stopId}/${action.transition}`, {
        method: 'POST',
        companyId,
        headers,
        body: {
          occurredAt: action.occurredAt,
          notes: action.notes ?? null,
          ...(action.transition === 'fail' ? { exceptionType: action.exceptionType ?? null } : {}),
        },
      })
    case 'DELIVERY':
      return apiUpload<PortalTripDetail>(
        `/portal/trips/${tripId}/stops/${action.stopId}/orders/${action.orderId}/delivery`,
        { companyId, headers, formData: buildDeliveryFormData(action.input) },
      )
    case 'INCIDENT':
      return apiRequest<PortalTripDetail>(`/portal/trips/${tripId}/incidents`, {
        method: 'POST',
        companyId,
        headers,
        body: {
          tripStopId: action.tripStopId,
          exceptionType: action.exceptionType,
          occurredAt: action.occurredAt,
          notes: action.notes ?? null,
        },
      })
  }
}

/** `portal.trip:read`. */
export function downloadPortalEvidence(
  companyId: string, tripId: string, documentId: string, evidenceId: string, signal?: AbortSignal,
): Promise<DownloadedFile> {
  return apiDownload(`/portal/trips/${tripId}/documents/${documentId}/evidence/${evidenceId}/content`,
    { companyId, signal })
}

// --- Mis facturas (fase 65, ADR-027) -------------------------------------------------------
//
// Lo que el transportista ve de sus facturas: un estado simplificado (no ve la auditoría por
// dentro), el vencimiento, lo pagado y las retenciones con su comprobante. Sin copia en el
// teléfono: una factura no se reporta desde la calle, así que sin señal la pantalla sólo lo dice.

/** El estado que ve el transportista, no el `InvoiceStatus` de la oficina. */
export const PORTAL_INVOICE_STATUSES = [
  'IN_REVIEW', 'APPROVED', 'SCHEDULED', 'PARTIALLY_PAID', 'PAID', 'ON_HOLD', 'REJECTED',
] as const
export type PortalInvoiceStatus = (typeof PORTAL_INVOICE_STATUSES)[number]

export interface PortalInvoiceSummary {
  id: string
  invoiceNumber: string
  documentKind: DocumentKind
  /** El código del país (01, 04…); null en el régimen genérico. */
  fiscalDocumentCode: string | null
  invoiceDate: string
  currency: string
  totalAmount: number
  status: PortalInvoiceStatus
  dueDate: string | null
  paidAmount: number
  /** Null mientras la factura no está aprobada: aún no hay un saldo que pagar. */
  openBalance: number | null
  /** Lo que el cargador retiene o deposita aparte; null = ninguna retención. */
  withholdingTotalAmount: number | null
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
  withholdings: ReportedWithholding[]
}

export interface PortalInvoiceDetail extends PortalInvoiceSummary {
  /** El nombre del impuesto del régimen con que se recibió (IGV, IVA…). */
  vatLabel: string | null
  withholdings: WithholdingView[]
  netAmount: number | null
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
