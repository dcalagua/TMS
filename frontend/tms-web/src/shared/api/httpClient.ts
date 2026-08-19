import { appEnv } from '../config/env'

/**
 * Error raised for any non-2xx backend response. Carries the HTTP status so callers can
 * distinguish "not authenticated" (401) from "not allowed" (403) from a real failure.
 */
export class ApiError extends Error {
  readonly status: number
  readonly details: unknown

  constructor(status: number, message: string, details?: unknown) {
    super(message)
    this.name = 'ApiError'
    this.status = status
    this.details = details
  }
}

/**
 * Supplies the bearer token for outgoing requests. Step 04 registers the Supabase Auth
 * session here; until then requests go out unauthenticated and the backend answers 401,
 * which is the correct behaviour.
 */
type TokenProvider = () => Promise<string | null> | string | null

let tokenProvider: TokenProvider = () => null

export function setAuthTokenProvider(provider: TokenProvider): void {
  tokenProvider = provider
}

export interface RequestOptions {
  method?: 'GET' | 'POST' | 'PUT' | 'PATCH' | 'DELETE'
  body?: unknown
  signal?: AbortSignal
  /** Query parameters; `undefined` and `null` values are omitted. */
  query?: Record<string, string | number | boolean | undefined | null>
}

function buildUrl(path: string, query?: RequestOptions['query']): string {
  const normalizedPath = path.startsWith('/') ? path : `/${path}`
  const url = new URL(`${appEnv.apiBaseUrl}${normalizedPath}`)

  for (const [key, value] of Object.entries(query ?? {})) {
    if (value !== undefined && value !== null) {
      url.searchParams.set(key, String(value))
    }
  }

  return url.toString()
}

async function parseBody(response: Response): Promise<unknown> {
  if (response.status === 204 || response.headers.get('content-length') === '0') {
    return null
  }

  const contentType = response.headers.get('content-type') ?? ''
  return contentType.includes('json') ? response.json() : response.text()
}

/** Performs a JSON request against the TMS backend. */
export async function apiRequest<T>(path: string, options: RequestOptions = {}): Promise<T> {
  const { method = 'GET', body, signal, query } = options
  const token = await tokenProvider()

  const headers: Record<string, string> = { Accept: 'application/json' }
  if (body !== undefined) {
    headers['Content-Type'] = 'application/json'
  }
  if (token) {
    headers.Authorization = `Bearer ${token}`
  }

  const response = await fetch(buildUrl(path, query), {
    method,
    headers,
    signal,
    body: body === undefined ? undefined : JSON.stringify(body),
  })

  const payload = await parseBody(response)

  if (!response.ok) {
    throw new ApiError(response.status, `${method} ${path} failed with ${response.status}`, payload)
  }

  return payload as T
}
