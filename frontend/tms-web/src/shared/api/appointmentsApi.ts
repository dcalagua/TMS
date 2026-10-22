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

export function updateLocationResource(
  companyId: string, id: string, request: LocationResourceRequest,
): Promise<LocationResourceView> {
  return apiRequest<LocationResourceView>(`/appointments/resources/${id}`, {
    method: 'PUT', companyId, body: request,
  })
}

/** Fuera de servicio: rechaza reservas **nuevas** y deja en paz las que ya existen. */
export function deactivateLocationResource(companyId: string, id: string): Promise<LocationResourceView> {
  return apiRequest<LocationResourceView>(`/appointments/resources/${id}/deactivate`, { method: 'POST', companyId })
}

export function activateLocationResource(companyId: string, id: string): Promise<LocationResourceView> {
  return apiRequest<LocationResourceView>(`/appointments/resources/${id}/activate`, { method: 'POST', companyId })
}

/** Un día de la semana en el horario de una puerta: horas **locales del sitio**, `HH:mm`. */
export interface DayHoursRequest {
  day: DayHoursView['day']
  opensAt: string
  closesAt: string
}

/**
 * Reemplaza la semana entera de una puerta. Una lista vacía deja la puerta **sin horario**, y sin
 * horario las reglas de reserva la leen como abierta siempre - no como cerrada.
 */
export function replaceResourceCalendar(
  companyId: string, id: string, days: DayHoursRequest[],
): Promise<LocationResourceView> {
  return apiRequest<LocationResourceView>(`/appointments/resources/${id}/calendar`, {
    method: 'PUT', companyId, body: { days },
  })
}

/** Mirrors `ResourceBlockView`: un cierre puntual, en instantes absolutos. */
export interface ResourceBlockView {
  id: string
  resourceId: string
  startsAt: string
  endsAt: string
  reason: string
}

/** Los cierres de una puerta que todavía no terminaron, del más próximo al más lejano. */
export function fetchResourceBlocks(
  companyId: string, id: string, signal?: AbortSignal,
): Promise<ResourceBlockView[]> {
  return apiRequest<ResourceBlockView[]>(`/appointments/resources/${id}/blocks`, { companyId, signal })
}

/** Cierra la puerta en un intervalo. Las reservas que ya caen dentro **no** se cancelan solas. */
export function blockLocationResource(
  companyId: string, id: string, startsAt: string, endsAt: string, reason: string,
): Promise<void> {
  return apiRequest<void>(`/appointments/resources/${id}/block`, {
    method: 'POST', companyId, query: { startsAt, endsAt, reason },
  })
}

export function liftResourceBlock(companyId: string, id: string, blockId: string): Promise<void> {
  return apiRequest<void>(`/appointments/resources/${id}/blocks/${blockId}`, { method: 'DELETE', companyId })
}
