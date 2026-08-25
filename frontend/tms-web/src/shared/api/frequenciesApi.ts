import { apiRequest } from './httpClient'
import type { PageResponse } from './pageResponse'

/** Mirrors the backend's `FrequencyView.WeeklyRuleView` record. ISO-8601: 1=Monday..7=Sunday. */
export interface WeeklyRuleView {
  dayOfWeek: number
  enabled: boolean
  cutoffTime: string | null
  leadTimeDays: number | null
}

/** Mirrors the backend's `FrequencyRequest.WeeklyRuleRequest` record. */
export interface WeeklyRuleRequest {
  dayOfWeek: number
  enabled: boolean
  cutoffTime?: string | null
  leadTimeDays?: number | null
}

/** Mirrors the backend's `FrequencyView` record. */
export interface FrequencyView {
  id: string
  code: string
  name: string
  description: string | null
  active: boolean
  weeklyRules: WeeklyRuleView[]
  createdAt: string
  updatedAt: string
}

/** Mirrors the backend's `FrequencyRequest` record - shared shape for create and update. The
 * whole weekly cadence is sent every time (`FrequencyService.replaceWeeklyRules`), not a delta. */
export interface FrequencyRequest {
  code: string
  name: string
  description?: string | null
  weeklyRules: WeeklyRuleRequest[]
}

export interface FrequencyListParams {
  companyId: string
  page?: number
  size?: number
  sort?: string
  code?: string
  name?: string
  active?: boolean
  signal?: AbortSignal
}

export function fetchFrequencies(params: FrequencyListParams): Promise<PageResponse<FrequencyView>> {
  const { companyId, signal, ...query } = params
  return apiRequest<PageResponse<FrequencyView>>('/masterdata/frequencies', { companyId, signal, query })
}

export function fetchFrequency(companyId: string, id: string, signal?: AbortSignal): Promise<FrequencyView> {
  return apiRequest<FrequencyView>(`/masterdata/frequencies/${id}`, { companyId, signal })
}

export function createFrequency(companyId: string, request: FrequencyRequest): Promise<FrequencyView> {
  return apiRequest<FrequencyView>('/masterdata/frequencies', { method: 'POST', companyId, body: request })
}

export function updateFrequency(companyId: string, id: string, request: FrequencyRequest): Promise<FrequencyView> {
  return apiRequest<FrequencyView>(`/masterdata/frequencies/${id}`, { method: 'PUT', companyId, body: request })
}

export function activateFrequency(companyId: string, id: string): Promise<FrequencyView> {
  return apiRequest<FrequencyView>(`/masterdata/frequencies/${id}/activate`, { method: 'POST', companyId })
}

export function deactivateFrequency(companyId: string, id: string): Promise<FrequencyView> {
  return apiRequest<FrequencyView>(`/masterdata/frequencies/${id}/deactivate`, { method: 'POST', companyId })
}

/** Mirrors the backend's `FrequencyExceptionView` record. `cutoffTimeOverride` is `null` when the
 * date does not replace the weekly rule's cutoff, which is the ordinary case. */
export interface FrequencyExceptionView {
  id: string
  exceptionDate: string
  serviceOverride: boolean
  cutoffTimeOverride: string | null
  note: string | null
  createdAt: string
}

/** Mirrors the backend's `FrequencyExceptionRequest` record. `cutoffTimeOverride` is only valid
 * together with `serviceOverride: true` - a closed date has no cutoff - and the backend answers
 * 400 if it is sent anyway. */
export interface FrequencyExceptionRequest {
  exceptionDate: string
  serviceOverride: boolean
  cutoffTimeOverride?: string | null
  note?: string | null
}

export function fetchFrequencyExceptions(
  companyId: string, frequencyId: string, signal?: AbortSignal,
): Promise<FrequencyExceptionView[]> {
  return apiRequest<FrequencyExceptionView[]>(`/masterdata/frequencies/${frequencyId}/exceptions`, {
    companyId, signal,
  })
}

export function createFrequencyException(
  companyId: string, frequencyId: string, request: FrequencyExceptionRequest,
): Promise<FrequencyExceptionView> {
  return apiRequest<FrequencyExceptionView>(`/masterdata/frequencies/${frequencyId}/exceptions`, {
    method: 'POST', companyId, body: request,
  })
}

export function deleteFrequencyException(companyId: string, frequencyId: string, exceptionId: string): Promise<void> {
  return apiRequest<void>(`/masterdata/frequencies/${frequencyId}/exceptions/${exceptionId}`, {
    method: 'DELETE', companyId,
  })
}
