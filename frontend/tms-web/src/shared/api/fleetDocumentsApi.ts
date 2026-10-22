import { apiRequest } from './httpClient'
import type { PageResponse } from './pageResponse'

/**
 * Documentos de cumplimiento de flota (migración V72): los papeles de vehículos y conductores más
 * allá de la licencia, con su vencimiento, la política de la empresa y lo que falta.
 *
 * El ESTADO lo calcula el backend al leer, contra el «hoy» de la empresa y su ventana de alerta;
 * esta capa sólo lo muestra. No saber un vencimiento no es estar vencido: `UNRECORDED` avisa y
 * nunca bloquea.
 */

/** Mirrors `FleetDocumentSubject`. */
export type FleetDocumentSubject = 'VEHICLE' | 'DRIVER'

/** Mirrors `FleetDocumentType`. Cada tipo es de UN sujeto. */
export type FleetDocumentType =
  | 'REGISTRATION'
  | 'TECHNICAL_INSPECTION'
  | 'MANDATORY_INSURANCE'
  | 'OPERATING_PERMIT'
  | 'CARGO_INSURANCE'
  | 'VEHICLE_OTHER'
  | 'MEDICAL_CERTIFICATE'
  | 'DEFENSIVE_DRIVING_COURSE'
  | 'DRIVER_OTHER'

export const FLEET_DOCUMENT_TYPES: Record<FleetDocumentSubject, FleetDocumentType[]> = {
  VEHICLE: ['REGISTRATION', 'TECHNICAL_INSPECTION', 'MANDATORY_INSURANCE', 'OPERATING_PERMIT', 'CARGO_INSURANCE', 'VEHICLE_OTHER'],
  DRIVER: ['MEDICAL_CERTIFICATE', 'DEFENSIVE_DRIVING_COURSE', 'DRIVER_OTHER'],
}

/** Mirrors `FleetDocumentStatus`. */
export type FleetDocumentStatus = 'VALID' | 'EXPIRING_SOON' | 'EXPIRED' | 'UNRECORDED'

export const FLEET_DOCUMENT_STATUSES: FleetDocumentStatus[] = ['EXPIRED', 'EXPIRING_SOON', 'VALID', 'UNRECORDED']

/** Mirrors `ComplianceEnforcement`: lo que hace un papel VENCIDO a una planificación. */
export type ComplianceEnforcement = 'BLOCK' | 'WARN'

/** Mirrors `FleetDocumentView`. */
export interface FleetDocumentView {
  id: string
  subjectType: FleetDocumentSubject
  subjectId: string
  /** Placa del vehículo o código del conductor. */
  subjectCode: string | null
  /** Código del vehículo o nombre del conductor. */
  subjectName: string | null
  documentType: FleetDocumentType
  documentNumber: string | null
  issuer: string | null
  issuedOn: string | null
  expiresOn: string | null
  notes: string | null
  active: boolean
  status: FleetDocumentStatus
  /** Días desde hoy al último día válido; negativo si ya venció, null si no tiene fecha. */
  daysToExpiry: number | null
  mandatory: boolean
  enforcement: ComplianceEnforcement
  createdAt: string
  updatedAt: string
  version: number
}

export interface FleetDocumentRequest {
  subjectType: FleetDocumentSubject
  subjectId: string
  documentType: FleetDocumentType
  documentNumber?: string | null
  issuer?: string | null
  issuedOn?: string | null
  expiresOn?: string | null
  notes?: string | null
}

/** Mirrors `ComplianceFinding`: algo que está mal con los papeles en un día. */
export interface ComplianceFinding {
  subject: FleetDocumentSubject
  subjectId: string
  subjectCode: string | null
  documentType: FleetDocumentType
  status: FleetDocumentStatus
  expiresOn: string | null
  documentId: string | null
  documentNumber: string | null
  onFile: boolean
  /** Sólo un papel VENCIDO cuya política dice BLOCK bloquea. */
  blocking: boolean
}

export interface AgeingBucket {
  bucket: 'EXPIRED_OVER_90' | 'EXPIRED_31_90' | 'EXPIRED_1_30' | 'DUE_0_7' | 'DUE_8_30' | 'DUE_31_90' | 'DUE_OVER_90' | 'NO_DATE'
  count: number
}

