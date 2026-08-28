import { apiRequest } from './httpClient'

/** Mirrors the backend's `ResourceType`. Los cuatro se comportan igual; existen para leerse. */
export const RESOURCE_TYPES = ['DOCK', 'DOOR', 'BAY', 'YARD'] as const
export type ResourceType = (typeof RESOURCE_TYPES)[number]

/** Mirrors `AppointmentPurpose`. */
export const APPOINTMENT_PURPOSES = ['PICKUP', 'DELIVERY'] as const
export type AppointmentPurpose = (typeof APPOINTMENT_PURPOSES)[number]

/** Mirrors `AppointmentStatus`, en orden de vida. */
export const APPOINTMENT_STATUSES = [
  'REQUESTED', 'CONFIRMED', 'RESCHEDULED', 'ARRIVED', 'COMPLETED', 'CANCELLED', 'NO_SHOW',
] as const
export type AppointmentStatus = (typeof APPOINTMENT_STATUSES)[number]

/** Mirrors `LocationResourceView.DayHoursView`. Horas **locales del sitio**, nunca del servidor. */
export interface DayHoursView {
  day: 'MONDAY' | 'TUESDAY' | 'WEDNESDAY' | 'THURSDAY' | 'FRIDAY' | 'SATURDAY' | 'SUNDAY'
  opensAt: string
  closesAt: string
}

/**
 * Mirrors `LocationResourceView` (migración V41).
 *
 * `openingHours` vacío significa que la puerta no tiene calendario, y las reglas de reserva lo leen
 * como **abierta**: una empresa que no configuró horarios no ha dicho que la puerta esté cerrada.
 */
export interface LocationResourceView {
  id: string
  locationId: string
  code: string
  name: string
  resourceType: ResourceType
  /** Cuánto dura una reserva aquí cuando nadie dice otra cosa. */
  defaultSlotMinutes: number
  active: boolean
  openingHours: DayHoursView[]
  createdAt: string
  updatedAt: string
}

/**
 * Mirrors the backend's `AppointmentView` (migración V41).
 *
 * `windowStart`/`windowEnd` son instantes absolutos. La zona del sitio es cómo se *muestran*, no
 * cómo se guardan: un momento que dos partes acordaron no tiene zona horaria.
 */
export interface AppointmentView {
  id: string
  resourceId: string
  resourceCode: string | null
  resourceName: string | null
  resourceType: ResourceType | null
  locationId: string
  locationCode: string | null
  locationName: string | null
  tripId: string | null
  tripStopId: string | null
  purpose: AppointmentPurpose
  status: AppointmentStatus
  /** Lo que puede pasarle después: la pantalla dibuja solo los botones que funcionan. */
  allowedTransitions: AppointmentStatus[]
  windowStart: string
  windowEnd: string
  durationMinutes: number
  reference: string | null
  notes: string | null
  arrivedAt: string | null
  completedAt: string | null
  cancelledAt: string | null
  cancelReason: string | null
  /** Dónde estaba antes de que alguien la moviera, o null si nunca se movió. */
  rescheduledFromStart: string | null
  createdAt: string
  updatedAt: string
}

export interface AppointmentRequest {
  resourceId: string
  purpose: AppointmentPurpose
  windowStart: string
  /** Omitirlo usa el `defaultSlotMinutes` de la puerta. */
  windowEnd?: string | null
  tripId?: string | null
  tripStopId?: string | null
  reference?: string | null
  notes?: string | null
}

/** El tablero de muelles: todo lo reservado en un sitio entre dos instantes. */
export function fetchAppointments(
  companyId: string, locationId: string, from: string, to: string, signal?: AbortSignal,
): Promise<AppointmentView[]> {
  return apiRequest<AppointmentView[]>('/appointments', {
    companyId, signal, query: { locationId, from, to },
  })
}

/** Las reservas de un viaje, en orden de visita. */
export function fetchAppointmentsForTrip(
  companyId: string, tripId: string, signal?: AbortSignal,
): Promise<AppointmentView[]> {
  return apiRequest<AppointmentView[]>(`/appointments/by-trip/${tripId}`, { companyId, signal })
}

export function bookAppointment(companyId: string, request: AppointmentRequest): Promise<AppointmentView> {
  return apiRequest<AppointmentView>('/appointments', { method: 'POST', companyId, body: request })
}

export function confirmAppointment(companyId: string, id: string): Promise<AppointmentView> {
  return apiRequest<AppointmentView>(`/appointments/${id}/confirm`, { method: 'POST', companyId })
}

export function rescheduleAppointment(
  companyId: string, id: string, windowStart: string, windowEnd?: string,
): Promise<AppointmentView> {
  return apiRequest<AppointmentView>(`/appointments/${id}/reschedule`, {
    method: 'POST', companyId, query: windowEnd ? { windowStart, windowEnd } : { windowStart },
  })
}

export function arriveAppointment(companyId: string, id: string): Promise<AppointmentView> {
  return apiRequest<AppointmentView>(`/appointments/${id}/arrive`, { method: 'POST', companyId })
}

export function completeAppointment(companyId: string, id: string): Promise<AppointmentView> {
  return apiRequest<AppointmentView>(`/appointments/${id}/complete`, { method: 'POST', companyId })
}

export function cancelAppointment(companyId: string, id: string, reason?: string): Promise<AppointmentView> {
  return apiRequest<AppointmentView>(`/appointments/${id}/cancel`, {
    method: 'POST', companyId, query: reason ? { reason } : undefined,
  })
}

/** Nadie vino. Libera el hueco y **conserva** el registro: es de lo que se discute una demora. */
export function markAppointmentNoShow(companyId: string, id: string): Promise<AppointmentView> {
  return apiRequest<AppointmentView>(`/appointments/${id}/no-show`, { method: 'POST', companyId })
}

/** Las puertas de un sitio, con sus horarios. */
export function fetchLocationResources(
  companyId: string, locationId: string, signal?: AbortSignal,
): Promise<LocationResourceView[]> {
  return apiRequest<LocationResourceView[]>('/appointments/resources', {
    companyId, signal, query: { locationId },
  })
}

export interface LocationResourceRequest {
  locationId: string
  code: string
  name: string
  resourceType: ResourceType
  defaultSlotMinutes: number
}

export function createLocationResource(
  companyId: string, request: LocationResourceRequest,
): Promise<LocationResourceView> {
  return apiRequest<LocationResourceView>('/appointments/resources', {
    method: 'POST', companyId, body: request,
  })
}
