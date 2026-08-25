import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter, Route, Routes, useParams } from 'react-router-dom'
import { afterEach, describe, expect, it, vi } from 'vitest'
import type {
  ControlTowerSummaryView,
  ControlTowerTripView,
  ControlTowerView,
} from '../../shared/api/controlTowerApi'
import { ApiError } from '../../shared/api/httpClient'
import { tripView } from '../trips/tripFixtures'
import { ControlTowerPage } from './ControlTowerPage'

const controlTowerMocks = vi.hoisted(() => ({
  fetchControlTower: vi.fn(),
  fetchControlTowerTrips: vi.fn(),
}))
vi.mock('../../shared/api/controlTowerApi', async () => {
  const actual =
    await vi.importActual<typeof import('../../shared/api/controlTowerApi')>('../../shared/api/controlTowerApi')
  return { ...actual, ...controlTowerMocks }
})

const originsApiMocks = vi.hoisted(() => ({ fetchOrigins: vi.fn() }))
vi.mock('../../shared/api/originsApi', async () => {
  const actual = await vi.importActual<typeof import('../../shared/api/originsApi')>('../../shared/api/originsApi')
  return { ...actual, fetchOrigins: originsApiMocks.fetchOrigins }
})

const carriersApiMocks = vi.hoisted(() => ({ fetchCarriers: vi.fn() }))
vi.mock('../../shared/api/carriersApi', async () => {
  const actual = await vi.importActual<typeof import('../../shared/api/carriersApi')>('../../shared/api/carriersApi')
  return { ...actual, fetchCarriers: carriersApiMocks.fetchCarriers }
})

const companyMocks = vi.hoisted(() => ({ useCompany: vi.fn() }))
vi.mock('../../shared/company/CompanyContext', () => ({ useCompany: companyMocks.useCompany }))

const SUMMARY: ControlTowerSummaryView = {
  tripsDraft: 1,
  tripsScheduled: 6,
  tripsInTransit: 4,
  tripsCompleted: 9,
  tripsCancelled: 2,
  tripsDepartedLate: 3,
  tripsOverdue: 2,
  openExceptions: 7,
  outstandingStops: 18,
  stopsPastWindow: 8,
  ordersUnplanned: 12,
}

// Every rendered counter is a different number on purpose: `getByText('5')` proving the delayed
// card is right would prove nothing if two cards showed a 5.

function overview(overrides: Partial<ControlTowerView> = {}): ControlTowerView {
  return {
    date: '2026-08-20',
    generatedAt: '2026-08-20T14:30:00Z',
    summary: SUMMARY,
    workload: [],
    openExceptions: [],
    outstandingStops: [],
    ...overrides,
  }
}

function row(overrides: Partial<ControlTowerTripView> = {}): ControlTowerTripView {
  return {
    trip: tripView('IN_TRANSIT', { actualDepartureAt: '2026-08-20T08:37:00Z' }),
    departureTimeliness: 'LATE',
    departureDelayMinutes: 37,
    stopsTotal: 7,
    stopsResolved: 4,
    stopsPastWindow: 2,
    nextStopSequence: 5,
    nextStopDueAt: '2026-08-20T19:00:00Z',
    openExceptions: 1,
    ...overrides,
  }
}

function page<T>(content: T[]) {
  return { content, page: 0, size: 25, totalElements: content.length }
}

function mockCompany() {
  companyMocks.useCompany.mockReturnValue({
    selected: { id: 'company-1', name: 'Acme Logistics', timeZone: 'America/Lima' },
    status: 'ready',
    hasPermission: () => true,
    hasCapability: () => true,
  })
}

function WorkspaceStub() {
  const { tripId } = useParams()
  return <div>Workspace for {tripId}</div>
}

function renderPage() {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  return render(
    <QueryClientProvider client={queryClient}>
      <MemoryRouter initialEntries={['/control-tower']}>
        <Routes>
          <Route path="/control-tower" element={<ControlTowerPage />} />
          <Route path="/trips/:tripId" element={<WorkspaceStub />} />
        </Routes>
      </MemoryRouter>
    </QueryClientProvider>,
  )
}

/** The two pickers and an empty board, for a test that is about something else. */
function stubSupporting() {
  originsApiMocks.fetchOrigins.mockResolvedValue(page([]))
  carriersApiMocks.fetchCarriers.mockResolvedValue(page([]))
  controlTowerMocks.fetchControlTowerTrips.mockResolvedValue(page([]))
}

afterEach(() => {
  vi.clearAllMocks()
})

