import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { ApiError } from '../../shared/api/httpClient'
import type { TripDetailView, TripView } from '../../shared/api/planningApi'
import { TripDetailDrawer } from './TripDetailDrawer'

const planningApiMocks = vi.hoisted(() => ({
  fetchTrip: vi.fn(),
  removeOrderFromTrip: vi.fn(),
  moveOrderToTrip: vi.fn(),
  reorderTripStops: vi.fn(),
  cancelTrip: vi.fn(),
}))
vi.mock('../../shared/api/planningApi', async () => {
  const actual = await vi.importActual<typeof import('../../shared/api/planningApi')>('../../shared/api/planningApi')
  return { ...actual, ...planningApiMocks }
})

const vehiclesApiMocks = vi.hoisted(() => ({ fetchVehicles: vi.fn() }))
vi.mock('../../shared/api/vehiclesApi', async () => {
  const actual = await vi.importActual<typeof import('../../shared/api/vehiclesApi')>('../../shared/api/vehiclesApi')
  return { ...actual, fetchVehicles: vehiclesApiMocks.fetchVehicles }
})

const alertMocks = vi.hoisted(() => ({
  notifySuccess: vi.fn(),
  notifyError: vi.fn(),
  confirmAction: vi.fn(),
}))
vi.mock('../../shared/ui/alerts', () => alertMocks)

function trip(overrides: Partial<TripView> = {}): TripView {
  return {
    id: 'trip-1', planningRunId: 'run-1', tripNumber: 1, status: 'DRAFT', vehicleId: 'vehicle-1',
    vehicleCode: 'VH-1', vehicleLicensePlate: 'ABC-123', carrierId: 'carrier-1', carrierName: 'Acme Carriers',
    plannedDepartureAt: null,
    capacity: {
      tripId: 'trip-1', source: 'LIVE', orderCount: 2,
      weight: { used: 40, limit: 1000, remaining: 960, percentUsed: 4, exceeded: false, unlimited: false },
      volume: { used: 2, limit: 20, remaining: 18, percentUsed: 10, exceeded: false, unlimited: false },
      pallets: { used: 2, limit: 10, remaining: 8, percentUsed: 20, exceeded: false, unlimited: false },
      withinCapacity: true,
    },
    stopCount: 2, orderCount: 2, version: 1, createdAt: '2026-01-01T00:00:00Z', updatedAt: '2026-01-01T00:00:00Z',
    ...overrides,
  }
}

function detail(overrides: Partial<TripDetailView> = {}): TripDetailView {
  return {
    trip: trip(),
    assignments: [
      {
        assignmentId: 'assign-1', orderId: 'order-1', orderNumber: 'TO-1', destinationId: 'dest-1',
        destinationCode: 'DEST-A', destinationName: 'Destination A', customerName: 'Acme', serviceDate: '2026-03-01',
        priority: 'NORMAL', requestedWindowStart: null, requestedWindowEnd: null, assignedWeightKg: 20,
        assignedVolumeM3: 1, assignedPallets: 1, wholeOrder: true, assignedAt: '2026-01-01T00:00:00Z',
      },
      {
        assignmentId: 'assign-2', orderId: 'order-2', orderNumber: 'TO-2', destinationId: 'dest-2',
        destinationCode: 'DEST-B', destinationName: 'Destination B', customerName: 'Beta', serviceDate: '2026-03-01',
        priority: 'NORMAL', requestedWindowStart: null, requestedWindowEnd: null, assignedWeightKg: 20,
        assignedVolumeM3: 1, assignedPallets: 1, wholeOrder: true, assignedAt: '2026-01-01T00:00:00Z',
      },
    ],
    stops: [
      { id: 'stop-1', sequence: 1, destinationId: 'dest-1', destinationCode: 'DEST-A', destinationName: 'Destination A', serviceWindowStart: null, serviceWindowEnd: null, orderCount: 1 },
      { id: 'stop-2', sequence: 2, destinationId: 'dest-2', destinationCode: 'DEST-B', destinationName: 'Destination B', serviceWindowStart: null, serviceWindowEnd: null, orderCount: 1 },
    ],
    ...overrides,
  }
}

const SIBLING = trip({ id: 'trip-2', tripNumber: 2, vehicleId: null, vehicleCode: null })

function renderDrawer(props: { siblingTrips?: TripView[]; canManage?: boolean; onChanged?: () => void; onClose?: () => void } = {}) {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  const onChanged = props.onChanged ?? vi.fn()
  const onClose = props.onClose ?? vi.fn()
  const utils = render(
    <QueryClientProvider client={queryClient}>
      <TripDetailDrawer
        companyId="company-1"
        tripId="trip-1"
        siblingTrips={props.siblingTrips ?? [trip(), SIBLING]}
        canManage={props.canManage ?? true}
        onClose={onClose}
        onChanged={onChanged}
      />
    </QueryClientProvider>,
  )
  return { ...utils, onChanged, onClose }
}

afterEach(() => {
  vi.clearAllMocks()
})

