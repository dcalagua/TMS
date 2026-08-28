import { apiRequest } from './httpClient'

/**
 * Mirrors the backend's `UnavailabilityReason` (migración V42).
 *
 * Cada motivo describe **un** tipo de recurso, y el backend rechaza la combinación equivocada:
 * un camión de vacaciones y un conductor en reparación son ambos absurdos. `OTHER` describe los
 * dos, porque una operación siempre tiene un motivo que nadie anticipó.
 */
export const VEHICLE_UNAVAILABILITY_REASONS = ['MAINTENANCE', 'REPAIR', 'INSPECTION', 'OTHER'] as const
export const DRIVER_UNAVAILABILITY_REASONS = ['ABSENCE', 'HOLIDAY', 'TRAINING', 'MEDICAL', 'OTHER'] as const

export type VehicleUnavailabilityReason = (typeof VEHICLE_UNAVAILABILITY_REASONS)[number]
export type DriverUnavailabilityReason = (typeof DRIVER_UNAVAILABILITY_REASONS)[number]
export type UnavailabilityReason = VehicleUnavailabilityReason | DriverUnavailabilityReason

/** Mirrors `UnavailabilityView`. Exactamente uno de `vehicleId` y `driverId` viene informado. */
export interface UnavailabilityView {
  id: string
  vehicleId: string | null
  driverId: string | null
  reason: UnavailabilityReason
  /** ISO-8601 con offset. Instantes absolutos: "libre el martes que viene", no "libre los martes". */
  startsAt: string
  /** Exclusivo, igual que el rango del constraint: un camión que sale del taller a las 12:00 está libre a las 12:00. */
  endsAt: string
  notes: string | null
}

export interface UnavailabilityRequest {
  reason: UnavailabilityReason
  startsAt: string
  endsAt: string
  notes?: string | null
}

/**
 * Mirrors `DriverShiftView`. Horas **locales del depósito**: el backend las guarda como minutos
 * desde la medianoche local, no como un `time`, para que ninguna zona horaria las desplace.
 */
export interface DriverShiftView {
  id: string
  driverId: string
  dayOfWeek: 'MONDAY' | 'TUESDAY' | 'WEDNESDAY' | 'THURSDAY' | 'FRIDAY' | 'SATURDAY' | 'SUNDAY'
  startsAt: string
  endsAt: string
}

export interface DriverShiftRequest {
  dayOfWeek: DriverShiftView['dayOfWeek']
  startsAt: string
  endsAt: string
}

export function listVehicleUnavailability(companyId: string, vehicleId: string): Promise<UnavailabilityView[]> {
  return apiRequest<UnavailabilityView[]>(`/fleet/vehicles/${vehicleId}/unavailability`, { companyId })
}

export function blockVehicle(
  companyId: string, vehicleId: string, request: UnavailabilityRequest,
): Promise<UnavailabilityView> {
  return apiRequest<UnavailabilityView>(`/fleet/vehicles/${vehicleId}/unavailability`, {
    method: 'POST', companyId, body: request,
  })
}

export function releaseVehicle(companyId: string, vehicleId: string, blockId: string): Promise<void> {
  return apiRequest<void>(`/fleet/vehicles/${vehicleId}/unavailability/${blockId}`, {
    method: 'DELETE', companyId,
  })
}

export function listDriverUnavailability(companyId: string, driverId: string): Promise<UnavailabilityView[]> {
  return apiRequest<UnavailabilityView[]>(`/fleet/drivers/${driverId}/unavailability`, { companyId })
}

export function blockDriver(
  companyId: string, driverId: string, request: UnavailabilityRequest,
): Promise<UnavailabilityView> {
  return apiRequest<UnavailabilityView>(`/fleet/drivers/${driverId}/unavailability`, {
    method: 'POST', companyId, body: request,
  })
}

export function releaseDriver(companyId: string, driverId: string, blockId: string): Promise<void> {
  return apiRequest<void>(`/fleet/drivers/${driverId}/unavailability/${blockId}`, {
    method: 'DELETE', companyId,
  })
}

export function listDriverShifts(companyId: string, driverId: string): Promise<DriverShiftView[]> {
  return apiRequest<DriverShiftView[]>(`/fleet/drivers/${driverId}/shifts`, { companyId })
}

export function setDriverShift(
  companyId: string, driverId: string, request: DriverShiftRequest,
): Promise<DriverShiftView> {
  return apiRequest<DriverShiftView>(`/fleet/drivers/${driverId}/shifts`, {
    method: 'PUT', companyId, body: request,
  })
}

export function clearDriverShift(companyId: string, driverId: string, shiftId: string): Promise<void> {
  return apiRequest<void>(`/fleet/drivers/${driverId}/shifts/${shiftId}`, { method: 'DELETE', companyId })
}
