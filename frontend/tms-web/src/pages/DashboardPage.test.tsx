import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { render, screen } from '@testing-library/react'
import { MemoryRouter } from 'react-router-dom'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { DashboardPage } from './DashboardPage'

const companyMocks = vi.hoisted(() => ({ useCompany: vi.fn() }))

vi.mock('../shared/auth/AuthContext', () => ({
  useAuth: () => ({ user: { id: 'user-1', email: 'driver@ebim.test' } }),
}))
vi.mock('../shared/company/CompanyContext', () => ({ useCompany: companyMocks.useCompany }))

const company = {
  id: 'company-1',
  name: 'Acme Logistics',
  timeZone: 'America/Lima',
  organization: { id: 'org-1', code: 'EBIM', name: 'EBIM Group' },
}

function renderDashboard() {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  return render(
    <QueryClientProvider client={queryClient}>
      <MemoryRouter>
        <DashboardPage />
      </MemoryRouter>
    </QueryClientProvider>,
  )
}

function systemInfoResponse(): Response {
  return new Response(
    JSON.stringify({
      application: 'TMS by EBIM',
      version: '0.1.0-SNAPSHOT',
      status: 'UP',
      profiles: ['local'],
      timestamp: '2026-01-01T00:00:00Z',
    }),
    { status: 200, headers: { 'content-type': 'application/json' } },
  )
}

afterEach(() => {
  vi.restoreAllMocks()
  vi.clearAllMocks()
})

describe('DashboardPage', () => {
  it('shows the signed-in identity and the selected company context', () => {
    companyMocks.useCompany.mockReturnValue({ selected: company, status: 'ready', hasCapability: () => true })
    vi.spyOn(globalThis, 'fetch').mockResolvedValue(systemInfoResponse())

    renderDashboard()

    expect(screen.getByText('driver@ebim.test')).toBeInTheDocument()
    expect(screen.getByText('Acme Logistics')).toBeInTheDocument()
    expect(screen.getByText('EBIM Group')).toBeInTheDocument()
    expect(screen.getByText('America/Lima')).toBeInTheDocument()
  })

  it('offers quick access only to the modules the company grants', () => {
    companyMocks.useCompany.mockReturnValue({
      selected: company,
      status: 'ready',
      hasCapability: (capability: string) => capability === 'FLEET_VIEW',
    })
    vi.spyOn(globalThis, 'fetch').mockResolvedValue(systemInfoResponse())

    renderDashboard()

    expect(screen.getByRole('link', { name: 'Vehículos' })).toBeInTheDocument()
    expect(screen.queryByRole('link', { name: 'Orígenes' })).not.toBeInTheDocument()
  })

  it('reports the API as unreachable instead of crashing', async () => {
    companyMocks.useCompany.mockReturnValue({ selected: company, status: 'ready', hasCapability: () => true })
    vi.spyOn(globalThis, 'fetch').mockRejectedValue(new TypeError('connection refused'))

    renderDashboard()

    expect(await screen.findByRole('alert')).toHaveTextContent('Inicia el backend')
  })

  it('invents no operational figures: it shows no counters the backend never returned', () => {
    companyMocks.useCompany.mockReturnValue({ selected: company, status: 'ready', hasCapability: () => true })
    vi.spyOn(globalThis, 'fetch').mockResolvedValue(systemInfoResponse())

    const { container } = renderDashboard()

    // The scaffolding copy the first version shipped with must not reach an operator.
    expect(container.textContent).not.toMatch(/V1 foundation|Next steps/i)
  })
})
