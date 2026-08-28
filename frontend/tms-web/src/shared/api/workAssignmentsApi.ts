import { apiRequest } from './httpClient'

/** Mirrors `WorkAssignment.Status` (migración V47). */
export type WorkAssignmentStatus = 'PLANNED' | 'CONFIRMED' | 'CANCELLED'

/**
 * Mirrors `ResourceRejectionReason`.
 *
 * Nueve motivos y no un `RESOURCE_NOT_AVAILABLE` genérico, **porque el sistema sabe la causa**. Una
 * licencia vencida, un camión en taller y un hueco demasiado corto para conducir son tres problemas
 * con tres soluciones distintas, y a un planificador al que sólo se le dice "no disponible" le toca
 * ir a averiguar cuál es.
 */
export type ResourceRejectionReason =
  | 'DRIVER_UNAVAILABLE'
  | 'VEHICLE_UNAVAILABLE'
  | 'MAINTENANCE_BLOCK'
  | 'SHIFT_CONFLICT'
  | 'TRIP_OVERLAP'
  | 'INSUFFICIENT_REPOSITION_TIME'
  /** El tramo no se pudo medir. **No es cero** - un día construido sobre eso no lo ha revisado nadie. */
  | 'ROUTING_UNKNOWN'
  | 'LICENSE_INVALID'
  /** Aceptado por un transportista que no es dueño del vehículo (V42). Se reporta, nunca se repara. */
  | 'CARRIER_MISMATCH'

export interface WorkAssignmentTripView {
  tripId: string
  shipmentNumber: string | null
  sequence: number
  plannedStart: string | null
  plannedEnd: string | null
  /** Minutos de conducción desde el envío anterior. Null en el primero, y null si no se pudo medir. */
  repositionMinutes: number | null
}

export interface WorkAssignmentConflictView {
  /** Qué envío, 1-based. `0` para un problema de todo el día, como una licencia vencida. */
  sequence: number
  tripId: string | null
  reason: ResourceRejectionReason
  /** La frase que dice qué pasa, compuesta en el servidor junto a las cifras que la sostienen. */
  detail: string
}

/**
 * Mirrors `WorkAssignmentView` (migración V47).
 *
 * `feasible` **no es un permiso**. Los envíos se despachan de uno en uno, y todo guard que hoy
 * rechaza una salida la sigue rechazando: un asignación de trabajo no es una vía alternativa para
 * saltarse un dispatch guard.
 */
export interface WorkAssignmentView {
  id: string
  operationalDate: string
  vehicleId: string
  vehicleCode: string | null
  driverId: string | null
  driverName: string | null
  status: WorkAssignmentStatus
  notes: string | null
  version: number
  feasible: boolean
  trips: WorkAssignmentTripView[]
  conflicts: WorkAssignmentConflictView[]
}

/**
 * El día entero viaja siempre, nunca un parche.
 *
 * Añadir, quitar y reordenar son la misma operación para el servidor - y tienen que serlo, porque
 * las tres revalidan la secuencia completa: mover un envío rompe el tramo que entra en él **y** el
 * que sale.
 */
export interface WorkAssignmentRequest {
  operationalDate: string
  vehicleId: string
  driverId?: string | null
  notes?: string | null
  /** En el orden en que se van a ejecutar. */
  tripIds: string[]
}

export function fetchWorkAssignments(
  companyId: string, date: string, signal?: AbortSignal,
): Promise<WorkAssignmentView[]> {
  return apiRequest<WorkAssignmentView[]>('/fleet/work-assignments', {
    companyId, signal, query: { date },
  })
}

export function fetchWorkAssignment(
  companyId: string, id: string, signal?: AbortSignal,
): Promise<WorkAssignmentView> {
  return apiRequest<WorkAssignmentView>(`/fleet/work-assignments/${id}`, { companyId, signal })
}

export function createWorkAssignment(
  companyId: string, request: WorkAssignmentRequest,
): Promise<WorkAssignmentView> {
  return apiRequest<WorkAssignmentView>('/fleet/work-assignments', {
    method: 'POST', companyId, body: request,
  })
}

/** Un PUT con el día entero: un envío omitido es un envío que ya no está en el día. */
export function updateWorkAssignment(
  companyId: string, id: string, request: WorkAssignmentRequest,
): Promise<WorkAssignmentView> {
  return apiRequest<WorkAssignmentView>(`/fleet/work-assignments/${id}`, {
    method: 'PUT', companyId, body: request,
  })
}

/** Rechazado mientras quede un conflicto, nombrando cada uno. */
export function confirmWorkAssignment(companyId: string, id: string): Promise<WorkAssignmentView> {
  return apiRequest<WorkAssignmentView>(`/fleet/work-assignments/${id}/confirm`, {
    method: 'POST', companyId,
  })
}

export function cancelWorkAssignment(companyId: string, id: string): Promise<WorkAssignmentView> {
  return apiRequest<WorkAssignmentView>(`/fleet/work-assignments/${id}/cancel`, {
    method: 'POST', companyId,
  })
}
