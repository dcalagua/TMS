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

/** The two `code` values meaning "this bearer token is not accepted any more". A bare 401
 * counts too: it is what the backend answers when no `Authorization` header arrived at all. */
const AUTH_FAILURE_CODES: ReadonlySet<string> = new Set(['unauthenticated', 'invalid-token'])

/** Shared by the retry logic here and by `problemMessages.isAuthProblem`, so "is this an
 * authentication failure?" has exactly one definition in the app. */
export function isAuthFailureResponse(status: number, code: string | null): boolean {
  return status === 401 || (code !== null && AUTH_FAILURE_CODES.has(code))
}

/**
 * Supplies the bearer token for outgoing requests. `AuthContext` registers a synchronous
 * reader over the session it currently holds; until a session exists requests go out
 * unauthenticated and the backend answers 401, which is the correct behaviour.
 */
type TokenProvider = () => Promise<string | null> | string | null

let tokenProvider: TokenProvider = () => null

export function setAuthTokenProvider(provider: TokenProvider): void {
  tokenProvider = provider
}

/**
 * Attempts to obtain a fresh access token for a request the backend just rejected, resolving
 * to `null` when the session genuinely cannot be recovered. Registered by `AuthContext`; the
 * default refuses, so an app that never registers one simply never retries.
 */
type AuthRefreshHandler = () => Promise<string | null>

let authRefreshHandler: AuthRefreshHandler = () => Promise.resolve(null)

export function setAuthRefreshHandler(handler: AuthRefreshHandler): void {
  authRefreshHandler = handler
}

let inFlightRefresh: Promise<string | null> | null = null

/**
 * Single-flight refresh. Several screens failing with 401 at the same instant must produce one
 * refresh, not one each - otherwise a page with six queries fires six refreshes and the later
 * ones invalidate the token the earlier ones just obtained.
 */
function refreshAuthOnce(): Promise<string | null> {
  inFlightRefresh ??= Promise.resolve()
    .then(() => authRefreshHandler())
    .catch(() => null)
    .finally(() => {
      inFlightRefresh = null
    })
  return inFlightRefresh
}

/** Test seam: drops any in-flight refresh so one test cannot leak state into the next. */
export function resetAuthRefreshState(): void {
  inFlightRefresh = null
}

/**
 * Central reaction to a failed response, registered by the auth/company layers instead of
 * being handled ad hoc at every call site. Handlers must not issue new requests synchronously
 * from within the callback - that is how a 401 handler causes an infinite refresh loop.
 *
 * An authentication failure reaches here only after the single refresh+retry below has already
 * failed, so a handler that signs the user out can trust the session is really unrecoverable
 * rather than merely stale.
 */
type ResponseErrorHandler = (error: ApiError) => void

const responseErrorHandlers = new Set<ResponseErrorHandler>()

export function onApiResponseError(handler: ResponseErrorHandler): () => void {
  responseErrorHandlers.add(handler)
  return () => responseErrorHandlers.delete(handler)
}

function reportResponseError(error: ApiError): void {
  for (const handler of responseErrorHandlers) {
    handler(error)
  }
}

function generateCorrelationId(): string {
  return crypto.randomUUID()
}

export interface RequestOptions {
  method?: 'GET' | 'POST' | 'PUT' | 'PATCH' | 'DELETE'
  body?: unknown
  /** A `multipart/form-data` body - file uploads. Mutually exclusive with `body`; the browser
   * sets `Content-Type` itself, because only it knows the multipart boundary. */
  formData?: FormData
  signal?: AbortSignal
  /** Query parameters; `undefined` and `null` values are omitted. */
  query?: Record<string, string | number | boolean | undefined | null>
  /** Company UUID sent as `X-Company-Id` for company-scoped endpoints. */
  companyId?: string
  /** `blob` for a file download; the error path stays JSON either way, because a failed
   * download still answers with an RFC 9457 document. */
  responseType?: 'json' | 'blob'
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

async function parseBody(response: Response, responseType: 'json' | 'blob'): Promise<unknown> {
  if (response.status === 204 || response.headers.get('content-length') === '0') {
    return null
  }

  const contentType = response.headers.get('content-type') ?? ''
  // Only a *successful* blob request yields a blob. A failed one carries problem+json, and
  // handing that back as an opaque Blob would lose the error the caller has to show.
  if (responseType === 'blob' && response.ok) {
    return { blob: await response.blob(), fileName: fileNameFromDisposition(response) }
  }
  return contentType.includes('json') ? response.json() : response.text()
}

/**
 * The name the server asked the browser to save the file under. Parsed rather than guessed
 * from the URL so that renaming the template on the server renames the download, and only the
 * last path segment is kept so a crafted header cannot suggest a directory.
 */
function fileNameFromDisposition(response: Response): string | null {
  const disposition = response.headers.get('content-disposition')
  if (!disposition) return null

  const encoded = /filename\*=UTF-8''([^;]+)/i.exec(disposition)?.[1]
  if (encoded !== undefined) return lastSegment(decodeURIComponent(encoded))

  const plain = /filename="?([^";]+)"?/i.exec(disposition)?.[1]
  return plain === undefined ? null : lastSegment(plain.trim())
}

