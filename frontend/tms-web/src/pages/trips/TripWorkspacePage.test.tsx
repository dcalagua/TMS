import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter, Route, Routes } from 'react-router-dom'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { ApiError } from '../../shared/api/httpClient'
import type {
  OrderDeliveryView,
  TransportEventView,
  TripExceptionView,
  TripStatus,
  TripStopView,
  TripView,
} from '../../shared/api/planningApi'
import { TripWorkspacePage } from './TripWorkspacePage'
import { deliveryEvidence, orderDelivery, tripDetail, tripException, tripStop } from './tripFixtures'

const planningApiMocks = vi.hoisted(() => ({
  fetchTrip: vi.fn(),
  fetchTripEvents: vi.fn(),
  markTripReady: vi.fn(),
  dispatchTrip: vi.fn(),
  completeTrip: vi.fn(),
  cancelTrip: vi.fn(),
  arriveAtStop: vi.fn(),
  startStopService: vi.fn(),
  completeStop: vi.fn(),
  skipStop: vi.fn(),
  failStop: vi.fn(),
  reportTripException: vi.fn(),
  resolveTripException: vi.fn(),
  recordDelivery: vi.fn(),
  uploadDeliveryEvidence: vi.fn(),
  downloadDeliveryEvidence: vi.fn(),
}))
vi.mock('../../shared/api/planningApi', async () => {
  const actual = await vi.importActual<typeof import('../../shared/api/planningApi')>('../../shared/api/planningApi')
  return { ...actual, ...planningApiMocks }
})

const companyMocks = vi.hoisted(() => ({ useCompany: vi.fn() }))
vi.mock('../../shared/company/CompanyContext', () => ({ useCompany: companyMocks.useCompany }))

// Tracking is a third query behind a third permission. Mocked unconditionally, not only for the
// tests that use it: an unmocked module here would reach the real client the moment somebody adds
// `monitoring.transport:read` to a fixture.
const trackingApiMocks = vi.hoisted(() => ({ fetchTripTracking: vi.fn() }))
vi.mock('../../shared/api/trackingApi', () => ({ fetchTripTracking: trackingApiMocks.fetchTripTracking }))

// Tendering is a fourth query behind a fourth permission (`planning.tender:read`), and mocked here
// for the same reason tracking is: no fixture below grants it today, and the moment one does an
// unmocked module would reach the real client. `TripTenderCard.test.tsx` is where the card itself
// is exercised.
const tendersApiMocks = vi.hoisted(() => ({ fetchTripTenders: vi.fn() }))
vi.mock('../../shared/api/tendersApi', async () => {
  const actual = await vi.importActual<typeof import('../../shared/api/tendersApi')>(
    '../../shared/api/tendersApi',
  )
  return { ...actual, fetchTripTenders: tendersApiMocks.fetchTripTenders }
})

// The map needs the Google Maps SDK; this suite is about the lifecycle, not the canvas.
vi.mock('../../shared/maps/TripStopMap', () => ({ TripStopMap: () => <div data-testid="trip-stop-map" /> }))

const alertMocks = vi.hoisted(() => ({
  confirmAction: vi.fn(),
  promptForText: vi.fn(),
  notifySuccess: vi.fn(),
  notifyError: vi.fn(),
}))
vi.mock('../../shared/ui/alerts', () => alertMocks)

function mockCompany(permissions: string[] = ['planning.trip:execute', 'planning.trip:manage']) {
  companyMocks.useCompany.mockReturnValue({
    selected: { id: 'company-1', name: 'Acme Logistics' },
    hasPermission: (permission: string) => permissions.includes(permission),
  })
}

function renderWorkspace() {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  return render(
    <QueryClientProvider client={queryClient}>
      <MemoryRouter initialEntries={['/trips/trip-1']}>
        <Routes>
          <Route path="/trips/:tripId" element={<TripWorkspacePage />} />
          <Route path="/trips" element={<div>Trips list</div>} />
        </Routes>
      </MemoryRouter>
    </QueryClientProvider>,
  )
}

async function openIn(
  status: TripStatus,
  overrides: Partial<TripView> = {},
  detailOverrides: {
    stops?: TripStopView[]
    exceptions?: TripExceptionView[]
    deliveries?: OrderDeliveryView[]
  } = {},
) {
  planningApiMocks.fetchTrip.mockResolvedValue(tripDetail(status, overrides, detailOverrides))
  renderWorkspace()
  await screen.findByRole('heading', { level: 1, name: 'SH-00000042' })
}

