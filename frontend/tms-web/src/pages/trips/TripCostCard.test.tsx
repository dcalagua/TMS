import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, describe, expect, it, vi } from 'vitest'
import type { TripCostView } from '../../shared/api/ratesApi'
import { TripCostCard } from './TripCostCard'

const ratesApiMocks = vi.hoisted(() => ({
  fetchTripCost: vi.fn(),
  estimateTripCost: vi.fn(),
  recordActualTripCost: vi.fn(),
  closeTripCost: vi.fn(),
  reopenTripCost: vi.fn(),
}))
vi.mock('../../shared/api/ratesApi', async () => {
  const actual = await vi.importActual<typeof import('../../shared/api/ratesApi')>('../../shared/api/ratesApi')
  return { ...actual, ...ratesApiMocks }
})

const alertMocks = vi.hoisted(() => ({
  notifySuccess: vi.fn(),
  notifyError: vi.fn(),
  confirmAction: vi.fn(),
}))
vi.mock('../../shared/ui/alerts', () => alertMocks)

const NOT_PRICED: TripCostView = {
  tripId: 'trip-1',
  priced: false,
  currency: null,
  estimatedAmount: null,
  estimatedAt: null,
  rateCardId: null,
  rateCardCode: null,
  rateCardName: null,
  rateCardScope: null,
  estimateComplete: true,
  components: [],
  actualAmount: null,
  actualReference: null,
  actualNotes: null,
  actualRecordedAt: null,
  variance: null,
  closed: false,
  closedAt: null,
  updatedAt: null,
}

const PRICED: TripCostView = {
  ...NOT_PRICED,
  priced: true,
  currency: 'PEN',
  estimatedAmount: 154.43,
  estimatedAt: '2026-08-20T10:00:00Z',
  rateCardId: 'card-1',
  rateCardCode: 'NORTE-2026',
  rateCardName: 'Corredor norte 2026',
  rateCardScope: 'ROUTE',
  estimateComplete: true,
  components: [
    {
      component: 'BASE',
      status: 'APPLIED',
      rate: null,
      quantity: null,
      unit: null,
      quantitySource: null,
      amount: 120,
      reason: null,
    },
    {
      component: 'DISTANCE',
      status: 'APPLIED',
      rate: 0.85,
      quantity: 40.5,
      unit: 'KM',
      quantitySource: 'ROUTE_REFERENCE',
      amount: 34.43,
      reason: null,
    },
  ],
  actualAmount: 180,
  variance: 25.57,
  actualRecordedAt: '2026-08-21T09:00:00Z',
}

const INCOMPLETE: TripCostView = {
  ...PRICED,
  estimateComplete: false,
  estimatedAmount: 120,
  actualAmount: null,
  variance: null,
  components: [
    PRICED.components[0]!,
    {
      component: 'DISTANCE',
      status: 'NOT_CALCULABLE',
      rate: null,
      quantity: null,
      unit: null,
      quantitySource: null,
      amount: 0,
      reason: 'DISTANCE_UNKNOWN',
    },
  ],
}

function renderCard(canManage = true) {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  return render(
    <QueryClientProvider client={queryClient}>
      <TripCostCard companyId="company-1" tripId="trip-1" canManage={canManage} />
    </QueryClientProvider>,
  )
}

afterEach(() => {
  vi.clearAllMocks()
})

describe('TripCostCard', () => {
  it('says a shipment has not been priced rather than showing a zero', async () => {
    ratesApiMocks.fetchTripCost.mockResolvedValue(NOT_PRICED)

    renderCard()

    expect(await screen.findByText('Este envío todavía no tiene costo estimado.')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Estimar' })).toBeInTheDocument()
  })

  it('shows estimated, actual and the gap between them, never one relabelled as the other', async () => {
    ratesApiMocks.fetchTripCost.mockResolvedValue(PRICED)

    renderCard()

    expect(await screen.findByText('Estimado')).toBeInTheDocument()
    // es-PE, not es-ES: Peru writes the decimal with a dot and groups thousands with a comma
    // (see LOCALES in shared/i18n/config). The Spanish UI is formatted for where it is used.
    expect(screen.getByText('PEN 154.43')).toBeInTheDocument()
    expect(screen.getByText('PEN 180.00')).toBeInTheDocument()
    expect(screen.getByText('PEN 25.57')).toBeInTheDocument()
    expect(screen.getByText(/NORTE-2026/)).toBeInTheDocument()
  })

  it('shows every line of the estimate, including where a quantity came from', async () => {
    ratesApiMocks.fetchTripCost.mockResolvedValue(PRICED)

    renderCard()

    expect(await screen.findByText('Distancia')).toBeInTheDocument()
    expect(screen.getByText(/40.5 KM × 0.85/)).toBeInTheDocument()
    expect(screen.getByText(/Distancia de referencia de la ruta/)).toBeInTheDocument()
  })

  it('warns above the total when a component could not be calculated', async () => {
    ratesApiMocks.fetchTripCost.mockResolvedValue(INCOMPLETE)

    renderCard()

    // By text, not by role: the card's loading placeholder is also a live region, so
    // findByRole('status') resolves on "Cargando..." before the warning exists.
    expect(await screen.findByText(/La estimación está incompleta/)).toBeInTheDocument()
    expect(screen.getByText('No calculable')).toBeInTheDocument()
    expect(screen.getByText('Sin distancia conocida')).toBeInTheDocument()
  })

  it('re-estimates on demand and refreshes what is shown', async () => {
    ratesApiMocks.fetchTripCost.mockResolvedValue(PRICED)
    ratesApiMocks.estimateTripCost.mockResolvedValue(PRICED)

    renderCard()
    await screen.findByText('Estimado')

    await userEvent.click(screen.getByRole('button', { name: 'Volver a estimar' }))

    await waitFor(() => {
      expect(ratesApiMocks.estimateTripCost).toHaveBeenCalledWith('company-1', 'trip-1')
    })
    expect(alertMocks.notifySuccess).toHaveBeenCalled()
  })

  it('offers no actions without rates.trip_cost:manage', async () => {
    ratesApiMocks.fetchTripCost.mockResolvedValue(PRICED)

    renderCard(false)

    await screen.findByText('Estimado')
    expect(screen.queryByRole('button', { name: 'Volver a estimar' })).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Registrar real' })).not.toBeInTheDocument()
  })
})
