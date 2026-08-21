import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import type { ComponentProps } from 'react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import type { OrderDetailView } from '../../shared/api/ordersApi'
import { OrderFormDrawer } from './OrderFormDrawer'

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
  declaredWeightKg: null,
  declaredVolumeM3: null,
  declaredPallets: null,
  totalsSource: 'CALCULATED',
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

/**
 * Picks a master through the async lookup: focus opens the panel, the listbox fills from the
 * mocked search, and the entry is clicked. Written as a helper because origin and destination
 * are chosen this way in almost every test, and because it is the interaction that replaced
 * `selectOptions` when the selects became comboboxes.
 */
async function pickLookup(label: RegExp, optionName: RegExp) {
  await userEvent.click(screen.getByLabelText(label))
  await userEvent.click(await screen.findByRole('option', { name: optionName }))
}

const ORIGIN_OPTION = /Origin A/
const DESTINATION_OPTION = /Destination A/

function renderModal(props: Partial<ComponentProps<typeof OrderFormDrawer>> = {}) {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  return render(
    <QueryClientProvider client={queryClient}>
      <OrderFormDrawer companyId="company-1" orderId={null} onClose={vi.fn()} onSaved={vi.fn()} {...props} />
    </QueryClientProvider>,
  )
}

afterEach(() => {
  vi.clearAllMocks()
})

