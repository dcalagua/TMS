import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, describe, expect, it, vi } from 'vitest'
import type { FrequencyView } from '../../shared/api/frequenciesApi'
import { FrequencyFormDrawer } from './FrequencyFormDrawer'

const frequenciesApiMocks = vi.hoisted(() => ({
  createFrequency: vi.fn(),
  updateFrequency: vi.fn(),
  fetchFrequencyExceptions: vi.fn(),
  createFrequencyException: vi.fn(),
  deleteFrequencyException: vi.fn(),
}))
vi.mock('../../shared/api/frequenciesApi', async () => {
  const actual =
    await vi.importActual<typeof import('../../shared/api/frequenciesApi')>('../../shared/api/frequenciesApi')
  return { ...actual, ...frequenciesApiMocks }
})

// The exceptions panel reports through SweetAlert2. Left real, it resolves after the test has
// torn the DOM down and surfaces as an unhandled error unrelated to anything asserted here.
const alertMocks = vi.hoisted(() => ({
  notifySuccess: vi.fn(),
  notifyError: vi.fn(),
  confirmDialog: vi.fn(),
}))
vi.mock('../../shared/ui/alerts', () => alertMocks)

/**
 * The drawer now hosts the date-exceptions sub-resource, which fetches. Rendering through a
 * query client keeps every case in this file honest about that rather than only the ones that
 * assert on it.
 */
function renderDrawer(ui: React.ReactElement) {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  return render(<QueryClientProvider client={queryClient}>{ui}</QueryClientProvider>)
}

const FREQUENCY: FrequencyView = {
  id: 'frequency-1',
  code: 'MON-WED-FRI',
  name: 'Monday Wednesday Friday',
  description: 'Standard route schedule',
  active: true,
  weeklyRules: [
    { dayOfWeek: 1, enabled: true, cutoffTime: '10:00:00', leadTimeDays: 1 },
    { dayOfWeek: 3, enabled: true, cutoffTime: null, leadTimeDays: null },
  ],
  createdAt: '2026-01-01T00:00:00Z',
  updatedAt: '2026-01-01T00:00:00Z',
}

afterEach(() => {
  vi.clearAllMocks()
})

