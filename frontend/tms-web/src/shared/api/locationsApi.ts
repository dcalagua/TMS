import { apiRequest } from './httpClient'
import type { PageResponse } from './pageResponse'

/** Mirrors the backend's `LocationType` enum (`masterdata/domain/LocationType.java`). */
export type LocationType =
  | 'WAREHOUSE'
  | 'DISTRIBUTION_CENTER'
  | 'PLANT'
  | 'HUB'
  | 'OTHER'
  | 'CUSTOMER'
  | 'STORE'
  | 'BRANCH'
  | 'DELIVERY_POINT'

export const LOCATION_TYPES: LocationType[] = [
  'WAREHOUSE', 'DISTRIBUTION_CENTER', 'PLANT', 'HUB', 'STORE', 'CUSTOMER', 'BRANCH', 'DELIVERY_POINT', 'OTHER',
]

/**
 * How a location may be used in a movement - mirrors the backend's `LocationRole`
 * (`masterdata/domain/LocationRole.java`). Not what the place is: that is `LocationType`.
 *
 * A location may hold both. The same store is the destination of a delivery and the origin of
 * the return, as one record with one address and one pair of coordinates.
 */
export type LocationRole = 'ORIGIN' | 'DESTINATION'

export const LOCATION_ROLES: LocationRole[] = ['ORIGIN', 'DESTINATION']

/** Mirrors the backend's `LocationView` record. */
export interface LocationView {
  id: string
  code: string
  name: string
  type: LocationType
  roles: LocationRole[]
  address: string | null
  addressReference: string | null
  district: string | null
  province: string | null
  department: string | null
  country: string
  timeZone: string
  latitude: number | null
  longitude: number | null
  zoneId: string | null
  zoneCode: string | null
  zoneName: string | null
  serviceTimeMinutes: number
  /**
   * El radio del geocerco de este sitio, en metros, o null cuando no tiene (migración V43).
   *
   * Sólo lectura aquí: se configura por su propio endpoint, porque se define una vez al dar de alta
   * el sitio y no se edita cada vez que alguien corrige una dirección.
   *
   * **ADR-007 sigue en pie**: una posición dentro de este círculo informa a una persona y no mueve
   * ningún ciclo de vida. La llegada que vale sigue siendo la que registra alguien.
   */
  geofenceRadiusM: number | null
  externalSystem: string | null
  externalReference: string | null
  active: boolean
  createdAt: string
  updatedAt: string
}

/** Mirrors the backend's `LocationRequest` record - shared shape for create and update. */
export interface LocationRequest {
  code: string
  name: string
  type: LocationType
  roles: LocationRole[]
  address?: string | null
  addressReference?: string | null
  district?: string | null
  province?: string | null
  department?: string | null
  country: string
  timeZone: string
  latitude?: number | null
  longitude?: number | null
  zoneId?: string | null
  serviceTimeMinutes: number
  externalSystem?: string | null
  externalReference?: string | null
}

export interface LocationListParams {
  companyId: string
  page?: number
  size?: number
  sort?: string
  /** One box over code, name and external reference - the backend searches all three. */
  search?: string
  type?: LocationType
  role?: LocationRole
  zoneId?: string
  active?: boolean
  signal?: AbortSignal
}

export function fetchLocations(params: LocationListParams): Promise<PageResponse<LocationView>> {
  const { companyId, signal, ...query } = params
  return apiRequest<PageResponse<LocationView>>('/masterdata/locations', { companyId, signal, query })
}

export function fetchLocation(companyId: string, id: string, signal?: AbortSignal): Promise<LocationView> {
  return apiRequest<LocationView>(`/masterdata/locations/${id}`, { companyId, signal })
}

export function createLocation(companyId: string, request: LocationRequest): Promise<LocationView> {
  return apiRequest<LocationView>('/masterdata/locations', { method: 'POST', companyId, body: request })
}

export function updateLocation(companyId: string, id: string, request: LocationRequest): Promise<LocationView> {
  return apiRequest<LocationView>(`/masterdata/locations/${id}`, { method: 'PUT', companyId, body: request })
}

export function activateLocation(companyId: string, id: string): Promise<LocationView> {
  return apiRequest<LocationView>(`/masterdata/locations/${id}/activate`, { method: 'POST', companyId })
}

export function deactivateLocation(companyId: string, id: string): Promise<LocationView> {
  return apiRequest<LocationView>(`/masterdata/locations/${id}/deactivate`, { method: 'POST', companyId })
}

// --- bulk import -----------------------------------------------------------------------

/** The `LocationImportController`'s `@RequestMapping` - shared by template/preview/apply. */
export const LOCATION_IMPORT_BASE_PATH = '/masterdata/locations/import'

/** Mirrors the backend's `LocationImportPreview` record. */
export interface LocationImportPreview {
  code: string
  outcome: 'CREATE' | 'SKIPPED_DUPLICATE' | 'REJECTED'
  rowNumber: number
  name: string
  type: LocationType
  roles: LocationRole[]
  zoneCode: string | null
  country: string
  timeZone: string
  latitude: number | null
  longitude: number | null
  serviceTimeMinutes: number
  externalSystem: string | null
  externalReference: string | null
}

/**
 * Fija o quita el círculo alrededor de un sitio (migración V43, ADR-011).
 *
 * `radiusMetres` null **borra** el geocerco - por eso viaja en un cuerpo y no como parámetro de
 * consulta, que no podría distinguir "quítalo" de "no lo mandé".
 */
export function setLocationGeofence(
  companyId: string, id: string, radiusMetres: number | null,
): Promise<LocationView> {
  return apiRequest<LocationView>(`/masterdata/locations/${id}/geofence`, {
    method: 'PUT', companyId, body: { radiusMetres },
  })
}
