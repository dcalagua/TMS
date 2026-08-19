import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import type { ComponentProps } from 'react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import type { VehicleView } from '../../shared/api/vehiclesApi'
import { VehicleFormModal } from './VehicleFormModal'

const vehiclesApiMocks = vi.hoisted(() => ({ createVehicle: vi.fn(), updateVehicle: vi.fn() }))
vi.mock('../../shared/api/vehiclesApi', async () => {
  const actual = await vi.importActual<typeof import('../../shared/api/vehiclesApi')>('../../shared/api/vehiclesApi')
  return { ...actual, createVehicle: vehiclesApiMocks.createVehicle, updateVehicle: vehiclesApiMocks.updateVehicle }
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

function emptyPage() {
  return { content: [], page: 0, size: 200, totalElements: 0 }
}

function vehicleTypesPage() {
  return {
    content: [{
      id: 'type-1', code: 'TYPE-1', name: '10 ton truck', maxWeightKg: 10000, maxVolumeM3: 40, maxPallets: 20,
      lengthM: null, widthM: null, heightM: null, bodyType: null, temperatureControlled: false,
      minTemperatureCelsius: null, maxTemperatureCelsius: null, axles: null, active: true,
      createdAt: '', updatedAt: '',
    }],
    page: 0, size: 200, totalElements: 1,
  }
}

function renderModal(props: Partial<ComponentProps<typeof VehicleFormModal>> = {}) {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  return render(
    <QueryClientProvider client={queryClient}>
      <VehicleFormModal companyId="company-1" vehicle={null} onClose={vi.fn()} onSaved={vi.fn()} {...props} />
    </QueryClientProvider>,
  )
}

afterEach(() => {
  vi.clearAllMocks()
})

describe('VehicleFormModal', () => {
  it('rejects an empty submission without calling the API', async () => {
    carriersApiMocks.fetchCarriers.mockResolvedValue(emptyPage())
    vehicleTypesApiMocks.fetchVehicleTypes.mockResolvedValue(emptyPage())
    const onSaved = vi.fn()
    renderModal({ onSaved })

    await userEvent.click(screen.getByRole('button', { name: 'Save' }))

    expect(await screen.findByText('Code is required')).toBeInTheDocument()
    expect(screen.getByText('License plate is required')).toBeInTheDocument()
    expect(screen.getByText('Vehicle type is required')).toBeInTheDocument()
    expect(vehiclesApiMocks.createVehicle).not.toHaveBeenCalled()
    expect(onSaved).not.toHaveBeenCalled()
  })

  it('rejects a license plate with characters outside the allowed shape', async () => {
    carriersApiMocks.fetchCarriers.mockResolvedValue(emptyPage())
    vehicleTypesApiMocks.fetchVehicleTypes.mockResolvedValue(vehicleTypesPage())
    renderModal()

    await userEvent.type(screen.getByLabelText(/^code/i), 'TRUCK-1')
    await userEvent.type(screen.getByLabelText(/license plate/i), 'ab')
    await userEvent.click(screen.getByRole('button', { name: 'Save' }))

    expect(await screen.findByText('4-12 characters: letters, digits or hyphen')).toBeInTheDocument()
    expect(vehiclesApiMocks.createVehicle).not.toHaveBeenCalled()
  })

  it('lists vehicle types and carriers fetched from the backend in their selects', async () => {
    carriersApiMocks.fetchCarriers.mockResolvedValue({
      content: [{ id: 'carrier-1', code: 'CARRIER-1', businessName: 'Carrier One SA', taxIdType: 'RUC', taxIdValue: '1', contactName: null, phone: null, email: null, active: true, createdAt: '', updatedAt: '' }],
      page: 0, size: 200, totalElements: 1,
    })
    vehicleTypesApiMocks.fetchVehicleTypes.mockResolvedValue(vehicleTypesPage())
    renderModal()

    expect(await screen.findByRole('option', { name: /TYPE-1/ })).toBeInTheDocument()
    expect(screen.getByRole('option', { name: /Carrier One SA/ })).toBeInTheDocument()
  })

  it('creates a vehicle with no carrier (owned fleet) and no overrides', async () => {
    carriersApiMocks.fetchCarriers.mockResolvedValue(emptyPage())
    vehicleTypesApiMocks.fetchVehicleTypes.mockResolvedValue(vehicleTypesPage())
    vehiclesApiMocks.createVehicle.mockResolvedValue({ ...VEHICLE, carrierId: null })
    const onSaved = vi.fn()
    renderModal({ onSaved })

    await userEvent.type(screen.getByLabelText(/^code/i), 'truck-2')
    await userEvent.type(screen.getByLabelText(/license plate/i), 'xyz-999')
    await userEvent.selectOptions(await screen.findByLabelText(/vehicle type/i), 'type-1')
    await userEvent.click(screen.getByRole('button', { name: 'Save' }))

    await waitFor(() =>
      expect(vehiclesApiMocks.createVehicle).toHaveBeenCalledWith(
        'company-1',
        expect.objectContaining({
          code: 'truck-2', licensePlate: 'xyz-999', carrierId: null, vehicleTypeId: 'type-1',
          maxWeightOverrideKg: null, maxVolumeOverrideM3: null, maxPalletsOverride: null,
          availabilityStatus: 'AVAILABLE',
        }),
      ),
    )
    expect(onSaved).toHaveBeenCalled()
  })

  it('creates a vehicle with a weight override', async () => {
    carriersApiMocks.fetchCarriers.mockResolvedValue(emptyPage())
    vehicleTypesApiMocks.fetchVehicleTypes.mockResolvedValue(vehicleTypesPage())
    vehiclesApiMocks.createVehicle.mockResolvedValue(VEHICLE)
    renderModal()

    await userEvent.type(screen.getByLabelText(/^code/i), 'truck-3')
    await userEvent.type(screen.getByLabelText(/license plate/i), 'ovr-001')
    await userEvent.selectOptions(await screen.findByLabelText(/vehicle type/i), 'type-1')
    await userEvent.type(screen.getByLabelText(/weight override/i), '9500')
    await userEvent.click(screen.getByRole('button', { name: 'Save' }))

    await waitFor(() =>
      expect(vehiclesApiMocks.createVehicle).toHaveBeenCalledWith(
        'company-1', expect.objectContaining({ maxWeightOverrideKg: 9500 }),
      ),
    )
  })

  it('pre-fills the form for an edit, still showing an assignment dropped from the active-only fetch', async () => {
    carriersApiMocks.fetchCarriers.mockResolvedValue(emptyPage())
    vehicleTypesApiMocks.fetchVehicleTypes.mockResolvedValue(emptyPage())
    vehiclesApiMocks.updateVehicle.mockResolvedValue(VEHICLE)
    const onSaved = vi.fn()
    renderModal({ vehicle: VEHICLE, onSaved })

    expect(screen.getByLabelText(/^code/i)).toHaveValue('TRUCK-1')
    expect(screen.getByLabelText(/license plate/i)).toHaveValue('ABC-123')
    expect(await screen.findByRole('option', { name: /TYPE-1/ })).toBeInTheDocument()
    expect(screen.getByRole('option', { name: /Carrier One SA|CARRIER-1/ })).toBeInTheDocument()

    await userEvent.click(screen.getByRole('button', { name: 'Save' }))

    await waitFor(() =>
      expect(vehiclesApiMocks.updateVehicle).toHaveBeenCalledWith(
        'company-1', 'vehicle-1', expect.objectContaining({ code: 'TRUCK-1', vehicleTypeId: 'type-1' }),
      ),
    )
    expect(onSaved).toHaveBeenCalled()
  })

  it('maps a backend field error onto the matching input instead of a generic message', async () => {
    carriersApiMocks.fetchCarriers.mockResolvedValue(emptyPage())
    vehicleTypesApiMocks.fetchVehicleTypes.mockResolvedValue(vehicleTypesPage())
    vehiclesApiMocks.createVehicle.mockRejectedValue({
      fieldErrors: [{ field: 'licensePlate', message: "license plate 'DUP-001' already exists" }],
    })
    renderModal()

    await userEvent.type(screen.getByLabelText(/^code/i), 'DUP')
    await userEvent.type(screen.getByLabelText(/license plate/i), 'dup-001')
    await userEvent.selectOptions(await screen.findByLabelText(/vehicle type/i), 'type-1')
    await userEvent.click(screen.getByRole('button', { name: 'Save' }))

    expect(await screen.findByText("license plate 'DUP-001' already exists")).toBeInTheDocument()
  })

  it('closes when Cancel is clicked', async () => {
    carriersApiMocks.fetchCarriers.mockResolvedValue(emptyPage())
    vehicleTypesApiMocks.fetchVehicleTypes.mockResolvedValue(emptyPage())
    const onClose = vi.fn()
    renderModal({ onClose })

    await userEvent.click(screen.getByRole('button', { name: 'Cancel' }))

    expect(onClose).toHaveBeenCalled()
  })
})
