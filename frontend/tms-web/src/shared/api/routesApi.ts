import { apiRequest } from './httpClient'
import type { PageResponse } from './pageResponse'

/** Mirrors the backend's `RouteView` record - the list-row shape (a stop count, not the stops
 * themselves; see `RouteView.java`'s class comment for why list and detail differ). */
export interface RouteView {
  id: string
  code: string
  name: string
  originId: string
  originCode: string | null
  originName: string | null
  zoneId: string | null
  zoneCode: string | null
  zoneName: string | null
  frequencyId: string | null
  frequencyCode: string | null
  frequencyName: string | null
  referenceDistanceKm: number | null
  referenceDurationMinutes: number | null
  stopCount: number
  active: boolean
  createdAt: string
  updatedAt: string
}

/** Mirrors the backend's `RouteDetailView.RouteStopView` record. */
export interface RouteStopView {
  destinationId: string
  destinationCode: string | null
  destinationName: string | null
  sequence: number
}

/** Mirrors the backend's `RouteDetailView` record - returned by get/create/update/activate/deactivate. */
export interface RouteDetailView {
  id: string
  code: string
  name: string
  originId: string
  originCode: string | null
  originName: string | null
  zoneId: string | null
  zoneCode: string | null
  zoneName: string | null
  frequencyId: string | null
  frequencyCode: string | null
  frequencyName: string | null
  referenceDistanceKm: number | null
  referenceDurationMinutes: number | null
  stops: RouteStopView[]
  active: boolean
  createdAt: string
  updatedAt: string
}

/** Mirrors the backend's `RouteRequest` record - shared shape for create and update.
 * `destinationIds` order IS the stop sequence; the server assigns 1..N from array order. */
export interface RouteRequest {
  code: string
  name: string
  originId: string
  zoneId?: string | null
  frequencyId?: string | null
  referenceDistanceKm?: number | null
  referenceDurationMinutes?: number | null
  destinationIds: string[]
}

export interface RouteListParams {
  companyId: string
  page?: number
  size?: number
  sort?: string
  code?: string
  name?: string
  originId?: string
  zoneId?: string
  active?: boolean
  signal?: AbortSignal
}

export function fetchRoutes(params: RouteListParams): Promise<PageResponse<RouteView>> {
  const { companyId, signal, ...query } = params
  return apiRequest<PageResponse<RouteView>>('/masterdata/routes', { companyId, signal, query })
}

export function fetchRoute(companyId: string, id: string, signal?: AbortSignal): Promise<RouteDetailView> {
  return apiRequest<RouteDetailView>(`/masterdata/routes/${id}`, { companyId, signal })
}

export function createRoute(companyId: string, request: RouteRequest): Promise<RouteDetailView> {
  return apiRequest<RouteDetailView>('/masterdata/routes', { method: 'POST', companyId, body: request })
}

export function updateRoute(companyId: string, id: string, request: RouteRequest): Promise<RouteDetailView> {
  return apiRequest<RouteDetailView>(`/masterdata/routes/${id}`, { method: 'PUT', companyId, body: request })
}

export function activateRoute(companyId: string, id: string): Promise<RouteDetailView> {
  return apiRequest<RouteDetailView>(`/masterdata/routes/${id}/activate`, { method: 'POST', companyId })
}

export function deactivateRoute(companyId: string, id: string): Promise<RouteDetailView> {
  return apiRequest<RouteDetailView>(`/masterdata/routes/${id}/deactivate`, { method: 'POST', companyId })
}
