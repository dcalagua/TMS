import { apiRequest } from './httpClient'
import type { OrganizationView } from './meApi'
import type { PageResponse } from './pageResponse'

/** Mirrors the backend's `CompanySettingsView` (migration V34). */
export interface CompanySettingsView {
  /** ISO 3166-1 alpha-2. Applied to a location import row that leaves `country` blank. */
  defaultCountry: string
  /** Goes in front of the order sequence: `TO-` + eight digits. */
  orderNumberPrefix: string
  shipmentNumberPrefix: string
  /**
   * Quién despacha un envío en esta empresa (ADR-013 §1, migración V52). Opcional en el tipo
   * porque un backend anterior a V52 no lo envía; la pantalla lo trata entonces como `MANUAL`,
   * que es el valor por defecto del servidor.
   */
  dispatchConfirmationMode?: DispatchConfirmationMode
}

/**
 * Mirrors the backend's `DispatchConfirmationMode` enum (`shared/settings`).
 *
 * - `MANUAL`: despacha una persona desde TMS; lo que diga el almacén solo se concilia.
 * - `EXTERNAL_REQUIRED`: despacha la confirmación del almacén (WMS). Una persona necesita
 *   `planning.trip:dispatch-override` y un motivo.
 * - `HYBRID`: el primero que confirme despacha; el segundo solo concilia.
 */
export type DispatchConfirmationMode = 'MANUAL' | 'EXTERNAL_REQUIRED' | 'HYBRID'

export const DISPATCH_CONFIRMATION_MODES: DispatchConfirmationMode[] = ['MANUAL', 'EXTERNAL_REQUIRED', 'HYBRID']

/**
 * Mirrors the backend's `CompanyProfileView`.
 *
 * Distinct from `CompanyAccessView` in `meApi`, which the shell reads on every sign-in to decide
 * what the menu shows. This is read by one screen and carries what the company *is* rather than
 * what the caller may do in it.
 */
export interface CompanyProfileView {
  id: string
  code: string
  name: string
  taxIdentifier: string | null
  timeZone: string
  active: boolean
  organization: OrganizationView
  /** A deactivated organization revokes every membership below it; shown, never edited here. */
  organizationActive: boolean
  /**
   * Whether this caller holds an organization-wide role and may therefore add a company. Not
   * derivable from any permission the shell already has - `iam.company:manage` is held by a
   * company administrator too. Hiding is UX only; the endpoint re-checks and answers 403.
   */
  canCreateCompany: boolean
  settings: CompanySettingsView
}

/** Mirrors the backend's `CompanyProfileRequest`: profile and settings saved as one screen. */
export interface CompanyProfileRequest {
  name: string
  taxIdentifier?: string | null
  timeZone: string
  defaultCountry: string
  orderNumberPrefix: string
  shipmentNumberPrefix: string
  /** Opcional: omitirlo conserva el modo actual en lugar de devolverlo a `MANUAL`. */
  dispatchConfirmationMode?: DispatchConfirmationMode
}

/** Mirrors the backend's `CompanyCreateRequest`. There is no `organizationId`: the caller's own is used. */
export interface CompanyCreateRequest {
  code: string
  name: string
  taxIdentifier?: string | null
  timeZone: string
}

/**
 * Mirrors the backend's `AdministeredUserView`.
 *
 * The identifier acted on is `membershipId`, not `appUserId`: what an administrator of one company
 * grants and revokes is a membership, and the same person may hold several.
 */
export interface AdministeredUserView {
  membershipId: string
  appUserId: string
  email: string
  fullName: string
  /** Installation-wide account flag. Shown so a disabled account is explicable; never written here. */
  userActive: boolean
  membershipActive: boolean
  /** Reaches every company of the organization, so this screen shows it and cannot change it. */
  organizationWide: boolean
  roleCodes: string[]
  createdAt: string
  /**
   * ADR-018 §1: el transportista de una membresía de transportista; `null` para una de oficina.
   * Opcional porque un backend anterior a V57 no lo envía.
   */
  carrierId?: string | null
  carrierName?: string | null
}

