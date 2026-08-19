import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { ApiError } from '../../shared/api/httpClient'
import type { RouteView } from '../../shared/api/routesApi'
import { RoutesPage } from './RoutesPage'

const routesApiMocks = vi.hoisted(() => ({
  fetchRoutes: vi.fn(),
  activateRoute: vi.fn(),
  deactivateRoute: vi.fn(),
}))
vi.mock('../../shared/api/routesApi', async () => {
  const actual = await vi.importActual<typeof import('../../shared/api/routesApi')>('../../shared/api/routesApi')
  return { ...actual, ...routesApiMocks }
})

const originsApiMocks = vi.hoisted(() => ({ fetchOrigins: vi.fn() }))
vi.mock('../../shared/api/originsApi', async () => {
  const actual = await vi.importActual<typeof import('../../shared/api/originsApi')>('../../shared/api/originsApi')
  return { ...actual, fetchOrigins: originsApiMocks.fetchOrigins }
})

const zonesApiMocks = vi.hoisted(() => ({ fetchZones: vi.fn() }))
vi.mock('../../shared/api/zonesApi', async () => {
  const actual = await vi.importActual<typeof import('../../shared/api/zonesApi')>('../../shared/api/zonesApi')
  return { ...actual, fetchZones: zonesApiMocks.fetchZones }
})

const companyMocks = vi.hoisted(() => ({ useCompany: vi.fn() }))
vi.mock('../../shared/company/CompanyContext', () => ({ useCompany: companyMocks.useCompany }))

const alertMocks = vi.hoisted(() => ({
  notifySuccess: vi.fn(),
  notifyError: vi.fn(),
  confirmAction: vi.fn(),
}))
vi.mock('../../shared/ui/alerts', () => alertMocks)

const ROUTE: RouteView = {
  id: 'route-1',
  code: 'NORTH-CORRIDOR',
  name: 'North Corridor',
  originId: 'origin-1',
  originCode: 'ORIGIN-A',
  originName: 'Origin A',
  zoneId: 'zone-1',
  zoneCode: 'ZONE-A',
  zoneName: 'Zone A',
  frequencyId: null,
  frequencyCode: null,
  frequencyName: null,
  referenceDistanceKm: 12.5,
  referenceDurationMinutes: 45,
  stopCount: 3,
  active: true,
  createdAt: '2026-01-01T00:00:00Z',
  updatedAt: '2026-01-01T00:00:00Z',
}

function page<T>(content: T[], overrides: Partial<{ page: number; size: number; totalElements: number }> = {}) {
  return { content, page: overrides.page ?? 0, size: overrides.size ?? 25, totalElements: overrides.totalElements ?? content.length }
}

function mockCompany(canManage: boolean) {
  companyMocks.useCompany.mockReturnValue({
    selected: { id: 'company-1', name: 'Acme Logistics' },
    hasPermission: (permission: string) => (permission === 'masterdata.route:manage' ? canManage : true),
  })
}

function renderPage() {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  return render(
    <QueryClientProvider client={queryClient}>
      <RoutesPage />
    </QueryClientProvider>,
  )
}

afterEach(() => {
  vi.clearAllMocks()
})

