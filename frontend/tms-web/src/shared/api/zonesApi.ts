import { apiRequest } from './httpClient'
import type { PageResponse } from './pageResponse'

/** Mirrors the backend's `ZoneView` record. */
export interface ZoneView {
  id: string
  code: string
  name: string
  description: string | null
  active: boolean
  createdAt: string
  updatedAt: string
}

/** Mirrors the backend's `ZoneRequest` record - shared shape for create and update. */
export interface ZoneRequest {
  code: string
  name: string
  description?: string | null
}

export interface ZoneListParams {
  companyId: string
  page?: number
  size?: number
  sort?: string
  code?: string
  name?: string
  active?: boolean
  signal?: AbortSignal
}

export function fetchZones(params: ZoneListParams): Promise<PageResponse<ZoneView>> {
  const { companyId, signal, ...query } = params
  return apiRequest<PageResponse<ZoneView>>('/masterdata/zones', { companyId, signal, query })
}

export function fetchZone(companyId: string, id: string, signal?: AbortSignal): Promise<ZoneView> {
  return apiRequest<ZoneView>(`/masterdata/zones/${id}`, { companyId, signal })
}

export function createZone(companyId: string, request: ZoneRequest): Promise<ZoneView> {
  return apiRequest<ZoneView>('/masterdata/zones', { method: 'POST', companyId, body: request })
}

export function updateZone(companyId: string, id: string, request: ZoneRequest): Promise<ZoneView> {
  return apiRequest<ZoneView>(`/masterdata/zones/${id}`, { method: 'PUT', companyId, body: request })
}

export function activateZone(companyId: string, id: string): Promise<ZoneView> {
  return apiRequest<ZoneView>(`/masterdata/zones/${id}/activate`, { method: 'POST', companyId })
}

export function deactivateZone(companyId: string, id: string): Promise<ZoneView> {
  return apiRequest<ZoneView>(`/masterdata/zones/${id}/deactivate`, { method: 'POST', companyId })
}
