import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import type { ComponentProps } from 'react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import type { OrderDetailView } from '../../shared/api/ordersApi'
import { OrderFormModal } from './OrderFormModal'

const ordersApiMocks = vi.hoisted(() => ({ fetchOrder: vi.fn(), createOrder: vi.fn(), updateOrder: vi.fn() }))
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

function page<T>(content: T[]) {
  return { content, page: 0, size: 200, totalElements: content.length }
}

const ORIGIN = { id: 'origin-1', code: 'ORIGIN-A', name: 'Origin A', type: 'WAREHOUSE' as const, address: null,
  latitude: null, longitude: null, timeZone: 'America/Lima', externalReference: null, active: true,
  createdAt: '2026-01-01T00:00:00Z', updatedAt: '2026-01-01T00:00:00Z' }

const DESTINATION = { id: 'dest-1', code: 'DEST-A', name: 'Destination A', type: 'CUSTOMER' as const,
  address: null, addressReference: null, district: null, province: null, department: null, country: 'PE',
  latitude: null, longitude: null, zoneId: null, zoneCode: null, zoneName: null, serviceTimeMinutes: 0,
  externalReference: null, active: true, createdAt: '2026-01-01T00:00:00Z', updatedAt: '2026-01-01T00:00:00Z' }

const ORDER_DETAIL: OrderDetailView = {
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
  version: 2,
  createdAt: '2026-01-01T00:00:00Z',
  updatedAt: '2026-01-01T00:00:00Z',
  lines: [
    { id: 'line-1', lineNumber: 1, materialCode: 'SKU-1', materialDescription: 'Widget', quantity: 2, uom: 'EA',
      unitWeightKg: 10, unitVolumeM3: 0.5, lineWeightKg: 20, lineVolumeM3: 1, palletQuantity: 1 },
  ],
}

function mockLookups() {
  originsApiMocks.fetchOrigins.mockResolvedValue(page([ORIGIN]))
  destinationsApiMocks.fetchDestinations.mockResolvedValue(page([DESTINATION]))
}

function renderModal(props: Partial<ComponentProps<typeof OrderFormModal>> = {}) {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  return render(
    <QueryClientProvider client={queryClient}>
      <OrderFormModal companyId="company-1" orderId={null} onClose={vi.fn()} onSaved={vi.fn()} {...props} />
    </QueryClientProvider>,
  )
}

afterEach(() => {
  vi.clearAllMocks()
})

