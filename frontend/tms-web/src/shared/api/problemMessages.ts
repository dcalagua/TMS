import i18n from '../i18n'
import { isAuthFailureResponse, type ApiError, type ProblemCode } from './httpClient'

/**
 * User-facing text for each backend `code` (`docs/api/API_CONVENTIONS.md` section 4.1).
 *
 * The branch is always on `code`, never on `detail`: `detail` is prose meant for logs, it is
 * written in one language only, and it may be reworded without notice. Each code maps to a key
 * in the `errors` namespace, so both languages stay in step and a missing translation is a
 * build failure rather than an English sentence in a Spanish screen.
 */
const KNOWN_CODES: readonly ProblemCode[] = [
  'unauthenticated',
  'invalid-token',
  'principal-not-provisioned',
  'company-scope-required',
  'company-scope-invalid',
  'company-scope-forbidden',
  'access-denied',
  'validation-failed',
  'malformed-request',
  'resource-not-found',
  'conflict',
  'internal-error',
]

function knownCode(code: string | null): code is ProblemCode {
  return code !== null && (KNOWN_CODES as readonly string[]).includes(code)
}

/** Friendly, code-driven text for an {@link ApiError} - never `error.message`/`detail` directly. */
export function describeApiError(error: ApiError): string {
  if (knownCode(error.code)) {
    return i18n.t(`codes.${error.code}`, { ns: 'errors' })
  }
  return i18n.t('fallback', { ns: 'errors' })
}

/** True for the two codes that mean "the bearer token is no good any more". Delegates to
 * `httpClient` so the UI and the retry logic cannot disagree about what an auth failure is. */
export function isAuthProblem(error: ApiError): boolean {
  return isAuthFailureResponse(error.status, error.code)
}

/** True for the one code that means "re-fetch `/me`, the company selection is stale". */
export function isCompanyScopeStale(error: ApiError): boolean {
  return error.code === 'company-scope-forbidden'
}

/**
 * Prefers the backend's own `detail` over the generic per-code copy, for the one family of
 * screens where that is the documented contract: planning's `conflict`/`malformed-request`
 * refusals are written server-side to be shown to a planner verbatim - capacity failures name
 * every dimension that failed, eligibility failures name the origin/date mismatch
 * (`docs/domain/CAPACITY_MODEL.md`, "The frontend is never trusted";
 * `docs/domain/PLANNING_MANUAL_V1.md` section 5). Every other screen keeps using
 * `describeApiError` so `detail` wording changes cannot silently alter their copy.
 */
export function describePlanningError(error: ApiError): string {
  if ((error.code === 'conflict' || error.code === 'malformed-request') && error.problem?.detail) {
    return error.problem.detail
  }
  return describeApiError(error)
}
