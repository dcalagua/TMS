import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { ApiError } from '../../shared/api/httpClient'
import type { FrequencyView } from '../../shared/api/frequenciesApi'
import { FrequenciesPage } from './FrequenciesPage'

const frequenciesApiMocks = vi.hoisted(() => ({
  fetchFrequencies: vi.fn(),
  createFrequency: vi.fn(),
  updateFrequency: vi.fn(),
  activateFrequency: vi.fn(),
  deactivateFrequency: vi.fn(),
}))
vi.mock('../../shared/api/frequenciesApi', async () => {
  const actual =
    await vi.importActual<typeof import('../../shared/api/frequenciesApi')>('../../shared/api/frequenciesApi')
  return { ...actual, ...frequenciesApiMocks }
})

const companyMocks = vi.hoisted(() => ({ useCompany: vi.fn() }))
vi.mock('../../shared/company/CompanyContext', () => ({ useCompany: companyMocks.useCompany }))

const alertMocks = vi.hoisted(() => ({
  notifySuccess: vi.fn(),
  notifyError: vi.fn(),
  confirmAction: vi.fn(),
}))
vi.mock('../../shared/ui/alerts', () => alertMocks)

const FREQUENCY: FrequencyView = {
  id: 'frequency-1',
  code: 'MON-WED-FRI',
  name: 'Monday Wednesday Friday',
  description: 'Standard route schedule',
  active: true,
  weeklyRules: [
    { dayOfWeek: 1, enabled: true, cutoffTime: '10:00:00', leadTimeDays: 1 },
    { dayOfWeek: 3, enabled: true, cutoffTime: '10:00:00', leadTimeDays: 1 },
    { dayOfWeek: 5, enabled: true, cutoffTime: null, leadTimeDays: null },
  ],
  createdAt: '2026-01-01T00:00:00Z',
  updatedAt: '2026-01-01T00:00:00Z',
}

function page(content: FrequencyView[], overrides: Partial<{ page: number; size: number; totalElements: number }> = {}) {
  return { content, page: overrides.page ?? 0, size: overrides.size ?? 25, totalElements: overrides.totalElements ?? content.length }
}

function mockCompany(canManage: boolean) {
  companyMocks.useCompany.mockReturnValue({
    selected: { id: 'company-1', name: 'Acme Logistics' },
    hasPermission: (permission: string) => (permission === 'masterdata.frequency:manage' ? canManage : true),
  })
}

function renderPage() {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  return render(
    <QueryClientProvider client={queryClient}>
      <FrequenciesPage />
    </QueryClientProvider>,
  )
}

afterEach(() => {
  vi.clearAllMocks()
})