describe('OrderFormModal', () => {
  it('rejects an empty submission without calling the API', async () => {
    mockLookups()
    const onSaved = vi.fn()
    renderModal({ onSaved })

    await userEvent.click(screen.getByRole('button', { name: 'Save' }))

    expect(await screen.findByText('Origin is required')).toBeInTheDocument()
    expect(screen.getByText('Destination is required')).toBeInTheDocument()
    expect(screen.getByText('Service date is required')).toBeInTheDocument()
    expect(ordersApiMocks.createOrder).not.toHaveBeenCalled()
    expect(onSaved).not.toHaveBeenCalled()
  })

  it('creates an order with no lines and shows a zero totals preview', async () => {
    mockLookups()
    ordersApiMocks.createOrder.mockResolvedValue(ORDER_DETAIL)
    const onSaved = vi.fn()
    renderModal({ onSaved })

    expect(screen.getByText(/Estimated totals/)).toHaveTextContent('0.000 kg')

    await screen.findByRole('option', { name: 'Origin A' })
    await userEvent.selectOptions(screen.getByLabelText(/^origin/i), 'origin-1')
    await userEvent.selectOptions(screen.getByLabelText(/^destination/i), 'dest-1')
    await userEvent.type(screen.getByLabelText(/service date/i), '2026-03-01')
    await userEvent.click(screen.getByRole('button', { name: 'Save' }))

    await waitFor(() =>
      expect(ordersApiMocks.createOrder).toHaveBeenCalledWith(
        'company-1',
        expect.objectContaining({ originId: 'origin-1', destinationId: 'dest-1', serviceDate: '2026-03-01', lines: [] }),
      ),
    )
    expect(onSaved).toHaveBeenCalled()
  })

  it('adds a line, computes a live totals preview, and submits it', async () => {
    mockLookups()
    ordersApiMocks.createOrder.mockResolvedValue(ORDER_DETAIL)
    renderModal()

    await screen.findByRole('option', { name: 'Origin A' })
    await userEvent.selectOptions(screen.getByLabelText(/^origin/i), 'origin-1')
    await userEvent.selectOptions(screen.getByLabelText(/^destination/i), 'dest-1')
    await userEvent.type(screen.getByLabelText(/service date/i), '2026-03-01')

    await userEvent.click(screen.getByRole('button', { name: 'Add line' }))
    await userEvent.type(screen.getByLabelText('Line 1 material code'), 'SKU-1')
    await userEvent.type(screen.getByLabelText('Line 1 description'), 'Widget')
    await userEvent.clear(screen.getByLabelText('Line 1 quantity'))
    await userEvent.type(screen.getByLabelText('Line 1 quantity'), '2')
    await userEvent.type(screen.getByLabelText('Line 1 unit weight'), '10')

    expect(await screen.findByText(/20\.000 kg/)).toBeInTheDocument()

    await userEvent.click(screen.getByRole('button', { name: 'Save' }))

    await waitFor(() =>
      expect(ordersApiMocks.createOrder).toHaveBeenCalledWith(
        'company-1',
        expect.objectContaining({
          lines: [expect.objectContaining({ materialCode: 'SKU-1', quantity: 2, unitWeightKg: 10 })],
        }),
      ),
    )
  })

  it('removes a line', async () => {
    mockLookups()
    renderModal()

    await userEvent.click(screen.getByRole('button', { name: 'Add line' }))
    expect(screen.getByLabelText('Line 1 material code')).toBeInTheDocument()

    await userEvent.click(screen.getByRole('button', { name: 'Remove line 1' }))
    expect(screen.queryByLabelText('Line 1 material code')).not.toBeInTheDocument()
    expect(screen.getByText('No lines added yet.')).toBeInTheDocument()
  })

  it('loads and pre-fills an existing order, and calls updateOrder with the order id and current version', async () => {
    mockLookups()
    ordersApiMocks.fetchOrder.mockResolvedValue(ORDER_DETAIL)
    ordersApiMocks.updateOrder.mockResolvedValue(ORDER_DETAIL)
    const onSaved = vi.fn()
    renderModal({ orderId: 'order-1', onSaved })

    expect(screen.getByText('Loading order...')).toBeInTheDocument()

    expect(await screen.findByLabelText(/service date/i)).toHaveValue('2026-03-01')
    expect(screen.getByLabelText('Line 1 material code')).toHaveValue('SKU-1')

    await userEvent.click(screen.getByRole('button', { name: 'Save' }))

    await waitFor(() =>
      expect(ordersApiMocks.updateOrder).toHaveBeenCalledWith(
        'company-1', 'order-1', expect.objectContaining({ version: 2 }),
      ),
    )
    expect(onSaved).toHaveBeenCalled()
  })

  it('shows a cancelled order as read-only with no Save action', async () => {
    mockLookups()
    ordersApiMocks.fetchOrder.mockResolvedValue({ ...ORDER_DETAIL, status: 'CANCELLED', cancelReason: 'customer request' })
    renderModal({ orderId: 'order-1' })

    expect(await screen.findByText(/customer request/)).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Save' })).not.toBeInTheDocument()
    expect(screen.getByLabelText(/service date/i)).toBeDisabled()
  })

  it('maps a backend field error onto the matching input', async () => {
    mockLookups()
    ordersApiMocks.createOrder.mockRejectedValue({
      fieldErrors: [{ field: 'originId', message: 'originId does not reference an active origin in this company.' }],
    })
    renderModal()

    await screen.findByRole('option', { name: 'Origin A' })
    await userEvent.selectOptions(screen.getByLabelText(/^origin/i), 'origin-1')
    await userEvent.selectOptions(screen.getByLabelText(/^destination/i), 'dest-1')
    await userEvent.type(screen.getByLabelText(/service date/i), '2026-03-01')
    await userEvent.click(screen.getByRole('button', { name: 'Save' }))

    expect(await screen.findByText('originId does not reference an active origin in this company.')).toBeInTheDocument()
  })

  it('closes when Cancel is clicked', async () => {
    mockLookups()
    const onClose = vi.fn()
    renderModal({ onClose })

    await userEvent.click(screen.getByRole('button', { name: 'Cancel' }))

    expect(onClose).toHaveBeenCalled()
  })
})