describe('FrequencyFormDrawer', () => {
  it('rejects an empty submission without calling the API', async () => {
    const onSaved = vi.fn()
    renderDrawer(<FrequencyFormDrawer companyId="company-1" frequency={null} onClose={vi.fn()} onSaved={onSaved} />)

    await userEvent.click(screen.getByRole('button', { name: 'Guardar' }))

    await waitFor(() => expect(screen.getAllByText('Este campo es obligatorio')).toHaveLength(2))
    expect(screen.getByLabelText(/^código/i)).toHaveClass('is-invalid')
    expect(frequenciesApiMocks.createFrequency).not.toHaveBeenCalled()
    expect(onSaved).not.toHaveBeenCalled()
  })

  it('rejects a code with characters outside the allowed shape', async () => {
    renderDrawer(<FrequencyFormDrawer companyId="company-1" frequency={null} onClose={vi.fn()} onSaved={vi.fn()} />)

    await userEvent.type(screen.getByLabelText(/^código/i), 'has space')
    await userEvent.click(screen.getByRole('button', { name: 'Guardar' }))

    expect(await screen.findByText('Solo letras, dígitos, guion bajo o guion')).toBeInTheDocument()
    expect(frequenciesApiMocks.createFrequency).not.toHaveBeenCalled()
  })

  it('renders a fixed Monday-Sunday grid and sends all 7 rows, checked days enabled', async () => {
    frequenciesApiMocks.createFrequency.mockResolvedValue(FREQUENCY)
    const onSaved = vi.fn()
    renderDrawer(<FrequencyFormDrawer companyId="company-1" frequency={null} onClose={vi.fn()} onSaved={onSaved} />)

    await userEvent.type(screen.getByLabelText(/^código/i), 'new-freq')
    await userEvent.type(screen.getByLabelText(/^nombre/i), 'New Frequency')

    await userEvent.click(screen.getByLabelText('Lunes'))
    await userEvent.click(screen.getByLabelText('Miércoles'))
    fireEvent.change(screen.getByLabelText(/Hora de corte de Lunes/i), { target: { value: '14:00' } })
    await userEvent.type(screen.getByLabelText(/Anticipación en días de Lunes/i), '2')

    await userEvent.click(screen.getByRole('button', { name: 'Guardar' }))

    await waitFor(() => expect(frequenciesApiMocks.createFrequency).toHaveBeenCalled())
    const request = frequenciesApiMocks.createFrequency.mock.calls[0]?.[1]
    expect(request.weeklyRules).toHaveLength(7)
    expect(request.weeklyRules[0]).toEqual({ dayOfWeek: 1, enabled: true, cutoffTime: '14:00:00', leadTimeDays: 2 })
    expect(request.weeklyRules[2]).toEqual({ dayOfWeek: 3, enabled: true, cutoffTime: null, leadTimeDays: null })
    expect(request.weeklyRules[1]).toEqual({ dayOfWeek: 2, enabled: false, cutoffTime: null, leadTimeDays: null })
    expect(onSaved).toHaveBeenCalled()
  })

  it('pre-fills the form for an edit: checked days and their cutoff/lead time', async () => {
    frequenciesApiMocks.updateFrequency.mockResolvedValue(FREQUENCY)
    const onSaved = vi.fn()
    renderDrawer(<FrequencyFormDrawer companyId="company-1" frequency={FREQUENCY} onClose={vi.fn()} onSaved={onSaved} />)

    expect(screen.getByLabelText(/^código/i)).toHaveValue('MON-WED-FRI')
    expect(screen.getByLabelText('Lunes')).toBeChecked()
    expect(screen.getByLabelText('Miércoles')).toBeChecked()
    expect(screen.getByLabelText('Martes')).not.toBeChecked()
    expect(screen.getByLabelText(/Hora de corte de Lunes/i)).toHaveValue('10:00')

    await userEvent.click(screen.getByRole('button', { name: 'Guardar' }))

    await waitFor(() =>
      expect(frequenciesApiMocks.updateFrequency).toHaveBeenCalledWith(
        'company-1',
        'frequency-1',
        expect.objectContaining({ code: 'MON-WED-FRI' }),
      ),
    )
    expect(onSaved).toHaveBeenCalled()
  })

  it('maps a backend field error onto the matching input instead of a generic message', async () => {
    frequenciesApiMocks.createFrequency.mockRejectedValue({
      fieldErrors: [{ field: 'code', message: "code 'DUP' already exists" }],
    })
    renderDrawer(<FrequencyFormDrawer companyId="company-1" frequency={null} onClose={vi.fn()} onSaved={vi.fn()} />)

    await userEvent.type(screen.getByLabelText(/^código/i), 'DUP')
    await userEvent.type(screen.getByLabelText(/^nombre/i), 'Duplicate')
    await userEvent.click(screen.getByRole('button', { name: 'Guardar' }))

    expect(await screen.findByText("code 'DUP' already exists")).toBeInTheDocument()
  })

  it('defers date exceptions until the frequency exists, because they hang off it', () => {
    renderDrawer(<FrequencyFormDrawer companyId="company-1" frequency={null} onClose={vi.fn()} onSaved={vi.fn()} />)

    expect(screen.getByText(/Guarda la frecuencia primero/i)).toBeInTheDocument()
    expect(frequenciesApiMocks.fetchFrequencyExceptions).not.toHaveBeenCalled()
  })

  it('lists the date exceptions of a saved frequency, each with its kind', async () => {
    frequenciesApiMocks.fetchFrequencyExceptions.mockResolvedValue([
      { id: 'exc-1', exceptionDate: '2026-12-25', serviceOverride: false, note: 'Navidad',
        createdAt: '2026-01-01T00:00:00Z' },
      { id: 'exc-2', exceptionDate: '2026-12-19', serviceOverride: true, note: null,
        createdAt: '2026-01-01T00:00:00Z' },
    ])

    renderDrawer(
      <FrequencyFormDrawer companyId="company-1" frequency={FREQUENCY} onClose={vi.fn()} onSaved={vi.fn()} />,
    )

    expect(await screen.findByText('2026-12-25')).toBeInTheDocument()
    // Scoped to the table: "Cerrado" and "Abierto" are also the two options of the kind
    // selector below it, and an unscoped query would pass on the wrong element.
    const rows = within(screen.getByRole('table', { name: 'Excepciones por fecha' }))
    // Closed removes a date the cadence would have served; open adds one it would not.
    expect(rows.getByText('Cerrado')).toBeInTheDocument()
    expect(rows.getByText('Abierto')).toBeInTheDocument()
    expect(rows.getByText('Navidad')).toBeInTheDocument()
  })

  it('adds a closed date and refreshes the list', async () => {
    frequenciesApiMocks.fetchFrequencyExceptions.mockResolvedValue([])
    frequenciesApiMocks.createFrequencyException.mockResolvedValue({
      id: 'exc-1', exceptionDate: '2026-12-25', serviceOverride: false, note: null,
      createdAt: '2026-01-01T00:00:00Z',
    })

    renderDrawer(
      <FrequencyFormDrawer companyId="company-1" frequency={FREQUENCY} onClose={vi.fn()} onSaved={vi.fn()} />,
    )
    await screen.findByText('Sin excepciones registradas.')

    fireEvent.change(screen.getByLabelText('Fecha'), { target: { value: '2026-12-25' } })
    await userEvent.click(screen.getByRole('button', { name: 'Agregar excepción' }))

    await waitFor(() =>
      expect(frequenciesApiMocks.createFrequencyException).toHaveBeenCalledWith('company-1', 'frequency-1', {
        exceptionDate: '2026-12-25',
        serviceOverride: false,
        note: null,
      }),
    )
  })

  it('closes when Cancel is clicked', async () => {
    const onClose = vi.fn()
    renderDrawer(<FrequencyFormDrawer companyId="company-1" frequency={null} onClose={onClose} onSaved={vi.fn()} />)

    await userEvent.click(screen.getByRole('button', { name: 'Cancelar' }))

    expect(onClose).toHaveBeenCalled()
  })
})
