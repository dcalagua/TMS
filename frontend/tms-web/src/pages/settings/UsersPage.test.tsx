import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, describe, expect, it, vi } from 'vitest'
import type { AdministeredUserView, RoleView } from '../../shared/api/administrationApi'
import { UsersPage } from './UsersPage'

const adminApiMocks = vi.hoisted(() => ({
  fetchAdministeredUsers: vi.fn(),
  fetchAssignableRoles: vi.fn(),
  inviteUser: vi.fn(),
  updateUserProfile: vi.fn(),
  updateUserRoles: vi.fn(),
  revokeUserAccess: vi.fn(),
  restoreUserAccess: vi.fn(),
}))
vi.mock('../../shared/api/administrationApi', async () => {
  const actual =
    await vi.importActual<typeof import('../../shared/api/administrationApi')>(
      '../../shared/api/administrationApi',
    )
  return { ...actual, ...adminApiMocks }
})

const companyMocks = vi.hoisted(() => ({ useCompany: vi.fn() }))
vi.mock('../../shared/company/CompanyContext', () => ({ useCompany: companyMocks.useCompany }))

const alertMocks = vi.hoisted(() => ({
  notifySuccess: vi.fn(),
  notifyError: vi.fn(),
  confirmAction: vi.fn(),
  promptForText: vi.fn(),
}))
vi.mock('../../shared/ui/alerts', () => alertMocks)

const ME = 'app-user-me'

function user(overrides: Partial<AdministeredUserView> = {}): AdministeredUserView {
  return {
    membershipId: 'membership-1',
    appUserId: 'app-user-1',
    email: 'ana@ebim.test',
    fullName: 'Ana Quispe',
    userActive: true,
    membershipActive: true,
    organizationWide: false,
    roleCodes: ['PLANNER'],
    createdAt: '2026-01-01T00:00:00Z',
    ...overrides,
  }
}

function role(overrides: Partial<RoleView> = {}): RoleView {
  return {
    code: 'PLANNER',
    name: 'Planner',
    description: 'Builds trips',
    scopeLevel: 'COMPANY',
    assignable: true,
    permissionCodes: ['orders.order:manage', 'planning.trip:manage'],
    ...overrides,
  }
}

function page(content: AdministeredUserView[]) {
  return { content, page: 0, size: 25, totalElements: content.length }
}

function mockCompany(permissions: string[]) {
  companyMocks.useCompany.mockReturnValue({
    selected: { id: 'company-1', name: 'Acme Logistics' },
    profile: { id: ME, email: 'me@ebim.test', fullName: 'Me' },
    hasPermission: (permission: string) => permissions.includes(permission),
  })
}

function renderPage() {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  return render(
    <QueryClientProvider client={queryClient}>
      <UsersPage />
    </QueryClientProvider>,
  )
}

afterEach(() => {
  vi.clearAllMocks()
})

