import { apiRequest } from './httpClient'
import type { PageResponse } from './pageResponse'

/** Mirrors the backend's `CarrierView` record. */
export interface CarrierView {
  id: string
  code: string
  businessName: string
  taxIdType: string
  taxIdValue: string
  contactName: string | null
  phone: string | null
  email: string | null
  externalReference: string | null
  active: boolean
  createdAt: string
  updatedAt: string
}

/** Mirrors the backend's `CarrierRequest` record - shared shape for create and update. */
export interface CarrierRequest {
  code: string
  businessName: string
  taxIdType: string
  taxIdValue: string
  contactName?: string | null
  phone?: string | null
  email?: string | null
  externalReference?: string | null
}

export interface CarrierListParams {
  companyId: string
  page?: number
  size?: number
  sort?: string
  code?: string
  businessName?: string
  taxIdValue?: string
  active?: boolean
  signal?: AbortSignal
}

export function fetchCarriers(params: CarrierListParams): Promise<PageResponse<CarrierView>> {
  const { companyId, signal, ...query } = params
  return apiRequest<PageResponse<CarrierView>>('/fleet/carriers', { companyId, signal, query })
}

export function fetchCarrier(companyId: string, id: string, signal?: AbortSignal): Promise<CarrierView> {
  return apiRequest<CarrierView>(`/fleet/carriers/${id}`, { companyId, signal })
}

export function createCarrier(companyId: string, request: CarrierRequest): Promise<CarrierView> {
  return apiRequest<CarrierView>('/fleet/carriers', { method: 'POST', companyId, body: request })
}

export function updateCarrier(companyId: string, id: string, request: CarrierRequest): Promise<CarrierView> {
  return apiRequest<CarrierView>(`/fleet/carriers/${id}`, { method: 'PUT', companyId, body: request })
}

export function activateCarrier(companyId: string, id: string): Promise<CarrierView> {
  return apiRequest<CarrierView>(`/fleet/carriers/${id}/activate`, { method: 'POST', companyId })
}

export function deactivateCarrier(companyId: string, id: string): Promise<CarrierView> {
  return apiRequest<CarrierView>(`/fleet/carriers/${id}/deactivate`, { method: 'POST', companyId })
}
