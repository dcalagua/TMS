import { apiRequest } from './httpClient'

/** Mirrors the backend's `LocationFrequencyView` record. */
export interface LocationFrequencyView {
  id: string
  frequencyId: string
  frequencyCode: string | null
  frequencyName: string | null
  effectiveFrom: string | null
  effectiveTo: string | null
  active: boolean
  createdAt: string
}

/** Mirrors the backend's `LocationFrequencyRequest` record. */
export interface LocationFrequencyRequest {
  frequencyId: string
  effectiveFrom?: string | null
  effectiveTo?: string | null
}

/** Mirrors the backend's `LocationFrequencyDateRangeRequest` record - `frequencyId` cannot be changed once created. */
export interface LocationFrequencyDateRangeRequest {
  effectiveFrom?: string | null
  effectiveTo?: string | null
}

/** Mirrors the backend's `EligibilityView` record. */
export interface EligibilityView {
  date: string
  eligible: boolean
  reason: string
  frequencyId: string | null
  cutoffTime: string | null
  leadTimeDays: number | null
}

export function fetchLocationFrequencies(companyId: string, locationId: string): Promise<LocationFrequencyView[]> {
  return apiRequest<LocationFrequencyView[]>(`/masterdata/locations/${locationId}/frequencies`, { companyId })
}

export function createLocationFrequency(
  companyId: string, locationId: string, request: LocationFrequencyRequest,
): Promise<LocationFrequencyView> {
  return apiRequest<LocationFrequencyView>(`/masterdata/locations/${locationId}/frequencies`, {
    method: 'POST', companyId, body: request,
  })
}

export function updateLocationFrequency(
  companyId: string, locationId: string, associationId: string, request: LocationFrequencyDateRangeRequest,
): Promise<LocationFrequencyView> {
  return apiRequest<LocationFrequencyView>(`/masterdata/locations/${locationId}/frequencies/${associationId}`, {
    method: 'PUT', companyId, body: request,
  })
}

export function activateLocationFrequency(
  companyId: string, locationId: string, associationId: string,
): Promise<LocationFrequencyView> {
  return apiRequest<LocationFrequencyView>(
    `/masterdata/locations/${locationId}/frequencies/${associationId}/activate`, { method: 'POST', companyId },
  )
}

export function deactivateLocationFrequency(
  companyId: string, locationId: string, associationId: string,
): Promise<LocationFrequencyView> {
  return apiRequest<LocationFrequencyView>(
    `/masterdata/locations/${locationId}/frequencies/${associationId}/deactivate`, { method: 'POST', companyId },
  )
}

export function deleteLocationFrequency(companyId: string, locationId: string, associationId: string): Promise<void> {
  return apiRequest<void>(`/masterdata/locations/${locationId}/frequencies/${associationId}`, {
    method: 'DELETE', companyId,
  })
}

export function fetchLocationEligibility(companyId: string, locationId: string, date: string): Promise<EligibilityView> {
  return apiRequest<EligibilityView>(`/masterdata/locations/${locationId}/eligibility`, {
    companyId, query: { date },
  })
}
