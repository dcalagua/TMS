import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, describe, expect, it, vi } from 'vitest'
import type { AuditEventView } from '../../shared/api/auditApi'
import { AuditPage } from './AuditPage'

const auditApiMocks = vi.hoisted(() => ({ fetchAuditEvents: vi.fn() }))
vi.mock('../../shared/api/auditApi', async () => {
  const actual = await vi.importActual<typeof import('../../shared/api/auditApi')>('../../shared/api/auditApi')
  return { ...actual, ...auditApiMocks }
})

const companyMocks = vi.hoisted(() => ({ useCompany: vi.fn() }))
vi.mock('../../shared/company/CompanyContext', () => ({ useCompany: companyMocks.useCompany }))

function entry(overrides: Partial<AuditEventView> = {}): AuditEventView {
  return {
    id: 'audit-1',
    occurredAt: '2026-08-20T14:30:00Z',
    actorAppUserId: 'app-user-1',
    actorEmail: 'ana@ebim.test',
    actorMachineLabel: null,
    aggregateType: 'TRIP',
    aggregateId: '11111111-2222-3333-4444-555555555555',
    action: 'CANCEL',
    correlationId: 'corr-1',
    metadata: { shipmentNumber: 'SH-00000042', reason: 'customer refused' },
    ...overrides,
  }
}

function page(content: AuditEventView[]) {
  return { content, page: 0, size: 50, totalElements: content.length }
}

function mockCompany() {
  companyMocks.useCompany.mockReturnValue({
    selected: { id: 'company-1', name: 'Acme Logistics' },
    hasPermission: () => true,
    hasCapability: () => true,
  })
}

function renderPage() {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  return render(
    <QueryClientProvider client={queryClient}>
      <AuditPage />
    </QueryClientProvider>,
  )
}

afterEach(() => {
  vi.clearAllMocks()
})

describe('AuditPage', () => {
  it('shows who did what, when, and to which record', async () => {
    mockCompany()
    auditApiMocks.fetchAuditEvents.mockResolvedValue(page([entry()]))

    renderPage()

    expect(await screen.findByText('ana@ebim.test')).toBeInTheDocument()
    expect(screen.getByText('Cancelación')).toBeInTheDocument()
    expect(screen.getByText('Viaje')).toBeInTheDocument()
    expect(screen.getByText('11111111-2222-3333-4444-555555555555')).toBeInTheDocument()
  })

  it('names the credential when a machine acted, not a blank actor', async () => {
    mockCompany()
    auditApiMocks.fetchAuditEvents.mockResolvedValue(
      page([entry({ actorEmail: null, actorAppUserId: null, actorMachineLabel: 'wms-integration' })]),
    )

    renderPage()

    expect(await screen.findByText('wms-integration')).toBeInTheDocument()
    expect(screen.getByText('Credencial de integración')).toBeInTheDocument()
  })

  it('says so when no actor was ever recorded, rather than showing an empty cell', async () => {
    mockCompany()
    auditApiMocks.fetchAuditEvents.mockResolvedValue(
      page([entry({ actorEmail: null, actorAppUserId: null, actorMachineLabel: null })]),
    )

    expect(await screen.findByText('Sin actor registrado', undefined, { container: renderPage().container }))
      .toBeInTheDocument()
  })

  it('asks the server for the newest page and never sends a company id in the query', async () => {
    mockCompany()
    auditApiMocks.fetchAuditEvents.mockResolvedValue(page([entry()]))

    renderPage()
    await screen.findByText('ana@ebim.test')

    // The tenant travels in the header that `apiRequest` sets from `companyId`, which is the
    // caller's scope - never as a filter the screen could get wrong.
    const [request] = auditApiMocks.fetchAuditEvents.mock.calls[0]!
    expect(request.companyId).toBe('company-1')
    expect(request.page).toBe(0)
  })

  it('sends the filters to the server rather than filtering the loaded page', async () => {
    mockCompany()
    auditApiMocks.fetchAuditEvents.mockResolvedValue(page([entry()]))

    renderPage()
    await screen.findByText('ana@ebim.test')

    await userEvent.click(screen.getByRole('combobox', { name: /^acción/i }))
    await userEvent.click(await screen.findByRole('option', { name: 'Cancelación' }))
    await userEvent.click(screen.getByRole('button', { name: 'Aplicar filtros' }))

    await waitFor(() => {
      expect(auditApiMocks.fetchAuditEvents).toHaveBeenLastCalledWith(
        expect.objectContaining({ action: 'CANCEL' }),
      )
    })
  })

  it('opens the detail of one entry with its recorded metadata', async () => {
    mockCompany()
    auditApiMocks.fetchAuditEvents.mockResolvedValue(page([entry()]))

    renderPage()
    await screen.findByText('ana@ebim.test')

    await userEvent.click(screen.getByRole('button', { name: /Ver el detalle de Cancelación/ }))

    const drawer = await screen.findByRole('dialog')
    expect(within(drawer).getByText('customer refused')).toBeInTheDocument()
    expect(within(drawer).getByText('corr-1')).toBeInTheDocument()
    expect(within(drawer).getByText('Este registro es de solo lectura y no puede modificarse.'))
      .toBeInTheDocument()
  })

  it('offers no way to change anything: the trail is read-only', async () => {
    mockCompany()
    auditApiMocks.fetchAuditEvents.mockResolvedValue(page([entry()]))

    renderPage()
    await screen.findByText('ana@ebim.test')

    // Asserted as an absence because that is the product decision. The table has no row menu,
    // and there is no create/edit/delete control anywhere - `tms.audit_event` refuses UPDATE and
    // DELETE to the runtime role, so a button here could only ever produce an error.
    for (const label of [/editar/i, /eliminar/i, /borrar/i, /nuevo/i, /guardar/i]) {
      expect(screen.queryByRole('button', { name: label })).not.toBeInTheDocument()
    }
    expect(screen.queryByRole('button', { name: 'Abrir menú de acciones' })).not.toBeInTheDocument()
  })

  it('says the range is empty rather than showing a blank table', async () => {
    mockCompany()
    auditApiMocks.fetchAuditEvents.mockResolvedValue(page([]))

    renderPage()

    expect(await screen.findByText('Sin movimientos')).toBeInTheDocument()
  })

  it('surfaces a backend refusal instead of an empty history', async () => {
    mockCompany()
    // What the service answers to a window that ends before it starts. An audit screen that
    // rendered "nothing happened" here would be answering a question nobody asked.
    auditApiMocks.fetchAuditEvents.mockRejectedValue(
      Object.assign(new Error('boom'), { status: 400, code: 'malformed-request', problem: null }),
    )

    renderPage()

    expect(await screen.findByRole('button', { name: /Reintentar|Volver a intentar/i })).toBeInTheDocument()
  })
})
