import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { render, screen } from '@testing-library/react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { DashboardPage } from './DashboardPage'

vi.mock('../shared/auth/AuthContext', () => ({
  useAuth: () => ({ user: { id: 'user-1', email: 'driver@ebim.test' } }),
}))
vi.mock('../shared/company/CompanyContext', () => ({
  useCompany: () => ({ selected: { id: 'company-1', name: 'Acme Logistics' } }),
}))

function renderWithProviders() {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  return render(
    <QueryClientProvider client={queryClient}>
      <DashboardPage />
    </QueryClientProvider>,
  )
}

afterEach(() => {
  vi.restoreAllMocks()
})

describe('DashboardPage', () => {
  it('shows the backend identity returned by the TMS API', async () => {
    vi.spyOn(globalThis, 'fetch').mockResolvedValue(
      new Response(
        JSON.stringify({
          application: 'TMS by EBIM',
          version: '0.1.0-SNAPSHOT',
          status: 'UP',
          profiles: ['local'],
          timestamp: '2026-01-01T00:00:00Z',
        }),
        { status: 200, headers: { 'content-type': 'application/json' } },
      ),
    )

    renderWithProviders()

    expect(await screen.findByText('0.1.0-SNAPSHOT')).toBeInTheDocument()
    expect(screen.getByText('UP')).toBeInTheDocument()
  })

  it('warns instead of crashing when the backend is unreachable', async () => {
    vi.spyOn(globalThis, 'fetch').mockRejectedValue(new TypeError('connection refused'))

    renderWithProviders()

    expect(await screen.findByRole('alert')).toHaveTextContent('TMS API unreachable')
  })

  it('shows the signed-in identity and selected company', () => {
    vi.spyOn(globalThis, 'fetch').mockResolvedValue(
      new Response('{}', { status: 200, headers: { 'content-type': 'application/json' } }),
    )

    renderWithProviders()

    expect(screen.getByText('driver@ebim.test')).toBeInTheDocument()
    expect(screen.getByText('Acme Logistics')).toBeInTheDocument()
  })
})