/** Mirrors the backend's `RoleView`. */
export interface RoleView {
  code: string
  name: string
  description: string | null
  /**
   * `CARRIER` (ADR-018): solo se concede a una membresía de transportista, y una membresía de
   * transportista solo recibe roles `CARRIER`. La pantalla ofrece una familia u otra según si se
   * eligió transportista.
   */
  scopeLevel: 'ORGANIZATION' | 'COMPANY' | 'CARRIER'
  /** An ORGANIZATION role on a company membership grants nothing, so the picker refuses it. */
  assignable: boolean
  permissionCodes: string[]
}

/**
 * ADR-018: los roles que admite una membresía. Una de transportista solo recibe roles `CARRIER`,
 * y una de oficina ninguno; el backend rechaza cualquier otra pareja, así que no se ofrece.
 */
export function rolesForMembership(roles: RoleView[], carrierScoped: boolean): RoleView[] {
  return roles.filter((role) => (role.scopeLevel === 'CARRIER') === carrierScoped)
}

export interface UserInviteRequest {
  email: string
  fullName: string
  roleCodes: string[]
  /** ADR-018: con transportista, la membresía es de transportista y solo admite roles `CARRIER`. */
  carrierId?: string | null
}

export interface UserListParams {
  companyId: string
  page?: number
  size?: number
  sort?: string
  /** Matched against email and full name together - one box, because that is how people search. */
  search?: string
  active?: boolean
  signal?: AbortSignal
}

export function fetchCompanyProfile(companyId: string, signal?: AbortSignal): Promise<CompanyProfileView> {
  return apiRequest<CompanyProfileView>('/admin/companies/current', { companyId, signal })
}

export function updateCompanyProfile(
  companyId: string,
  request: CompanyProfileRequest,
): Promise<CompanyProfileView> {
  return apiRequest<CompanyProfileView>('/admin/companies/current', { method: 'PUT', companyId, body: request })
}

export function createCompany(companyId: string, request: CompanyCreateRequest): Promise<CompanyProfileView> {
  return apiRequest<CompanyProfileView>('/admin/companies', { method: 'POST', companyId, body: request })
}

export function fetchAdministeredUsers(params: UserListParams): Promise<PageResponse<AdministeredUserView>> {
  const { companyId, signal, ...query } = params
  return apiRequest<PageResponse<AdministeredUserView>>('/admin/users', { companyId, signal, query })
}

export function fetchAssignableRoles(companyId: string, signal?: AbortSignal): Promise<RoleView[]> {
  return apiRequest<RoleView[]>('/admin/users/roles', { companyId, signal })
}

export function inviteUser(companyId: string, request: UserInviteRequest): Promise<AdministeredUserView> {
  return apiRequest<AdministeredUserView>('/admin/users', { method: 'POST', companyId, body: request })
}

export function updateUserProfile(
  companyId: string,
  membershipId: string,
  fullName: string,
): Promise<AdministeredUserView> {
  return apiRequest<AdministeredUserView>(`/admin/users/${membershipId}`, {
    method: 'PUT',
    companyId,
    body: { fullName },
  })
}

export function updateUserRoles(
  companyId: string,
  membershipId: string,
  roleCodes: string[],
): Promise<AdministeredUserView> {
  return apiRequest<AdministeredUserView>(`/admin/users/${membershipId}/roles`, {
    method: 'PUT',
    companyId,
    body: { roleCodes },
  })
}

export function revokeUserAccess(companyId: string, membershipId: string): Promise<AdministeredUserView> {
  return apiRequest<AdministeredUserView>(`/admin/users/${membershipId}/revoke`, { method: 'POST', companyId })
}

export function restoreUserAccess(companyId: string, membershipId: string): Promise<AdministeredUserView> {
  return apiRequest<AdministeredUserView>(`/admin/users/${membershipId}/restore`, { method: 'POST', companyId })
}
