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

/** Mirrors the backend's `LocationRole` enum (`masterdata/domain/LocationRole.java`). */
export type LocationRole = 'ORIGIN' | 'SHIP_TO' | 'STORE' | 'DC' | 'PLANT' | 'HUB' | 'OTHER'

export const LOCATION_ROLES: LocationRole[] = ['ORIGIN', 'SHIP_TO', 'STORE', 'DC', 'PLANT', 'HUB', 'OTHER']

/**
 * The two roles that do something rather than describe something: they decide whether the
 * location has an origin and/or a destination projection, and therefore whether it can be used
 * as an order's origin or destination at all. See `docs/architecture/ADR_LOCATION_MODEL.md`.
 */
export const PROJECTING_ROLES: LocationRole[] = ['ORIGIN', 'SHIP_TO']

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
  externalSystem: string | null
  externalReference: string | null
  /** Id of the `tms.origin` projection, or `null` when the location has no ORIGIN role. */
  originId: string | null
  /** Id of the `tms.destination` projection, or `null` when the location has no SHIP_TO role. */
  destinationId: string | null
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
