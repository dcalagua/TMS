import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, describe, expect, it, vi } from 'vitest'
import type { CarrierView } from '../../shared/api/carriersApi'
import { CarrierFormModal } from './CarrierFormModal'

const carriersApiMocks = vi.hoisted(() => ({ createCarrier: vi.fn(), updateCarrier: vi.fn() }))
vi.mock('../../shared/api/carriersApi', async () => {
  const actual = await vi.importActual<typeof import('../../shared/api/carriersApi')>('../../shared/api/carriersApi')
  return { ...actual, createCarrier: carriersApiMocks.createCarrier, updateCarrier: carriersApiMocks.updateCarrier }
})

const CARRIER: CarrierView = {
  id: 'carrier-1',
  code: 'ACME',
  businessName: 'Acme Transport S.A.',
  taxIdType: 'RUC',
  taxIdValue: '20100000001',
  contactName: 'Jane Doe',
  phone: '+51 999 999 999',
  email: 'ops@acme.example.test',
  active: true,
  createdAt: '2026-01-01T00:00:00Z',
  updatedAt: '2026-01-01T00:00:00Z',
}

afterEach(() => {
  vi.clearAllMocks()
})

describe('CarrierFormModal', () => {
  it('rejects an empty submission without calling the API', async () => {
    const onSaved = vi.fn()
    render(<CarrierFormModal companyId="company-1" carrier={null} onClose={vi.fn()} onSaved={onSaved} />)

    await userEvent.click(screen.getByRole('button', { name: 'Save' }))

    expect(await screen.findByText('Code is required')).toBeInTheDocument()
    expect(screen.getByText('Business name is required')).toBeInTheDocument()
    expect(screen.getByText('Tax id value is required')).toBeInTheDocument()
    expect(carriersApiMocks.createCarrier).not.toHaveBeenCalled()
    expect(onSaved).not.toHaveBeenCalled()
  })

  it('rejects a code with characters outside the allowed shape', async () => {
    render(<CarrierFormModal companyId="company-1" carrier={null} onClose={vi.fn()} onSaved={vi.fn()} />)

    await userEvent.type(screen.getByLabelText(/^code/i), 'has space')
    await userEvent.click(screen.getByRole('button', { name: 'Save' }))

    expect(await screen.findByText('Letters, digits, underscore or hyphen only')).toBeInTheDocument()
    expect(carriersApiMocks.createCarrier).not.toHaveBeenCalled()
  })

  it('creates a carrier with the entered values and reports success', async () => {
    carriersApiMocks.createCarrier.mockResolvedValue({ ...CARRIER, code: 'BETA' })
    const onSaved = vi.fn()
    render(<CarrierFormModal companyId="company-1" carrier={null} onClose={vi.fn()} onSaved={onSaved} />)

    await userEvent.type(screen.getByLabelText(/^code/i), 'beta')
    await userEvent.type(screen.getByLabelText(/business name/i), 'Beta Transport')
    await userEvent.clear(screen.getByLabelText(/tax id type/i))
    await userEvent.type(screen.getByLabelText(/tax id type/i), 'RUC')
    await userEvent.type(screen.getByLabelText(/tax id value/i), '20200000002')
    await userEvent.click(screen.getByRole('button', { name: 'Save' }))

    await waitFor(() =>
      expect(carriersApiMocks.createCarrier).toHaveBeenCalledWith(
        'company-1',
        expect.objectContaining({
          code: 'beta', businessName: 'Beta Transport', taxIdType: 'RUC', taxIdValue: '20200000002',
          contactName: null, phone: null, email: null,
        }),
      ),
    )
    expect(onSaved).toHaveBeenCalled()
  })

  it('pre-fills the form for an edit and calls updateCarrier with the carrier id', async () => {
    carriersApiMocks.updateCarrier.mockResolvedValue(CARRIER)
    const onSaved = vi.fn()
    render(<CarrierFormModal companyId="company-1" carrier={CARRIER} onClose={vi.fn()} onSaved={onSaved} />)

    expect(screen.getByLabelText(/^code/i)).toHaveValue('ACME')
    expect(screen.getByLabelText(/business name/i)).toHaveValue('Acme Transport S.A.')
    expect(screen.getByLabelText(/tax id value/i)).toHaveValue('20100000001')

    await userEvent.clear(screen.getByLabelText(/business name/i))
    await userEvent.type(screen.getByLabelText(/business name/i), 'Acme Transport Renamed')
    await userEvent.click(screen.getByRole('button', { name: 'Save' }))

    await waitFor(() =>
      expect(carriersApiMocks.updateCarrier).toHaveBeenCalledWith(
        'company-1',
        'carrier-1',
        expect.objectContaining({ businessName: 'Acme Transport Renamed' }),
      ),
    )
    expect(onSaved).toHaveBeenCalled()
  })

  it('maps a backend field error onto the matching input instead of a generic message', async () => {
    carriersApiMocks.createCarrier.mockRejectedValue({
      fieldErrors: [{ field: 'email', message: 'email is not a valid address.' }],
    })
    render(<CarrierFormModal companyId="company-1" carrier={null} onClose={vi.fn()} onSaved={vi.fn()} />)

    await userEvent.type(screen.getByLabelText(/^code/i), 'DUP')
    await userEvent.type(screen.getByLabelText(/business name/i), 'Duplicate SA')
    await userEvent.type(screen.getByLabelText(/tax id value/i), '20100000099')
    await userEvent.click(screen.getByRole('button', { name: 'Save' }))

    expect(await screen.findByText('email is not a valid address.')).toBeInTheDocument()
  })

  it('closes when Cancel is clicked', async () => {
    const onClose = vi.fn()
    render(<CarrierFormModal companyId="company-1" carrier={null} onClose={onClose} onSaved={vi.fn()} />)

    await userEvent.click(screen.getByRole('button', { name: 'Cancel' }))

    expect(onClose).toHaveBeenCalled()
  })
})