describe('OrderFormDrawer', () => {
  it('rejects an empty submission without calling the API', async () => {
    mockLookups()
    const onSaved = vi.fn()
    renderModal({ onSaved })

    await userEvent.click(screen.getByRole('button', { name: 'Guardar' }))

    expect(await screen.findAllByText('Este campo es obligatorio')).not.toHaveLength(0)
    // Origin, destination and service date are the three required header fields.
    expect(screen.getAllByText('Este campo es obligatorio')).toHaveLength(3)
    expect(ordersApiMocks.createOrder).not.toHaveBeenCalled()
    expect(onSaved).not.toHaveBeenCalled()
  })

  it('creates an order with no lines and shows a zero totals preview', async () => {
    mockLookups()
    ordersApiMocks.createOrder.mockResolvedValue(ORDER_DETAIL)
    const onSaved = vi.fn()
    renderModal({ onSaved })

    expect(screen.getByText(/Totales estimados/)).toHaveTextContent('0 kg')

    await pickLookup(/^origen/i, ORIGIN_OPTION)
    await pickLookup(/^destino/i, DESTINATION_OPTION)
    await userEvent.type(screen.getByLabelText(/^fecha de servicio/i), '2026-03-01')
    await userEvent.click(screen.getByRole('button', { name: 'Guardar' }))

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

    await pickLookup(/^origen/i, ORIGIN_OPTION)
    await pickLookup(/^destino/i, DESTINATION_OPTION)
    await userEvent.type(screen.getByLabelText(/^fecha de servicio/i), '2026-03-01')

    await userEvent.click(screen.getByRole('button', { name: 'Agregar línea' }))
    await userEvent.type(screen.getByLabelText('Código de material de la línea 1'), 'SKU-1')
    await userEvent.type(screen.getByLabelText('Descripción de la línea 1'), 'Widget')
    await userEvent.clear(screen.getByLabelText('Cantidad de la línea 1'))
    await userEvent.type(screen.getByLabelText('Cantidad de la línea 1'), '2')
    await userEvent.type(screen.getByLabelText('Peso unitario de la línea 1'), '10')

    expect(await screen.findByText(/20 kg/)).toBeInTheDocument()

    await userEvent.click(screen.getByRole('button', { name: 'Guardar' }))

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

    await userEvent.click(screen.getByRole('button', { name: 'Agregar línea' }))
    expect(screen.getByLabelText('Código de material de la línea 1')).toBeInTheDocument()

    await userEvent.click(screen.getByRole('button', { name: 'Quitar la línea 1' }))
    expect(screen.queryByLabelText('Código de material de la línea 1')).not.toBeInTheDocument()
    expect(screen.getByText('Aún no hay líneas.')).toBeInTheDocument()
  })

  it('loads and pre-fills an existing order, and calls updateOrder with the order id and current version', async () => {
    mockLookups()
    ordersApiMocks.fetchOrder.mockResolvedValue(ORDER_DETAIL)
    ordersApiMocks.updateOrder.mockResolvedValue(ORDER_DETAIL)
    const onSaved = vi.fn()
    renderModal({ orderId: 'order-1', onSaved })

    expect(screen.getByText('Cargando pedido...')).toBeInTheDocument()

    expect(await screen.findByLabelText(/^fecha de servicio/i)).toHaveValue('2026-03-01')
    expect(screen.getByLabelText('Código de material de la línea 1')).toHaveValue('SKU-1')

    await userEvent.click(screen.getByRole('button', { name: 'Guardar' }))

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
    expect(screen.queryByRole('button', { name: 'Guardar' })).not.toBeInTheDocument()
    expect(screen.getByLabelText(/^fecha de servicio/i)).toBeDisabled()
  })

  it('maps a backend field error onto the matching input', async () => {
    mockLookups()
    ordersApiMocks.createOrder.mockRejectedValue({
      fieldErrors: [{ field: 'originId', message: 'originId does not reference an active origin in this company.' }],
    })
    renderModal()

    await pickLookup(/^origen/i, ORIGIN_OPTION)
    await pickLookup(/^destino/i, DESTINATION_OPTION)
    await userEvent.type(screen.getByLabelText(/^fecha de servicio/i), '2026-03-01')
    await userEvent.click(screen.getByRole('button', { name: 'Guardar' }))

    expect(await screen.findByText('originId does not reference an active origin in this company.')).toBeInTheDocument()
  })

  it('sends declared totals as null when the operator leaves them empty', async () => {
    // null, never 0: "not stated" and "stated as zero" are different inputs to OrderTotals.
    mockLookups()
    ordersApiMocks.createOrder.mockResolvedValue(ORDER_DETAIL)
    renderModal()

    await pickLookup(/^origen/i, ORIGIN_OPTION)
    await pickLookup(/^destino/i, DESTINATION_OPTION)
    await userEvent.type(screen.getByLabelText(/^fecha de servicio/i), '2026-03-01')
    await userEvent.click(screen.getByRole('button', { name: 'Guardar' }))

    await waitFor(() =>
      expect(ordersApiMocks.createOrder).toHaveBeenCalledWith(
        'company-1',
        expect.objectContaining({ declaredWeightKg: null, declaredVolumeM3: null, declaredPallets: null }),
      ),
    )
  })

  it('plans a line-less order from its declared totals and says so', async () => {
    mockLookups()
    ordersApiMocks.createOrder.mockResolvedValue(ORDER_DETAIL)
    renderModal()

    await pickLookup(/^origen/i, ORIGIN_OPTION)
    await pickLookup(/^destino/i, DESTINATION_OPTION)
    await userEvent.type(screen.getByLabelText(/^fecha de servicio/i), '2026-03-01')
    await userEvent.type(screen.getByLabelText(/^peso declarado/i), '1200')
    await userEvent.type(screen.getByLabelText(/^pallets declarados/i), '2')

    // With no lines the declared figures are the effective ones - the DECLARED branch of the rule.
    const totals = screen.getByText(/Totales estimados/).closest('div')
    // Separator-agnostic: which one `format.weight` picks is the locale's business, not this
    // test's, and asserting it here would break the day the test environment's ICU data changes.
    expect(totals).toHaveTextContent(/1.200 kg/)
    expect(totals).toHaveTextContent('2 pallets')
    expect(totals).toHaveTextContent('Declarados')

    await userEvent.click(screen.getByRole('button', { name: 'Guardar' }))

    await waitFor(() =>
      expect(ordersApiMocks.createOrder).toHaveBeenCalledWith(
        'company-1',
        expect.objectContaining({ declaredWeightKg: 1200, declaredPallets: 2, lines: [] }),
      ),
    )
  })

  it('prefers the lines over a declared figure once a line describes the measure', async () => {
    mockLookups()
    renderModal()

    await userEvent.type(screen.getByLabelText(/^peso declarado/i), '999')
    expect(screen.getByText(/Totales estimados/).closest('div')).toHaveTextContent('999 kg')

    await userEvent.click(screen.getByRole('button', { name: 'Agregar línea' }))
    await userEvent.clear(screen.getByLabelText('Cantidad de la línea 1'))
    await userEvent.type(screen.getByLabelText('Cantidad de la línea 1'), '2')
    await userEvent.type(screen.getByLabelText('Peso unitario de la línea 1'), '10')

    const totals = screen.getByText(/Totales estimados/).closest('div')
    expect(totals).toHaveTextContent('20 kg')
    expect(totals).toHaveTextContent('Calculados desde las líneas')
    // Volume: no line describes it, so the declared value - here absent - still fills the gap.
    expect(totals).toHaveTextContent('0 m³')
  })

  it('pre-fills the declared totals of an existing order', async () => {
    mockLookups()
    ordersApiMocks.fetchOrder.mockResolvedValue({
      ...ORDER_DETAIL, lines: [], declaredWeightKg: 1200, declaredPallets: 2, totalsSource: 'DECLARED',
    })
    renderModal({ orderId: 'order-1' })

    expect(await screen.findByLabelText(/^peso declarado/i)).toHaveValue(1200)
    expect(screen.getByLabelText(/^pallets declarados/i)).toHaveValue(2)
    expect(screen.getByLabelText(/^volumen declarado/i)).toHaveValue(null)
  })

  it('closes when Cancel is clicked', async () => {
    mockLookups()
    const onClose = vi.fn()
    renderModal({ onClose })

    await userEvent.click(screen.getByRole('button', { name: 'Cancelar' }))

    expect(onClose).toHaveBeenCalled()
  })
})
