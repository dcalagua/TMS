import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, describe, expect, it, vi } from 'vitest'
import type { LocationView } from '../../shared/api/locationsApi'
import { DestinationsPage } from './DestinationsPage'

/**
 * The counterpart of `OriginsPage.test.tsx`. The case worth its own assertion here is the store
 * that holds both uses: it must appear in this list *and* in Orígenes, as one record, because
 * the same place receives the delivery and ships the return.
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

/** Miraflores: destination of the delivery, origin of the return. One record, two uses. */
const STORE: LocationView = {
  id: 'location-1',
  code: 'MIRAFLORES',
  name: 'Tienda Miraflores',
  type: 'STORE',
  roles: ['ORIGIN', 'DESTINATION'],
  address: 'Av. Larco 400',
  addressReference: 'Frente al parque',
  district: 'Miraflores',
  province: 'Lima',
  department: 'Lima',
  country: 'PE',
  timeZone: 'America/Lima',
  latitude: -12.12,
  longitude: -77.03,
  zoneId: null,
  zoneCode: null,
  zoneName: null,
  serviceTimeMinutes: 20,
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
      <DestinationsPage />
    </QueryClientProvider>,
  )
}

afterEach(() => {
  vi.clearAllMocks()
})

describe('DestinationsPage', () => {
  it('asks the Locations endpoint for the DESTINATION role', async () => {
    mockCompany(true)
    locationsApiMocks.fetchLocations.mockResolvedValue(page([STORE]))

    renderPage()

    await screen.findByText('MIRAFLORES')
    expect(locationsApiMocks.fetchLocations).toHaveBeenCalledWith(
      expect.objectContaining({ companyId: 'company-1', role: 'DESTINATION' }),
    )
  })

  it('shows a store that also ships without duplicating it', async () => {
    mockCompany(true)
    locationsApiMocks.fetchLocations.mockResolvedValue(page([STORE]))

    renderPage()

    const rows = await screen.findAllByText('MIRAFLORES')
    expect(rows).toHaveLength(1)
    // Its type is what it is; its two uses are what it may do. Both are on the one row.
    expect(screen.getByText('Tienda')).toBeInTheDocument()
    expect(screen.getByText('Origen')).toBeInTheDocument()
    expect(screen.getByText('Destino')).toBeInTheDocument()
  })

  it('is titled Destinos and does not offer the operational use as a filter', async () => {
    mockCompany(true)
    locationsApiMocks.fetchLocations.mockResolvedValue(page([STORE]))

    renderPage()
    await screen.findByText('MIRAFLORES')

    expect(screen.getByRole('heading', { name: 'Destinos' })).toBeInTheDocument()
    expect(screen.queryByLabelText('Uso operacional')).not.toBeInTheDocument()
  })

  it('creates through the Location drawer with the destination use already ticked', async () => {
    mockCompany(true)
    locationsApiMocks.fetchLocations.mockResolvedValue(page([]))
    locationsApiMocks.createLocation.mockResolvedValue(STORE)

    renderPage()
    await screen.findByText('Sin destinos')

    await userEvent.click(screen.getAllByRole('button', { name: 'Nuevo destino' })[0] as HTMLElement)
    const dialog = within(screen.getByRole('dialog'))
    expect(dialog.getByRole('checkbox', { name: 'Puede utilizarse como destino' })).toBeChecked()
    expect(dialog.getByRole('checkbox', { name: 'Puede utilizarse como origen' })).not.toBeChecked()

    await userEvent.type(dialog.getByLabelText(/^código/i), 'SURCO')
    await userEvent.type(dialog.getByLabelText(/^nombre/i), 'Tienda Surco')
    await userEvent.click(dialog.getByRole('button', { name: 'Guardar' }))

    await waitFor(() =>
      expect(locationsApiMocks.createLocation).toHaveBeenCalledWith(
        'company-1',
        expect.objectContaining({ code: 'SURCO', roles: ['DESTINATION'] }),
      ),
    )
  })

  it('hides create and manage actions without masterdata.location:manage', async () => {
    mockCompany(false)
    locationsApiMocks.fetchLocations.mockResolvedValue(page([STORE]))

    renderPage()
    await screen.findByText('MIRAFLORES')

    expect(screen.queryByRole('button', { name: 'Nuevo destino' })).not.toBeInTheDocument()
  })
})
