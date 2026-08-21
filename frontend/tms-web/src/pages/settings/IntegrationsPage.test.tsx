import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, describe, expect, it, vi } from 'vitest'
import type {
  IntegrationClientView,
  IntegrationRequestView,
  WebhookDeliveryView,
  WebhookSubscriptionView,
} from '../../shared/api/integrationsApi'
import { IntegrationsPage } from './IntegrationsPage'

const apiMocks = vi.hoisted(() => ({
  fetchIntegrationClients: vi.fn(),
  fetchIntegrationRequests: vi.fn(),
  fetchWebhookSubscriptions: vi.fn(),
  fetchWebhookDeliveries: vi.fn(),
  fetchWebhookEventTypes: vi.fn(),
}))
vi.mock('../../shared/api/integrationsApi', async () => {
  const actual = await vi.importActual<typeof import('../../shared/api/integrationsApi')>(
    '../../shared/api/integrationsApi',
  )
  return { ...actual, ...apiMocks }
})

const companyMocks = vi.hoisted(() => ({ useCompany: vi.fn() }))
vi.mock('../../shared/company/CompanyContext', () => ({ useCompany: companyMocks.useCompany }))

const alertMocks = vi.hoisted(() => ({ notifySuccess: vi.fn(), notifyError: vi.fn() }))
vi.mock('../../shared/ui/alerts', () => alertMocks)

function client(overrides: Partial<IntegrationClientView> = {}): IntegrationClientView {
  return {
    id: 'client-1',
    clientId: 'tmsc_AAAAAAAAAAAAAAAAAAAAAA',
    name: 'Store WMS',
    description: null,
    scopes: ['integration.order:write'],
    carrierId: null,
    active: true,
    lastUsedAt: '2026-08-20T10:00:00Z',
    secretRotatedAt: null,
    rotationGraceEndsAt: null,
    revokedAt: null,
    createdAt: '2026-01-01T00:00:00Z',
    updatedAt: '2026-01-01T00:00:00Z',
    ...overrides,
  }
}

function subscription(overrides: Partial<WebhookSubscriptionView> = {}): WebhookSubscriptionView {
  return {
    id: 'sub-1',
    name: 'ERP orders',
    description: null,
    targetUrl: 'https://erp.example.com/tms/webhooks',
    eventTypes: ['SHIPMENT_CONFIRMED'],
    active: true,
    suspendedReason: null,
    secretHint: '7fQ2',
    secretRotatedAt: null,
    consecutiveFailures: 0,
    lastSuccessAt: '2026-08-20T09:00:00Z',
    lastFailureAt: null,
    createdAt: '2026-01-01T00:00:00Z',
    updatedAt: '2026-01-01T00:00:00Z',
    ...overrides,
  }
}

function page<T>(content: T[]) {
  return { content, page: 0, size: 10, totalElements: content.length }
}

function mockCompany(permissions: string[]) {
  companyMocks.useCompany.mockReturnValue({
    selected: { id: 'company-1', name: 'Acme Logistics' },
    profile: { id: 'app-user-me', email: 'me@ebim.test', fullName: 'Me' },
    hasPermission: (permission: string) => permissions.includes(permission),
  })
}

function mockEmptyLists() {
  apiMocks.fetchIntegrationClients.mockResolvedValue(page<IntegrationClientView>([]))
  apiMocks.fetchIntegrationRequests.mockResolvedValue(page<IntegrationRequestView>([]))
  apiMocks.fetchWebhookSubscriptions.mockResolvedValue(page<WebhookSubscriptionView>([]))
  apiMocks.fetchWebhookDeliveries.mockResolvedValue(page<WebhookDeliveryView>([]))
  apiMocks.fetchWebhookEventTypes.mockResolvedValue(['SHIPMENT_CONFIRMED', 'SHIPMENT_COMPLETED'])
}

function renderPage() {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  return render(
    <QueryClientProvider client={queryClient}>
      <IntegrationsPage />
    </QueryClientProvider>,
  )
}

afterEach(() => {
  vi.clearAllMocks()
})

