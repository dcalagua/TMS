import { apiRequest } from "./httpClient";

export interface SystemInfo {
  application: string;
  version: string;
  status: string;
  profiles: string[];
  timestamp: string;
}

/** Endpoint público de identificación del backend — la comprobación de alcance de la API. */
export function fetchSystemInfo(signal?: AbortSignal): Promise<SystemInfo> {
  return apiRequest<SystemInfo>("/system/info", { signal });
}
