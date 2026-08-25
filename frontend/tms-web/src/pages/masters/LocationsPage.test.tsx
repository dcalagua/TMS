import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { ApiError } from '../../shared/api/httpClient'
import type { LocationView } from '../../shared/api/locationsApi'
import { LocationsPage } from './LocationsPage'

const locationsApiMocks = vi.hoisted(() => ({
  fetchLocations: vi.fn(),
  createLocation: vi.fn(),
  updateLocation: vi.fn(),
  activateLocation: vi.fn(),
  deactivateLocation: vi.fn(),
}))
vi.mock('../../shared/api/locationsApi', async () => {
  const actual = await vi.importActual<typeof import('../../shared/api/locationsApi')>('../../shared/api/locationsApi')
  return { ...actual, ...locationsApiMocks }
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

const LOCATION: LocationView = {
  id: 'location-1',
  code: 'LIM-DC',
  name: 'Lima Distribution Centre',
  type: 'DISTRIBUTION_CENTER',
  roles: ['ORIGIN', 'DESTINATION'],
  address: 'Av. Argentina 1234',
  addressReference: 'Puerta azul',
  district: 'Callao',
  province: 'Callao',
  department: 'Callao',
  country: 'PE',
  timeZone: 'America/Lima',
  latitude: -12.046374,
  longitude: -77.042793,
  zoneId: 'zone-1',
  zoneCode: 'ZONE-A',
  zoneName: 'Zone A',
  serviceTimeMinutes: 25,
  externalSystem: null,
  externalReference: null,
  active: true,
  createdAt: '2026-01-01T00:00:00Z',
  updatedAt: '2026-01-01T00:00:00Z',
}

function page<T>(content: T[], overrides: Partial<{ page: number; size: number; totalElements: number }> = {}) {
  return {
    content,
    page: overrides.page ?? 0,
    size: overrides.size ?? 25,
    totalElements: overrides.totalElements ?? content.length,
  }
}

function mockCompany(canManage: boolean) {
  companyMocks.useCompany.mockReturnValue({
    selected: { id: 'company-1', name: 'Acme Logistics' },
    hasPermission: (permission: string) => (permission === 'masterdata.location:manage' ? canManage : true),
  })
}

function renderPage() {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  return render(
    <QueryClientProvider client={queryClient}>
      <LocationsPage />
    </QueryClientProvider>,
  )
}

afterEach(() => {
  vi.clearAllMocks()
})

describe('LocationsPage', () => {
  it('shows a loading state while the first page is fetched', () => {
    mockCompany(true)
    locationsApiMocks.fetchLocations.mockReturnValue(new Promise(() => {}))
    zonesApiMocks.fetchZones.mockResolvedValue(page([]))

    renderPage()

    expect(screen.getByText('Cargando registros...')).toBeInTheDocument()
  })

  it('shows an empty state when the company has no locations yet', async () => {
    mockCompany(true)
    locationsApiMocks.fetchLocations.mockResolvedValue(page([]))
    zonesApiMocks.fetchZones.mockResolvedValue(page([]))

    renderPage()

    expect(await screen.findByText('Sin ubicaciones')).toBeInTheDocument()
  })

  it('shows an error state with a retry action when the request fails', async () => {
    mockCompany(true)
    locationsApiMocks.fetchLocations.mockRejectedValue(
      new ApiError(500, { code: 'internal-error' }, 'corr-1', 'boom'),
    )
    zonesApiMocks.fetchZones.mockResolvedValue(page([]))

    renderPage()

    expect(await screen.findByText('Ocurrió un error de nuestro lado. Vuelve a intentarlo.')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Reintentar' })).toBeInTheDocument()
  })

  it('lists locations with their translated type and roles', async () => {
    mockCompany(true)
    locationsApiMocks.fetchLocations.mockResolvedValue(page([LOCATION]))
    zonesApiMocks.fetchZones.mockResolvedValue(page([]))

    renderPage()

    expect(await screen.findByText('LIM-DC')).toBeInTheDocument()
    expect(screen.getByText('Lima Distribution Centre')).toBeInTheDocument()
    expect(screen.getAllByText('Centro de distribución').length).toBeGreaterThan(0)
    expect(screen.getByText('Zone A')).toBeInTheDocument()
  })

  it('shows the operational use as badges, and says so when a place has none', async () => {
    mockCompany(true)
    locationsApiMocks.fetchLocations.mockResolvedValue(
      page([
        { ...LOCATION, id: 'l-both' },
        { ...LOCATION, id: 'l-none', code: 'UNUSED', roles: [] },
      ]),
    )
    zonesApiMocks.fetchZones.mockResolvedValue(page([]))

    renderPage()

    await screen.findByText('LIM-DC')
    // The type column already says "Centro de distribución"; the use column must say how the
    // place may be used and nothing else, or it is the Type/Roles duplication all over again.
    expect(screen.getByText('Origen')).toBeInTheDocument()
    expect(screen.getByText('Destino')).toBeInTheDocument()
    expect(screen.getByText('Sin uso definido')).toBeInTheDocument()
  })

  it('hides create and manage actions for a caller without masterdata.location:manage', async () => {
    mockCompany(false)
    locationsApiMocks.fetchLocations.mockResolvedValue(page([LOCATION]))
    zonesApiMocks.fetchZones.mockResolvedValue(page([]))

    renderPage()
    await screen.findByText('LIM-DC')

    expect(screen.queryByRole('button', { name: 'Nueva ubicación' })).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Abrir menú de acciones' })).not.toBeInTheDocument()
  })

  it('creates a location through the drawer and refreshes the list', async () => {
    mockCompany(true)
    locationsApiMocks.fetchLocations.mockResolvedValue(page([]))
    zonesApiMocks.fetchZones.mockResolvedValue(page([]))
    locationsApiMocks.createLocation.mockResolvedValue({ ...LOCATION, code: 'NEW-LOC' })

    renderPage()
    await screen.findByText('Sin ubicaciones')

    // Two of them: the header action and the empty state's own. Either opens the same drawer.
    await userEvent.click(screen.getAllByRole('button', { name: 'Nueva ubicación' })[0] as HTMLElement)
    const dialog = within(screen.getByRole('dialog'))
    await userEvent.type(dialog.getByLabelText(/^código/i), 'NEW-LOC')
    await userEvent.type(dialog.getByLabelText(/^nombre/i), 'New Location')
    await userEvent.click(dialog.getByRole('button', { name: 'Guardar' }))

    await waitFor(() =>
      expect(locationsApiMocks.createLocation).toHaveBeenCalledWith(
        'company-1',
        expect.objectContaining({ code: 'NEW-LOC', roles: ['DESTINATION'], timeZone: 'America/Lima' }),
      ),
    )
    await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument())
    expect(alertMocks.notifySuccess).toHaveBeenCalledWith('Registro creado')
  })

  it('deactivates a location only after the confirmation dialog is accepted', async () => {
    mockCompany(true)
    locationsApiMocks.fetchLocations.mockResolvedValue(page([LOCATION]))
    zonesApiMocks.fetchZones.mockResolvedValue(page([]))
    locationsApiMocks.deactivateLocation.mockResolvedValue({ ...LOCATION, active: false })

    alertMocks.confirmAction.mockResolvedValueOnce(false)
    renderPage()
    await screen.findByText('LIM-DC')

    await userEvent.click(screen.getAllByRole('button', { name: 'Abrir menú de acciones' })[0] as HTMLElement)
    await userEvent.click(await screen.findByRole('menuitem', { name: 'Desactivar' }))
    await waitFor(() => expect(alertMocks.confirmAction).toHaveBeenCalled())
    expect(locationsApiMocks.deactivateLocation).not.toHaveBeenCalled()

    alertMocks.confirmAction.mockResolvedValueOnce(true)
    await userEvent.click(screen.getAllByRole('button', { name: 'Abrir menú de acciones' })[0] as HTMLElement)
    await userEvent.click(await screen.findByRole('menuitem', { name: 'Desactivar' }))

    await waitFor(() =>
      expect(locationsApiMocks.deactivateLocation).toHaveBeenCalledWith('company-1', 'location-1'),
    )
    expect(alertMocks.notifySuccess).toHaveBeenCalledWith('Registro desactivado', 'Lima Distribution Centre')
  })

  it('sends the search box to the backend as one parameter over code, name and external reference', async () => {
    mockCompany(true)
    locationsApiMocks.fetchLocations.mockResolvedValue(page([LOCATION]))
    zonesApiMocks.fetchZones.mockResolvedValue(page([]))

    renderPage()
    await screen.findByText('LIM-DC')

    await userEvent.type(screen.getByLabelText(/^buscar$/i), 'lima')
    await userEvent.click(screen.getByRole('button', { name: 'Aplicar filtros' }))

    await waitFor(() =>
      expect(locationsApiMocks.fetchLocations).toHaveBeenLastCalledWith(
        expect.objectContaining({ search: 'lima' }),
      ),
    )
  })

  it('shows a chip for each applied filter and clears it on demand', async () => {
    mockCompany(true)
    locationsApiMocks.fetchLocations.mockResolvedValue(page([LOCATION]))
    zonesApiMocks.fetchZones.mockResolvedValue(page([]))

    renderPage()
    await screen.findByText('LIM-DC')

    await userEvent.type(screen.getByLabelText(/^buscar$/i), 'lima')
    await userEvent.click(screen.getByRole('button', { name: 'Aplicar filtros' }))
    await waitFor(() => expect(screen.getByText('Filtros:')).toBeInTheDocument())

    await userEvent.click(screen.getByRole('button', { name: /Quitar filtro/i }))

    await waitFor(() =>
      expect(locationsApiMocks.fetchLocations).toHaveBeenLastCalledWith(
        expect.objectContaining({ search: undefined }),
      ),
    )
  })
})
