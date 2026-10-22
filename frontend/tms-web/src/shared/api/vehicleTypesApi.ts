import { apiRequest } from './httpClient'
import type { FuelType } from './emissionsApi'
import type { PageResponse } from './pageResponse'

/** Mirrors the backend's `VehicleBodyType` enum (`fleet/domain/VehicleBodyType.java`). */
export type VehicleBodyType = 'DRY_VAN' | 'REFRIGERATED' | 'FLATBED' | 'TANKER' | 'CONTAINER' | 'CURTAIN_SIDER' | 'OTHER'

export const VEHICLE_BODY_TYPES: VehicleBodyType[] = [
  'DRY_VAN', 'REFRIGERATED', 'FLATBED', 'TANKER', 'CONTAINER', 'CURTAIN_SIDER', 'OTHER',
]

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
  /** Parte de largo × ancho que una carga puede cubrir (V62). Null: el de la empresa, o el del despliegue. */
  loadFloorFillFactor?: number | null
  /** V73: con qué combustible se midió el factor. */
  fuelType?: FuelType | null
  /** V73: kg CO₂e por km. Null: sus viajes no tienen huella («sin datos»), nunca cero. */
  emissionFactorKgCo2ePerKm?: number | null
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
  /** 0 < x ≤ 1. Null: el de la empresa. Enviarlo siempre: un PUT que lo omite lo borra. */
  loadFloorFillFactor?: number | null
  /** V73. Enviarlos siempre: un PUT que los omite los borra. */
  fuelType?: FuelType | null
  emissionFactorKgCo2ePerKm?: number | null
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

// --- bulk import -----------------------------------------------------------------------

/** The `VehicleTypeImportController`'s `@RequestMapping` - shared by template/preview/apply. */
export const VEHICLE_TYPE_IMPORT_BASE_PATH = '/fleet/vehicle-types/import'

/** Mirrors the backend's `VehicleTypeImportPreview` record. */
export interface VehicleTypeImportPreview {
  code: string
  outcome: 'CREATE' | 'SKIPPED_DUPLICATE' | 'REJECTED'
  rowNumber: number
  name: string
  maxWeightKg: number | null
  maxVolumeM3: number | null
  maxPallets: number
  lengthM: number | null
  widthM: number | null
  heightM: number | null
  bodyType: VehicleBodyType | null
  temperatureControlled: boolean
  minTemperatureCelsius: number | null
  maxTemperatureCelsius: number | null
  axles: number | null
}
