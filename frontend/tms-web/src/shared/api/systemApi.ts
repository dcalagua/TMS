import { apiRequest } from './httpClient'

export interface SystemInfo {
  application: string
  version: string
  status: string
  profiles: string[]
  timestamp: string
}

/** Public backend identification endpoint - the reachability check for the API. */
export function fetchSystemInfo(signal?: AbortSignal): Promise<SystemInfo> {
  return apiRequest<SystemInfo>('/system/info', { signal })
}