describe('ControlTowerPage', () => {
  it('shows the counters the server sent, without recomputing any of them', async () => {
    mockCompany()
    stubSupporting()
    controlTowerMocks.fetchControlTower.mockResolvedValue(overview())

    renderPage()

    // Awaited on a value and not on a label: every KpiCard renders its label while the query is
    // still pending, so findByText('En ruta') resolves against the loading card and the counters
    // are not there yet.
    expect(await screen.findByText('5')).toBeInTheDocument()
    expect(screen.getByText('En ruta')).toBeInTheDocument()
    // Departed late (3) plus still-not-departed (2), with both named in the hint: the two are
    // different phone calls and the screen must not merge them into one opaque number.
    expect(screen.getByText('3 salieron tarde, 2 aún no salen')).toBeInTheDocument()
    expect(screen.getByText('7')).toBeInTheDocument()
    expect(screen.getByText('De 18 pendientes en ruta')).toBeInTheDocument()
  })

  it('says the backlog is unreadable rather than showing it as zero', async () => {
    mockCompany()
    stubSupporting()
    controlTowerMocks.fetchControlTower.mockResolvedValue(
      overview({ summary: { ...SUMMARY, ordersUnplanned: null } }),
    )

    renderPage()

    // Null means "you may not read orders", which is not the same claim as "there are none".
    expect(await screen.findByText('Requiere permiso de lectura de pedidos')).toBeInTheDocument()
    expect(screen.queryByText('Con fecha de servicio de este día')).not.toBeInTheDocument()
  })

  it("renders the server's departure verdict and the minutes behind it", async () => {
    mockCompany()
    originsApiMocks.fetchOrigins.mockResolvedValue(page([]))
    carriersApiMocks.fetchCarriers.mockResolvedValue(page([]))
    controlTowerMocks.fetchControlTower.mockResolvedValue(overview())
    controlTowerMocks.fetchControlTowerTrips.mockResolvedValue(page([row()]))

    renderPage()

    expect(await screen.findByText('SH-00000042')).toBeInTheDocument()
    expect(screen.getByText('Salió tarde')).toBeInTheDocument()
    // The magnitude is shown, not just the verdict: V1 declares no grace period, so the screen
    // has to let a person tell three minutes from ninety-five.
    expect(screen.getByText('+37 min')).toBeInTheDocument()
    expect(screen.getByText('4 de 7')).toBeInTheDocument()
    expect(screen.getByText('2 fuera de ventana')).toBeInTheDocument()
  })

  it('narrows only the table with the status filter, never the day-wide counters', async () => {
    mockCompany()
    originsApiMocks.fetchOrigins.mockResolvedValue(page([]))
    carriersApiMocks.fetchCarriers.mockResolvedValue(page([]))
    controlTowerMocks.fetchControlTower.mockResolvedValue(overview())
    controlTowerMocks.fetchControlTowerTrips.mockResolvedValue(page([row()]))

    renderPage()
    await screen.findByText('SH-00000042')

    await userEvent.click(screen.getByRole('combobox', { name: /^Estado/ }))
    await userEvent.click(await screen.findByRole('option', { name: 'En ruta' }))
    await userEvent.click(screen.getByRole('button', { name: 'Aplicar filtros' }))

    await waitFor(() =>
      expect(controlTowerMocks.fetchControlTowerTrips).toHaveBeenLastCalledWith(
        expect.objectContaining({ companyId: 'company-1', status: 'IN_TRANSIT' }),
      ),
    )
    // The overview is never asked to narrow by state. If it were, filtering to IN_TRANSIT would
    // zero out every other counter and the one screen built to surface problems would hide them.
    for (const [args] of controlTowerMocks.fetchControlTower.mock.calls) {
      expect(args).not.toHaveProperty('status')
    }
  })

  it('opens the trip workspace from a board row', async () => {
    mockCompany()
    originsApiMocks.fetchOrigins.mockResolvedValue(page([]))
    carriersApiMocks.fetchCarriers.mockResolvedValue(page([]))
    controlTowerMocks.fetchControlTower.mockResolvedValue(overview())
    controlTowerMocks.fetchControlTowerTrips.mockResolvedValue(page([row()]))

    renderPage()
    await userEvent.click(await screen.findByRole('button', { name: 'Abrir' }))

    expect(await screen.findByText('Workspace for trip-1')).toBeInTheDocument()
  })

  it('says the day could not be read instead of leaving the counters looking calm', async () => {
    mockCompany()
    stubSupporting()
    controlTowerMocks.fetchControlTower.mockRejectedValue(
      new ApiError(500, { code: 'internal-error' }, 'corr-1', 'boom'),
    )

    renderPage()

    expect(await screen.findByRole('alert')).toBeInTheDocument()
  })

  it('shows an empty table when the day has no trips with the filters applied', async () => {
    mockCompany()
    stubSupporting()
    controlTowerMocks.fetchControlTower.mockResolvedValue(overview())

    renderPage()

    expect(await screen.findByText('Sin viajes')).toBeInTheDocument()
  })
})
