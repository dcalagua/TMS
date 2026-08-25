import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { ApiError } from '../../shared/api/httpClient'
import type { TripDetailView, TripStopView, TripView } from '../../shared/api/planningApi'
import { TripDetailDrawer } from './TripDetailDrawer'

const planningApiMocks = vi.hoisted(() => ({
  fetchTrip: vi.fn(),
  removeOrderFromTrip: vi.fn(),
  moveOrderToTrip: vi.fn(),
  reorderTripStops: vi.fn(),
  updateTripRoute: vi.fn(),
  cancelTrip: vi.fn(),
}))
vi.mock('../../shared/api/planningApi', async () => {
  const actual = await vi.importActual<typeof import('../../shared/api/planningApi')>('../../shared/api/planningApi')
  return { ...actual, ...planningApiMocks }
})

const routesApiMocks = vi.hoisted(() => ({ fetchRoutes: vi.fn() }))
vi.mock('../../shared/api/routesApi', async () => {
  const actual = await vi.importActual<typeof import('../../shared/api/routesApi')>('../../shared/api/routesApi')
  return { ...actual, fetchRoutes: routesApiMocks.fetchRoutes }
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
    id: 'trip-1', companyId: 'company-1', planningRunId: 'run-1', planNumber: 'PL-00000001',
    planningDate: '2026-03-01', shipmentNumber: 'SH-00000001', originId: 'origin-1',
    originCode: 'LIM-01', originName: 'Lima depot', originLatitude: null, originLongitude: null,
    vehicleTypeCode: null, routeId: null, routeCode: null, routeName: null,
    tripNumber: 1, status: 'DRAFT', vehicleId: 'vehicle-1',
    vehicleCode: 'VH-1', vehicleLicensePlate: 'ABC-123', carrierId: 'carrier-1', carrierName: 'Acme Carriers',
    plannedDepartureAt: null,
    driverId: null, driverCode: null, driverName: null, driverPhone: null,
    driverLicenseNumber: null, driverLicenseExpiresOn: null, driverLicenseStatus: null,
    readyAt: null, actualDepartureAt: null, actualCompletionAt: null,
    cancelledAt: null, cancelReason: null, allowedTransitions: ['CONFIRMED', 'CANCELLED'],
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

/** A stop nobody has worked yet - the shape every stop of a draft trip has (migration V27). */
const UNWORKED: Pick<
  TripStopView,
  | 'executionStatus'
  | 'allowedExecutionTransitions'
  | 'actualArrivalAt'
  | 'serviceStartedAt'
  | 'actualDepartureAt'
  | 'executionNotes'
  | 'dwellMinutes'
  | 'openExceptionCount'
> = {
  executionStatus: 'PENDING',
  allowedExecutionTransitions: [],
  actualArrivalAt: null,
  serviceStartedAt: null,
  actualDepartureAt: null,
  executionNotes: null,
  dwellMinutes: null,
  openExceptionCount: 0,
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
      // dest-1 is geocoded, dest-2 is not: the drawer must say so for the second rather than
      // implying it can be mapped.
      { id: 'stop-1', sequence: 1, destinationId: 'dest-1', destinationCode: 'DEST-A', destinationName: 'Destination A', latitude: -12.05, longitude: -77.04, address: 'Av. Uno 123', serviceWindowStart: '08:00:00', serviceWindowEnd: '10:00:00', orderCount: 1, ...UNWORKED },
      { id: 'stop-2', sequence: 2, destinationId: 'dest-2', destinationCode: 'DEST-B', destinationName: 'Destination B', latitude: null, longitude: null, address: null, serviceWindowStart: null, serviceWindowEnd: null, orderCount: 1, ...UNWORKED },
    ],
    // This drawer is the *planning* view of a trip: it never shows execution, so the problems
    // and delivery lists are empty here and exercised by the trip workspace instead.
    exceptions: [],
    deliveries: [],
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

beforeEach(() => {
  // The drawer asks for the corridors that depart from this shipment's origin every time it
  // renders an editable trip; tests that care override it.
  routesApiMocks.fetchRoutes.mockResolvedValue({ content: [], page: 0, size: 20, totalElements: 0 })
})

afterEach(() => {
  vi.clearAllMocks()
})

describe('TripDetailDrawer', () => {
  it('renders the shipment header the backend resolved, without joining anything client-side', async () => {
    planningApiMocks.fetchTrip.mockResolvedValue(detail())

    renderDrawer()

    expect(await screen.findByText('SH-00000001')).toBeInTheDocument()
    expect(screen.getByText('PL-00000001')).toBeInTheDocument()
    expect(screen.getByText('Datos del envio'.replace('envio', 'env\u00edo'))).toBeInTheDocument()
    expect(screen.getByText('Lima depot')).toBeInTheDocument()
  })

  it('says a destination cannot be mapped instead of implying it can', async () => {
    planningApiMocks.fetchTrip.mockResolvedValue(detail())

    renderDrawer()

    await screen.findByText('TO-1')
    // dest-1 is geocoded and dest-2 is not, so exactly one stop carries the warning.
    expect(screen.getAllByText('Sin coordenadas')).toHaveLength(1)
  })

  it('offers only the routes of this shipment\'s origin and sends the route with its sequence flag', async () => {
    planningApiMocks.fetchTrip.mockResolvedValue(detail())
    planningApiMocks.updateTripRoute.mockResolvedValue(detail())
    routesApiMocks.fetchRoutes.mockResolvedValue({
      content: [{
        id: 'route-1', code: 'RTE-1', name: 'North corridor', originId: 'origin-1', originCode: 'LIM-01',
        originName: 'Lima depot', zoneId: null, zoneCode: null, zoneName: null, frequencyId: null,
        frequencyCode: null, frequencyName: null, referenceDistanceKm: null, referenceDurationMinutes: null,
        stopCount: 2, active: true, createdAt: '2026-01-01T00:00:00Z', updatedAt: '2026-01-01T00:00:00Z',
      }],
      page: 0, size: 20, totalElements: 1,
    })

    renderDrawer()

    await screen.findByText('TO-1')
    await waitFor(() =>
      expect(routesApiMocks.fetchRoutes).toHaveBeenCalledWith(
        expect.objectContaining({ companyId: 'company-1', originId: 'origin-1', active: true }),
      ),
    )

    await userEvent.click(await screen.findByRole('combobox', { name: 'Ruta' }))
    await userEvent.click(await screen.findByRole('option', { name: 'RTE-1 — North corridor' }))
    await userEvent.click(screen.getByRole('button', { name: 'Guardar la ruta' }))

    await waitFor(() =>
      expect(planningApiMocks.updateTripRoute).toHaveBeenCalledWith('company-1', 'trip-1', {
        routeId: 'route-1', applySequence: true, version: 1,
      }),
    )
  })

  it('records a route without reordering when the planner unticks the sequence box', async () => {
    planningApiMocks.fetchTrip.mockResolvedValue(detail())
    planningApiMocks.updateTripRoute.mockResolvedValue(detail())
    routesApiMocks.fetchRoutes.mockResolvedValue({
      content: [{
        id: 'route-1', code: 'RTE-1', name: 'North corridor', originId: 'origin-1', originCode: 'LIM-01',
        originName: 'Lima depot', zoneId: null, zoneCode: null, zoneName: null, frequencyId: null,
        frequencyCode: null, frequencyName: null, referenceDistanceKm: null, referenceDurationMinutes: null,
        stopCount: 2, active: true, createdAt: '2026-01-01T00:00:00Z', updatedAt: '2026-01-01T00:00:00Z',
      }],
      page: 0, size: 20, totalElements: 1,
    })

    renderDrawer()

    await screen.findByText('TO-1')
    await userEvent.click(screen.getByRole('checkbox', { name: /Reordenar los destinos/ }))
    await userEvent.click(await screen.findByRole('combobox', { name: 'Ruta' }))
    await userEvent.click(await screen.findByRole('option', { name: 'RTE-1 — North corridor' }))
    await userEvent.click(screen.getByRole('button', { name: 'Guardar la ruta' }))

    await waitFor(() =>
      expect(planningApiMocks.updateTripRoute).toHaveBeenCalledWith('company-1', 'trip-1', {
        routeId: 'route-1', applySequence: false, version: 1,
      }),
    )
  })

  it('does not offer the route picker on a confirmed shipment', async () => {
    planningApiMocks.fetchTrip.mockResolvedValue(detail({ trip: trip({ status: 'CONFIRMED' }) }))

    renderDrawer()

    await screen.findByText('TO-1')
    expect(screen.queryByLabelText('Ruta')).not.toBeInTheDocument()
    expect(routesApiMocks.fetchRoutes).not.toHaveBeenCalled()
  })

  it('shows a loading state before the trip detail resolves', () => {
    planningApiMocks.fetchTrip.mockReturnValue(new Promise(() => {}))

    renderDrawer()

    expect(screen.getByText('Cargando el viaje...')).toBeInTheDocument()
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
    expect(screen.queryByRole('button', { name: 'Quitar' })).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Mover' })).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Cambiar vehículo' })).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Cancelar viaje' })).not.toBeInTheDocument()
  })

  it('removes an order and applies the returned trip detail, notifying the board', async () => {
    planningApiMocks.fetchTrip.mockResolvedValue(detail())
    const updated = detail({ assignments: [detail().assignments[1]!] })
    planningApiMocks.removeOrderFromTrip.mockResolvedValue(updated)
    const { onChanged } = renderDrawer()

    await screen.findByText('TO-1')
    await userEvent.click(screen.getAllByRole('button', { name: 'Quitar' })[0]!)

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
    await userEvent.click(screen.getAllByRole('button', { name: 'Mover' })[0]!)

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
    await userEvent.click(screen.getAllByRole('button', { name: 'Mover' })[0]!)

    await waitFor(() =>
      expect(alertMocks.notifyError).toHaveBeenCalledWith('No se pudo mover el pedido', 'Trip 2 would exceed capacity: pallets 12.00/10.00.'),
    )
    expect(screen.getByText('TO-1')).toBeInTheDocument()
  })

  it('reorders stops locally and only enables Save once the order actually changed', async () => {
    planningApiMocks.fetchTrip.mockResolvedValue(detail())
    planningApiMocks.reorderTripStops.mockResolvedValue(detail())
    renderDrawer()

    await screen.findByText('TO-1')
    const saveButton = screen.getByRole('button', { name: 'Guardar el orden' })
    expect(saveButton).toBeDisabled()

    await userEvent.click(screen.getByRole('button', { name: 'Subir el destino 2' }))
    expect(saveButton).toBeEnabled()

    await userEvent.click(saveButton)

    await waitFor(() =>
      expect(planningApiMocks.reorderTripStops).toHaveBeenCalledWith('company-1', 'trip-1', {
        destinationIds: ['dest-2', 'dest-1'],
      }),
    )
  })

  it('shows a hint until a stop is selected, then its address, service window and order count', async () => {
    planningApiMocks.fetchTrip.mockResolvedValue(detail())
    renderDrawer()

    await screen.findByText('TO-1')
    expect(screen.getByText('Selecciona una parada en el mapa o la lista para ver sus datos.')).toBeInTheDocument()

    await userEvent.click(screen.getByRole('button', { name: /Destination A/ }))

    expect(screen.getByText('Av. Uno 123')).toBeInTheDocument()
    expect(screen.getByText('08:00–10:00')).toBeInTheDocument()
    expect(screen.getByText('Parada 1 de 2')).toBeInTheDocument()
  })

  it('says a selected stop has no address or service window instead of leaving it blank', async () => {
    planningApiMocks.fetchTrip.mockResolvedValue(detail())
    renderDrawer()

    await screen.findByText('TO-1')
    await userEvent.click(screen.getByRole('button', { name: /Destination B/ }))

    expect(screen.getByText('Sin dirección registrada')).toBeInTheDocument()
    expect(screen.getByText('Sin ventana de servicio')).toBeInTheDocument()
  })

  it('clears the stop selection once that destination leaves the trip', async () => {
    planningApiMocks.fetchTrip.mockResolvedValue(detail())
    const updated = detail({ stops: [detail().stops[0]!] })
    planningApiMocks.removeOrderFromTrip.mockResolvedValue(updated)
    renderDrawer()

    await screen.findByText('TO-1')
    await userEvent.click(screen.getByRole('button', { name: /Destination B/ }))
    expect(screen.getByText('Parada 2 de 2')).toBeInTheDocument()

    await userEvent.click(screen.getAllByRole('button', { name: 'Quitar' })[1]!)

    await waitFor(() =>
      expect(screen.getByText('Selecciona una parada en el mapa o la lista para ver sus datos.')).toBeInTheDocument(),
    )
  })

  it('cancels the trip only after the confirmation dialog is accepted, sending the trip version', async () => {
    planningApiMocks.fetchTrip.mockResolvedValue(detail())
    planningApiMocks.cancelTrip.mockResolvedValue(detail({ trip: trip({ status: 'CANCELLED' }) }))
    renderDrawer()

    await screen.findByText('TO-1')

    alertMocks.confirmAction.mockResolvedValueOnce(false)
    await userEvent.click(screen.getByRole('button', { name: 'Cancelar viaje' }))
    await waitFor(() => expect(alertMocks.confirmAction).toHaveBeenCalled())
    expect(planningApiMocks.cancelTrip).not.toHaveBeenCalled()

    alertMocks.confirmAction.mockResolvedValueOnce(true)
    await userEvent.click(screen.getByRole('button', { name: 'Cancelar viaje' }))

    await waitFor(() => expect(planningApiMocks.cancelTrip).toHaveBeenCalledWith('company-1', 'trip-1', { version: 1 }))
  })

  it('opens the vehicle modal and applies its result', async () => {
    planningApiMocks.fetchTrip.mockResolvedValue(detail())
    vehiclesApiMocks.fetchVehicles.mockResolvedValue({ content: [], page: 0, size: 200, totalElements: 0 })
    renderDrawer()

    await screen.findByText('TO-1')
    await userEvent.click(screen.getByRole('button', { name: 'Cambiar vehículo' }))

    expect(await screen.findByText('Vehículo del viaje 1')).toBeInTheDocument()
  })
})
