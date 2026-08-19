import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { ApiError } from '../../shared/api/httpClient'
import type { EligibleOrderView, PlanningRunView, TripDetailView, TripView } from '../../shared/api/planningApi'
import { EligibleOrdersPanel } from './EligibleOrdersPanel'

const planningApiMocks = vi.hoisted(() => ({
  fetchEligibleOrders: vi.fn(),
  assignOrderToTrip: vi.fn(),
}))
vi.mock('../../shared/api/planningApi', async () => {
  const actual = await vi.importActual<typeof import('../../shared/api/planningApi')>('../../shared/api/planningApi')
  return { ...actual, ...planningApiMocks }
})

const destinationsApiMocks = vi.hoisted(() => ({ fetchDestinations: vi.fn() }))
vi.mock('../../shared/api/destinationsApi', async () => {
  const actual = await vi.importActual<typeof import('../../shared/api/destinationsApi')>('../../shared/api/destinationsApi')
  return { ...actual, fetchDestinations: destinationsApiMocks.fetchDestinations }
})

const alertMocks = vi.hoisted(() => ({ notifySuccess: vi.fn(), notifyError: vi.fn() }))
vi.mock('../../shared/ui/alerts', () => alertMocks)

const RUN: PlanningRunView = {
  id: 'run-1', planNumber: 'PLN-1', originId: 'origin-1', originCode: 'ORIGIN-A', originName: 'Origin A',
  planningDate: '2026-03-01', mode: 'MANUAL', status: 'DRAFT', notes: null, tripCount: 1, assignedOrderCount: 0,
  confirmedAt: null, cancelledAt: null, cancelReason: null, version: 0, createdAt: '2026-01-01T00:00:00Z',
  updatedAt: '2026-01-01T00:00:00Z',
}

function trip(overrides: Partial<TripView> = {}): TripView {
  return {
    id: 'trip-1', planningRunId: 'run-1', tripNumber: 1, status: 'DRAFT', vehicleId: null, vehicleCode: null,
    vehicleLicensePlate: null, carrierId: null, carrierName: null, plannedDepartureAt: null,
    capacity: {
      tripId: 'trip-1', source: 'NONE', orderCount: 0,
      weight: { used: 0, limit: null, remaining: null, percentUsed: null, exceeded: false, unlimited: true },
      volume: { used: 0, limit: null, remaining: null, percentUsed: null, exceeded: false, unlimited: true },
      pallets: { used: 0, limit: null, remaining: null, percentUsed: null, exceeded: false, unlimited: true },
      withinCapacity: true,
    },
    stopCount: 0, orderCount: 0, version: 0, createdAt: '2026-01-01T00:00:00Z', updatedAt: '2026-01-01T00:00:00Z',
    ...overrides,
  }
}

const ORDER: EligibleOrderView = {
  id: 'order-1', orderNumber: 'TO-1', originId: 'origin-1', destinationId: 'dest-1', destinationCode: 'DEST-A',
  destinationName: 'Destination A', customerName: 'Acme', customerReference: 'PO-1', serviceDate: '2026-03-01',
  priority: 'NORMAL', requestedWindowStart: null, requestedWindowEnd: null, totalWeightKg: 20, totalVolumeM3: 1,
  totalPallets: 1,
}

function page<T>(content: T[], overrides: Partial<{ page: number; size: number; totalElements: number }> = {}) {
  return { content, page: overrides.page ?? 0, size: overrides.size ?? 10, totalElements: overrides.totalElements ?? content.length }
}

function renderPanel(props: { trips?: TripView[]; canManage?: boolean; onAssigned?: (detail: TripDetailView) => void } = {}) {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  const onAssigned = props.onAssigned ?? vi.fn()
  const utils = render(
    <QueryClientProvider client={queryClient}>
      <EligibleOrdersPanel
        companyId="company-1"
        run={RUN}
        trips={props.trips ?? [trip()]}
        canManage={props.canManage ?? true}
        onAssigned={onAssigned}
      />
    </QueryClientProvider>,
  )
  return { ...utils, onAssigned }
}

