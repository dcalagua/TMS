import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter, Route, Routes, useParams } from 'react-router-dom'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { ApiError } from '../../shared/api/httpClient'
import { TripsPage } from './TripsPage'
import { tripView } from './tripFixtures'

const planningApiMocks = vi.hoisted(() => ({ fetchTrips: vi.fn() }))
vi.mock('../../shared/api/planningApi', async () => {
  const actual = await vi.importActual<typeof import('../../shared/api/planningApi')>('../../shared/api/planningApi')
  return { ...actual, ...planningApiMocks }
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

function page<T>(content: T[]) {
  return { content, page: 0, size: 20, totalElements: content.length }
}

function mockCompany() {
  companyMocks.useCompany.mockReturnValue({
    selected: { id: 'company-1', name: 'Acme Logistics' },
    hasPermission: () => true,
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
      <MemoryRouter initialEntries={['/trips']}>
        <Routes>
          <Route path="/trips" element={<TripsPage />} />
          <Route path="/trips/:tripId" element={<WorkspaceStub />} />
        </Routes>
      </MemoryRouter>
    </QueryClientProvider>,
  )
}

afterEach(() => {
  vi.clearAllMocks()
})

describe('TripsPage', () => {
  it('shows an empty state when the company has no trips yet', async () => {
    mockCompany()
    originsApiMocks.fetchOrigins.mockResolvedValue(page([]))
    carriersApiMocks.fetchCarriers.mockResolvedValue(page([]))
    planningApiMocks.fetchTrips.mockResolvedValue(page([]))

    renderPage()

    expect(await screen.findByText('No hay viajes')).toBeInTheDocument()
  })

  it('shows an error state with a retry action when the request fails', async () => {
    mockCompany()
    originsApiMocks.fetchOrigins.mockResolvedValue(page([]))
    carriersApiMocks.fetchCarriers.mockResolvedValue(page([]))
    planningApiMocks.fetchTrips.mockRejectedValue(new ApiError(500, { code: 'internal-error' }, 'corr-1', 'boom'))

    renderPage()

    expect(await screen.findByRole('button', { name: 'Reintentar' })).toBeInTheDocument()
  })

  it('lists a trip by its shipment number, carrier, plate and status', async () => {
    mockCompany()
    originsApiMocks.fetchOrigins.mockResolvedValue(page([]))
    carriersApiMocks.fetchCarriers.mockResolvedValue(page([]))
    planningApiMocks.fetchTrips.mockResolvedValue(page([tripView('IN_TRANSIT')]))

    renderPage()

    // The shipment number, not "trip 1 of PL-00000001": a trip number means nothing outside its
    // own planning board.
    expect(await screen.findByText('SH-00000042')).toBeInTheDocument()
    expect(screen.getByText('Carrier One')).toBeInTheDocument()
    expect(screen.getByText('ABC-123')).toBeInTheDocument()
    expect(screen.getByText('En ruta', { selector: 'span' })).toBeInTheDocument()
  })

  it('shows an em dash for a trip whose capacity limit is unknown, never a zero', async () => {
    mockCompany()
    originsApiMocks.fetchOrigins.mockResolvedValue(page([]))
    carriersApiMocks.fetchCarriers.mockResolvedValue(page([]))
    const unlimited = { used: 0, limit: null, remaining: null, percentUsed: null, exceeded: false, unlimited: true }
    planningApiMocks.fetchTrips.mockResolvedValue(page([
      tripView('DRAFT', {
        capacity: {
          tripId: 'trip-1', source: 'NONE', orderCount: 0,
          weight: unlimited, volume: unlimited, pallets: unlimited, withinCapacity: true,
        },
      }),
    ]))

    renderPage()
    await screen.findByText('SH-00000042')

    // A null percentage means "no limit known" (`docs/domain/CAPACITY_MODEL.md`); rendering it as
    // 0 % would read as an empty truck.
    expect(screen.getAllByText('—').length).toBeGreaterThan(0)
  })

  it('sends the status filter to the backend rather than filtering in the browser', async () => {
    mockCompany()
    originsApiMocks.fetchOrigins.mockResolvedValue(page([]))
    carriersApiMocks.fetchCarriers.mockResolvedValue(page([]))
    planningApiMocks.fetchTrips.mockResolvedValue(page([tripView('CONFIRMED')]))

    renderPage()
    await screen.findByText('SH-00000042')

    await userEvent.click(screen.getByRole('combobox', { name: /^Estado/ }))
    await userEvent.click(await screen.findByRole('option', { name: 'En ruta' }))
    await userEvent.click(screen.getByRole('button', { name: 'Aplicar filtros' }))

    await waitFor(() =>
      expect(planningApiMocks.fetchTrips).toHaveBeenLastCalledWith(
        expect.objectContaining({ companyId: 'company-1', status: 'IN_TRANSIT' }),
      ),
    )
  })

  it('opens the workspace as its own route when Abrir is clicked', async () => {
    mockCompany()
    originsApiMocks.fetchOrigins.mockResolvedValue(page([]))
    carriersApiMocks.fetchCarriers.mockResolvedValue(page([]))
    planningApiMocks.fetchTrips.mockResolvedValue(page([tripView('CONFIRMED')]))

    renderPage()
    await userEvent.click(await screen.findByRole('button', { name: 'Abrir' }))

    expect(await screen.findByText('Workspace for trip-1')).toBeInTheDocument()
  })
})
