import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, describe, expect, it, vi } from 'vitest'
import type { LocationView } from '../../shared/api/locationsApi'
import { OriginsPage } from './OriginsPage'

/**
 * Origins is a view, not a master. What has to hold is that it asks the Locations endpoint for
 * the `ORIGIN` role and nothing else, that it never offers the use as a filter (it *is* the
 * filter), and that creating from here produces a place that appears in this list - the failure
 * that would otherwise send an operator to Ubicaciones to fix what they just created.
 */

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

const frequenciesApiMocks = vi.hoisted(() => ({ fetchFrequencies: vi.fn() }))
vi.mock('../../shared/api/frequenciesApi', async () => {
  const actual =
    await vi.importActual<typeof import('../../shared/api/frequenciesApi')>('../../shared/api/frequenciesApi')
  return { ...actual, fetchFrequencies: frequenciesApiMocks.fetchFrequencies }
})

const companyMocks = vi.hoisted(() => ({ useCompany: vi.fn() }))
vi.mock('../../shared/company/CompanyContext', () => ({ useCompany: companyMocks.useCompany }))

const alertMocks = vi.hoisted(() => ({
  notifySuccess: vi.fn(),
  notifyError: vi.fn(),
  confirmAction: vi.fn(),
}))
vi.mock('../../shared/ui/alerts', () => alertMocks)

const PLANT: LocationView = {
  id: 'location-1',
  code: 'PLANT-01',
  name: 'Lurin Plant',
  type: 'PLANT',
  roles: ['ORIGIN'],
  address: 'Panamericana Sur km 30',
  addressReference: null,
  district: 'Lurin',
  province: 'Lima',
  department: 'Lima',
  country: 'PE',
  timeZone: 'America/Lima',
  latitude: -12.28,
  longitude: -76.87,
  zoneId: null,
  zoneCode: null,
  zoneName: null,
  serviceTimeMinutes: 0,
  externalSystem: null,
  externalReference: null,
  active: true,
  createdAt: '2026-01-01T00:00:00Z',
  updatedAt: '2026-01-01T00:00:00Z',
}

function page<T>(content: T[], totalElements = content.length) {
  return { content, page: 0, size: 25, totalElements }
}

function mockCompany(canManage: boolean) {
  companyMocks.useCompany.mockReturnValue({
    selected: { id: 'company-1', name: 'Acme Logistics' },
    hasPermission: (permission: string) => (permission === 'masterdata.location:manage' ? canManage : true),
  })
}

function renderPage() {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  zonesApiMocks.fetchZones.mockResolvedValue(page([]))
  frequenciesApiMocks.fetchFrequencies.mockResolvedValue(page([]))
  return render(
    <QueryClientProvider client={queryClient}>
      <OriginsPage />
    </QueryClientProvider>,
  )
}

afterEach(() => {
  vi.clearAllMocks()
})

describe('OriginsPage', () => {
  it('asks the Locations endpoint for the ORIGIN role', async () => {
    mockCompany(true)
    locationsApiMocks.fetchLocations.mockResolvedValue(page([PLANT]))

    renderPage()

    await screen.findByText('PLANT-01')
    expect(locationsApiMocks.fetchLocations).toHaveBeenCalledWith(
      expect.objectContaining({ companyId: 'company-1', role: 'ORIGIN' }),
    )
  })

  it('is titled Orígenes and explains that it is a view of Ubicaciones', async () => {
    mockCompany(true)
    locationsApiMocks.fetchLocations.mockResolvedValue(page([PLANT]))

    renderPage()

    expect(screen.getByRole('heading', { name: 'Orígenes' })).toBeInTheDocument()
    expect(screen.getByText(/filtrada por uso operacional/i)).toBeInTheDocument()
    await screen.findByText('PLANT-01')
  })

  it('does not offer the operational use as a filter, because it is the screen', async () => {
    mockCompany(true)
    locationsApiMocks.fetchLocations.mockResolvedValue(page([PLANT]))

    renderPage()
    await screen.findByText('PLANT-01')

    expect(screen.queryByLabelText('Uso operacional')).not.toBeInTheDocument()
    expect(screen.getByLabelText('Tipo')).toBeInTheDocument()
  })

  it('creates through the Location drawer with the origin use already ticked', async () => {
    mockCompany(true)
    locationsApiMocks.fetchLocations.mockResolvedValue(page([]))
    locationsApiMocks.createLocation.mockResolvedValue(PLANT)

    renderPage()
    await screen.findByText('Sin orígenes')

    await userEvent.click(screen.getAllByRole('button', { name: 'Nuevo origen' })[0] as HTMLElement)
    const dialog = within(screen.getByRole('dialog'))
    expect(dialog.getByRole('checkbox', { name: 'Puede utilizarse como origen' })).toBeChecked()

    await userEvent.type(dialog.getByLabelText(/^código/i), 'PLANT-02')
    await userEvent.type(dialog.getByLabelText(/^nombre/i), 'Second plant')
    await userEvent.click(dialog.getByRole('button', { name: 'Guardar' }))

    await waitFor(() =>
      expect(locationsApiMocks.createLocation).toHaveBeenCalledWith(
        'company-1',
        expect.objectContaining({ code: 'PLANT-02', roles: ['ORIGIN'] }),
      ),
    )
  })

  it('hides create and manage actions without masterdata.location:manage', async () => {
    mockCompany(false)
    locationsApiMocks.fetchLocations.mockResolvedValue(page([PLANT]))

    renderPage()
    await screen.findByText('PLANT-01')

    expect(screen.queryByRole('button', { name: 'Nuevo origen' })).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Abrir menú de acciones' })).not.toBeInTheDocument()
  })
})