describe('TripDetailDrawer', () => {
  it('shows a loading state before the trip detail resolves', () => {
    planningApiMocks.fetchTrip.mockReturnValue(new Promise(() => {}))

    renderDrawer()

    expect(screen.getByText('Loading trip...')).toBeInTheDocument()
  })

  it('renders the vehicle, capacity bars and assignments once loaded', async () => {
    planningApiMocks.fetchTrip.mockResolvedValue(detail())

    renderDrawer()

    expect(await screen.findByText('TO-1')).toBeInTheDocument()
    expect(screen.getByText('TO-2')).toBeInTheDocument()
    expect(screen.getByText('VH-1')).toBeInTheDocument()
    expect(screen.getByText('Destination A', { selector: 'span' })).toBeInTheDocument()
  })

  it('hides mutation controls for a caller without manage permission', async () => {
    planningApiMocks.fetchTrip.mockResolvedValue(detail())

    renderDrawer({ canManage: false })

    await screen.findByText('TO-1')
    expect(screen.queryByRole('button', { name: 'Remove' })).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Move' })).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Change vehicle' })).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Cancel trip' })).not.toBeInTheDocument()
  })

  it('removes an order and applies the returned trip detail, notifying the board', async () => {
    planningApiMocks.fetchTrip.mockResolvedValue(detail())
    const updated = detail({ assignments: [detail().assignments[1]!] })
    planningApiMocks.removeOrderFromTrip.mockResolvedValue(updated)
    const { onChanged } = renderDrawer()

    await screen.findByText('TO-1')
    await userEvent.click(screen.getAllByRole('button', { name: 'Remove' })[0]!)

    await waitFor(() => expect(planningApiMocks.removeOrderFromTrip).toHaveBeenCalledWith('company-1', 'trip-1', 'order-1'))
    expect(onChanged).toHaveBeenCalled()
    expect(await screen.findByText('Destination B', { selector: 'span' })).toBeInTheDocument()
    expect(screen.queryByText('TO-1')).not.toBeInTheDocument()
  })

  it('moves an order to the selected sibling trip', async () => {
    planningApiMocks.fetchTrip.mockResolvedValue(detail())
    planningApiMocks.moveOrderToTrip.mockResolvedValue(detail({ assignments: [detail().assignments[1]!] }))
    renderDrawer()

    await screen.findByText('TO-1')
    await userEvent.click(screen.getAllByRole('button', { name: 'Move' })[0]!)

    await waitFor(() =>
      expect(planningApiMocks.moveOrderToTrip).toHaveBeenCalledWith('company-1', 'trip-1', 'order-1', { targetTripId: 'trip-2' }),
    )
  })

  it('shows the backend refusal verbatim when a move is rejected, leaving the source unchanged', async () => {
    planningApiMocks.fetchTrip.mockResolvedValue(detail())
    planningApiMocks.moveOrderToTrip.mockRejectedValue(
      new ApiError(409, { code: 'conflict', detail: 'Trip 2 would exceed capacity: pallets 12.00/10.00.' }, 'corr-1', 'boom'),
    )
    renderDrawer()

    await screen.findByText('TO-1')
    await userEvent.click(screen.getAllByRole('button', { name: 'Move' })[0]!)

    await waitFor(() =>
      expect(alertMocks.notifyError).toHaveBeenCalledWith('Could not move order', 'Trip 2 would exceed capacity: pallets 12.00/10.00.'),
    )
    expect(screen.getByText('TO-1')).toBeInTheDocument()
  })

  it('reorders stops locally and only enables Save once the order actually changed', async () => {
    planningApiMocks.fetchTrip.mockResolvedValue(detail())
    planningApiMocks.reorderTripStops.mockResolvedValue(detail())
    renderDrawer()

    await screen.findByText('TO-1')
    const saveButton = screen.getByRole('button', { name: 'Save stop order' })
    expect(saveButton).toBeDisabled()

    await userEvent.click(screen.getByRole('button', { name: 'Move stop 2 up' }))
    expect(saveButton).toBeEnabled()

    await userEvent.click(saveButton)

    await waitFor(() =>
      expect(planningApiMocks.reorderTripStops).toHaveBeenCalledWith('company-1', 'trip-1', {
        destinationIds: ['dest-2', 'dest-1'],
      }),
    )
  })

  it('cancels the trip only after the confirmation dialog is accepted, sending the trip version', async () => {
    planningApiMocks.fetchTrip.mockResolvedValue(detail())
    planningApiMocks.cancelTrip.mockResolvedValue(detail({ trip: trip({ status: 'CANCELLED' }) }))
    renderDrawer()

    await screen.findByText('TO-1')

    alertMocks.confirmAction.mockResolvedValueOnce(false)
    await userEvent.click(screen.getByRole('button', { name: 'Cancel trip' }))
    await waitFor(() => expect(alertMocks.confirmAction).toHaveBeenCalled())
    expect(planningApiMocks.cancelTrip).not.toHaveBeenCalled()

    alertMocks.confirmAction.mockResolvedValueOnce(true)
    await userEvent.click(screen.getByRole('button', { name: 'Cancel trip' }))

    await waitFor(() => expect(planningApiMocks.cancelTrip).toHaveBeenCalledWith('company-1', 'trip-1', { version: 1 }))
  })

  it('opens the vehicle modal and applies its result', async () => {
    planningApiMocks.fetchTrip.mockResolvedValue(detail())
    vehiclesApiMocks.fetchVehicles.mockResolvedValue({ content: [], page: 0, size: 200, totalElements: 0 })
    renderDrawer()

    await screen.findByText('TO-1')
    await userEvent.click(screen.getByRole('button', { name: 'Change vehicle' }))

    expect(await screen.findByText('Vehículo del viaje 1')).toBeInTheDocument()
  })
})
