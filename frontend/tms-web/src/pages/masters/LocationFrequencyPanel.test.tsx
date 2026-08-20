import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { LocationFrequencyPanel } from './LocationFrequencyPanel'

const frequenciesApiMocks = vi.hoisted(() => ({ fetchFrequencies: vi.fn() }))
vi.mock('../../shared/api/frequenciesApi', async () => {
  const actual = await vi.importActual<typeof import('../../shared/api/frequenciesApi')>('../../shared/api/frequenciesApi')
  return { ...actual, fetchFrequencies: frequenciesApiMocks.fetchFrequencies }
})

const locationFrequenciesApiMocks = vi.hoisted(() => ({
  fetchLocationFrequencies: vi.fn(),
  createLocationFrequency: vi.fn(),
  updateLocationFrequency: vi.fn(),
  activateLocationFrequency: vi.fn(),
  deactivateLocationFrequency: vi.fn(),
  deleteLocationFrequency: vi.fn(),
  fetchLocationEligibility: vi.fn(),
}))
vi.mock('../../shared/api/locationFrequenciesApi', async () => {
  const actual =
    await vi.importActual<typeof import('../../shared/api/locationFrequenciesApi')>('../../shared/api/locationFrequenciesApi')
  return { ...actual, ...locationFrequenciesApiMocks }
})

vi.mock('../../shared/ui/alerts', async () => {
  const actual = await vi.importActual<typeof import('../../shared/ui/alerts')>('../../shared/ui/alerts')
  return { ...actual, notifySuccess: vi.fn(), notifyError: vi.fn() }
})

const CONFIRM_DIALOG_MOCK = vi.hoisted(() => vi.fn().mockResolvedValue(true))
vi.mock('../../shared/ui/components', async () => {
  const actual = await vi.importActual<typeof import('../../shared/ui/components')>('../../shared/ui/components')
  return { ...actual, confirmDialog: CONFIRM_DIALOG_MOCK }
})

function renderPanel() {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  return render(
    <QueryClientProvider client={queryClient}>
      <LocationFrequencyPanel companyId="company-1" locationId="location-1" locationName="Lima DC" />
    </QueryClientProvider>,
  )
}

afterEach(() => {
  vi.clearAllMocks()
})

