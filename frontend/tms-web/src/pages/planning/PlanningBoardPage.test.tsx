import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter, Route, Routes } from 'react-router-dom'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { ApiError } from '../../shared/api/httpClient'
import type { PlanningRunDetailView, TripView } from '../../shared/api/planningApi'
import { PlanningBoardPage } from './PlanningBoardPage'

const planningApiMocks = vi.hoisted(() => ({
  fetchPlanningRun: vi.fn(),
  confirmPlanningRun: vi.fn(),
  cancelPlanningRun: vi.fn(),
  fetchEligibleOrders: vi.fn(),
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

const companyMocks = vi.hoisted(() => ({ useCompany: vi.fn() }))
vi.mock('../../shared/company/CompanyContext', () => ({ useCompany: companyMocks.useCompany }))

const alertMocks = vi.hoisted(() => ({
  notifySuccess: vi.fn(),
  notifyError: vi.fn(),
  confirmAction: vi.fn(),
}))
vi.mock('../../shared/ui/alerts', () => alertMocks)

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

function board(overrides: Partial<PlanningRunDetailView> = {}): PlanningRunDetailView {
  return {
    run: {
      id: 'run-1', planNumber: 'PLN-1', originId: 'origin-1', originCode: 'ORIGIN-A', originName: 'Origin A',
      planningDate: '2026-03-01', mode: 'MANUAL', status: 'DRAFT', notes: null, tripCount: 1, assignedOrderCount: 0,
      confirmedAt: null, cancelledAt: null, cancelReason: null, version: 2, createdAt: '2026-01-01T00:00:00Z',
      updatedAt: '2026-01-01T00:00:00Z',
    },
    trips: [trip()],
    ...overrides,
  }
}

function mockCompany(permissions: string[] = ['planning.plan:manage', 'planning.trip:manage']) {
  companyMocks.useCompany.mockReturnValue({
    selected: { id: 'company-1', name: 'Acme Logistics' },
    hasPermission: (permission: string) => permissions.includes(permission),
  })
}

function emptyPage() {
  return { content: [], page: 0, size: 10, totalElements: 0 }
}

function renderBoard() {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  return render(
    <QueryClientProvider client={queryClient}>
      <MemoryRouter initialEntries={['/planning/run-1']}>
        <Routes>
          <Route path="/planning" element={<div>Planning runs list</div>} />
          <Route path="/planning/:runId" element={<PlanningBoardPage />} />
        </Routes>
      </MemoryRouter>
    </QueryClientProvider>,
  )
}

afterEach(() => {
  vi.clearAllMocks()
})

describe('PlanningBoardPage', () => {
  it('shows a loading state while the run is fetched', () => {
    mockCompany()
    planningApiMocks.fetchPlanningRun.mockReturnValue(new Promise(() => {}))

    renderBoard()

    expect(screen.getByText('Loading planning run...')).toBeInTheDocument()
  })

  it('shows an error state with retry when the run fails to load', async () => {
    mockCompany()
    planningApiMocks.fetchPlanningRun.mockRejectedValue(new ApiError(500, { code: 'internal-error' }, 'corr-1', 'boom'))

    renderBoard()

    expect(await screen.findByText('Ocurrió un error de nuestro lado. Vuelve a intentarlo.')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Reintentar' })).toBeInTheDocument()
  })

  it('renders the run header, its trips as cards, and the eligible orders panel', async () => {
    mockCompany()
    planningApiMocks.fetchPlanningRun.mockResolvedValue(board())
    planningApiMocks.fetchEligibleOrders.mockResolvedValue(emptyPage())
    destinationsApiMocks.fetchDestinations.mockResolvedValue(emptyPage())

    renderBoard()

    expect(await screen.findByText('PLN-1')).toBeInTheDocument()
    expect(screen.getByText('Origin A · 2026-03-01')).toBeInTheDocument()
    expect(screen.getByText('Trip 1')).toBeInTheDocument()
    expect(screen.getByText('Eligible orders')).toBeInTheDocument()
  })

  it('hides run and trip management actions for a caller without manage permissions', async () => {
    mockCompany([])
    planningApiMocks.fetchPlanningRun.mockResolvedValue(board())
    planningApiMocks.fetchEligibleOrders.mockResolvedValue(emptyPage())
    destinationsApiMocks.fetchDestinations.mockResolvedValue(emptyPage())

    renderBoard()

    await screen.findByText('PLN-1')
    expect(screen.queryByRole('button', { name: 'New trip' })).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Confirm plan' })).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Cancel plan' })).not.toBeInTheDocument()
  })

  it('confirms the plan only after the dialog is accepted, sending the run version', async () => {
    mockCompany()
    planningApiMocks.fetchPlanningRun.mockResolvedValue(board())
    planningApiMocks.fetchEligibleOrders.mockResolvedValue(emptyPage())
    destinationsApiMocks.fetchDestinations.mockResolvedValue(emptyPage())
    planningApiMocks.confirmPlanningRun.mockResolvedValue(board({ run: { ...board().run, status: 'CONFIRMED' } }))

    renderBoard()
    await screen.findByText('PLN-1')

    alertMocks.confirmAction.mockResolvedValueOnce(true)
    await userEvent.click(screen.getByRole('button', { name: 'Confirm plan' }))

    await waitFor(() => expect(planningApiMocks.confirmPlanningRun).toHaveBeenCalledWith('company-1', 'run-1', { version: 2 }))
    expect(alertMocks.notifySuccess).toHaveBeenCalledWith('Plan confirmed', 'PLN-1')
  })

  it('shows the backend refusal verbatim when confirmation fails an incomplete trip', async () => {
    mockCompany()
    planningApiMocks.fetchPlanningRun.mockResolvedValue(board())
    planningApiMocks.fetchEligibleOrders.mockResolvedValue(emptyPage())
    destinationsApiMocks.fetchDestinations.mockResolvedValue(emptyPage())
    planningApiMocks.confirmPlanningRun.mockRejectedValue(
      new ApiError(409, { code: 'conflict', detail: 'Trip 1 has no vehicle assigned.' }, 'corr-1', 'boom'),
    )

    renderBoard()
    await screen.findByText('PLN-1')

    alertMocks.confirmAction.mockResolvedValueOnce(true)
    await userEvent.click(screen.getByRole('button', { name: 'Confirm plan' }))

    await waitFor(() =>
      expect(alertMocks.notifyError).toHaveBeenCalledWith('Could not confirm the plan', 'Trip 1 has no vehicle assigned.'),
    )
  })

  it('does not offer New trip, Confirm plan or Cancel plan once the run is no longer a draft', async () => {
    mockCompany()
    planningApiMocks.fetchPlanningRun.mockResolvedValue(board({ run: { ...board().run, status: 'CONFIRMED' } }))
    planningApiMocks.fetchEligibleOrders.mockResolvedValue(emptyPage())
    destinationsApiMocks.fetchDestinations.mockResolvedValue(emptyPage())

    renderBoard()

    await screen.findByText('PLN-1')
    expect(screen.queryByRole('button', { name: 'New trip' })).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Confirm plan' })).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Cancel plan' })).not.toBeInTheDocument()
  })

  it('navigates back to the run list', async () => {
    mockCompany()
    planningApiMocks.fetchPlanningRun.mockResolvedValue(board())
    planningApiMocks.fetchEligibleOrders.mockResolvedValue(emptyPage())
    destinationsApiMocks.fetchDestinations.mockResolvedValue(emptyPage())

    renderBoard()
    await screen.findByText('PLN-1')
    await userEvent.click(screen.getByRole('link', { name: '← Planning runs' }))

    expect(await screen.findByText('Planning runs list')).toBeInTheDocument()
  })
})
