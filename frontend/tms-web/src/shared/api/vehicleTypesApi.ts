import { apiRequest } from './httpClient'
import type { PageResponse } from './pageResponse'

/** Mirrors the backend's `VehicleBodyType` enum (`fleet/domain/VehicleBodyType.java`). */
export type VehicleBodyType = 'DRY_VAN' | 'REFRIGERATED' | 'FLATBED' | 'TANKER' | 'CONTAINER' | 'CURTAIN_SIDER' | 'OTHER'

export const VEHICLE_BODY_TYPES: VehicleBodyType[] = [
  'DRY_VAN', 'REFRIGERATED', 'FLATBED', 'TANKER', 'CONTAINER', 'CURTAIN_SIDER', 'OTHER',
]

export const VEHICLE_BODY_TYPE_LABELS: Record<VehicleBodyType, string> = {
  DRY_VAN: 'Dry van',
  REFRIGERATED: 'Refrigerated',
  FLATBED: 'Flatbed',
  TANKER: 'Tanker',
  CONTAINER: 'Container',
  CURTAIN_SIDER: 'Curtain sider',
  OTHER: 'Other',
}

/** Mirrors the backend's `VehicleTypeView` record. Units are explicit in every field name - never mixed. */
export interface VehicleTypeView {
  id: string
  code: string
  name: string
  maxWeightKg: number
  maxVolumeM3: number
  maxPallets: number
  lengthM: number | null
  widthM: number | null
  heightM: number | null
  bodyType: VehicleBodyType | null
  temperatureControlled: boolean
  minTemperatureCelsius: number | null
  maxTemperatureCelsius: number | null
  axles: number | null
  active: boolean
  createdAt: string
  updatedAt: string
}

/** Mirrors the backend's `VehicleTypeRequest` record - shared shape for create and update. */
export interface VehicleTypeRequest {
  code: string
  name: string
  maxWeightKg: number
  maxVolumeM3: number
  maxPallets: number
  lengthM?: number | null
  widthM?: number | null
  heightM?: number | null
  bodyType?: VehicleBodyType | null
  temperatureControlled: boolean
  minTemperatureCelsius?: number | null
  maxTemperatureCelsius?: number | null
  axles?: number | null
}

export interface VehicleTypeListParams {
  companyId: string
  page?: number
  size?: number
  sort?: string
  code?: string
  name?: string
  bodyType?: VehicleBodyType
  active?: boolean
  signal?: AbortSignal
}

export function fetchVehicleTypes(params: VehicleTypeListParams): Promise<PageResponse<VehicleTypeView>> {
  const { companyId, signal, ...query } = params
  return apiRequest<PageResponse<VehicleTypeView>>('/fleet/vehicle-types', { companyId, signal, query })
}

export function fetchVehicleType(companyId: string, id: string, signal?: AbortSignal): Promise<VehicleTypeView> {
  return apiRequest<VehicleTypeView>(`/fleet/vehicle-types/${id}`, { companyId, signal })
}

export function createVehicleType(companyId: string, request: VehicleTypeRequest): Promise<VehicleTypeView> {
  return apiRequest<VehicleTypeView>('/fleet/vehicle-types', { method: 'POST', companyId, body: request })
}

export function updateVehicleType(
  companyId: string, id: string, request: VehicleTypeRequest,
): Promise<VehicleTypeView> {
  return apiRequest<VehicleTypeView>(`/fleet/vehicle-types/${id}`, { method: 'PUT', companyId, body: request })
}

export function activateVehicleType(companyId: string, id: string): Promise<VehicleTypeView> {
  return apiRequest<VehicleTypeView>(`/fleet/vehicle-types/${id}/activate`, { method: 'POST', companyId })
}

export function deactivateVehicleType(companyId: string, id: string): Promise<VehicleTypeView> {
  return apiRequest<VehicleTypeView>(`/fleet/vehicle-types/${id}/deactivate`, { method: 'POST', companyId })
}