describe('LocationFrequencyPanel', () => {
  it('shows the empty state when the location has no associated frequency', async () => {
    locationFrequenciesApiMocks.fetchLocationFrequencies.mockResolvedValue([])
    frequenciesApiMocks.fetchFrequencies.mockResolvedValue({ content: [], page: 0, size: 200, totalElements: 0 })

    renderPanel()

    expect(await screen.findByText('Aún no hay frecuencias asociadas a esta ubicación.')).toBeInTheDocument()
  })

  it('lists an existing association with its date range and active state', async () => {
    locationFrequenciesApiMocks.fetchLocationFrequencies.mockResolvedValue([
      {
        id: 'assoc-1',
        frequencyId: 'freq-1',
        frequencyCode: 'WEEKLY',
        frequencyName: 'Weekly delivery',
        effectiveFrom: '2026-01-01',
        effectiveTo: null,
        active: true,
        createdAt: '2026-01-01T00:00:00Z',
      },
    ])
    frequenciesApiMocks.fetchFrequencies.mockResolvedValue({ content: [], page: 0, size: 200, totalElements: 0 })

    renderPanel()

    expect(await screen.findByText('WEEKLY — Weekly delivery')).toBeInTheDocument()
    expect(screen.getByText('Activo')).toBeInTheDocument()
  })

  it('creates a new association and refreshes the list', async () => {
    locationFrequenciesApiMocks.fetchLocationFrequencies.mockResolvedValue([])
    frequenciesApiMocks.fetchFrequencies.mockResolvedValue({
      content: [{ id: 'freq-1', code: 'WEEKLY', name: 'Weekly delivery', description: null, active: true, weeklyRules: [],
        createdAt: '2026-01-01T00:00:00Z', updatedAt: '2026-01-01T00:00:00Z' }],
      page: 0, size: 200, totalElements: 1,
    })
    locationFrequenciesApiMocks.createLocationFrequency.mockResolvedValue({
      id: 'assoc-1', frequencyId: 'freq-1', frequencyCode: 'WEEKLY', frequencyName: 'Weekly delivery',
      effectiveFrom: null, effectiveTo: null, active: true, createdAt: '2026-01-01T00:00:00Z',
    })

    renderPanel()

    await userEvent.click(await screen.findByRole('combobox', { name: /frecuencia/i }))
    await userEvent.click(await screen.findByRole('option', { name: 'Weekly delivery' }))
    await userEvent.click(screen.getByRole('button', { name: 'Asociar frecuencia' }))

    await waitFor(() =>
      expect(locationFrequenciesApiMocks.createLocationFrequency).toHaveBeenCalledWith('company-1', 'location-1', {
        frequencyId: 'freq-1',
        effectiveFrom: null,
        effectiveTo: null,
      }),
    )
  })

  it('deletes an association after the destructive confirm is accepted', async () => {
    locationFrequenciesApiMocks.fetchLocationFrequencies.mockResolvedValue([
      {
        id: 'assoc-1', frequencyId: 'freq-1', frequencyCode: 'WEEKLY', frequencyName: 'Weekly delivery',
        effectiveFrom: null, effectiveTo: null, active: true, createdAt: '2026-01-01T00:00:00Z',
      },
    ])
    frequenciesApiMocks.fetchFrequencies.mockResolvedValue({ content: [], page: 0, size: 200, totalElements: 0 })
    locationFrequenciesApiMocks.deleteLocationFrequency.mockResolvedValue(undefined)

    renderPanel()

    await userEvent.click(await screen.findByRole('button', { name: 'Eliminar' }))

    await waitFor(() =>
      expect(locationFrequenciesApiMocks.deleteLocationFrequency).toHaveBeenCalledWith('company-1', 'location-1', 'assoc-1'),
    )
  })

  it('does not delete when the confirm dialog is dismissed', async () => {
    CONFIRM_DIALOG_MOCK.mockResolvedValueOnce(false)
    locationFrequenciesApiMocks.fetchLocationFrequencies.mockResolvedValue([
      {
        id: 'assoc-1', frequencyId: 'freq-1', frequencyCode: 'WEEKLY', frequencyName: 'Weekly delivery',
        effectiveFrom: null, effectiveTo: null, active: true, createdAt: '2026-01-01T00:00:00Z',
      },
    ])
    frequenciesApiMocks.fetchFrequencies.mockResolvedValue({ content: [], page: 0, size: 200, totalElements: 0 })

    renderPanel()

    await userEvent.click(await screen.findByRole('button', { name: 'Eliminar' }))

    await waitFor(() => expect(CONFIRM_DIALOG_MOCK).toHaveBeenCalled())
    expect(locationFrequenciesApiMocks.deleteLocationFrequency).not.toHaveBeenCalled()
  })

  it('checks eligibility for the chosen date and shows the result', async () => {
    locationFrequenciesApiMocks.fetchLocationFrequencies.mockResolvedValue([
      {
        id: 'assoc-1', frequencyId: 'freq-1', frequencyCode: 'WEEKLY', frequencyName: 'Weekly delivery',
        effectiveFrom: null, effectiveTo: null, active: true, createdAt: '2026-01-01T00:00:00Z',
      },
    ])
    frequenciesApiMocks.fetchFrequencies.mockResolvedValue({ content: [], page: 0, size: 200, totalElements: 0 })
    locationFrequenciesApiMocks.fetchLocationEligibility.mockResolvedValue({
      date: '2026-08-24', eligible: true, reason: 'ok', frequencyId: 'freq-1', cutoffTime: '15:00:00', leadTimeDays: 2,
    })

    renderPanel()
    await screen.findByText('WEEKLY — Weekly delivery')

    await userEvent.clear(screen.getByLabelText('Fecha a verificar'))
    await userEvent.type(screen.getByLabelText('Fecha a verificar'), '2026-08-24')
    await userEvent.click(screen.getByRole('button', { name: 'Verificar' }))

    expect(await screen.findByText('Elegible para despacho')).toBeInTheDocument()
    expect(locationFrequenciesApiMocks.fetchLocationEligibility).toHaveBeenCalledWith('company-1', 'location-1', '2026-08-24')
  })
})
