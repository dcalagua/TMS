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

/** Mirrors the backend's `RouteDetailView.RouteStopView` record.
 *
 * Service time arrives three ways because the editor shows all three: what this stop overrides
 * (`serviceTimeOverrideMinutes`, null when it overrides nothing), what it would inherit
 * (`destinationServiceTimeMinutes`, used as the field's placeholder), and what actually applies
 * (`effectiveServiceTimeMinutes`). Never recompute the third here - the backend owns that rule. */
export interface RouteStopView {
  destinationId: string
  destinationCode: string | null
  destinationName: string | null
  sequence: number
  serviceTimeOverrideMinutes: number | null
  destinationServiceTimeMinutes: number | null
  effectiveServiceTimeMinutes: number | null
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

/** Mirrors the backend's `RouteRequest.RouteStopRequest` record. Omit
 * `serviceTimeOverrideMinutes` to inherit the destination location's service time; `0` is a real
 * override (a drop-and-go stop), not a synonym for omitting it. */
export interface RouteStopRequest {
  destinationId: string
  serviceTimeOverrideMinutes?: number | null
}

/** Mirrors the backend's `RouteRequest` record - shared shape for create and update.
 * `stops` order IS the stop sequence; the server assigns 1..N from array order. The whole list is
 * sent every time, not a delta, so a stop re-sent without an override loses the one it had. */
export interface RouteRequest {
  code: string
  name: string
  originId: string
  zoneId?: string | null
  frequencyId?: string | null
  referenceDistanceKm?: number | null
  referenceDurationMinutes?: number | null
  stops: RouteStopRequest[]
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