/** `Select` is a button + listbox, not a native `<select>`: open it, then click the option. */
async function pickOption(comboboxName: RegExp | string, optionName: RegExp | string) {
  await userEvent.click(screen.getByRole('combobox', { name: comboboxName }))
  await userEvent.click(await screen.findByRole('option', { name: optionName }))
}

beforeEach(() => {
  // The timeline is its own query and fires on every render of this page; left unmocked it would
  // reach the real client. An empty day is the right default - each timeline test supplies its own.
  planningApiMocks.fetchTripEvents.mockResolvedValue([])
  tendersApiMocks.fetchTripTenders.mockResolvedValue([])
  trackingApiMocks.fetchTripTracking.mockResolvedValue({
    shipmentNumber: 'SH-00000042',
    status: 'IN_TRANSIT',
    trackable: true,
    providerConfigured: false,
    vehicleCode: 'VH-001',
    vehicleLicensePlate: 'ABC-123',
    lastPosition: null,
    track: [],
  })
})

afterEach(() => {
  vi.clearAllMocks()
})

describe('TripWorkspacePage', () => {
  it('renders the shipment header, its stops and its orders', async () => {
    mockCompany()
    await openIn('CONFIRMED')

    expect(screen.getByText('Confirmado', { selector: 'span' })).toBeInTheDocument()
    // All, not one: the destination names its stop and again the order sitting on that stop, and
    // both are what a dispatcher reads - neither is a duplicate to be removed.
    expect(screen.getAllByText('Tienda Uno')).not.toHaveLength(0)
    expect(screen.getByText('ORD-00000001')).toBeInTheDocument()
    expect(screen.getByText('ABC-123')).toBeInTheDocument()
  })

  describe('the tracking card is behind its own permission', () => {
    it('is absent, and not merely empty, without monitoring.transport:read', async () => {
      mockCompany()
      await openIn('IN_TRANSIT', { actualDepartureAt: '2026-08-20T08:12:00Z' })

      expect(screen.queryByRole('heading', { name: 'Ubicación' })).not.toBeInTheDocument()
      // Nothing is asked for either - a role that will never be allowed an answer must not spend a
      // 403 on every visit to this page.
      expect(trackingApiMocks.fetchTripTracking).not.toHaveBeenCalled()
    })

    it('appears with the permission and says why there is no position', async () => {
      mockCompany(['planning.trip:execute', 'monitoring.transport:read'])
      await openIn('IN_TRANSIT', { actualDepartureAt: '2026-08-20T08:12:00Z' })

      expect(await screen.findByText(/no tiene un proveedor de seguimiento/)).toBeInTheDocument()
      expect(trackingApiMocks.fetchTripTracking).toHaveBeenCalledWith('company-1', 'trip-1', expect.anything())
    })
  })

  it('shows an error state with a retry action when the trip cannot be loaded', async () => {
    mockCompany()
    planningApiMocks.fetchTrip.mockRejectedValue(new ApiError(500, { code: 'internal-error' }, 'corr-1', 'boom'))

    renderWorkspace()

    expect(await screen.findByRole('button', { name: 'Reintentar' })).toBeInTheDocument()
  })

  describe('the action buttons follow allowedTransitions, not a local copy of the lifecycle', () => {
    it('offers ready and cancel on a confirmed trip', async () => {
      mockCompany()
      await openIn('CONFIRMED')

      expect(screen.getByRole('button', { name: 'Marcar listo' })).toBeInTheDocument()
      expect(screen.getByRole('button', { name: 'Cancelar viaje' })).toBeInTheDocument()
      expect(screen.queryByRole('button', { name: 'Registrar salida' })).not.toBeInTheDocument()
      expect(screen.queryByRole('button', { name: 'Cerrar viaje' })).not.toBeInTheDocument()
    })

    it('offers only completion once the vehicle has left - a departed trip cannot be cancelled', async () => {
      mockCompany()
      await openIn('IN_TRANSIT', { readyAt: '2026-08-20T07:30:00Z', actualDepartureAt: '2026-08-20T08:12:00Z' })

      expect(screen.getByRole('button', { name: 'Cerrar viaje' })).toBeInTheDocument()
      expect(screen.queryByRole('button', { name: 'Cancelar viaje' })).not.toBeInTheDocument()
      expect(screen.queryByRole('button', { name: 'Registrar salida' })).not.toBeInTheDocument()
    })

    it('offers nothing on a completed trip', async () => {
      mockCompany()
      await openIn('COMPLETED', {
        readyAt: '2026-08-20T07:30:00Z',
        actualDepartureAt: '2026-08-20T08:12:00Z',
        actualCompletionAt: '2026-08-20T17:40:00Z',
      })

      for (const label of ['Marcar listo', 'Registrar salida', 'Cerrar viaje', 'Cancelar viaje']) {
        expect(screen.queryByRole('button', { name: label })).not.toBeInTheDocument()
      }
    })

    it('hides the execution actions from a caller without planning.trip:execute', async () => {
      mockCompany(['planning.trip:manage'])
      await openIn('CONFIRMED')

      expect(screen.queryByRole('button', { name: 'Marcar listo' })).not.toBeInTheDocument()
      // manage alone still allows withdrawing the trip - cancellation is the shared action.
      expect(screen.getByRole('button', { name: 'Cancelar viaje' })).toBeInTheDocument()
    })
  })

  describe('recording a transition', () => {
    it('sends the version the screen was rendered from and no time when the box is empty', async () => {
      mockCompany()
      alertMocks.confirmAction.mockResolvedValue(true)
      planningApiMocks.markTripReady.mockResolvedValue(tripDetail('READY_FOR_DISPATCH'))
      await openIn('CONFIRMED')

      await userEvent.click(screen.getByRole('button', { name: 'Marcar listo' }))

      await waitFor(() =>
        expect(planningApiMocks.markTripReady).toHaveBeenCalledWith('company-1', 'trip-1', {
          version: 3,
          occurredAt: null,
        }),
      )
    })

    it('sends the operator-supplied time as an instant when one is typed', async () => {
      mockCompany()
      alertMocks.confirmAction.mockResolvedValue(true)
      planningApiMocks.dispatchTrip.mockResolvedValue(tripDetail('IN_TRANSIT'))
      await openIn('READY_FOR_DISPATCH', { readyAt: '2026-08-20T07:30:00Z' })

      // fireEvent, not userEvent.type: a `datetime-local` input is a composite control and
      // typing into it character by character produces intermediate invalid values.
      fireEvent.change(screen.getByLabelText('Hora real'), { target: { value: '2026-08-20T08:47' } })
      await userEvent.click(screen.getByRole('button', { name: 'Registrar salida' }))

      await waitFor(() => expect(planningApiMocks.dispatchTrip).toHaveBeenCalled())
      const [, , request] = planningApiMocks.dispatchTrip.mock.calls[0]!
      // Read as local time - the operator is standing in it - and sent as a UTC instant.
      expect(request.occurredAt).toBe(new Date('2026-08-20T08:47').toISOString())
    })

    it('does nothing when the confirmation is dismissed', async () => {
      mockCompany()
      alertMocks.confirmAction.mockResolvedValue(false)
      await openIn('CONFIRMED')

      await userEvent.click(screen.getByRole('button', { name: 'Marcar listo' }))

      expect(planningApiMocks.markTripReady).not.toHaveBeenCalled()
    })

    it('surfaces a backend refusal instead of pretending the transition happened', async () => {
      mockCompany()
      alertMocks.confirmAction.mockResolvedValue(true)
      planningApiMocks.markTripReady.mockRejectedValue(
        new ApiError(409, { code: 'conflict', detail: 'Trip 1 is DRAFT and cannot move to READY_FOR_DISPATCH.' },
          'corr-1', 'conflict'),
      )
      await openIn('CONFIRMED')

      await userEvent.click(screen.getByRole('button', { name: 'Marcar listo' }))

      await waitFor(() => expect(alertMocks.notifyError).toHaveBeenCalled())
      expect(alertMocks.notifySuccess).not.toHaveBeenCalled()
    })
  })

  describe('cancellation', () => {
    it('asks for a reason inside the confirmation and sends it', async () => {
      mockCompany()
      alertMocks.promptForText.mockResolvedValue('El cliente cerró')
      planningApiMocks.cancelTrip.mockResolvedValue(tripDetail('CANCELLED'))
      await openIn('CONFIRMED')

      await userEvent.click(screen.getByRole('button', { name: 'Cancelar viaje' }))

      await waitFor(() =>
        expect(planningApiMocks.cancelTrip).toHaveBeenCalledWith('company-1', 'trip-1', {
          version: 3,
          reason: 'El cliente cerró',
        }),
      )
      // Required in the dialog, so the request can never carry an empty reason.
      expect(alertMocks.promptForText).toHaveBeenCalledWith(expect.objectContaining({ required: true }))
    })

    it('does nothing when the reason prompt is dismissed', async () => {
      mockCompany()
      alertMocks.promptForText.mockResolvedValue(null)
      await openIn('CONFIRMED')

      await userEvent.click(screen.getByRole('button', { name: 'Cancelar viaje' }))

      expect(planningApiMocks.cancelTrip).not.toHaveBeenCalled()
    })
  })

  describe('the execution timeline', () => {
    it('shows the planned and the actual departure side by side, with the delay between them', async () => {
      mockCompany()
      await openIn('IN_TRANSIT', {
        readyAt: '2026-08-20T07:30:00Z',
        // 47 minutes after the 08:00Z the plan asked for.
        actualDepartureAt: '2026-08-20T08:47:00Z',
      })

      expect(screen.getByText('Salida planificada')).toBeInTheDocument()
      expect(screen.getByText('Salida real')).toBeInTheDocument()
      expect(screen.getByText('47 min de retraso')).toBeInTheDocument()
    })

    it('marks a step that has not happened as pending rather than blank', async () => {
      mockCompany()
      await openIn('CONFIRMED')

      // ready, departed and completed are all still ahead of a freshly confirmed trip.
      expect(screen.getAllByText('Pendiente')).toHaveLength(3)
    })

    it('shows the cancellation reason on a cancelled trip', async () => {
      mockCompany()
      await openIn('CANCELLED', {
        cancelledAt: '2026-08-20T07:00:00Z',
        cancelReason: 'El cliente cerró',
      })

      expect(screen.getByText(/El cliente cerró/)).toBeInTheDocument()
    })
  })

  describe('working the stops', () => {
    const departed = { readyAt: '2026-08-20T07:30:00Z', actualDepartureAt: '2026-08-20T08:12:00Z' }

    it('offers no stop action while the trip has not left, whatever the stop says', async () => {
      mockCompany()
      await openIn('CONFIRMED')

      // The stop is PENDING, but its trip has not been dispatched - and the server says so by
      // returning no allowed transitions, which is the only thing this screen reads.
      expect(screen.queryByRole('button', { name: 'Llegada' })).not.toBeInTheDocument()
      expect(screen.queryByRole('button', { name: 'Omitir' })).not.toBeInTheDocument()
    })

    it('offers exactly the outcomes the server allows for the stop', async () => {
      mockCompany()
      await openIn('IN_TRANSIT', departed)

      expect(screen.getByRole('button', { name: 'Llegada' })).toBeInTheDocument()
      expect(screen.getByRole('button', { name: 'Omitir' })).toBeInTheDocument()
      expect(screen.getByRole('button', { name: 'No atendida' })).toBeInTheDocument()
      // Service and completion only become reachable once the vehicle has arrived.
      expect(screen.queryByRole('button', { name: 'Iniciar atención' })).not.toBeInTheDocument()
      expect(screen.queryByRole('button', { name: 'Atendida' })).not.toBeInTheDocument()
    })

    it('records an arrival with the operator-supplied time and no version', async () => {
      mockCompany()
      planningApiMocks.arriveAtStop.mockResolvedValue(tripDetail('IN_TRANSIT', departed))
      await openIn('IN_TRANSIT', departed)

      fireEvent.change(screen.getByLabelText('Hora real'), { target: { value: '2026-08-20T09:30' } })
      await userEvent.click(screen.getByRole('button', { name: 'Llegada' }))

      await waitFor(() => expect(planningApiMocks.arriveAtStop).toHaveBeenCalled())
      const [companyId, tripId, stopId, request] = planningApiMocks.arriveAtStop.mock.calls[0]!
      expect([companyId, tripId, stopId]).toEqual(['company-1', 'trip-1', 'stop-1'])
      expect(request.occurredAt).toBe(new Date('2026-08-20T09:30').toISOString())
      expect(request).not.toHaveProperty('version')
    })

    it('shows the actual times and the dwell once a stop has been served', async () => {
      mockCompany()
      await openIn('IN_TRANSIT', departed, {
        stops: [
          tripStop('IN_TRANSIT', {
            executionStatus: 'COMPLETED',
            actualArrivalAt: '2026-08-20T09:30:00Z',
            actualDepartureAt: '2026-08-20T09:55:00Z',
            dwellMinutes: 25,
            executionNotes: 'Entregado en recepción',
          }),
        ],
      })

      expect(screen.getByText('Atendida', { selector: 'span' })).toBeInTheDocument()
      expect(screen.getByText(/25 min en la parada/)).toBeInTheDocument()
      expect(screen.getByText('Entregado en recepción')).toBeInTheDocument()
    })

    it('asks for a typed reason before it will record a stop as skipped', async () => {
      mockCompany()
      planningApiMocks.skipStop.mockResolvedValue(tripDetail('IN_TRANSIT', departed))
      await openIn('IN_TRANSIT', departed)

      await userEvent.click(screen.getByRole('button', { name: 'Omitir' }))

      // The drawer, not a bare confirmation: a skip without a reason is the gap this exists to close.
      expect(await screen.findByRole('combobox', { name: /^Motivo/ })).toBeInTheDocument()
      expect(planningApiMocks.skipStop).not.toHaveBeenCalled()

      await pickOption(/^Motivo/, 'Cliente cerrado')
      await userEvent.click(screen.getByRole('button', { name: 'Omitir la parada' }))

      await waitFor(() => expect(planningApiMocks.skipStop).toHaveBeenCalled())
      const [, , stopId, request] = planningApiMocks.skipStop.mock.calls[0]!
      expect(stopId).toBe('stop-1')
      expect(request.exceptionType).toBe('CUSTOMER_CLOSED')
    })

    it('hides every stop action from a caller without planning.trip:execute', async () => {
      mockCompany(['planning.trip:manage'])
      await openIn('IN_TRANSIT', departed)

      for (const label of ['Llegada', 'Omitir', 'No atendida']) {
        expect(screen.queryByRole('button', { name: label })).not.toBeInTheDocument()
      }
    })
  })

  describe('the operational timeline', () => {
    function event(overrides: Partial<TransportEventView> = {}): TransportEventView {
      return {
        id: 'event-1',
        tripId: 'trip-1',
        tripStopId: null,
        stopSequence: null,
        stopDestinationCode: null,
        stopDestinationName: null,
        eventType: 'TRIP_DISPATCHED',
        eventTime: '2026-08-20T08:12:00Z',
        recordedAt: '2026-08-20T08:12:00Z',
        source: 'OPERATOR',
        actorName: 'ana@ebim.test',
        notes: null,
        metadata: null,
        ...overrides,
      }
    }

    it('renders each entry with its label, its actor and the stop it happened at', async () => {
      mockCompany()
      planningApiMocks.fetchTripEvents.mockResolvedValue([
        event(),
        event({
          id: 'event-2',
          eventType: 'ARRIVED_AT_STOP',
          tripStopId: 'stop-1',
          stopSequence: 1,
          stopDestinationName: 'Tienda Uno',
          eventTime: '2026-08-20T09:30:00Z',
          recordedAt: '2026-08-20T09:30:00Z',
        }),
      ])
      await openIn('IN_TRANSIT', { actualDepartureAt: '2026-08-20T08:12:00Z' })

      expect(await screen.findByText('Salida')).toBeInTheDocument()
      expect(screen.getByText('Llegada a la parada')).toBeInTheDocument()
      expect(screen.getAllByText(/ana@ebim.test/)).not.toHaveLength(0)
      expect(screen.getByText(/Parada 1 · Tienda Uno/)).toBeInTheDocument()
    })

    it('flags an entry that was typed long after the fact', async () => {
      mockCompany()
      planningApiMocks.fetchTripEvents.mockResolvedValue([
        event({ eventTime: '2026-08-20T08:12:00Z', recordedAt: '2026-08-20T11:12:00Z' }),
      ])
      await openIn('IN_TRANSIT', { actualDepartureAt: '2026-08-20T08:12:00Z' })

      expect(await screen.findByText(/registrado 180 min después/)).toBeInTheDocument()
    })

    it('says so plainly when nothing has been recorded yet', async () => {
      mockCompany()
      await openIn('CONFIRMED')

      expect(
        await screen.findByText('Todavía no se ha registrado nada en este viaje.'),
      ).toBeInTheDocument()
    })
  })

  describe('problems', () => {
    it('lists them with their stop and offers to close the open ones', async () => {
      mockCompany()
      await openIn('IN_TRANSIT', { actualDepartureAt: '2026-08-20T08:12:00Z' }, {
        exceptions: [tripException()],
      })

      expect(screen.getByText('Cliente cerrado')).toBeInTheDocument()
      expect(screen.getByText('Local cerrado al llegar')).toBeInTheDocument()
      expect(screen.getByRole('button', { name: 'Cerrar incidencia' })).toBeInTheDocument()
    })

    it('sends the resolution note the dialog required', async () => {
      mockCompany()
      alertMocks.promptForText.mockResolvedValue('Se reprogramó para mañana')
      planningApiMocks.resolveTripException.mockResolvedValue(
        tripDetail('IN_TRANSIT', { actualDepartureAt: '2026-08-20T08:12:00Z' }),
      )
      await openIn('IN_TRANSIT', { actualDepartureAt: '2026-08-20T08:12:00Z' }, {
        exceptions: [tripException()],
      })

      await userEvent.click(screen.getByRole('button', { name: 'Cerrar incidencia' }))

      await waitFor(() =>
        expect(planningApiMocks.resolveTripException).toHaveBeenCalledWith(
          'company-1', 'trip-1', 'exception-1', { notes: 'Se reprogramó para mañana' },
        ),
      )
      expect(alertMocks.promptForText).toHaveBeenCalledWith(expect.objectContaining({ required: true }))
    })

    it('offers no closed-problem button once it has been resolved', async () => {
      mockCompany()
      await openIn('COMPLETED', { actualCompletionAt: '2026-08-20T17:40:00Z' }, {
        exceptions: [
          tripException({
            status: 'RESOLVED',
            resolvedAt: '2026-08-20T13:00:00Z',
            resolutionNotes: 'Se reprogramó',
          }),
        ],
      })

      expect(screen.queryByRole('button', { name: 'Cerrar incidencia' })).not.toBeInTheDocument()
      expect(screen.getByText(/Se reprogramó/)).toBeInTheDocument()
    })

    it('can still be reported after the trip is closed - they are written up afterwards', async () => {
      mockCompany()
      await openIn('COMPLETED', { actualCompletionAt: '2026-08-20T17:40:00Z' })

      expect(screen.getByRole('button', { name: 'Reportar incidencia' })).toBeInTheDocument()
    })

    it('is not offered on a draft trip, which has no operations to report on', async () => {
      mockCompany()
      await openIn('DRAFT')

      expect(screen.queryByRole('button', { name: 'Reportar incidencia' })).not.toBeInTheDocument()
    })
  })

  describe('deliveries', () => {
    const departed = { actualDepartureAt: '2026-08-20T08:12:00Z' }
    const servedStop = [
      tripStop('IN_TRANSIT', {
        executionStatus: 'COMPLETED',
        actualArrivalAt: '2026-08-20T09:30:00Z',
        actualDepartureAt: '2026-08-20T09:55:00Z',
      }),
    ]

    it('says nothing about deliveries before the vehicle leaves', async () => {
      mockCompany()
      await openIn('CONFIRMED')

      // Not "Not recorded" against every order: a shipment that has not left has no delivery story,
      // and a column of empty outcomes would be noise that reads like a problem.
      expect(screen.queryByText('Sin registrar')).not.toBeInTheDocument()
      expect(screen.queryByRole('button', { name: 'Registrar entrega' })).not.toBeInTheDocument()
    })

    it('shows an order as not recorded until somebody records it', async () => {
      mockCompany()
      await openIn('IN_TRANSIT', departed, { stops: servedStop })

      expect(screen.getByText('Sin registrar')).toBeInTheDocument()
      expect(screen.getByRole('button', { name: 'Registrar entrega' })).toBeInTheDocument()
    })

    it('shows the recorded outcome, the receiver and the evidence on file', async () => {
      mockCompany()
      await openIn('IN_TRANSIT', departed, {
        stops: servedStop,
        deliveries: [
          orderDelivery({
            result: 'PARTIAL',
            notes: 'Falta un pallet',
            evidence: [deliveryEvidence()],
          }),
        ],
      })

      expect(screen.getByText('Entrega parcial')).toBeInTheDocument()
      expect(screen.getByText(/Recibido por R. Díaz/)).toBeInTheDocument()
      expect(screen.getByText('Falta un pallet')).toBeInTheDocument()
      expect(screen.getByRole('button', { name: /Firma/ })).toBeInTheDocument()
      // A recorded delivery can be corrected and can take another artefact, not recorded twice.
      expect(screen.getByRole('button', { name: 'Corregir' })).toBeInTheDocument()
      expect(screen.getByRole('button', { name: 'Adjuntar' })).toBeInTheDocument()
    })

    it('records one order at one stop, addressing it by both', async () => {
      mockCompany()
      planningApiMocks.recordDelivery.mockResolvedValue(tripDetail('IN_TRANSIT', departed))
      await openIn('IN_TRANSIT', departed, { stops: servedStop })

      await userEvent.click(screen.getByRole('button', { name: 'Registrar entrega' }))
      fireEvent.change(await screen.findByLabelText(/Fecha y hora de entrega/), {
        target: { value: '2026-08-20T09:45' },
      })
      await userEvent.click(screen.getByRole('button', { name: 'Guardar entrega' }))

      await waitFor(() => expect(planningApiMocks.recordDelivery).toHaveBeenCalled())
      const [companyId, tripId, stopId, orderId, request] = planningApiMocks.recordDelivery.mock.calls[0]!
      expect([companyId, tripId, stopId, orderId]).toEqual(['company-1', 'trip-1', 'stop-1', 'order-1'])
      expect(request.result).toBe('DELIVERED')
      expect(request.deliveredAt).toBe(new Date('2026-08-20T09:45').toISOString())
    })

    it('can still be recorded after the trip is closed - that is when the paperwork arrives', async () => {
      mockCompany()
      await openIn('COMPLETED', { actualCompletionAt: '2026-08-20T17:40:00Z' }, {
        stops: [tripStop('COMPLETED', { executionStatus: 'COMPLETED' })],
      })

      expect(screen.getByRole('button', { name: 'Registrar entrega' })).toBeInTheDocument()
    })

    it('offers nothing at a stop the vehicle has not reached', async () => {
      mockCompany()
      await openIn('IN_TRANSIT', departed, { stops: [tripStop('IN_TRANSIT')] })

      expect(screen.getByText('Sin registrar')).toBeInTheDocument()
      expect(screen.queryByRole('button', { name: 'Registrar entrega' })).not.toBeInTheDocument()
    })

    it('hides recording from a caller without planning.trip:execute', async () => {
      mockCompany(['planning.trip:manage'])
      await openIn('IN_TRANSIT', departed, { stops: servedStop })

      expect(screen.queryByRole('button', { name: 'Registrar entrega' })).not.toBeInTheDocument()
    })

    it('attaches an artefact to a recorded delivery', async () => {
      mockCompany()
      planningApiMocks.uploadDeliveryEvidence.mockResolvedValue(tripDetail('IN_TRANSIT', departed))
      await openIn('IN_TRANSIT', departed, { stops: servedStop, deliveries: [orderDelivery()] })

      await userEvent.click(screen.getByRole('button', { name: 'Adjuntar' }))
      const file = new File(['signature'], 'firma.png', { type: 'image/png' })
      await userEvent.upload(await screen.findByLabelText(/Archivo/), file)
      // Two buttons say "Adjuntar" once the drawer is open - the one that opened it and the one
      // that submits it. The drawer's is the later of the two in the document.
      const attachButtons = screen.getAllByRole('button', { name: 'Adjuntar' })
      await userEvent.click(attachButtons[attachButtons.length - 1]!)

      await waitFor(() => expect(planningApiMocks.uploadDeliveryEvidence).toHaveBeenCalled())
      const [companyId, tripId, deliveryId, input] = planningApiMocks.uploadDeliveryEvidence.mock.calls[0]!
      expect([companyId, tripId, deliveryId]).toEqual(['company-1', 'trip-1', 'delivery-1'])
      expect(input.evidenceType).toBe('SIGNATURE')
      expect(input.file).toBe(file)
    })
  })
})
