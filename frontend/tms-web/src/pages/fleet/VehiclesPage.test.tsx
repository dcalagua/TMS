import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { ApiError } from '../../shared/api/httpClient'
import type { VehicleView } from '../../shared/api/vehiclesApi'
import { VehiclesPage } from './VehiclesPage'

const vehiclesApiMocks = vi.hoisted(() => ({
  fetchVehicles: vi.fn(),
  createVehicle: vi.fn(),
  updateVehicle: vi.fn(),
  activateVehicle: vi.fn(),
  deactivateVehicle: vi.fn(),
}))
vi.mock('../../shared/api/vehiclesApi', async () => {
  const actual = await vi.importActual<typeof import('../../shared/api/vehiclesApi')>('../../shared/api/vehiclesApi')
  return { ...actual, ...vehiclesApiMocks }
})

const carriersApiMocks = vi.hoisted(() => ({ fetchCarriers: vi.fn() }))
vi.mock('../../shared/api/carriersApi', async () => {
  const actual = await vi.importActual<typeof import('../../shared/api/carriersApi')>('../../shared/api/carriersApi')
  return { ...actual, fetchCarriers: carriersApiMocks.fetchCarriers }
})

const vehicleTypesApiMocks = vi.hoisted(() => ({ fetchVehicleTypes: vi.fn() }))
vi.mock('../../shared/api/vehicleTypesApi', async () => {
  const actual =
    await vi.importActual<typeof import('../../shared/api/vehicleTypesApi')>('../../shared/api/vehicleTypesApi')
  return { ...actual, fetchVehicleTypes: vehicleTypesApiMocks.fetchVehicleTypes }
})

const companyMocks = vi.hoisted(() => ({ useCompany: vi.fn() }))
vi.mock('../../shared/company/CompanyContext', () => ({ useCompany: companyMocks.useCompany }))

const alertMocks = vi.hoisted(() => ({
  notifySuccess: vi.fn(),
  notifyError: vi.fn(),
  confirmAction: vi.fn(),
}))
vi.mock('../../shared/ui/alerts', () => alertMocks)

const VEHICLE: VehicleView = {
  id: 'vehicle-1',
  code: 'TRUCK-1',
  licensePlate: 'ABC-123',
  carrierId: 'carrier-1',
  carrierCode: 'CARRIER-1',
  carrierBusinessName: 'Carrier One SA',
  vehicleTypeId: 'type-1',
  vehicleTypeCode: 'TYPE-1',
  vehicleTypeName: '10 ton truck',
  maxWeightOverrideKg: null,
  maxVolumeOverrideM3: null,
  maxPalletsOverride: null,
  effectiveMaxWeightKg: 10000,
  effectiveMaxVolumeM3: 40,
  effectiveMaxPallets: 20,
  availabilityStatus: 'AVAILABLE',
  active: true,
  createdAt: '2026-01-01T00:00:00Z',
  updatedAt: '2026-01-01T00:00:00Z',
}

function page(content: VehicleView[], overrides: Partial<{ page: number; size: number; totalElements: number }> = {}) {
  return { content, page: overrides.page ?? 0, size: overrides.size ?? 25, totalElements: overrides.totalElements ?? content.length }
}

function emptyPage() {
  return { content: [], page: 0, size: 200, totalElements: 0 }
}

function mockCompany(canManage: boolean) {
  companyMocks.useCompany.mockReturnValue({
    selected: { id: 'company-1', name: 'Acme Logistics' },
    hasPermission: (permission: string) => (permission === 'fleet.vehicle:manage' ? canManage : true),
  })
}

function renderPage() {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  return render(
    <QueryClientProvider client={queryClient}>
      <VehiclesPage />
    </QueryClientProvider>,
  )
}

afterEach(() => {
  vi.clearAllMocks()
})

