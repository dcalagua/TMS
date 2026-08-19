import { appEnv } from '../config/env'

/** Header names from the API contract (`docs/api/API_CONVENTIONS.md`). Kept in one place so a
 * rename cannot drift between the client, the auth layer and tests. */
export const COMPANY_ID_HEADER = 'X-Company-Id'
export const CORRELATION_ID_HEADER = 'X-Correlation-Id'

/** The `code` values the backend documents in `docs/api/API_CONVENTIONS.md` section 4.1. */
export type ProblemCode =
  | 'unauthenticated'
  | 'invalid-token'
  | 'principal-not-provisioned'
  | 'company-scope-required'
  | 'company-scope-invalid'
  | 'company-scope-forbidden'
  | 'access-denied'
  | 'validation-failed'
  | 'malformed-request'
  | 'resource-not-found'
  | 'conflict'
  | 'internal-error'

export interface ProblemFieldError {
  field: string
  message: string
}

/** An RFC 9457 `application/problem+json` document, as shaped by `ApiExceptionHandler`. */
export interface ProblemDetails {
  type?: string
  title?: string
  status?: number
  detail?: string
  instance?: string
  code?: ProblemCode | string
  timestamp?: string
  correlationId?: string
  errors?: ProblemFieldError[]
}

function isProblemDetails(payload: unknown): payload is ProblemDetails {
  return typeof payload === 'object' && payload !== null && 'code' in payload
}

/**
 * Error raised for any non-2xx backend response. Carries the HTTP status and the stable
 * `code` from the Problem Details document so callers branch on `code`, never on `detail`
 * (`API_CONVENTIONS.md` section 4) - `detail` is human prose and may be reworded.
 */
export class ApiError extends Error {
  readonly status: number
  readonly code: ProblemCode | string | null
  readonly correlationId: string | null
  readonly fieldErrors: ProblemFieldError[]
  readonly problem: ProblemDetails | null

  constructor(status: number, problem: ProblemDetails | null, correlationId: string, fallbackMessage: string) {
    super(problem?.detail ?? fallbackMessage)
    this.name = 'ApiError'
    this.status = status
    this.code = problem?.code ?? null
    this.correlationId = problem?.correlationId ?? correlationId
    this.fieldErrors = problem?.errors ?? []
    this.problem = problem
  }
}

/**
 * Supplies the bearer token for outgoing requests. `AuthContext` registers the Supabase
 * Auth session here; until a session exists requests go out unauthenticated and the
 * backend answers 401, which is the correct behaviour.
 */
type TokenProvider = () => Promise<string | null> | string | null

let tokenProvider: TokenProvider = () => null

export function setAuthTokenProvider(provider: TokenProvider): void {
  tokenProvider = provider
}

/**
 * Central reaction to a failed response, registered by the auth/company layers instead of
 * being handled ad hoc at every call site. Handlers must not issue new requests synchronously
 * from within the callback - that is how a 401 handler causes an infinite refresh loop.
 */
type ResponseErrorHandler = (error: ApiError) => void

const responseErrorHandlers = new Set<ResponseErrorHandler>()

export function onApiResponseError(handler: ResponseErrorHandler): () => void {
  responseErrorHandlers.add(handler)
  return () => responseErrorHandlers.delete(handler)
}

function generateCorrelationId(): string {
  return crypto.randomUUID()
}

export interface RequestOptions {
  method?: 'GET' | 'POST' | 'PUT' | 'PATCH' | 'DELETE'
  body?: unknown
  signal?: AbortSignal
  /** Query parameters; `undefined` and `null` values are omitted. */
  query?: Record<string, string | number | boolean | undefined | null>
  /** Company UUID sent as `X-Company-Id` for company-scoped endpoints. */
  companyId?: string
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
  const { method = 'GET', body, signal, query, companyId } = options
  const token = await tokenProvider()
  const correlationId = generateCorrelationId()

  const headers: Record<string, string> = {
    Accept: 'application/json',
    [CORRELATION_ID_HEADER]: correlationId,
  }
  if (body !== undefined) {
    headers['Content-Type'] = 'application/json'
  }
  if (token) {
    headers.Authorization = `Bearer ${token}`
  }
  if (companyId) {
    headers[COMPANY_ID_HEADER] = companyId
  }

  const response = await fetch(buildUrl(path, query), {
    method,
    headers,
    signal,
    body: body === undefined ? undefined : JSON.stringify(body),
  })

  const payload = await parseBody(response)

  if (!response.ok) {
    const problem = isProblemDetails(payload) ? payload : null
    const error = new ApiError(
      response.status,
      problem,
      response.headers.get(CORRELATION_ID_HEADER) ?? correlationId,
      `${method} ${path} failed with ${response.status}`,
    )
    for (const handler of responseErrorHandlers) {
      handler(error)
    }
    throw error
  }

  return payload as T
}
