import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { ApiError } from '../../shared/api/httpClient'
import type { OrderView } from '../../shared/api/ordersApi'
import { OrdersPage } from './OrdersPage'

const ordersApiMocks = vi.hoisted(() => ({
  fetchOrders: vi.fn(),
  markOrderReadyForPlanning: vi.fn(),
  cancelOrder: vi.fn(),
  fetchOrder: vi.fn(),
  createOrder: vi.fn(),
  updateOrder: vi.fn(),
}))
vi.mock('../../shared/api/ordersApi', async () => {
  const actual = await vi.importActual<typeof import('../../shared/api/ordersApi')>('../../shared/api/ordersApi')
  return { ...actual, ...ordersApiMocks }
})

const originsApiMocks = vi.hoisted(() => ({ fetchOrigins: vi.fn() }))
vi.mock('../../shared/api/originsApi', async () => {
  const actual = await vi.importActual<typeof import('../../shared/api/originsApi')>('../../shared/api/originsApi')
  return { ...actual, fetchOrigins: originsApiMocks.fetchOrigins }
})

const destinationsApiMocks = vi.hoisted(() => ({ fetchDestinations: vi.fn() }))
vi.mock('../../shared/api/destinationsApi', async () => {
  const actual =
    await vi.importActual<typeof import('../../shared/api/destinationsApi')>('../../shared/api/destinationsApi')
  return { ...actual, fetchDestinations: destinationsApiMocks.fetchDestinations }
})

const companyMocks = vi.hoisted(() => ({ useCompany: vi.fn() }))
vi.mock('../../shared/company/CompanyContext', () => ({ useCompany: companyMocks.useCompany }))

const alertMocks = vi.hoisted(() => ({
  notifySuccess: vi.fn(),
  notifyError: vi.fn(),
  confirmAction: vi.fn(),
}))
vi.mock('../../shared/ui/alerts', () => alertMocks)

const ORDER: OrderView = {
  id: 'order-1',
  orderNumber: 'TO-00000001',
  externalSource: null,
  externalReference: null,
  originId: 'origin-1',
  originCode: 'ORIGIN-A',
  originName: 'Origin A',
  destinationId: 'dest-1',
  destinationCode: 'DEST-A',
  destinationName: 'Destination A',
  customerName: 'Acme',
  customerReference: 'PO-1',
  serviceDate: '2026-03-01',
  priority: 'NORMAL',
  requestedWindowStart: null,
  requestedWindowEnd: null,
  status: 'NOT_READY',
  cancelReason: null,
  totalWeightKg: 20,
  totalVolumeM3: 1,
  totalPallets: 1,
  lineCount: 2,
  version: 0,
  createdAt: '2026-01-01T00:00:00Z',
  updatedAt: '2026-01-01T00:00:00Z',
}

function page<T>(content: T[], overrides: Partial<{ page: number; size: number; totalElements: number }> = {}) {
  return { content, page: overrides.page ?? 0, size: overrides.size ?? 25, totalElements: overrides.totalElements ?? content.length }
}

function mockCompany(canManage: boolean) {
  companyMocks.useCompany.mockReturnValue({
    selected: { id: 'company-1', name: 'Acme Logistics' },
    hasPermission: (permission: string) => (permission === 'orders.order:manage' ? canManage : true),
  })
}

function mockLookups() {
  originsApiMocks.fetchOrigins.mockResolvedValue(page([]))
  destinationsApiMocks.fetchDestinations.mockResolvedValue(page([]))
}

function renderPage() {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  return render(
    <QueryClientProvider client={queryClient}>
      <OrdersPage />
    </QueryClientProvider>,
  )
}

afterEach(() => {
  vi.clearAllMocks()
})