export interface FleetComplianceSummary {
  today: string
  alertDays: number
  total: number
  valid: number
  expiringSoon: number
  expired: number
  unrecorded: number
  missingMandatory: number
  ageing: AgeingBucket[]
}

export interface MissingDocument {
  subjectType: FleetDocumentSubject
  subjectId: string
  subjectCode: string | null
  subjectName: string | null
  documentType: FleetDocumentType
  enforcement: ComplianceEnforcement
}

export interface SubjectCompliance {
  subjectType: FleetDocumentSubject
  subjectId: string
  today: string
  alertDays: number
  documents: FleetDocumentView[]
  findings: ComplianceFinding[]
}

export interface TypePolicy {
  documentType: FleetDocumentType
  subjectType: FleetDocumentSubject
  mandatory: boolean
  enforcement: ComplianceEnforcement
  defaultMandatory: boolean
  defaultEnforcement: ComplianceEnforcement
  configured: boolean
}

export interface FleetDocumentPolicy {
  alertDays: number
  types: TypePolicy[]
}

export interface FleetDocumentPolicyRequest {
  alertDays: number
  types: { documentType: FleetDocumentType; mandatory: boolean; enforcement: ComplianceEnforcement }[]
}

export interface FleetDocumentListParams {
  companyId: string
  page?: number
  size?: number
  sort?: string
  subjectType?: FleetDocumentSubject
  vehicleId?: string
  driverId?: string
  documentType?: FleetDocumentType
  status?: FleetDocumentStatus
  active?: boolean
  signal?: AbortSignal
}

export function fetchFleetDocuments(params: FleetDocumentListParams): Promise<PageResponse<FleetDocumentView>> {
  const { companyId, signal, ...query } = params
  return apiRequest<PageResponse<FleetDocumentView>>('/fleet/documents', { companyId, signal, query })
}

export function fetchFleetComplianceSummary(companyId: string, signal?: AbortSignal): Promise<FleetComplianceSummary> {
  return apiRequest<FleetComplianceSummary>('/fleet/documents/summary', { companyId, signal })
}

export function fetchMissingFleetDocuments(companyId: string, signal?: AbortSignal): Promise<MissingDocument[]> {
  return apiRequest<MissingDocument[]>('/fleet/documents/missing', { companyId, signal })
}

export function fetchSubjectCompliance(companyId: string, subjectType: FleetDocumentSubject, subjectId: string,
  signal?: AbortSignal): Promise<SubjectCompliance> {
  return apiRequest<SubjectCompliance>(`/fleet/documents/subjects/${subjectType}/${subjectId}`, { companyId, signal })
}

export function createFleetDocument(companyId: string, request: FleetDocumentRequest): Promise<FleetDocumentView> {
  return apiRequest<FleetDocumentView>('/fleet/documents', { method: 'POST', companyId, body: request })
}

export function updateFleetDocument(companyId: string, id: string, request: FleetDocumentRequest): Promise<FleetDocumentView> {
  return apiRequest<FleetDocumentView>(`/fleet/documents/${id}`, { method: 'PUT', companyId, body: request })
}

export function activateFleetDocument(companyId: string, id: string): Promise<FleetDocumentView> {
  return apiRequest<FleetDocumentView>(`/fleet/documents/${id}/activate`, { method: 'POST', companyId })
}

export function deactivateFleetDocument(companyId: string, id: string): Promise<FleetDocumentView> {
  return apiRequest<FleetDocumentView>(`/fleet/documents/${id}/deactivate`, { method: 'POST', companyId })
}

export function fetchFleetDocumentPolicy(companyId: string, signal?: AbortSignal): Promise<FleetDocumentPolicy> {
  return apiRequest<FleetDocumentPolicy>('/fleet/document-policy', { companyId, signal })
}

export function updateFleetDocumentPolicy(companyId: string, request: FleetDocumentPolicyRequest): Promise<FleetDocumentPolicy> {
  return apiRequest<FleetDocumentPolicy>('/fleet/document-policy', { method: 'PUT', companyId, body: request })
}
