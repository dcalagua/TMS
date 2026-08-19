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

    await userEvent.click(screen.getByRole('button', { name: 'Guardar' }))

    // Code, name, max weight and max volume are all required.
    expect(await screen.findAllByText('Este campo es obligatorio')).toHaveLength(4)
    expect(screen.getByLabelText(/^código/i)).toHaveClass('is-invalid')
    expect(screen.getByLabelText(/^peso máx/i)).toHaveClass('is-invalid')
    expect(vehicleTypesApiMocks.createVehicleType).not.toHaveBeenCalled()
    expect(onSaved).not.toHaveBeenCalled()
  })

  it('rejects zero or negative capacity values', async () => {
    render(<VehicleTypeFormModal companyId="company-1" vehicleType={null} onClose={vi.fn()} onSaved={vi.fn()} />)

    await userEvent.type(screen.getByLabelText(/^código/i), 'ZERO')
    await userEvent.type(screen.getByLabelText(/^nombre/i), 'Zero')
    await userEvent.type(screen.getByLabelText(/^peso máx/i), '0')
    await userEvent.type(screen.getByLabelText(/^volumen máx/i), '10')
    await userEvent.click(screen.getByRole('button', { name: 'Guardar' }))

    expect(await screen.findByText('Debe ser un número mayor que cero')).toBeInTheDocument()
    expect(vehicleTypesApiMocks.createVehicleType).not.toHaveBeenCalled()
  })

  it('rejects a temperature range without temperature controlled checked', async () => {
    render(<VehicleTypeFormModal companyId="company-1" vehicleType={null} onClose={vi.fn()} onSaved={vi.fn()} />)

    await userEvent.type(screen.getByLabelText(/^código/i), 'REEFER')
    await userEvent.type(screen.getByLabelText(/^nombre/i), 'Reefer')
    await userEvent.type(screen.getByLabelText(/^peso máx/i), '9000')
    await userEvent.type(screen.getByLabelText(/^volumen máx/i), '25')
    await userEvent.type(screen.getByLabelText(/^temperatura mín/i), '-18')
    await userEvent.click(screen.getByRole('button', { name: 'Guardar' }))

    expect(await screen.findByText('Solo se permite si la unidad es de temperatura controlada')).toBeInTheDocument()
    expect(vehicleTypesApiMocks.createVehicleType).not.toHaveBeenCalled()
  })

  it('creates a vehicle type with the entered values, including zero pallets', async () => {
    vehicleTypesApiMocks.createVehicleType.mockResolvedValue({ ...VEHICLE_TYPE, code: 'TANKER' })
    const onSaved = vi.fn()
    render(<VehicleTypeFormModal companyId="company-1" vehicleType={null} onClose={vi.fn()} onSaved={onSaved} />)

    await userEvent.type(screen.getByLabelText(/^código/i), 'tanker')
    await userEvent.type(screen.getByLabelText(/^nombre/i), 'Tanker')
    await userEvent.type(screen.getByLabelText(/^peso máx/i), '15000')
    await userEvent.type(screen.getByLabelText(/^volumen máx/i), '20')
    await userEvent.clear(screen.getByLabelText(/^pallets máx/i))
    await userEvent.type(screen.getByLabelText(/^pallets máx/i), '0')
    await userEvent.click(screen.getByRole('button', { name: 'Guardar' }))

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

    expect(screen.getByLabelText(/^código/i)).toHaveValue('TRUCK-10T')
    expect(screen.getByLabelText(/^peso máx/i)).toHaveValue('10000')

    await userEvent.clear(screen.getByLabelText(/^nombre/i))
    await userEvent.type(screen.getByLabelText(/^nombre/i), 'Renamed truck')
    await userEvent.click(screen.getByRole('button', { name: 'Guardar' }))

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

    await userEvent.type(screen.getByLabelText(/^código/i), 'DUP')
    await userEvent.type(screen.getByLabelText(/^nombre/i), 'Duplicate')
    await userEvent.type(screen.getByLabelText(/^peso máx/i), '1000')
    await userEvent.type(screen.getByLabelText(/^volumen máx/i), '10')
    await userEvent.click(screen.getByRole('button', { name: 'Guardar' }))

    expect(await screen.findByText("code 'DUP' already exists")).toBeInTheDocument()
  })

  it('closes when Cancel is clicked', async () => {
    const onClose = vi.fn()
    render(<VehicleTypeFormModal companyId="company-1" vehicleType={null} onClose={onClose} onSaved={vi.fn()} />)

    await userEvent.click(screen.getByRole('button', { name: 'Cancelar' }))

    expect(onClose).toHaveBeenCalled()
  })
})
