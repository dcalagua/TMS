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

    await userEvent.click(screen.getByRole('button', { name: 'Save' }))

    expect(await screen.findByText('Code is required')).toBeInTheDocument()
    expect(screen.getByText('Name is required')).toBeInTheDocument()
    expect(screen.getByText('Time zone is required')).toBeInTheDocument()
    expect(originsApiMocks.createOrigin).not.toHaveBeenCalled()
    expect(onSaved).not.toHaveBeenCalled()
  })

  it('rejects a code with characters outside the allowed shape', async () => {
    render(<OriginFormModal companyId="company-1" origin={null} onClose={vi.fn()} onSaved={vi.fn()} />)

    await userEvent.type(screen.getByLabelText(/^code/i), 'has space')
    await userEvent.click(screen.getByRole('button', { name: 'Save' }))

    expect(await screen.findByText('Letters, digits, underscore or hyphen only')).toBeInTheDocument()
    expect(originsApiMocks.createOrigin).not.toHaveBeenCalled()
  })

  it('requires both latitude and longitude, or neither', async () => {
    render(<OriginFormModal companyId="company-1" origin={null} onClose={vi.fn()} onSaved={vi.fn()} />)

    await userEvent.type(screen.getByLabelText(/^code/i), 'PARTIAL')
    await userEvent.type(screen.getByLabelText(/^name/i), 'Partial')
    await userEvent.type(screen.getByLabelText(/^latitude/i), '10.5')
    await userEvent.type(screen.getByLabelText(/^time zone/i), 'America/Lima')
    await userEvent.click(screen.getByRole('button', { name: 'Save' }))

    expect(await screen.findByText('Provide both latitude and longitude, or leave both blank')).toBeInTheDocument()
    expect(originsApiMocks.createOrigin).not.toHaveBeenCalled()
  })

  it('rejects an unknown time zone identifier', async () => {
    render(<OriginFormModal companyId="company-1" origin={null} onClose={vi.fn()} onSaved={vi.fn()} />)

    await userEvent.type(screen.getByLabelText(/^code/i), 'GOOD')
    await userEvent.type(screen.getByLabelText(/^name/i), 'Good')
    await userEvent.type(screen.getByLabelText(/^time zone/i), 'Not/AZone')
    await userEvent.click(screen.getByRole('button', { name: 'Save' }))

    expect(await screen.findByText(/must be a valid IANA time zone/i)).toBeInTheDocument()
    expect(originsApiMocks.createOrigin).not.toHaveBeenCalled()
  })

  it('creates an origin with the entered values and reports success', async () => {
    originsApiMocks.createOrigin.mockResolvedValue({ ...ORIGIN, code: 'SOUTH-HUB' })
    const onSaved = vi.fn()
    render(<OriginFormModal companyId="company-1" origin={null} onClose={vi.fn()} onSaved={onSaved} />)

    await userEvent.type(screen.getByLabelText(/^code/i), 'south-hub')
    await userEvent.type(screen.getByLabelText(/^name/i), 'South Hub')
    await userEvent.type(screen.getByLabelText(/^time zone/i), 'America/Lima')
    await userEvent.click(screen.getByRole('button', { name: 'Save' }))

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

    expect(screen.getByLabelText(/^code/i)).toHaveValue('NORTH-HUB')
    expect(screen.getByLabelText(/^name/i)).toHaveValue('North Hub')

    await userEvent.clear(screen.getByLabelText(/^name/i))
    await userEvent.type(screen.getByLabelText(/^name/i), 'North Hub Renamed')
    await userEvent.click(screen.getByRole('button', { name: 'Save' }))

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

    await userEvent.type(screen.getByLabelText(/^code/i), 'DUP')
    await userEvent.type(screen.getByLabelText(/^name/i), 'Duplicate')
    await userEvent.type(screen.getByLabelText(/^time zone/i), 'America/Lima')
    await userEvent.click(screen.getByRole('button', { name: 'Save' }))

    expect(await screen.findByText("code 'DUP' already exists")).toBeInTheDocument()
  })

  it('closes when Cancel is clicked', async () => {
    const onClose = vi.fn()
    render(<OriginFormModal companyId="company-1" origin={null} onClose={onClose} onSaved={vi.fn()} />)

    await userEvent.click(screen.getByRole('button', { name: 'Cancel' }))

    expect(onClose).toHaveBeenCalled()
  })
})
