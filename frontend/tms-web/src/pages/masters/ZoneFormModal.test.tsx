import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, describe, expect, it, vi } from 'vitest'
import type { ZoneView } from '../../shared/api/zonesApi'
import { ZoneFormModal } from './ZoneFormModal'

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

describe('ZoneFormModal', () => {
  it('rejects an empty submission without calling the API', async () => {
    const onSaved = vi.fn()
    render(<ZoneFormModal companyId="company-1" zone={null} onClose={vi.fn()} onSaved={onSaved} />)

    await userEvent.click(screen.getByRole('button', { name: 'Save' }))

    expect(await screen.findByText('Code is required')).toBeInTheDocument()
    expect(screen.getByText('Name is required')).toBeInTheDocument()
    expect(zonesApiMocks.createZone).not.toHaveBeenCalled()
    expect(onSaved).not.toHaveBeenCalled()
  })

  it('rejects a code with characters outside the allowed shape', async () => {
    render(<ZoneFormModal companyId="company-1" zone={null} onClose={vi.fn()} onSaved={vi.fn()} />)

    await userEvent.type(screen.getByLabelText(/^code/i), 'has space')
    await userEvent.click(screen.getByRole('button', { name: 'Save' }))

    expect(await screen.findByText('Letters, digits, underscore or hyphen only')).toBeInTheDocument()
    expect(zonesApiMocks.createZone).not.toHaveBeenCalled()
  })

  it('creates a zone with the entered values and reports success', async () => {
    zonesApiMocks.createZone.mockResolvedValue({ ...ZONE, code: 'SOUTH-ZONE' })
    const onSaved = vi.fn()
    render(<ZoneFormModal companyId="company-1" zone={null} onClose={vi.fn()} onSaved={onSaved} />)

    await userEvent.type(screen.getByLabelText(/^code/i), 'south-zone')
    await userEvent.type(screen.getByLabelText(/^name/i), 'South Zone')
    await userEvent.click(screen.getByRole('button', { name: 'Save' }))

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
    render(<ZoneFormModal companyId="company-1" zone={ZONE} onClose={vi.fn()} onSaved={onSaved} />)

    expect(screen.getByLabelText(/^code/i)).toHaveValue('NORTH-ZONE')
    expect(screen.getByLabelText(/^name/i)).toHaveValue('North Zone')

    await userEvent.clear(screen.getByLabelText(/^name/i))
    await userEvent.type(screen.getByLabelText(/^name/i), 'North Zone Renamed')
    await userEvent.click(screen.getByRole('button', { name: 'Save' }))

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
    render(<ZoneFormModal companyId="company-1" zone={null} onClose={vi.fn()} onSaved={vi.fn()} />)

    await userEvent.type(screen.getByLabelText(/^code/i), 'DUP')
    await userEvent.type(screen.getByLabelText(/^name/i), 'Duplicate')
    await userEvent.click(screen.getByRole('button', { name: 'Save' }))

    expect(await screen.findByText("code 'DUP' already exists")).toBeInTheDocument()
  })

  it('closes when Cancel is clicked', async () => {
    const onClose = vi.fn()
    render(<ZoneFormModal companyId="company-1" zone={null} onClose={onClose} onSaved={vi.fn()} />)

    await userEvent.click(screen.getByRole('button', { name: 'Cancel' }))

    expect(onClose).toHaveBeenCalled()
  })
})
