import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, describe, expect, it, vi } from 'vitest'
import type { DriverView } from '../../shared/api/driversApi'
import { ApiError } from '../../shared/api/httpClient'
import { DriversPage } from './DriversPage'

const driversApiMocks = vi.hoisted(() => ({
  fetchDrivers: vi.fn(),
  createDriver: vi.fn(),
  updateDriver: vi.fn(),
  activateDriver: vi.fn(),
  deactivateDriver: vi.fn(),
}))
vi.mock('../../shared/api/driversApi', async () => {
  const actual = await vi.importActual<typeof import('../../shared/api/driversApi')>('../../shared/api/driversApi')
  return { ...actual, ...driversApiMocks }
})

const carriersApiMocks = vi.hoisted(() => ({ fetchCarriers: vi.fn() }))
vi.mock('../../shared/api/carriersApi', async () => {
  const actual = await vi.importActual<typeof import('../../shared/api/carriersApi')>('../../shared/api/carriersApi')
  return { ...actual, fetchCarriers: carriersApiMocks.fetchCarriers }
})

const companyMocks = vi.hoisted(() => ({ useCompany: vi.fn() }))
vi.mock('../../shared/company/CompanyContext', () => ({ useCompany: companyMocks.useCompany }))

const alertMocks = vi.hoisted(() => ({
  notifySuccess: vi.fn(),
  notifyError: vi.fn(),
  confirmAction: vi.fn(),
}))
vi.mock('../../shared/ui/alerts', () => alertMocks)

function driver(overrides: Partial<DriverView> = {}): DriverView {
  return {
    id: 'driver-1',
    code: 'DR-ANA',
    firstName: 'Ana',
    lastName: 'Quispe',
    fullName: 'Quispe, Ana',
    documentType: 'DNI',
    documentNumber: '12345678',
    phone: '+51 999 111 222',
    licenseNumber: 'Q-987654',
    licenseCategory: 'A-IIB',
    licenseExpiresOn: '2027-05-31',
    licenseStatus: 'VALID',
    carrierId: 'carrier-1',
    carrierCode: 'CARRIER-1',
    carrierBusinessName: 'Carrier One SA',
    active: true,
    createdAt: '2026-01-01T00:00:00Z',
    updatedAt: '2026-01-01T00:00:00Z',
    ...overrides,
  }
}

function page(content: DriverView[]) {
  return { content, page: 0, size: 25, totalElements: content.length }
}

function emptyPage() {
  return { content: [], page: 0, size: 200, totalElements: 0 }
}

function mockCompany(canManage: boolean) {
  companyMocks.useCompany.mockReturnValue({
    selected: { id: 'company-1', name: 'Acme Logistics' },
    hasPermission: (permission: string) => (permission === 'fleet.driver:manage' ? canManage : true),
  })
}

function renderPage() {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  return render(
    <QueryClientProvider client={queryClient}>
      <DriversPage />
    </QueryClientProvider>,
  )
}

afterEach(() => {
  vi.clearAllMocks()
})

describe('DriversPage', () => {
  it('shows an empty state when the company has no drivers yet', async () => {
    mockCompany(true)
    driversApiMocks.fetchDrivers.mockResolvedValue(page([]))
    carriersApiMocks.fetchCarriers.mockResolvedValue(emptyPage())

    renderPage()

    expect(await screen.findByText('Sin conductores')).toBeInTheDocument()
  })

  it('shows an error state when the request fails', async () => {
    mockCompany(true)
    driversApiMocks.fetchDrivers.mockRejectedValue(new ApiError(500, { code: 'internal-error' }, 'corr-1', 'boom'))
    carriersApiMocks.fetchCarriers.mockResolvedValue(emptyPage())

    renderPage()

    expect(await screen.findByText('Ocurrió un error de nuestro lado. Vuelve a intentarlo.')).toBeInTheDocument()
  })

  it('renders the derived full name and the carrier, falling back to own staff', async () => {
    mockCompany(true)
    driversApiMocks.fetchDrivers.mockResolvedValue(
      page([driver(), driver({ id: 'driver-2', code: 'DR-LUIS', fullName: 'Rojas, Luis', carrierId: null, carrierCode: null, carrierBusinessName: null })]),
    )
    carriersApiMocks.fetchCarriers.mockResolvedValue(emptyPage())

    renderPage()

    expect(await screen.findByText('Quispe, Ana')).toBeInTheDocument()
    expect(screen.getByText('Carrier One SA')).toBeInTheDocument()
    expect(screen.getByText('Personal propio')).toBeInTheDocument()
  })

  /**
   * The badge is the server's answer rendered, never a date subtraction done here - see
   * `driversApi.DriverLicenseStatus`. These three cases prove the page reads the field rather
   * than recomputing it: the expiry dates below are deliberately inconsistent with the statuses,
   * and the page must still show what the server said.
   */
  it('renders the licence status the server computed, not one it derives from the date', async () => {
    mockCompany(true)
    driversApiMocks.fetchDrivers.mockResolvedValue(
      page([
        driver({ id: 'd-1', licenseStatus: 'EXPIRED', licenseExpiresOn: '2099-01-01' }),
        driver({ id: 'd-2', licenseStatus: 'EXPIRING_SOON', licenseExpiresOn: '2099-01-02' }),
        driver({ id: 'd-3', licenseStatus: 'UNRECORDED', licenseExpiresOn: null }),
      ]),
    )
    carriersApiMocks.fetchCarriers.mockResolvedValue(emptyPage())

    renderPage()

    expect(await screen.findByText('Vencida')).toBeInTheDocument()
    expect(screen.getByText('Por vencer')).toBeInTheDocument()
    expect(screen.getByText('Sin registrar')).toBeInTheDocument()
    // A driver with no expiry on file says so instead of showing an empty cell.
    expect(screen.getByText('Sin fecha')).toBeInTheDocument()
  })

  it('sends the licence filter to the server rather than filtering the loaded page', async () => {
    mockCompany(true)
    driversApiMocks.fetchDrivers.mockResolvedValue(page([driver()]))
    carriersApiMocks.fetchCarriers.mockResolvedValue(emptyPage())

    renderPage()
    await screen.findByText('Quispe, Ana')

    // `Select` is a button + listbox, not a native `<select>`: open it, then click the option.
    await userEvent.click(screen.getByRole('combobox', { name: /^licencia/i }))
    await userEvent.click(await screen.findByRole('option', { name: 'Vencida' }))
    await userEvent.click(screen.getByRole('button', { name: 'Aplicar filtros' }))

    await waitFor(() => {
      expect(driversApiMocks.fetchDrivers).toHaveBeenLastCalledWith(
        expect.objectContaining({ licenseStatus: 'EXPIRED' }),
      )
    })
  })

  it('hides the create and row actions from a caller without fleet.driver:manage', async () => {
    mockCompany(false)
    driversApiMocks.fetchDrivers.mockResolvedValue(page([driver()]))
    carriersApiMocks.fetchCarriers.mockResolvedValue(emptyPage())

    renderPage()
    await screen.findByText('Quispe, Ana')

    expect(screen.queryByRole('button', { name: 'Nuevo conductor' })).not.toBeInTheDocument()
  })
})
