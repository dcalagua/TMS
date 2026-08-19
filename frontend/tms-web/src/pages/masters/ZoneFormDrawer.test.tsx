import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, describe, expect, it, vi } from 'vitest'
import type { ZoneView } from '../../shared/api/zonesApi'
import { ZoneFormDrawer } from './ZoneFormDrawer'

const zonesApiMocks = vi.hoisted(() => ({ createZone: vi.fn(), updateZone: vi.fn() }))
vi.mock('../../shared/api/zonesApi', async () => {
  const actual = await vi.importActual<typeof import('../../shared/api/zonesApi')>('../../shared/api/zonesApi')
  return { ...actual, createZone: zonesApiMocks.createZone, updateZone: zonesApiMocks.updateZone }
})

const ZONE: ZoneView = {
  id: 'zone-1',
  code: 'NORTH-ZONE',
  name: 'North Zone',
  description: 'Northern operational area',
  active: true,
  createdAt: '2026-01-01T00:00:00Z',
  updatedAt: '2026-01-01T00:00:00Z',
}

afterEach(() => {
  vi.clearAllMocks()
})

describe('ZoneFormDrawer', () => {
  it('rejects an empty submission without calling the API', async () => {
    const onSaved = vi.fn()
    render(<ZoneFormDrawer companyId="company-1" zone={null} onClose={vi.fn()} onSaved={onSaved} />)

    await userEvent.click(screen.getByRole('button', { name: 'Guardar' }))

    // Both required fields report inline, each under its own label.
    await waitFor(() => expect(screen.getAllByText('Este campo es obligatorio')).toHaveLength(2))
    expect(screen.getByLabelText(/^código/i)).toHaveClass('is-invalid')
    expect(screen.getByLabelText(/^nombre/i)).toHaveClass('is-invalid')
    expect(zonesApiMocks.createZone).not.toHaveBeenCalled()
    expect(onSaved).not.toHaveBeenCalled()
  })

  it('rejects a code with characters outside the allowed shape', async () => {
    render(<ZoneFormDrawer companyId="company-1" zone={null} onClose={vi.fn()} onSaved={vi.fn()} />)

    await userEvent.type(screen.getByLabelText(/^código/i), 'has space')
    await userEvent.click(screen.getByRole('button', { name: 'Guardar' }))

    expect(await screen.findByText('Solo letras, dígitos, guion bajo o guion')).toBeInTheDocument()
    expect(zonesApiMocks.createZone).not.toHaveBeenCalled()
  })

  it('creates a zone with the entered values and reports success', async () => {
    zonesApiMocks.createZone.mockResolvedValue({ ...ZONE, code: 'SOUTH-ZONE' })
    const onSaved = vi.fn()
    render(<ZoneFormDrawer companyId="company-1" zone={null} onClose={vi.fn()} onSaved={onSaved} />)

    await userEvent.type(screen.getByLabelText(/^código/i), 'south-zone')
    await userEvent.type(screen.getByLabelText(/^nombre/i), 'South Zone')
    await userEvent.click(screen.getByRole('button', { name: 'Guardar' }))

    await waitFor(() =>
      expect(zonesApiMocks.createZone).toHaveBeenCalledWith(
        'company-1',
        expect.objectContaining({ code: 'south-zone', name: 'South Zone', description: null }),
      ),
    )
    expect(onSaved).toHaveBeenCalled()
  })

  it('pre-fills the form for an edit and calls updateZone with the zone id', async () => {
    zonesApiMocks.updateZone.mockResolvedValue(ZONE)
    const onSaved = vi.fn()
    render(<ZoneFormDrawer companyId="company-1" zone={ZONE} onClose={vi.fn()} onSaved={onSaved} />)

    expect(screen.getByLabelText(/^código/i)).toHaveValue('NORTH-ZONE')
    expect(screen.getByLabelText(/^nombre/i)).toHaveValue('North Zone')

    await userEvent.clear(screen.getByLabelText(/^nombre/i))
    await userEvent.type(screen.getByLabelText(/^nombre/i), 'North Zone Renamed')
    await userEvent.click(screen.getByRole('button', { name: 'Guardar' }))

    await waitFor(() =>
      expect(zonesApiMocks.updateZone).toHaveBeenCalledWith(
        'company-1',
        'zone-1',
        expect.objectContaining({ name: 'North Zone Renamed' }),
      ),
    )
    expect(onSaved).toHaveBeenCalled()
  })

  it('maps a backend field error onto the matching input instead of a generic message', async () => {
    zonesApiMocks.createZone.mockRejectedValue({
      fieldErrors: [{ field: 'code', message: "code 'DUP' already exists" }],
    })
    render(<ZoneFormDrawer companyId="company-1" zone={null} onClose={vi.fn()} onSaved={vi.fn()} />)

    await userEvent.type(screen.getByLabelText(/^código/i), 'DUP')
    await userEvent.type(screen.getByLabelText(/^nombre/i), 'Duplicate')
    await userEvent.click(screen.getByRole('button', { name: 'Guardar' }))

    expect(await screen.findByText("code 'DUP' already exists")).toBeInTheDocument()
  })

  it('closes when Cancel is clicked', async () => {
    const onClose = vi.fn()
    render(<ZoneFormDrawer companyId="company-1" zone={null} onClose={onClose} onSaved={vi.fn()} />)

    await userEvent.click(screen.getByRole('button', { name: 'Cancelar' }))

    expect(onClose).toHaveBeenCalled()
  })
})