describe('OrdersPage', () => {
  it('shows a loading state while the first page is fetched', () => {
    mockCompany(true)
    mockLookups()
    ordersApiMocks.fetchOrders.mockReturnValue(new Promise(() => {}))

    renderPage()

    expect(screen.getByText('Cargando registros...')).toBeInTheDocument()
  })

  it('shows an empty state when the company has no orders yet', async () => {
    mockCompany(true)
    mockLookups()
    ordersApiMocks.fetchOrders.mockResolvedValue(page([]))

    renderPage()

    expect(await screen.findByText('No orders found')).toBeInTheDocument()
  })

  it('shows an error state with a retry action when the request fails', async () => {
    mockCompany(true)
    mockLookups()
    ordersApiMocks.fetchOrders.mockRejectedValue(new ApiError(500, { code: 'internal-error' }, 'corr-1', 'boom'))

    renderPage()

    expect(await screen.findByText('Ocurrió un error de nuestro lado. Vuelve a intentarlo.')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Reintentar' })).toBeInTheDocument()
  })

  it('lists orders with lane, totals, line count and status', async () => {
    mockCompany(true)
    mockLookups()
    ordersApiMocks.fetchOrders.mockResolvedValue(page([ORDER], { totalElements: 60, size: 25 }))

    renderPage()

    expect(await screen.findByText('TO-00000001')).toBeInTheDocument()
    expect(screen.getByText('Origin A → Destination A')).toBeInTheDocument()
    expect(screen.getByText('Not ready', { selector: 'span' })).toBeInTheDocument()
    expect(screen.getByText(/Página 1 de 3/)).toBeInTheDocument()
  })

  it('hides create and manage actions for a caller without orders.order:manage', async () => {
    mockCompany(false)
    mockLookups()
    ordersApiMocks.fetchOrders.mockResolvedValue(page([ORDER]))

    renderPage()
    await screen.findByText('TO-00000001')

    expect(screen.queryByRole('button', { name: 'Nuevo pedido' })).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Edit' })).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Mark ready' })).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Cancel' })).not.toBeInTheDocument()
  })

  it('marks an order ready only after the confirmation dialog is accepted', async () => {
    mockCompany(true)
    mockLookups()
    ordersApiMocks.fetchOrders.mockResolvedValue(page([ORDER]))
    ordersApiMocks.markOrderReadyForPlanning.mockResolvedValue({ ...ORDER, status: 'READY_FOR_PLANNING' })

    alertMocks.confirmAction.mockResolvedValueOnce(false)
    renderPage()
    await screen.findByText('TO-00000001')

    await userEvent.click(screen.getByRole('button', { name: 'Mark ready' }))
    await waitFor(() => expect(alertMocks.confirmAction).toHaveBeenCalled())
    expect(ordersApiMocks.markOrderReadyForPlanning).not.toHaveBeenCalled()

    alertMocks.confirmAction.mockResolvedValueOnce(true)
    await userEvent.click(screen.getByRole('button', { name: 'Mark ready' }))

    await waitFor(() => expect(ordersApiMocks.markOrderReadyForPlanning).toHaveBeenCalledWith('company-1', 'order-1'))
    expect(alertMocks.notifySuccess).toHaveBeenCalledWith('Order marked ready for planning', 'TO-00000001')
  })

  it('cancels an order only after the confirmation dialog is accepted', async () => {
    mockCompany(true)
    mockLookups()
    ordersApiMocks.fetchOrders.mockResolvedValue(page([ORDER]))
    ordersApiMocks.cancelOrder.mockResolvedValue({ ...ORDER, status: 'CANCELLED' })

    alertMocks.confirmAction.mockResolvedValueOnce(true)
    renderPage()
    await screen.findByText('TO-00000001')

    await userEvent.click(screen.getByRole('button', { name: 'Cancel' }))

    await waitFor(() => expect(ordersApiMocks.cancelOrder).toHaveBeenCalledWith('company-1', 'order-1'))
    expect(alertMocks.notifySuccess).toHaveBeenCalledWith('Order cancelled', 'TO-00000001')
  })

  it('does not offer mark-ready or cancel for a planned order, only view', async () => {
    mockCompany(true)
    mockLookups()
    ordersApiMocks.fetchOrders.mockResolvedValue(page([{ ...ORDER, status: 'PLANNED' }]))

    renderPage()
    await screen.findByText('TO-00000001')

    expect(screen.queryByRole('button', { name: 'Mark ready' })).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Cancel' })).not.toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'View' })).toBeInTheDocument()
  })

  it('applies the order number filter to the query', async () => {
    mockCompany(true)
    mockLookups()
    ordersApiMocks.fetchOrders.mockResolvedValue(page([ORDER]))

    renderPage()
    await screen.findByText('TO-00000001')

    await userEvent.type(screen.getByLabelText(/order #/i), 'TO-1')
    await userEvent.click(screen.getByRole('button', { name: 'Aplicar filtros' }))

    await waitFor(() =>
      expect(ordersApiMocks.fetchOrders).toHaveBeenLastCalledWith(expect.objectContaining({ orderNumber: 'TO-1' })),
    )
  })
})