describe('UsersPage', () => {
  it('lists the people who can act here with the roles they hold', async () => {
    mockCompany(['iam.user:read', 'iam.membership:manage'])
    adminApiMocks.fetchAdministeredUsers.mockResolvedValue(page([user()]))
    adminApiMocks.fetchAssignableRoles.mockResolvedValue([role()])

    renderPage()

    expect(await screen.findByText('Ana Quispe')).toBeInTheDocument()
    expect(screen.getByText('ana@ebim.test')).toBeInTheDocument()
    expect(screen.getByText('PLANNER')).toBeInTheDocument()
    // Scoped to the row: the status filter above the table also shows its selected value, which
    // is the same words, so a page-wide query matches the filter as readily as the badge.
    expect(within(screen.getByRole('row', { name: /Ana Quispe/ })).getByText('Con acceso'))
      .toBeInTheDocument()
  })

  /**
   * The row is shown - "who can act here" that omitted the people with the most authority would be
   * false - and it offers no action, because revoking it from a company screen would remove that
   * person from every other company of the organization. The endpoints refuse it too; this only
   * avoids offering a button that would answer 409.
   */
  it('lists an organization-wide member and offers no action on them', async () => {
    mockCompany(['iam.user:read', 'iam.membership:manage', 'iam.user:manage'])
    adminApiMocks.fetchAdministeredUsers.mockResolvedValue(
      page([user({ membershipId: 'membership-org', fullName: 'Root Admin', organizationWide: true })]),
    )
    adminApiMocks.fetchAssignableRoles.mockResolvedValue([role()])

    renderPage()

    expect(await screen.findByText('Root Admin')).toBeInTheDocument()
    expect(screen.getByText('Toda la organización')).toBeInTheDocument()
    expect(screen.getByText('No editable aquí')).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Abrir menú de acciones' })).not.toBeInTheDocument()
  })

  /**
   * The blunt self-guard of `UserAdministrationService`, mirrored in the UI so the button is not
   * offered at all. The backend refuses it regardless of what the browser rendered.
   */
  it('does not offer to revoke your own access', async () => {
    mockCompany(['iam.user:read', 'iam.membership:manage'])
    adminApiMocks.fetchAdministeredUsers.mockResolvedValue(
      page([user({ appUserId: ME, fullName: 'Me', email: 'me@ebim.test' })]),
    )
    adminApiMocks.fetchAssignableRoles.mockResolvedValue([role()])

    renderPage()
    await screen.findByText('me@ebim.test')
    // The row marks itself, so an administrator can see why it offers less than the others do.
    expect(screen.getByText('(tú)')).toBeInTheDocument()

    await userEvent.click(screen.getByRole('button', { name: 'Abrir menú de acciones' }))

    expect(await screen.findByRole('menuitem', { name: /Editar/ })).toBeInTheDocument()
    expect(screen.queryByRole('menuitem', { name: /Revocar acceso/ })).not.toBeInTheDocument()
  })

  it('shows an account disabled installation-wide as such, not as revoked access', async () => {
    mockCompany(['iam.user:read'])
    adminApiMocks.fetchAdministeredUsers.mockResolvedValue(page([user({ userActive: false })]))
    adminApiMocks.fetchAssignableRoles.mockResolvedValue([role()])

    renderPage()

    expect(await screen.findByText('Cuenta deshabilitada')).toBeInTheDocument()
    // In the row - the filter above the table carries the same words whatever the row says.
    expect(within(screen.getByRole('row', { name: /Ana Quispe/ })).queryByText('Con acceso'))
      .not.toBeInTheDocument()
  })

  it('hides the invite button from a caller who cannot grant access', async () => {
    mockCompany(['iam.user:read'])
    adminApiMocks.fetchAdministeredUsers.mockResolvedValue(page([user()]))
    adminApiMocks.fetchAssignableRoles.mockResolvedValue([role()])

    renderPage()
    await screen.findByText('Ana Quispe')

    expect(screen.queryByRole('button', { name: 'Invitar' })).not.toBeInTheDocument()
  })

  it('sends the search box to the server rather than filtering the loaded page', async () => {
    mockCompany(['iam.user:read'])
    adminApiMocks.fetchAdministeredUsers.mockResolvedValue(page([user()]))
    adminApiMocks.fetchAssignableRoles.mockResolvedValue([role()])

    renderPage()
    await screen.findByText('Ana Quispe')

    await userEvent.type(screen.getByLabelText('Buscar'), 'quispe')
    await userEvent.click(screen.getByRole('button', { name: 'Aplicar filtros' }))

    await waitFor(() => {
      expect(adminApiMocks.fetchAdministeredUsers).toHaveBeenLastCalledWith(
        expect.objectContaining({ search: 'quispe' }),
      )
    })
  })

  it('revokes access at the membership after a confirmation', async () => {
    mockCompany(['iam.user:read', 'iam.membership:manage'])
    adminApiMocks.fetchAdministeredUsers.mockResolvedValue(page([user()]))
    adminApiMocks.fetchAssignableRoles.mockResolvedValue([role()])
    adminApiMocks.revokeUserAccess.mockResolvedValue(user({ membershipActive: false }))
    alertMocks.confirmAction.mockResolvedValue(true)

    renderPage()
    await screen.findByText('Ana Quispe')

    await userEvent.click(screen.getByRole('button', { name: 'Abrir menú de acciones' }))
    await userEvent.click(await screen.findByRole('menuitem', { name: /Revocar acceso/ }))

    await waitFor(() => {
      expect(adminApiMocks.revokeUserAccess).toHaveBeenCalledWith('company-1', 'membership-1')
    })
  })
})
