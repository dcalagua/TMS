import { apiRequest } from './httpClient'
import type { PageResponse } from './pageResponse'

/** Mirrors the backend's `OriginType` enum (`masterdata/domain/OriginType.java`). */
export type OriginType = 'WAREHOUSE' | 'DISTRIBUTION_CENTER' | 'PLANT' | 'HUB' | 'OTHER'

export const ORIGIN_TYPES: OriginType[] = ['WAREHOUSE', 'DISTRIBUTION_CENTER', 'PLANT', 'HUB', 'OTHER']

/** Mirrors the backend's `OriginView` record. */
export interface OriginView {
  id: string
  code: string
  name: string
  type: OriginType
  address: string | null
  latitude: number | null
  longitude: number | null
  timeZone: string
  externalReference: string | null
  active: boolean
  createdAt: string
  updatedAt: string
}

/** Mirrors the backend's `OriginRequest` record - shared shape for create and update. */
export interface OriginRequest {
  code: string
  name: string
  type: OriginType
  address?: string | null
  latitude?: number | null
  longitude?: number | null
  timeZone: string
  externalReference?: string | null
}

export interface OriginListParams {
  companyId: string
  page?: number
  size?: number
  sort?: string
  code?: string
  name?: string
  /** One free-text term matched against the code OR the name - what the lookup field sends.
   * `code`/`name` narrow one field each and remain what the filter bar uses. */
  search?: string
  type?: OriginType
  active?: boolean
  signal?: AbortSignal
}

export function fetchOrigins(params: OriginListParams): Promise<PageResponse<OriginView>> {
  const { companyId, signal, ...query } = params
  return apiRequest<PageResponse<OriginView>>('/masterdata/origins', { companyId, signal, query })
}

export function fetchOrigin(companyId: string, id: string, signal?: AbortSignal): Promise<OriginView> {
  return apiRequest<OriginView>(`/masterdata/origins/${id}`, { companyId, signal })
}

export function createOrigin(companyId: string, request: OriginRequest): Promise<OriginView> {
  return apiRequest<OriginView>('/masterdata/origins', { method: 'POST', companyId, body: request })
}

export function updateOrigin(companyId: string, id: string, request: OriginRequest): Promise<OriginView> {
  return apiRequest<OriginView>(`/masterdata/origins/${id}`, { method: 'PUT', companyId, body: request })
}

export function activateOrigin(companyId: string, id: string): Promise<OriginView> {
  return apiRequest<OriginView>(`/masterdata/origins/${id}/activate`, { method: 'POST', companyId })
}

export function deactivateOrigin(companyId: string, id: string): Promise<OriginView> {
  return apiRequest<OriginView>(`/masterdata/origins/${id}/deactivate`, { method: 'POST', companyId })
}