describe('VehiclesPage', () => {
  it('shows a loading state while the first page is fetched', () => {
    mockCompany(true)
    vehiclesApiMocks.fetchVehicles.mockReturnValue(new Promise(() => {}))
    carriersApiMocks.fetchCarriers.mockResolvedValue(emptyPage())
    vehicleTypesApiMocks.fetchVehicleTypes.mockResolvedValue(emptyPage())

    renderPage()

    expect(screen.getByText('Cargando registros...')).toBeInTheDocument()
  })

  it('shows an empty state when the company has no vehicles yet', async () => {
    mockCompany(true)
    vehiclesApiMocks.fetchVehicles.mockResolvedValue(page([]))
    carriersApiMocks.fetchCarriers.mockResolvedValue(emptyPage())
    vehicleTypesApiMocks.fetchVehicleTypes.mockResolvedValue(emptyPage())

    renderPage()

    expect(await screen.findByText('No vehicles found')).toBeInTheDocument()
  })

  it('shows an error state with a retry action when the request fails', async () => {
    mockCompany(true)
    vehiclesApiMocks.fetchVehicles.mockRejectedValue(new ApiError(500, { code: 'internal-error' }, 'corr-1', 'boom'))
    carriersApiMocks.fetchCarriers.mockResolvedValue(emptyPage())
    vehicleTypesApiMocks.fetchVehicleTypes.mockResolvedValue(emptyPage())

    renderPage()

    expect(await screen.findByText('Ocurrió un error de nuestro lado. Vuelve a intentarlo.')).toBeInTheDocument()
  })

  it('lists vehicles with plate/code, carrier, type, effective capacity and availability', async () => {
    mockCompany(true)
    vehiclesApiMocks.fetchVehicles.mockResolvedValue(page([VEHICLE], { totalElements: 60, size: 25 }))
    carriersApiMocks.fetchCarriers.mockResolvedValue(emptyPage())
    vehicleTypesApiMocks.fetchVehicleTypes.mockResolvedValue(emptyPage())

    renderPage()

    expect(await screen.findByText('ABC-123')).toBeInTheDocument()
    expect(screen.getByText('TRUCK-1')).toBeInTheDocument()
    expect(screen.getByText('Carrier One SA')).toBeInTheDocument()
    expect(screen.getByText('10 ton truck')).toBeInTheDocument()
    expect(screen.getByText(/10000 kg/)).toBeInTheDocument()
    expect(screen.getByText('Available', { selector: 'span' })).toBeInTheDocument()
    expect(screen.getByText(/Página 1 de 3/)).toBeInTheDocument()
  })

  it('shows owned fleet when a vehicle has no carrier', async () => {
    mockCompany(true)
    vehiclesApiMocks.fetchVehicles.mockResolvedValue(page([{ ...VEHICLE, carrierId: null, carrierBusinessName: null }]))
    carriersApiMocks.fetchCarriers.mockResolvedValue(emptyPage())
    vehicleTypesApiMocks.fetchVehicleTypes.mockResolvedValue(emptyPage())

    renderPage()

    expect(await screen.findByText('Owned fleet')).toBeInTheDocument()
  })

  it('hides create and manage actions for a caller without fleet.vehicle:manage', async () => {
    mockCompany(false)
    vehiclesApiMocks.fetchVehicles.mockResolvedValue(page([VEHICLE]))
    carriersApiMocks.fetchCarriers.mockResolvedValue(emptyPage())
    vehicleTypesApiMocks.fetchVehicleTypes.mockResolvedValue(emptyPage())

    renderPage()

    await screen.findByText('ABC-123')

    expect(screen.queryByRole('button', { name: 'Nuevo vehículo' })).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Edit' })).not.toBeInTheDocument()
  })

  it('deactivates a vehicle only after the confirmation dialog is accepted', async () => {
    mockCompany(true)
    vehiclesApiMocks.fetchVehicles.mockResolvedValue(page([VEHICLE]))
    carriersApiMocks.fetchCarriers.mockResolvedValue(emptyPage())
    vehicleTypesApiMocks.fetchVehicleTypes.mockResolvedValue(emptyPage())
    vehiclesApiMocks.deactivateVehicle.mockResolvedValue({ ...VEHICLE, active: false })

    alertMocks.confirmAction.mockResolvedValueOnce(true)
    renderPage()
    await screen.findByText('ABC-123')

    await userEvent.click(screen.getByRole('button', { name: 'Deactivate' }))

    await waitFor(() => expect(vehiclesApiMocks.deactivateVehicle).toHaveBeenCalledWith('company-1', 'vehicle-1'))
    expect(alertMocks.notifySuccess).toHaveBeenCalledWith('Vehicle deactivated', 'ABC-123')
  })

  it('applies the license plate filter to the query', async () => {
    mockCompany(true)
    vehiclesApiMocks.fetchVehicles.mockResolvedValue(page([VEHICLE]))
    carriersApiMocks.fetchCarriers.mockResolvedValue(emptyPage())
    vehicleTypesApiMocks.fetchVehicleTypes.mockResolvedValue(emptyPage())

    renderPage()
    await screen.findByText('ABC-123')

    await userEvent.type(screen.getByLabelText(/license plate/i), 'abc')
    await userEvent.click(screen.getByRole('button', { name: 'Aplicar filtros' }))

    await waitFor(() =>
      expect(vehiclesApiMocks.fetchVehicles).toHaveBeenLastCalledWith(expect.objectContaining({ licensePlate: 'abc' })),
    )
  })
})