afterEach(() => {
  vi.clearAllMocks()
})

describe('EligibleOrdersPanel', () => {
  it('fetches eligible orders pinned to the run origin and planning date, one page at a time', async () => {
    destinationsApiMocks.fetchDestinations.mockResolvedValue(page([]))
    planningApiMocks.fetchEligibleOrders.mockResolvedValue(page([ORDER]))

    renderPanel()

    await screen.findByText('TO-1')
    expect(planningApiMocks.fetchEligibleOrders).toHaveBeenCalledWith(
      expect.objectContaining({ originId: 'origin-1', serviceDate: '2026-03-01', page: 0, size: 10 }),
    )
  })

  it('shows an empty state when nothing is eligible', async () => {
    destinationsApiMocks.fetchDestinations.mockResolvedValue(page([]))
    planningApiMocks.fetchEligibleOrders.mockResolvedValue(page([]))

    renderPanel()

    expect(await screen.findByText('Sin pedidos elegibles')).toBeInTheDocument()
  })

  it('hides the assign control for a caller without manage permission', async () => {
    destinationsApiMocks.fetchDestinations.mockResolvedValue(page([]))
    planningApiMocks.fetchEligibleOrders.mockResolvedValue(page([ORDER]))

    renderPanel({ canManage: false })

    await screen.findByText('TO-1')
    expect(screen.queryByRole('button', { name: 'Asignar' })).not.toBeInTheDocument()
  })

  it('prompts to create a trip first when there are no open trips', async () => {
    destinationsApiMocks.fetchDestinations.mockResolvedValue(page([]))
    planningApiMocks.fetchEligibleOrders.mockResolvedValue(page([ORDER]))

    renderPanel({ trips: [] })

    await screen.findByText('TO-1')
    expect(screen.getByText('Crea un viaje antes de asignar pedidos.')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Asignar' })).toBeDisabled()
  })

  it('assigns an order to the selected trip and reports the updated trip detail', async () => {
    destinationsApiMocks.fetchDestinations.mockResolvedValue(page([]))
    planningApiMocks.fetchEligibleOrders.mockResolvedValue(page([ORDER]))
    const detail = { trip: trip({ orderCount: 1 }), assignments: [], stops: [] }
    planningApiMocks.assignOrderToTrip.mockResolvedValue(detail)
    const { onAssigned } = renderPanel()

    await screen.findByText('TO-1')
    await userEvent.click(screen.getByRole('button', { name: 'Asignar' }))

    await waitFor(() =>
      expect(planningApiMocks.assignOrderToTrip).toHaveBeenCalledWith('company-1', 'trip-1', { orderId: 'order-1' }),
    )
    expect(onAssigned).toHaveBeenCalledWith(detail)
    expect(alertMocks.notifySuccess).toHaveBeenCalled()
  })

  it('shows the backend refusal verbatim on a capacity conflict, without swallowing it', async () => {
    destinationsApiMocks.fetchDestinations.mockResolvedValue(page([]))
    planningApiMocks.fetchEligibleOrders.mockResolvedValue(page([ORDER]))
    planningApiMocks.assignOrderToTrip.mockRejectedValue(
      new ApiError(409, { code: 'conflict', detail: 'Trip 1 would exceed capacity: weight 1200.00/1000.00 kg.' }, 'corr-1', 'boom'),
    )
    renderPanel()

    await screen.findByText('TO-1')
    await userEvent.click(screen.getByRole('button', { name: 'Asignar' }))

    await waitFor(() =>
      expect(alertMocks.notifyError).toHaveBeenCalledWith(
        'No se pudo asignar el pedido', 'Trip 1 would exceed capacity: weight 1200.00/1000.00 kg.',
      ),
    )
  })
})
