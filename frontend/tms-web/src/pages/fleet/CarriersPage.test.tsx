import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { ApiError } from '../../shared/api/httpClient'
import type { CarrierView } from '../../shared/api/carriersApi'
import { CarriersPage } from './CarriersPage'

const carriersApiMocks = vi.hoisted(() => ({
  fetchCarriers: vi.fn(),
  createCarrier: vi.fn(),
  updateCarrier: vi.fn(),
  activateCarrier: vi.fn(),
  deactivateCarrier: vi.fn(),
}))
vi.mock('../../shared/api/carriersApi', async () => {
  const actual = await vi.importActual<typeof import('../../shared/api/carriersApi')>('../../shared/api/carriersApi')
  return { ...actual, ...carriersApiMocks }
})

const companyMocks = vi.hoisted(() => ({ useCompany: vi.fn() }))
vi.mock('../../shared/company/CompanyContext', () => ({ useCompany: companyMocks.useCompany }))

const alertMocks = vi.hoisted(() => ({
  notifySuccess: vi.fn(),
  notifyError: vi.fn(),
  confirmAction: vi.fn(),
}))
vi.mock('../../shared/ui/alerts', () => alertMocks)

const CARRIER: CarrierView = {
  id: 'carrier-1',
  code: 'ACME',
  businessName: 'Acme Transport S.A.',
  taxIdType: 'RUC',
  taxIdValue: '20100000001',
  contactName: 'Jane Doe',
  phone: '+51 999 999 999',
  email: 'ops@acme.example.test',
  active: true,
  createdAt: '2026-01-01T00:00:00Z',
  updatedAt: '2026-01-01T00:00:00Z',
}

function page(content: CarrierView[], overrides: Partial<{ page: number; size: number; totalElements: number }> = {}) {
  return { content, page: overrides.page ?? 0, size: overrides.size ?? 25, totalElements: overrides.totalElements ?? content.length }
}

function mockCompany(canManage: boolean) {
  companyMocks.useCompany.mockReturnValue({
    selected: { id: 'company-1', name: 'Acme Logistics' },
    hasPermission: (permission: string) => (permission === 'fleet.carrier:manage' ? canManage : true),
  })
}

function renderPage() {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  return render(
    <QueryClientProvider client={queryClient}>
      <CarriersPage />
    </QueryClientProvider>,
  )
}

afterEach(() => {
  vi.clearAllMocks()
})

describe('CarriersPage', () => {
  it('shows a loading state while the first page is fetched', () => {
    mockCompany(true)
    carriersApiMocks.fetchCarriers.mockReturnValue(new Promise(() => {}))

    renderPage()

    expect(screen.getByText('Cargando registros...')).toBeInTheDocument()
  })

  it('shows an empty state when the company has no carriers yet', async () => {
    mockCompany(true)
    carriersApiMocks.fetchCarriers.mockResolvedValue(page([]))

    renderPage()

    expect(await screen.findByText('No carriers found')).toBeInTheDocument()
  })

  it('shows an error state with a retry action when the request fails', async () => {
    mockCompany(true)
    carriersApiMocks.fetchCarriers.mockRejectedValue(new ApiError(500, { code: 'internal-error' }, 'corr-1', 'boom'))

    renderPage()

    expect(await screen.findByText('Ocurrió un error de nuestro lado. Vuelve a intentarlo.')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Reintentar' })).toBeInTheDocument()
  })

  it('lists carriers returned by the backend', async () => {
    mockCompany(true)
    carriersApiMocks.fetchCarriers.mockResolvedValue(page([CARRIER], { totalElements: 60, size: 25 }))

    renderPage()

    expect(await screen.findByText('ACME')).toBeInTheDocument()
    expect(screen.getByText('Acme Transport S.A.')).toBeInTheDocument()
    expect(screen.getByText('RUC 20100000001')).toBeInTheDocument()
    expect(screen.getByText(/Página 1 de 3/)).toBeInTheDocument()
  })

  it('hides create and manage actions for a caller without fleet.carrier:manage', async () => {
    mockCompany(false)
    carriersApiMocks.fetchCarriers.mockResolvedValue(page([CARRIER]))

    renderPage()

    await screen.findByText('ACME')

    expect(screen.queryByRole('button', { name: 'Nuevo transportista' })).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Edit' })).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Deactivate' })).not.toBeInTheDocument()
  })

  it('creates a carrier through the modal and refreshes the list', async () => {
    mockCompany(true)
    carriersApiMocks.fetchCarriers.mockResolvedValue(page([]))
    carriersApiMocks.createCarrier.mockResolvedValue({ ...CARRIER, code: 'BETA' })

    renderPage()
    await screen.findByText('No carriers found')

    await userEvent.click(screen.getByRole('button', { name: 'Nuevo transportista' }))
    const dialog = screen.getByRole('dialog')
    await userEvent.type(within(dialog).getByLabelText(/^code/i), 'BETA')
    await userEvent.type(within(dialog).getByLabelText(/business name/i), 'Beta Transport')
    await userEvent.type(within(dialog).getByLabelText(/tax id value/i), '20200000002')
    await userEvent.click(within(dialog).getByRole('button', { name: 'Save' }))

    await waitFor(() =>
      expect(carriersApiMocks.createCarrier).toHaveBeenCalledWith('company-1', expect.objectContaining({ code: 'BETA' })),
    )
    await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument())
    expect(alertMocks.notifySuccess).toHaveBeenCalledWith('Carrier created')
  })

  it('deactivates a carrier only after the confirmation dialog is accepted', async () => {
    mockCompany(true)
    carriersApiMocks.fetchCarriers.mockResolvedValue(page([CARRIER]))
    carriersApiMocks.deactivateCarrier.mockResolvedValue({ ...CARRIER, active: false })

    alertMocks.confirmAction.mockResolvedValueOnce(false)
    renderPage()
    await screen.findByText('ACME')

    await userEvent.click(screen.getByRole('button', { name: 'Deactivate' }))
    await waitFor(() => expect(alertMocks.confirmAction).toHaveBeenCalled())
    expect(carriersApiMocks.deactivateCarrier).not.toHaveBeenCalled()

    alertMocks.confirmAction.mockResolvedValueOnce(true)
    await userEvent.click(screen.getByRole('button', { name: 'Deactivate' }))

    await waitFor(() => expect(carriersApiMocks.deactivateCarrier).toHaveBeenCalledWith('company-1', 'carrier-1'))
    expect(alertMocks.notifySuccess).toHaveBeenCalledWith('Carrier deactivated', 'Acme Transport S.A.')
  })

  it('applies the code filter to the query', async () => {
    mockCompany(true)
    carriersApiMocks.fetchCarriers.mockResolvedValue(page([CARRIER]))

    renderPage()
    await screen.findByText('ACME')

    await userEvent.type(screen.getByLabelText(/^code$/i), 'acm')
    await userEvent.click(screen.getByRole('button', { name: 'Aplicar filtros' }))

    await waitFor(() =>
      expect(carriersApiMocks.fetchCarriers).toHaveBeenLastCalledWith(expect.objectContaining({ code: 'acm' })),
    )
  })
})
