import { apiRequest } from './httpClient'
import type { PageResponse } from './pageResponse'

/**
 * Mirrors the backend's `DriverLicenseStatus` enum
 * (`shared/reference/DriverLicenseStatus.java`).
 *
 * Derived server-side and never recomputed here. The browser could subtract two dates just as
 * easily, and that is exactly the problem: the same comparison decides whether an assignment is
 * refused, so a second copy in TypeScript would eventually show a green badge on a driver the
 * endpoint behind the button rejects.
 */
export type DriverLicenseStatus = 'UNRECORDED' | 'VALID' | 'EXPIRING_SOON' | 'EXPIRED'

export const DRIVER_LICENSE_STATUSES: DriverLicenseStatus[] = [
  'VALID', 'EXPIRING_SOON', 'EXPIRED', 'UNRECORDED',
]

/** Mirrors the backend's `DriverView` record. `fullName` and `licenseStatus` are both derived. */
export interface DriverView {
  id: string
  code: string
  firstName: string
  lastName: string
  fullName: string
  documentType: string
  documentNumber: string
  phone: string | null
  licenseNumber: string
  licenseCategory: string | null
  /** The last day the licence is valid, inclusive; null when the company does not record it. */
  licenseExpiresOn: string | null
  licenseStatus: DriverLicenseStatus
  carrierId: string | null
  carrierCode: string | null
  carrierBusinessName: string | null
  active: boolean
  createdAt: string
  updatedAt: string
}

/** Mirrors the backend's `DriverRequest` record - shared shape for create and update.
 *
 * There is no `active` field: activation is its own endpoint, so retiring a driver is never a
 * side effect of correcting their phone number. */
export interface DriverRequest {
  code: string
  firstName: string
  lastName: string
  documentType: string
  documentNumber: string
  phone?: string | null
  licenseNumber: string
  licenseCategory?: string | null
  licenseExpiresOn?: string | null
  carrierId?: string | null
}

export interface DriverListParams {
  companyId: string
  page?: number
  size?: number
  sort?: string
  code?: string
  /** Matched against either half of the name - the backend does not care which one it is. */
  name?: string
  carrierId?: string
  licenseStatus?: DriverLicenseStatus
  active?: boolean
  signal?: AbortSignal
}

export function fetchDrivers(params: DriverListParams): Promise<PageResponse<DriverView>> {
  const { companyId, signal, ...query } = params
  return apiRequest<PageResponse<DriverView>>('/fleet/drivers', { companyId, signal, query })
}

export function fetchDriver(companyId: string, id: string, signal?: AbortSignal): Promise<DriverView> {
  return apiRequest<DriverView>(`/fleet/drivers/${id}`, { companyId, signal })
}

export function createDriver(companyId: string, request: DriverRequest): Promise<DriverView> {
  return apiRequest<DriverView>('/fleet/drivers', { method: 'POST', companyId, body: request })
}

export function updateDriver(companyId: string, id: string, request: DriverRequest): Promise<DriverView> {
  return apiRequest<DriverView>(`/fleet/drivers/${id}`, { method: 'PUT', companyId, body: request })
}

export function activateDriver(companyId: string, id: string): Promise<DriverView> {
  return apiRequest<DriverView>(`/fleet/drivers/${id}/activate`, { method: 'POST', companyId })
}

export function deactivateDriver(companyId: string, id: string): Promise<DriverView> {
  return apiRequest<DriverView>(`/fleet/drivers/${id}/deactivate`, { method: 'POST', companyId })
}
