import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { render, screen, waitFor } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { apiRequest } from '../api/httpClient'
import type { CompanyAccessView } from '../api/meApi'
import { CompanyProvider, useCompany } from './CompanyContext'

const authMocks = vi.hoisted(() => ({ useAuth: vi.fn() }))
vi.mock('../auth/AuthContext', () => ({ useAuth: authMocks.useAuth }))

const meMocks = vi.hoisted(() => ({ fetchMe: vi.fn() }))
vi.mock('../api/meApi', () => ({ fetchMe: meMocks.fetchMe }))

// The provider fires a SweetAlert2 toast on a stale company selection; the alert itself is
// out of scope here and jsdom has no layout engine for it to run against.
vi.mock('../ui/alerts', () => ({ notifyError: vi.fn(), notifySuccess: vi.fn(), confirmAction: vi.fn() }))

function company(id: string, name: string, permissions: string[] = [], capabilities: string[] = []): CompanyAccessView {
  return {
    id,
    code: id.toUpperCase(),
    name,
    timeZone: 'UTC',
    organization: { id: 'org-1', code: 'ORG', name: 'EBIM Group' },
    permissions,
    capabilities,
  }
}

function Probe() {
  const { status, companies, selected, hasCapability, selectCompany } = useCompany()
  return (
    <div>
      <div data-testid="status">{status}</div>
      <div data-testid="selected">{selected?.name ?? 'none'}</div>
      <div data-testid="count">{companies.length}</div>
      <div data-testid="cap">{String(hasCapability('MASTER_DATA_VIEW'))}</div>
      {companies.map((item) => (
        <button key={item.id} type="button" onClick={() => selectCompany(item.id)}>
          {item.name}
        </button>
      ))}
    </div>
  )
}

function renderWithProviders() {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  return render(
    <QueryClientProvider client={queryClient}>
      <CompanyProvider>
        <Probe />
      </CompanyProvider>
    </QueryClientProvider>,
  )
}

beforeEach(() => {
  window.localStorage.clear()
})

afterEach(() => {
  vi.clearAllMocks()
})

describe('CompanyProvider', () => {
  it('stays idle and never calls GET /me before sign-in', () => {
    authMocks.useAuth.mockReturnValue({ status: 'signedOut' })

    renderWithProviders()

    expect(screen.getByTestId('status')).toHaveTextContent('idle')
    expect(meMocks.fetchMe).not.toHaveBeenCalled()
  })

  it('exposes only the companies GET /me returned and defaults to the first one', async () => {
    authMocks.useAuth.mockReturnValue({ status: 'signedIn' })
    meMocks.fetchMe.mockResolvedValue({
      user: { id: 'u1', email: 'a@ebim.test', fullName: 'A' },
      companies: [
        company('c1', 'Acme Logistics', ['masterdata.origin:read'], ['MASTER_DATA_VIEW']),
        company('c2', 'Acme West'),
      ],
    })

    renderWithProviders()

    await waitFor(() => expect(screen.getByTestId('status')).toHaveTextContent('ready'))
    expect(screen.getByTestId('count')).toHaveTextContent('2')
    expect(screen.getByTestId('selected')).toHaveTextContent('Acme Logistics')
    expect(screen.getByTestId('cap')).toHaveTextContent('true')
  })

  it('persists the selected company and restores it on the next mount', async () => {
    authMocks.useAuth.mockReturnValue({ status: 'signedIn' })
    meMocks.fetchMe.mockResolvedValue({
      user: { id: 'u1', email: 'a@ebim.test', fullName: 'A' },
      companies: [company('c1', 'Acme Logistics'), company('c2', 'Acme West')],
    })

    const first = renderWithProviders()
    await waitFor(() => expect(screen.getByTestId('status')).toHaveTextContent('ready'))
    screen.getByText('Acme West').click()
    await waitFor(() => expect(screen.getByTestId('selected')).toHaveTextContent('Acme West'))
    first.unmount()

    renderWithProviders()
    await waitFor(() => expect(screen.getByTestId('selected')).toHaveTextContent('Acme West'))
  })

  it('forces reselection when the backend answers company-scope-forbidden', async () => {
    authMocks.useAuth.mockReturnValue({ status: 'signedIn' })
    meMocks.fetchMe.mockResolvedValue({
      user: { id: 'u1', email: 'a@ebim.test', fullName: 'A' },
      companies: [company('c1', 'Acme Logistics'), company('c2', 'Acme West')],
    })

    renderWithProviders()
    await waitFor(() => expect(screen.getByTestId('status')).toHaveTextContent('ready'))
    screen.getByText('Acme West').click()
    await waitFor(() => expect(screen.getByTestId('selected')).toHaveTextContent('Acme West'))

    vi.spyOn(globalThis, 'fetch').mockResolvedValue(
      new Response(
        JSON.stringify({ code: 'company-scope-forbidden', detail: 'The selected company is not available.' }),
        { status: 403, headers: { 'content-type': 'application/json' } },
      ),
    )
    await apiRequest('/orders').catch(() => undefined)

    await waitFor(() => expect(window.localStorage.getItem('tms.selectedCompanyId')).toBeNull())
    await waitFor(() => expect(screen.getByTestId('selected')).toHaveTextContent('Acme Logistics'))
  })
})
