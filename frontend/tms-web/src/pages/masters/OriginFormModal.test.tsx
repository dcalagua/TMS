import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, describe, expect, it, vi } from 'vitest'
import type { OriginView } from '../../shared/api/originsApi'
import { OriginFormModal } from './OriginFormModal'

const originsApiMocks = vi.hoisted(() => ({ createOrigin: vi.fn(), updateOrigin: vi.fn() }))
vi.mock('../../shared/api/originsApi', async () => {
  const actual = await vi.importActual<typeof import('../../shared/api/originsApi')>('../../shared/api/originsApi')
  return { ...actual, createOrigin: originsApiMocks.createOrigin, updateOrigin: originsApiMocks.updateOrigin }
})

const ORIGIN: OriginView = {
  id: 'origin-1',
  code: 'NORTH-HUB',
  name: 'North Hub',
  type: 'HUB',
  address: '123 Main St',
  latitude: -12.046374,
  longitude: -77.042793,
  timeZone: 'America/Lima',
  externalReference: 'EWM-1',
  active: true,
  createdAt: '2026-01-01T00:00:00Z',
  updatedAt: '2026-01-01T00:00:00Z',
}

afterEach(() => {
  vi.clearAllMocks()
})

describe('OriginFormModal', () => {
  it('rejects an empty submission without calling the API', async () => {
    const onSaved = vi.fn()
    render(<OriginFormModal companyId="company-1" origin={null} onClose={vi.fn()} onSaved={onSaved} />)

    await userEvent.click(screen.getByRole('button', { name: 'Guardar' }))

    // Code, name and time zone are the three required fields; each reports under its own label.
    await waitFor(() => expect(screen.getAllByText('Este campo es obligatorio')).toHaveLength(3))
    expect(screen.getByLabelText(/^código/i)).toHaveClass('is-invalid')
    expect(screen.getByLabelText(/^zona horaria/i)).toHaveClass('is-invalid')
    expect(originsApiMocks.createOrigin).not.toHaveBeenCalled()
    expect(onSaved).not.toHaveBeenCalled()
  })

  it('rejects a code with characters outside the allowed shape', async () => {
    render(<OriginFormModal companyId="company-1" origin={null} onClose={vi.fn()} onSaved={vi.fn()} />)

    await userEvent.type(screen.getByLabelText(/^código/i), 'has space')
    await userEvent.click(screen.getByRole('button', { name: 'Guardar' }))

    expect(await screen.findByText('Solo letras, dígitos, guion bajo o guion')).toBeInTheDocument()
    expect(originsApiMocks.createOrigin).not.toHaveBeenCalled()
  })

  it('requires both latitude and longitude, or neither', async () => {
    render(<OriginFormModal companyId="company-1" origin={null} onClose={vi.fn()} onSaved={vi.fn()} />)

    await userEvent.type(screen.getByLabelText(/^código/i), 'PARTIAL')
    await userEvent.type(screen.getByLabelText(/^nombre/i), 'Partial')
    await userEvent.type(screen.getByLabelText(/^latitud/i), '10.5')
    await userEvent.type(screen.getByLabelText(/^zona horaria/i), 'America/Lima')
    await userEvent.click(screen.getByRole('button', { name: 'Guardar' }))

    expect(await screen.findByText('Indica latitud y longitud, o deja ambas en blanco')).toBeInTheDocument()
    expect(originsApiMocks.createOrigin).not.toHaveBeenCalled()
  })

  it('rejects an unknown time zone identifier', async () => {
    render(<OriginFormModal companyId="company-1" origin={null} onClose={vi.fn()} onSaved={vi.fn()} />)

    await userEvent.type(screen.getByLabelText(/^código/i), 'GOOD')
    await userEvent.type(screen.getByLabelText(/^nombre/i), 'Good')
    await userEvent.type(screen.getByLabelText(/^zona horaria/i), 'Not/AZone')
    await userEvent.click(screen.getByRole('button', { name: 'Guardar' }))

    expect(await screen.findByText(/zona horaria IANA válida/i)).toBeInTheDocument()
    expect(originsApiMocks.createOrigin).not.toHaveBeenCalled()
  })

  it('creates an origin with the entered values and reports success', async () => {
    originsApiMocks.createOrigin.mockResolvedValue({ ...ORIGIN, code: 'SOUTH-HUB' })
    const onSaved = vi.fn()
    render(<OriginFormModal companyId="company-1" origin={null} onClose={vi.fn()} onSaved={onSaved} />)

    await userEvent.type(screen.getByLabelText(/^código/i), 'south-hub')
    await userEvent.type(screen.getByLabelText(/^nombre/i), 'South Hub')
    await userEvent.type(screen.getByLabelText(/^zona horaria/i), 'America/Lima')
    await userEvent.click(screen.getByRole('button', { name: 'Guardar' }))

    await waitFor(() =>
      expect(originsApiMocks.createOrigin).toHaveBeenCalledWith(
        'company-1',
        expect.objectContaining({ code: 'south-hub', name: 'South Hub', timeZone: 'America/Lima', latitude: null, longitude: null }),
      ),
    )
    expect(onSaved).toHaveBeenCalled()
  })

  it('pre-fills the form for an edit and calls updateOrigin with the origin id', async () => {
    originsApiMocks.updateOrigin.mockResolvedValue(ORIGIN)
    const onSaved = vi.fn()
    render(<OriginFormModal companyId="company-1" origin={ORIGIN} onClose={vi.fn()} onSaved={onSaved} />)

    expect(screen.getByLabelText(/^código/i)).toHaveValue('NORTH-HUB')
    expect(screen.getByLabelText(/^nombre/i)).toHaveValue('North Hub')

    await userEvent.clear(screen.getByLabelText(/^nombre/i))
    await userEvent.type(screen.getByLabelText(/^nombre/i), 'North Hub Renamed')
    await userEvent.click(screen.getByRole('button', { name: 'Guardar' }))

    await waitFor(() =>
      expect(originsApiMocks.updateOrigin).toHaveBeenCalledWith(
        'company-1',
        'origin-1',
        expect.objectContaining({ name: 'North Hub Renamed' }),
      ),
    )
    expect(onSaved).toHaveBeenCalled()
  })

  it('maps a backend field error onto the matching input instead of a generic message', async () => {
    originsApiMocks.createOrigin.mockRejectedValue({
      fieldErrors: [{ field: 'code', message: "code 'DUP' already exists" }],
    })
    render(<OriginFormModal companyId="company-1" origin={null} onClose={vi.fn()} onSaved={vi.fn()} />)

    await userEvent.type(screen.getByLabelText(/^código/i), 'DUP')
    await userEvent.type(screen.getByLabelText(/^nombre/i), 'Duplicate')
    await userEvent.type(screen.getByLabelText(/^zona horaria/i), 'America/Lima')
    await userEvent.click(screen.getByRole('button', { name: 'Guardar' }))

    expect(await screen.findByText("code 'DUP' already exists")).toBeInTheDocument()
  })

  it('closes when Cancel is clicked', async () => {
    const onClose = vi.fn()
    render(<OriginFormModal companyId="company-1" origin={null} onClose={onClose} onSaved={vi.fn()} />)

    await userEvent.click(screen.getByRole('button', { name: 'Cancelar' }))

    expect(onClose).toHaveBeenCalled()
  })
})
