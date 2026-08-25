import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, describe, expect, it, vi } from 'vitest'
import type { CompanyProfileView } from '../../shared/api/administrationApi'
import { CompanySettingsPage } from './CompanySettingsPage'

const adminApiMocks = vi.hoisted(() => ({
  fetchCompanyProfile: vi.fn(),
  updateCompanyProfile: vi.fn(),
  createCompany: vi.fn(),
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

const refetchCompanies = vi.fn()

function profile(overrides: Partial<CompanyProfileView> = {}): CompanyProfileView {
  return {
    id: 'company-1',
    code: 'ACME-LIM',
    name: 'Acme Logistics',
    taxIdentifier: '20123456789',
    timeZone: 'America/Lima',
    active: true,
    organization: { id: 'org-1', code: 'ACME', name: 'Acme Group' },
    organizationActive: true,
    canCreateCompany: false,
    settings: { defaultCountry: 'PE', orderNumberPrefix: 'TO-', shipmentNumberPrefix: 'SH-' },
    ...overrides,
  }
}

function mockCompany(permissions: string[]) {
  companyMocks.useCompany.mockReturnValue({
    selected: { id: 'company-1', name: 'Acme Logistics' },
    profile: { id: 'app-user-me', email: 'me@ebim.test', fullName: 'Me' },
    hasPermission: (permission: string) => permissions.includes(permission),
    refetch: refetchCompanies,
  })
}

function renderPage() {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  return render(
    <QueryClientProvider client={queryClient}>
      <CompanySettingsPage />
    </QueryClientProvider>,
  )
}

afterEach(() => {
  vi.clearAllMocks()
})

describe('CompanySettingsPage', () => {
  it('shows the company, its organization and its settings', async () => {
    mockCompany(['iam.company:read', 'iam.company:manage'])
    adminApiMocks.fetchCompanyProfile.mockResolvedValue(profile())

    renderPage()

    expect(await screen.findByDisplayValue('Acme Logistics')).toBeInTheDocument()
    expect(screen.getByDisplayValue('ACME — Acme Group')).toBeInTheDocument()
    expect(screen.getByDisplayValue('America/Lima')).toBeInTheDocument()
    expect(screen.getByDisplayValue('TO-')).toBeInTheDocument()
  })

  /** "TO-" is abstract; `TO-00000042` is the question an administrator is actually asking. */
  it('previews the document number the prefix will produce, live', async () => {
    mockCompany(['iam.company:read', 'iam.company:manage'])
    adminApiMocks.fetchCompanyProfile.mockResolvedValue(profile())

    renderPage()
    const prefix = await screen.findByDisplayValue('TO-')

    expect(screen.getByText('Ejemplo: TO-00000042')).toBeInTheDocument()

    await userEvent.clear(prefix)
    await userEvent.type(prefix, 'GRP-')

    expect(await screen.findByText('Ejemplo: GRP-00000042')).toBeInTheDocument()
  })

  it('saves the profile and the settings as one request', async () => {
    mockCompany(['iam.company:read', 'iam.company:manage'])
    adminApiMocks.fetchCompanyProfile.mockResolvedValue(profile())
    adminApiMocks.updateCompanyProfile.mockResolvedValue(profile({ name: 'Acme Logistics SA' }))

    renderPage()
    const name = await screen.findByDisplayValue('Acme Logistics')

    await userEvent.clear(name)
    await userEvent.type(name, 'Acme Logistics SA')
    await userEvent.click(screen.getByRole('button', { name: 'Guardar' }))

    await waitFor(() => {
      expect(adminApiMocks.updateCompanyProfile).toHaveBeenCalledWith('company-1', {
        name: 'Acme Logistics SA',
        taxIdentifier: '20123456789',
        timeZone: 'America/Lima',
        defaultCountry: 'PE',
        orderNumberPrefix: 'TO-',
        shipmentNumberPrefix: 'SH-',
      })
    })
    // The switcher shows the name and the shell measures "today" in the zone, so a save has to
    // reach `/me` and not only this screen.
    expect(refetchCompanies).toHaveBeenCalled()
  })

  it('is read-only for a caller who holds iam.company:read alone', async () => {
    mockCompany(['iam.company:read'])
    adminApiMocks.fetchCompanyProfile.mockResolvedValue(profile())

    renderPage()

    expect(await screen.findByDisplayValue('Acme Logistics')).toBeDisabled()
    expect(screen.queryByRole('button', { name: 'Guardar' })).not.toBeInTheDocument()
  })

  /**
   * `canCreateCompany` is not derivable from any permission the shell holds - a company
   * administrator holds `iam.company:manage` too - so the backend answers it and the button follows.
   * Hiding is UX; the endpoint re-asks the database and answers 403.
   */
  it('offers to add a company only when the caller holds an organization-wide role', async () => {
    mockCompany(['iam.company:read', 'iam.company:manage'])
    adminApiMocks.fetchCompanyProfile.mockResolvedValue(profile({ canCreateCompany: false }))

    const { unmount } = renderPage()
    await screen.findByDisplayValue('Acme Logistics')
    expect(screen.queryByRole('button', { name: 'Nueva compañía' })).not.toBeInTheDocument()
    unmount()

    adminApiMocks.fetchCompanyProfile.mockResolvedValue(profile({ canCreateCompany: true }))
    renderPage()

    expect(await screen.findByRole('button', { name: 'Nueva compañía' })).toBeInTheDocument()
  })

  it('says so when the organization above the company is inactive', async () => {
    mockCompany(['iam.company:read'])
    adminApiMocks.fetchCompanyProfile.mockResolvedValue(profile({ organizationActive: false }))

    renderPage()

    expect(await screen.findByText('Organización inactiva')).toBeInTheDocument()
  })
})
