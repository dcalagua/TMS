import { apiRequest } from './httpClient'
import type { PageResponse } from './pageResponse'

/** Mirrors the backend's `DestinationType` enum (`masterdata/domain/DestinationType.java`). */
export type DestinationType = 'CUSTOMER' | 'STORE' | 'BRANCH' | 'HUB' | 'DISTRIBUTION_CENTER' | 'DELIVERY_POINT'

export const DESTINATION_TYPES: DestinationType[] = [
  'CUSTOMER', 'STORE', 'BRANCH', 'HUB', 'DISTRIBUTION_CENTER', 'DELIVERY_POINT',
]

/** Mirrors the backend's `DestinationView` record. */
export interface DestinationView {
  id: string
  code: string
  name: string
  type: DestinationType
  address: string | null
  addressReference: string | null
  district: string | null
  province: string | null
  department: string | null
  country: string
  latitude: number | null
  longitude: number | null
  zoneId: string | null
  zoneCode: string | null
  zoneName: string | null
  serviceTimeMinutes: number
  externalReference: string | null
  active: boolean
  createdAt: string
  updatedAt: string
}

/** Mirrors the backend's `DestinationRequest` record - shared shape for create and update. */
export interface DestinationRequest {
  code: string
  name: string
  type: DestinationType
  address?: string | null
  addressReference?: string | null
  district?: string | null
  province?: string | null
  department?: string | null
  country: string
  latitude?: number | null
  longitude?: number | null
  zoneId?: string | null
  serviceTimeMinutes: number
  externalReference?: string | null
}

export interface DestinationListParams {
  companyId: string
  page?: number
  size?: number
  sort?: string
  code?: string
  name?: string
  /** One free-text term matched against the code OR the name - what the lookup field sends.
   * `code`/`name` narrow one field each and remain what the filter bar uses. */
  search?: string
  type?: DestinationType
  zoneId?: string
  active?: boolean
  signal?: AbortSignal
}

export function fetchDestinations(params: DestinationListParams): Promise<PageResponse<DestinationView>> {
  const { companyId, signal, ...query } = params
  return apiRequest<PageResponse<DestinationView>>('/masterdata/destinations', { companyId, signal, query })
}

export function fetchDestination(companyId: string, id: string, signal?: AbortSignal): Promise<DestinationView> {
  return apiRequest<DestinationView>(`/masterdata/destinations/${id}`, { companyId, signal })
}

export function createDestination(companyId: string, request: DestinationRequest): Promise<DestinationView> {
  return apiRequest<DestinationView>('/masterdata/destinations', { method: 'POST', companyId, body: request })
}

export function updateDestination(
  companyId: string, id: string, request: DestinationRequest,
): Promise<DestinationView> {
  return apiRequest<DestinationView>(`/masterdata/destinations/${id}`, { method: 'PUT', companyId, body: request })
}

export function activateDestination(companyId: string, id: string): Promise<DestinationView> {
  return apiRequest<DestinationView>(`/masterdata/destinations/${id}/activate`, { method: 'POST', companyId })
}

export function deactivateDestination(companyId: string, id: string): Promise<DestinationView> {
  return apiRequest<DestinationView>(`/masterdata/destinations/${id}/deactivate`, { method: 'POST', companyId })
}