describe('RoutesPage', () => {
  it('shows a loading state while the first page is fetched', () => {
    mockCompany(true)
    routesApiMocks.fetchRoutes.mockReturnValue(new Promise(() => {}))
    originsApiMocks.fetchOrigins.mockResolvedValue(page([]))
    zonesApiMocks.fetchZones.mockResolvedValue(page([]))

    renderPage()

    expect(screen.getByText('Loading records...')).toBeInTheDocument()
  })

  it('shows an empty state when the company has no routes yet', async () => {
    mockCompany(true)
    routesApiMocks.fetchRoutes.mockResolvedValue(page([]))
    originsApiMocks.fetchOrigins.mockResolvedValue(page([]))
    zonesApiMocks.fetchZones.mockResolvedValue(page([]))

    renderPage()

    expect(await screen.findByText('No routes found')).toBeInTheDocument()
  })

  it('shows an error state with a retry action when the request fails', async () => {
    mockCompany(true)
    routesApiMocks.fetchRoutes.mockRejectedValue(new ApiError(500, { code: 'internal-error' }, 'corr-1', 'boom'))
    originsApiMocks.fetchOrigins.mockResolvedValue(page([]))
    zonesApiMocks.fetchZones.mockResolvedValue(page([]))

    renderPage()

    expect(await screen.findByText('Something went wrong on our side. Please try again.')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Try again' })).toBeInTheDocument()
  })

  it('lists routes with origin, zone and stop count, and shows pagination once there is more than one page', async () => {
    mockCompany(true)
    routesApiMocks.fetchRoutes.mockResolvedValue(page([ROUTE], { totalElements: 60, size: 25 }))
    originsApiMocks.fetchOrigins.mockResolvedValue(page([]))
    zonesApiMocks.fetchZones.mockResolvedValue(page([]))

    renderPage()

    expect(await screen.findByText('NORTH-CORRIDOR')).toBeInTheDocument()
    expect(screen.getByText('Origin A')).toBeInTheDocument()
    expect(screen.getByText('Zone A')).toBeInTheDocument()
    expect(screen.getByText('3')).toBeInTheDocument()
    expect(screen.getByText(/Page 1 of 3/)).toBeInTheDocument()
  })

  it('distinguishes a Master Route from a future calculated Trip route in its description', async () => {
    mockCompany(true)
    routesApiMocks.fetchRoutes.mockResolvedValue(page([]))
    originsApiMocks.fetchOrigins.mockResolvedValue(page([]))
    zonesApiMocks.fetchZones.mockResolvedValue(page([]))

    renderPage()

    expect(await screen.findByText(/distinct from a Trip's calculated route/i)).toBeInTheDocument()
  })

  it('hides create and manage actions for a caller without masterdata.route:manage', async () => {
    mockCompany(false)
    routesApiMocks.fetchRoutes.mockResolvedValue(page([ROUTE]))
    originsApiMocks.fetchOrigins.mockResolvedValue(page([]))
    zonesApiMocks.fetchZones.mockResolvedValue(page([]))

    renderPage()
    await screen.findByText('NORTH-CORRIDOR')

    expect(screen.queryByRole('button', { name: 'New route' })).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Edit' })).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Deactivate' })).not.toBeInTheDocument()
  })

  it('deactivates a route only after the confirmation dialog is accepted', async () => {
    mockCompany(true)
    routesApiMocks.fetchRoutes.mockResolvedValue(page([ROUTE]))
    originsApiMocks.fetchOrigins.mockResolvedValue(page([]))
    zonesApiMocks.fetchZones.mockResolvedValue(page([]))
    routesApiMocks.deactivateRoute.mockResolvedValue({ ...ROUTE, active: false })

    alertMocks.confirmAction.mockResolvedValueOnce(false)
    renderPage()
    await screen.findByText('NORTH-CORRIDOR')

    await userEvent.click(screen.getByRole('button', { name: 'Deactivate' }))
    await waitFor(() => expect(alertMocks.confirmAction).toHaveBeenCalled())
    expect(routesApiMocks.deactivateRoute).not.toHaveBeenCalled()

    alertMocks.confirmAction.mockResolvedValueOnce(true)
    await userEvent.click(screen.getByRole('button', { name: 'Deactivate' }))

    await waitFor(() => expect(routesApiMocks.deactivateRoute).toHaveBeenCalledWith('company-1', 'route-1'))
    expect(alertMocks.notifySuccess).toHaveBeenCalledWith('Route deactivated', 'North Corridor')
  })

  it('applies the code filter to the query', async () => {
    mockCompany(true)
    routesApiMocks.fetchRoutes.mockResolvedValue(page([ROUTE]))
    originsApiMocks.fetchOrigins.mockResolvedValue(page([]))
    zonesApiMocks.fetchZones.mockResolvedValue(page([]))

    renderPage()
    await screen.findByText('NORTH-CORRIDOR')

    await userEvent.type(screen.getByLabelText(/^code$/i), 'north')
    await userEvent.click(screen.getByRole('button', { name: 'Apply' }))

    await waitFor(() =>
      expect(routesApiMocks.fetchRoutes).toHaveBeenLastCalledWith(expect.objectContaining({ code: 'north' })),
    )
  })
})
