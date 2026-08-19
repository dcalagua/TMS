import type { ApiError, ProblemCode } from './httpClient'

/**
 * User-facing text for each backend `code` (`docs/api/API_CONVENTIONS.md` section 4.1).
 * Screens must not build their own copy from `detail` - it is prose meant for logs and may
 * be reworded without notice.
 */
const MESSAGES: Record<ProblemCode, string> = {
  unauthenticated: 'Your session has expired. Please sign in again.',
  'invalid-token': 'Your session is no longer valid. Please sign in again.',
  'principal-not-provisioned':
    'Your account is not set up in TMS yet. Contact an administrator to request access.',
  'company-scope-required': 'No company is selected. Choose a company and try again.',
  'company-scope-invalid': 'The selected company is invalid. Choose a company and try again.',
  'company-scope-forbidden': 'You no longer have access to that company. Choose another one.',
  'access-denied': 'You do not have permission to do this.',
  'validation-failed': 'Some fields need to be corrected.',
  'malformed-request': 'The request could not be processed.',
  'resource-not-found': 'The requested item could not be found.',
  conflict: 'This change conflicts with another update. Reload and try again.',
  'internal-error': 'Something went wrong on our side. Please try again.',
}

const FALLBACK_MESSAGE = 'Something went wrong. Please try again.'

/** Friendly, code-driven text for an {@link ApiError} - never `error.message`/`detail` directly. */
export function describeApiError(error: ApiError): string {
  if (error.code && error.code in MESSAGES) {
    return MESSAGES[error.code as ProblemCode]
  }
  return FALLBACK_MESSAGE
}

/** True for the two codes that mean "the bearer token is no good any more". */
export function isAuthProblem(error: ApiError): boolean {
  return error.status === 401 || error.code === 'unauthenticated' || error.code === 'invalid-token'
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
