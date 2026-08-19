import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, describe, expect, it, vi } from 'vitest'
import type { CarrierView } from '../../shared/api/carriersApi'
import { CarrierFormDrawer } from './CarrierFormDrawer'

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

describe('CarrierFormDrawer', () => {
  it('rejects an empty submission without calling the API', async () => {
    const onSaved = vi.fn()
    render(<CarrierFormDrawer companyId="company-1" carrier={null} onClose={vi.fn()} onSaved={onSaved} />)

    await userEvent.click(screen.getByRole('button', { name: 'Guardar' }))

    // Code, business name, tax id type and tax id value are all required.
    expect(await screen.findAllByText('Este campo es obligatorio')).toHaveLength(3)
    expect(screen.getByLabelText(/^código/i)).toHaveClass('is-invalid')
    expect(screen.getByLabelText(/^razón social/i)).toHaveClass('is-invalid')
    expect(screen.getByLabelText(/^número de documento/i)).toHaveClass('is-invalid')
    expect(carriersApiMocks.createCarrier).not.toHaveBeenCalled()
    expect(onSaved).not.toHaveBeenCalled()
  })

  it('rejects a code with characters outside the allowed shape', async () => {
    render(<CarrierFormDrawer companyId="company-1" carrier={null} onClose={vi.fn()} onSaved={vi.fn()} />)

    await userEvent.type(screen.getByLabelText(/^código/i), 'has space')
    await userEvent.click(screen.getByRole('button', { name: 'Guardar' }))

    expect(await screen.findByText('Solo letras, dígitos, guion bajo o guion')).toBeInTheDocument()
    expect(carriersApiMocks.createCarrier).not.toHaveBeenCalled()
  })

  it('creates a carrier with the entered values and reports success', async () => {
    carriersApiMocks.createCarrier.mockResolvedValue({ ...CARRIER, code: 'BETA' })
    const onSaved = vi.fn()
    render(<CarrierFormDrawer companyId="company-1" carrier={null} onClose={vi.fn()} onSaved={onSaved} />)

    await userEvent.type(screen.getByLabelText(/^código/i), 'beta')
    await userEvent.type(screen.getByLabelText(/^razón social/i), 'Beta Transport')
    await userEvent.clear(screen.getByLabelText(/^tipo de documento/i))
    await userEvent.type(screen.getByLabelText(/^tipo de documento/i), 'RUC')
    await userEvent.type(screen.getByLabelText(/^número de documento/i), '20200000002')
    await userEvent.click(screen.getByRole('button', { name: 'Guardar' }))

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
    render(<CarrierFormDrawer companyId="company-1" carrier={CARRIER} onClose={vi.fn()} onSaved={onSaved} />)

    expect(screen.getByLabelText(/^código/i)).toHaveValue('ACME')
    expect(screen.getByLabelText(/^razón social/i)).toHaveValue('Acme Transport S.A.')
    expect(screen.getByLabelText(/^número de documento/i)).toHaveValue('20100000001')

    await userEvent.clear(screen.getByLabelText(/^razón social/i))
    await userEvent.type(screen.getByLabelText(/^razón social/i), 'Acme Transport Renamed')
    await userEvent.click(screen.getByRole('button', { name: 'Guardar' }))

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
    render(<CarrierFormDrawer companyId="company-1" carrier={null} onClose={vi.fn()} onSaved={vi.fn()} />)

    await userEvent.type(screen.getByLabelText(/^código/i), 'DUP')
    await userEvent.type(screen.getByLabelText(/^razón social/i), 'Duplicate SA')
    await userEvent.type(screen.getByLabelText(/^número de documento/i), '20100000099')
    await userEvent.click(screen.getByRole('button', { name: 'Guardar' }))

    expect(await screen.findByText('email is not a valid address.')).toBeInTheDocument()
  })

  it('closes when Cancel is clicked', async () => {
    const onClose = vi.fn()
    render(<CarrierFormDrawer companyId="company-1" carrier={null} onClose={onClose} onSaved={vi.fn()} />)

    await userEvent.click(screen.getByRole('button', { name: 'Cancelar' }))

    expect(onClose).toHaveBeenCalled()
  })
})