describe('FrequenciesPage', () => {
  it('shows a loading state while the first page is fetched', () => {
    mockCompany(true)
    frequenciesApiMocks.fetchFrequencies.mockReturnValue(new Promise(() => {}))

    renderPage()

    expect(screen.getByText('Cargando registros...')).toBeInTheDocument()
  })

  it('shows an empty state when the company has no frequencies yet', async () => {
    mockCompany(true)
    frequenciesApiMocks.fetchFrequencies.mockResolvedValue(page([]))

    renderPage()

    expect(await screen.findByText('Sin frecuencias')).toBeInTheDocument()
  })

  it('shows an error state with a retry action when the request fails', async () => {
    mockCompany(true)
    frequenciesApiMocks.fetchFrequencies.mockRejectedValue(
      new ApiError(500, { code: 'internal-error' }, 'corr-1', 'boom'),
    )

    renderPage()

    expect(await screen.findByText('Ocurrió un error de nuestro lado. Vuelve a intentarlo.')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Reintentar' })).toBeInTheDocument()
  })

  it('lists frequencies and summarizes their enabled weekly days', async () => {
    mockCompany(true)
    frequenciesApiMocks.fetchFrequencies.mockResolvedValue(page([FREQUENCY], { totalElements: 60, size: 25 }))

    renderPage()

    expect(await screen.findByText('MON-WED-FRI')).toBeInTheDocument()
    expect(screen.getByText('Mon, Wed, Fri')).toBeInTheDocument()
    expect(screen.getByText(/Página 1 de 3/)).toBeInTheDocument()
  })

  it('hides create and manage actions for a caller without masterdata.frequency:manage', async () => {
    mockCompany(false)
    frequenciesApiMocks.fetchFrequencies.mockResolvedValue(page([FREQUENCY]))

    renderPage()

    await screen.findByText('MON-WED-FRI')

    expect(screen.queryByRole('button', { name: 'Nueva frecuencia' })).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Abrir menú de acciones' })).not.toBeInTheDocument()
  })

  it('creates a frequency through the modal and refreshes the list', async () => {
    mockCompany(true)
    frequenciesApiMocks.fetchFrequencies.mockResolvedValue(page([]))
    frequenciesApiMocks.createFrequency.mockResolvedValue({ ...FREQUENCY, code: 'NEW-FREQ' })

    renderPage()
    await screen.findByText('Sin frecuencias')

    await userEvent.click(screen.getByRole('button', { name: 'Nueva frecuencia' }))
    const dialog = within(screen.getByRole('dialog'))
    await userEvent.type(dialog.getByLabelText(/^código/i), 'NEW-FREQ')
    await userEvent.type(dialog.getByLabelText(/^nombre/i), 'New Frequency')
    await userEvent.click(dialog.getByRole('button', { name: 'Guardar' }))

    await waitFor(() =>
      expect(frequenciesApiMocks.createFrequency).toHaveBeenCalledWith(
        'company-1',
        expect.objectContaining({ code: 'NEW-FREQ' }),
      ),
    )
    await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument())
    expect(alertMocks.notifySuccess).toHaveBeenCalledWith('Registro creado')
  })

  it('deactivates a frequency only after the confirmation dialog is accepted', async () => {
    mockCompany(true)
    frequenciesApiMocks.fetchFrequencies.mockResolvedValue(page([FREQUENCY]))
    frequenciesApiMocks.deactivateFrequency.mockResolvedValue({ ...FREQUENCY, active: false })

    alertMocks.confirmAction.mockResolvedValueOnce(false)
    renderPage()
    await screen.findByText('MON-WED-FRI')

    await userEvent.click(screen.getAllByRole('button', { name: 'Abrir menú de acciones' })[0] as HTMLElement)
    await userEvent.click(await screen.findByRole('menuitem', { name: 'Desactivar' }))
    await waitFor(() => expect(alertMocks.confirmAction).toHaveBeenCalled())
    expect(frequenciesApiMocks.deactivateFrequency).not.toHaveBeenCalled()

    alertMocks.confirmAction.mockResolvedValueOnce(true)
    await userEvent.click(screen.getAllByRole('button', { name: 'Abrir menú de acciones' })[0] as HTMLElement)
    await userEvent.click(await screen.findByRole('menuitem', { name: 'Desactivar' }))

    await waitFor(() =>
      expect(frequenciesApiMocks.deactivateFrequency).toHaveBeenCalledWith('company-1', 'frequency-1'),
    )
    expect(alertMocks.notifySuccess).toHaveBeenCalledWith('Registro desactivado', 'Monday Wednesday Friday')
  })

  it('applies the code filter to the query', async () => {
    mockCompany(true)
    frequenciesApiMocks.fetchFrequencies.mockResolvedValue(page([FREQUENCY]))

    renderPage()
    await screen.findByText('MON-WED-FRI')

    await userEvent.type(screen.getByLabelText(/^código$/i), 'mon')
    await userEvent.click(screen.getByRole('button', { name: 'Aplicar filtros' }))

    await waitFor(() =>
      expect(frequenciesApiMocks.fetchFrequencies).toHaveBeenLastCalledWith(expect.objectContaining({ code: 'mon' })),
    )
  })
})
