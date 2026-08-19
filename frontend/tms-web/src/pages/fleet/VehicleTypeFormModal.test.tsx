import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, describe, expect, it, vi } from 'vitest'
import type { VehicleTypeView } from '../../shared/api/vehicleTypesApi'
import { VehicleTypeFormModal } from './VehicleTypeFormModal'

const vehicleTypesApiMocks = vi.hoisted(() => ({ createVehicleType: vi.fn(), updateVehicleType: vi.fn() }))
vi.mock('../../shared/api/vehicleTypesApi', async () => {
  const actual =
    await vi.importActual<typeof import('../../shared/api/vehicleTypesApi')>('../../shared/api/vehicleTypesApi')
  return {
    ...actual,
    createVehicleType: vehicleTypesApiMocks.createVehicleType,
    updateVehicleType: vehicleTypesApiMocks.updateVehicleType,
  }
})

const VEHICLE_TYPE: VehicleTypeView = {
  id: 'type-1',
  code: 'TRUCK-10T',
  name: '10 ton truck',
  maxWeightKg: 10000,
  maxVolumeM3: 40,
  maxPallets: 20,
  lengthM: 8,
  widthM: 2.4,
  heightM: 2.6,
  bodyType: 'DRY_VAN',
  temperatureControlled: false,
  minTemperatureCelsius: null,
  maxTemperatureCelsius: null,
  axles: 2,
  active: true,
  createdAt: '2026-01-01T00:00:00Z',
  updatedAt: '2026-01-01T00:00:00Z',
}

afterEach(() => {
  vi.clearAllMocks()
})

describe('VehicleTypeFormModal', () => {
  it('rejects an empty submission without calling the API', async () => {
    const onSaved = vi.fn()
    render(<VehicleTypeFormModal companyId="company-1" vehicleType={null} onClose={vi.fn()} onSaved={onSaved} />)

    await userEvent.click(screen.getByRole('button', { name: 'Save' }))

    expect(await screen.findByText('Code is required')).toBeInTheDocument()
    expect(screen.getByText('Name is required')).toBeInTheDocument()
    expect(screen.getByText('Max weight is required')).toBeInTheDocument()
    expect(screen.getByText('Max volume is required')).toBeInTheDocument()
    expect(vehicleTypesApiMocks.createVehicleType).not.toHaveBeenCalled()
    expect(onSaved).not.toHaveBeenCalled()
  })

  it('rejects zero or negative capacity values', async () => {
    render(<VehicleTypeFormModal companyId="company-1" vehicleType={null} onClose={vi.fn()} onSaved={vi.fn()} />)

    await userEvent.type(screen.getByLabelText(/^code/i), 'ZERO')
    await userEvent.type(screen.getByLabelText(/^name/i), 'Zero')
    await userEvent.type(screen.getByLabelText(/max weight/i), '0')
    await userEvent.type(screen.getByLabelText(/max volume/i), '10')
    await userEvent.click(screen.getByRole('button', { name: 'Save' }))

    expect(await screen.findByText('Must be greater than zero')).toBeInTheDocument()
    expect(vehicleTypesApiMocks.createVehicleType).not.toHaveBeenCalled()
  })

  it('rejects a temperature range without temperature controlled checked', async () => {
    render(<VehicleTypeFormModal companyId="company-1" vehicleType={null} onClose={vi.fn()} onSaved={vi.fn()} />)

    await userEvent.type(screen.getByLabelText(/^code/i), 'REEFER')
    await userEvent.type(screen.getByLabelText(/^name/i), 'Reefer')
    await userEvent.type(screen.getByLabelText(/max weight/i), '9000')
    await userEvent.type(screen.getByLabelText(/max volume/i), '25')
    await userEvent.type(screen.getByLabelText(/min temperature/i), '-18')
    await userEvent.click(screen.getByRole('button', { name: 'Save' }))

    expect(await screen.findByText('Only allowed when temperature controlled is checked')).toBeInTheDocument()
    expect(vehicleTypesApiMocks.createVehicleType).not.toHaveBeenCalled()
  })

  it('creates a vehicle type with the entered values, including zero pallets', async () => {
    vehicleTypesApiMocks.createVehicleType.mockResolvedValue({ ...VEHICLE_TYPE, code: 'TANKER' })
    const onSaved = vi.fn()
    render(<VehicleTypeFormModal companyId="company-1" vehicleType={null} onClose={vi.fn()} onSaved={onSaved} />)

    await userEvent.type(screen.getByLabelText(/^code/i), 'tanker')
    await userEvent.type(screen.getByLabelText(/^name/i), 'Tanker')
    await userEvent.type(screen.getByLabelText(/max weight/i), '15000')
    await userEvent.type(screen.getByLabelText(/max volume/i), '20')
    await userEvent.clear(screen.getByLabelText(/max pallets/i))
    await userEvent.type(screen.getByLabelText(/max pallets/i), '0')
    await userEvent.click(screen.getByRole('button', { name: 'Save' }))

    await waitFor(() =>
      expect(vehicleTypesApiMocks.createVehicleType).toHaveBeenCalledWith(
        'company-1',
        expect.objectContaining({ code: 'tanker', maxWeightKg: 15000, maxVolumeM3: 20, maxPallets: 0 }),
      ),
    )
    expect(onSaved).toHaveBeenCalled()
  })

  it('pre-fills the form for an edit and calls updateVehicleType with the id', async () => {
    vehicleTypesApiMocks.updateVehicleType.mockResolvedValue(VEHICLE_TYPE)
    const onSaved = vi.fn()
    render(<VehicleTypeFormModal companyId="company-1" vehicleType={VEHICLE_TYPE} onClose={vi.fn()} onSaved={onSaved} />)

    expect(screen.getByLabelText(/^code/i)).toHaveValue('TRUCK-10T')
    expect(screen.getByLabelText(/max weight/i)).toHaveValue('10000')

    await userEvent.clear(screen.getByLabelText(/^name/i))
    await userEvent.type(screen.getByLabelText(/^name/i), 'Renamed truck')
    await userEvent.click(screen.getByRole('button', { name: 'Save' }))

    await waitFor(() =>
      expect(vehicleTypesApiMocks.updateVehicleType).toHaveBeenCalledWith(
        'company-1',
        'type-1',
        expect.objectContaining({ name: 'Renamed truck' }),
      ),
    )
    expect(onSaved).toHaveBeenCalled()
  })

  it('maps a backend field error onto the matching input instead of a generic message', async () => {
    vehicleTypesApiMocks.createVehicleType.mockRejectedValue({
      fieldErrors: [{ field: 'code', message: "code 'DUP' already exists" }],
    })
    render(<VehicleTypeFormModal companyId="company-1" vehicleType={null} onClose={vi.fn()} onSaved={vi.fn()} />)

    await userEvent.type(screen.getByLabelText(/^code/i), 'DUP')
    await userEvent.type(screen.getByLabelText(/^name/i), 'Duplicate')
    await userEvent.type(screen.getByLabelText(/max weight/i), '1000')
    await userEvent.type(screen.getByLabelText(/max volume/i), '10')
    await userEvent.click(screen.getByRole('button', { name: 'Save' }))

    expect(await screen.findByText("code 'DUP' already exists")).toBeInTheDocument()
  })

  it('closes when Cancel is clicked', async () => {
    const onClose = vi.fn()
    render(<VehicleTypeFormModal companyId="company-1" vehicleType={null} onClose={onClose} onSaved={vi.fn()} />)

    await userEvent.click(screen.getByRole('button', { name: 'Cancel' }))

    expect(onClose).toHaveBeenCalled()
  })
})
