import { apiRequest } from './httpClient'
import type { PageResponse } from './pageResponse'

/** Mirrors the backend's `VehicleAvailabilityStatus` enum (`fleet/domain/VehicleAvailabilityStatus.java`). */
export type VehicleAvailabilityStatus = 'AVAILABLE' | 'IN_MAINTENANCE' | 'OUT_OF_SERVICE'

export const VEHICLE_AVAILABILITY_STATUSES: VehicleAvailabilityStatus[] = [
  'AVAILABLE', 'IN_MAINTENANCE', 'OUT_OF_SERVICE',
]

/**
 * Mirrors the backend's `VehicleView` record. `effective*` fields are already resolved
 * server-side by `EffectiveCapacityResolver` (vehicle override first, otherwise the vehicle
 * type's default) - the frontend never re-implements that rule.
 */
export interface VehicleView {
  id: string
  code: string
  licensePlate: string
  carrierId: string | null
  carrierCode: string | null
  carrierBusinessName: string | null
  vehicleTypeId: string
  vehicleTypeCode: string | null
  vehicleTypeName: string | null
  maxWeightOverrideKg: number | null
  maxVolumeOverrideM3: number | null
  maxPalletsOverride: number | null
  effectiveMaxWeightKg: number
  effectiveMaxVolumeM3: number
  effectiveMaxPallets: number
  availabilityStatus: VehicleAvailabilityStatus
  active: boolean
  createdAt: string
  updatedAt: string
}

/** Mirrors the backend's `VehicleRequest` record - shared shape for create and update. */
export interface VehicleRequest {
  code: string
  licensePlate: string
  carrierId?: string | null
  vehicleTypeId: string
  maxWeightOverrideKg?: number | null
  maxVolumeOverrideM3?: number | null
  maxPalletsOverride?: number | null
  availabilityStatus: VehicleAvailabilityStatus
}

export interface VehicleListParams {
  companyId: string
  page?: number
  size?: number
  sort?: string
  code?: string
  licensePlate?: string
  carrierId?: string
  vehicleTypeId?: string
  availabilityStatus?: VehicleAvailabilityStatus
  active?: boolean
  signal?: AbortSignal
}

export function fetchVehicles(params: VehicleListParams): Promise<PageResponse<VehicleView>> {
  const { companyId, signal, ...query } = params
  return apiRequest<PageResponse<VehicleView>>('/fleet/vehicles', { companyId, signal, query })
}

export function fetchVehicle(companyId: string, id: string, signal?: AbortSignal): Promise<VehicleView> {
  return apiRequest<VehicleView>(`/fleet/vehicles/${id}`, { companyId, signal })
}

export function createVehicle(companyId: string, request: VehicleRequest): Promise<VehicleView> {
  return apiRequest<VehicleView>('/fleet/vehicles', { method: 'POST', companyId, body: request })
}

export function updateVehicle(companyId: string, id: string, request: VehicleRequest): Promise<VehicleView> {
  return apiRequest<VehicleView>(`/fleet/vehicles/${id}`, { method: 'PUT', companyId, body: request })
}

export function activateVehicle(companyId: string, id: string): Promise<VehicleView> {
  return apiRequest<VehicleView>(`/fleet/vehicles/${id}/activate`, { method: 'POST', companyId })
}

export function deactivateVehicle(companyId: string, id: string): Promise<VehicleView> {
  return apiRequest<VehicleView>(`/fleet/vehicles/${id}/deactivate`, { method: 'POST', companyId })
}