describe('IntegrationsPage', () => {
  it('lists inbound credentials with their scopes and the client id', async () => {
    mockCompany(['integration.client:read', 'integration.webhook:read'])
    mockEmptyLists()
    apiMocks.fetchIntegrationClients.mockResolvedValue(page([client()]))

    renderPage()

    expect(await screen.findByText('Store WMS')).toBeInTheDocument()
    // The client id is the public half of the credential and is shown in full: quoting it is how a
    // support conversation starts.
    expect(screen.getByText('tmsc_AAAAAAAAAAAAAAAAAAAAAA')).toBeInTheDocument()
    expect(screen.getByText('integration.order:write')).toBeInTheDocument()
  })

  it('marks a credential whose rotation window is still open', async () => {
    mockCompany(['integration.client:read'])
    mockEmptyLists()
    apiMocks.fetchIntegrationClients.mockResolvedValue(
      page([client({ rotationGraceEndsAt: '2026-08-28T10:00:00Z' })]),
    )

    renderPage()

    // Two secrets are accepted until the window closes and somebody has to redeploy before it does,
    // so "active" alone would be hiding the fact that matters.
    expect(await screen.findByText('Rotación en curso')).toBeInTheDocument()
  })

  it('hides the issue button from a caller who may only read credentials', async () => {
    mockCompany(['integration.client:read'])
    mockEmptyLists()
    apiMocks.fetchIntegrationClients.mockResolvedValue(page([client()]))

    renderPage()
    await screen.findByText('Store WMS')

    expect(screen.queryByRole('button', { name: /Emitir credencial/ })).not.toBeInTheDocument()
  })

  it('shows only the outbound tab to a caller who cannot read credentials', async () => {
    mockCompany(['integration.webhook:read'])
    mockEmptyLists()

    renderPage()

    expect(await screen.findByRole('tab', { name: /Saliente/ })).toBeInTheDocument()
    expect(screen.queryByRole('tab', { name: /Entrante/ })).not.toBeInTheDocument()
    // ...and it opens on it, rather than on a tab that is not there.
    expect(await screen.findByText('Destinos de webhooks')).toBeInTheDocument()
  })

  it('answers a caller with neither permission with an empty state, not a wall of errors', async () => {
    mockCompany([])
    mockEmptyLists()

    renderPage()

    expect(await screen.findByText('No disponible para tu rol')).toBeInTheDocument()
    expect(apiMocks.fetchIntegrationClients).not.toHaveBeenCalled()
    expect(apiMocks.fetchWebhookSubscriptions).not.toHaveBeenCalled()
  })

  it('lists webhook endpoints without ever showing a usable secret', async () => {
    mockCompany(['integration.client:read', 'integration.webhook:read'])
    mockEmptyLists()
    apiMocks.fetchWebhookSubscriptions.mockResolvedValue(page([subscription()]))

    renderPage()
    await userEvent.click(await screen.findByRole('tab', { name: /Saliente/ }))

    expect(await screen.findByText('ERP orders')).toBeInTheDocument()
    expect(screen.getByText('https://erp.example.com/tms/webhooks')).toBeInTheDocument()
    // Four characters: enough for "the one ending 7fQ2", not enough to sign anything.
    expect(screen.getByText('Secreto terminado en 7fQ2')).toBeInTheDocument()
  })

  it('shows an automatic suspension as such, not as a pause somebody chose', async () => {
    mockCompany(['integration.webhook:read'])
    mockEmptyLists()
    apiMocks.fetchWebhookSubscriptions.mockResolvedValue(
      page([
        subscription({
          active: false,
          suspendedReason: 'Suspended automatically after 10 consecutive deliveries failed.',
          consecutiveFailures: 10,
        }),
      ]),
    )

    renderPage()

    // The operator has to fix their side before reactivating, which "paused" would not tell them.
    expect(await screen.findByText('Suspendido automáticamente')).toBeInTheDocument()
    expect(screen.getByText('10 envíos fallidos seguidos')).toBeInTheDocument()
  })
})
