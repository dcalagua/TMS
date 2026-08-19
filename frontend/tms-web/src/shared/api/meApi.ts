import { apiRequest } from './httpClient'

export interface OrganizationView {
  id: string
  code: string
  name: string
}

/**
 * One company the caller may operate in, mirroring the backend's `CompanyAccessView`.
 * `permissions` and `capabilities` are UX input, not grants: hiding or showing a menu entry
 * from them changes nothing about what the backend allows, because every endpoint re-checks
 * the fine-grained permission itself against the company sent as `X-Company-Id`.
 */
export interface CompanyAccessView {
  id: string
  code: string
  name: string
  timeZone: string
  organization: OrganizationView
  permissions: string[]
  capabilities: string[]
}

export interface UserView {
  id: string
  email: string
  fullName: string
}

export interface MeView {
  user: UserView
  companies: CompanyAccessView[]
}

/** Everything needed right after sign-in: profile, selectable companies, and per-company
 * permissions/capabilities in one call (`docs/overnight/03_BACKEND_SECURITY.md` section 9). */
export function fetchMe(signal?: AbortSignal): Promise<MeView> {
  return apiRequest<MeView>('/me', { signal })
}