/** The bare file name: anything before the last separator is a directory the server has no
 * business suggesting. Returns null for a value that is only separators. */
function lastSegment(value: string): string | null {
  const bare = value.split(/[\\/]/).pop()
  return bare === undefined || bare === '' ? null : bare
}

/** What a `responseType: 'blob'` request resolves to. */
export interface DownloadedFile {
  blob: Blob
  fileName: string | null
}

interface Attempt {
  ok: boolean
  payload: unknown
  error: ApiError | null
}

async function sendRequest(path: string, options: RequestOptions, token: string | null): Promise<Attempt> {
  const { method = 'GET', body, formData, signal, query, companyId, responseType = 'json' } = options
  const correlationId = generateCorrelationId()

  const headers: Record<string, string> = {
    // Still `application/json` for a download: the *error* shape is what this negotiates, and
    // the endpoints that return a file ignore Accept for their success case.
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
    // `Content-Type` is deliberately absent for FormData: fetch adds it together with the
    // multipart boundary, and setting it by hand produces a body the server cannot split.
    body: formData ?? (body === undefined ? undefined : JSON.stringify(body)),
  })

  const payload = await parseBody(response, responseType)

  if (response.ok) {
    return { ok: true, payload, error: null }
  }

  const problem = isProblemDetails(payload) ? payload : null
  const error = new ApiError(
    response.status,
    problem,
    response.headers.get(CORRELATION_ID_HEADER) ?? correlationId,
    `${method} ${path} failed with ${response.status}`,
  )
  return { ok: false, payload, error }
}

/**
 * Performs a JSON request against the TMS backend.
 *
 * An authentication failure gets exactly one recovery attempt: refresh the session, and replay
 * the request once if that produced a different token. There is no second retry and no retry
 * for any other status, so a backend that keeps answering 401 costs one extra request in
 * total rather than looping.
 */
export async function apiRequest<T>(path: string, options: RequestOptions = {}): Promise<T> {
  const token = (await tokenProvider()) ?? null
  const attempt = await sendRequest(path, options, token)

  if (attempt.ok) {
    return attempt.payload as T
  }

  const error = attempt.error as ApiError

  if (isAuthFailureResponse(error.status, error.code) && options.signal?.aborted !== true) {
    const refreshedToken = await refreshAuthOnce()

    if (refreshedToken !== null && refreshedToken !== token) {
      const retry = await sendRequest(path, options, refreshedToken)
      if (retry.ok) {
        return retry.payload as T
      }
      const retryError = retry.error as ApiError
      reportResponseError(retryError)
      throw retryError
    }
  }

  reportResponseError(error)
  throw error
}

/**
 * Uploads a `multipart/form-data` body and reads a JSON answer. Everything else - the bearer
 * token, the company header, the single refresh-and-retry, the Problem Details translation -
 * is {@link apiRequest}'s, because an upload that failed authentication must behave exactly
 * like every other request that did.
 */
export function apiUpload<T>(
  path: string,
  options: { companyId?: string; formData: FormData; signal?: AbortSignal },
): Promise<T> {
  return apiRequest<T>(path, { method: 'POST', ...options })
}

/** Downloads a file, resolving to its bytes and the name the server suggested. */
export function apiDownload(
  path: string,
  options: { companyId?: string; query?: RequestOptions['query']; signal?: AbortSignal } = {},
): Promise<DownloadedFile> {
  return apiRequest<DownloadedFile>(path, { ...options, responseType: 'blob' })
}
