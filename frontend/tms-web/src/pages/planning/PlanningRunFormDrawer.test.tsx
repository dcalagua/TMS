import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { ApiError } from '../../shared/api/httpClient'
import type { PlanningRunDetailView } from '../../shared/api/planningApi'
import { PlanningRunFormDrawer } from './PlanningRunFormDrawer'

const planningApiMocks = vi.hoisted(() => ({ createPlanningRun: vi.fn() }))
vi.mock('../../shared/api/planningApi', async () => {
  const actual = await vi.importActual<typeof import('../../shared/api/planningApi')>('../../shared/api/planningApi')
  return { ...actual, createPlanningRun: planningApiMocks.createPlanningRun }
})

const originsApiMocks = vi.hoisted(() => ({ fetchOrigins: vi.fn() }))
vi.mock('../../shared/api/originsApi', async () => {
  const actual = await vi.importActual<typeof import('../../shared/api/originsApi')>('../../shared/api/originsApi')
  return { ...actual, fetchOrigins: originsApiMocks.fetchOrigins }
})

function page<T>(content: T[]) {
  return { content, page: 0, size: 200, totalElements: content.length }
}

function renderModal(onCreated = vi.fn(), onClose = vi.fn()) {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  const utils = render(
    <QueryClientProvider client={queryClient}>
      <PlanningRunFormDrawer companyId="company-1" onClose={onClose} onCreated={onCreated} />
    </QueryClientProvider>,
  )
  return { ...utils, onCreated, onClose }
}

afterEach(() => {
  vi.clearAllMocks()
})

describe('PlanningRunFormDrawer', () => {
  it('submits the origin, planning date and trimmed notes, then reports the created run', async () => {
    originsApiMocks.fetchOrigins.mockResolvedValue(page([{ id: 'origin-1', code: 'ORIGIN-A', name: 'Origin A' }]))
    const created: PlanningRunDetailView = {
      run: {
        id: 'run-1', planNumber: 'PLN-1', originId: 'origin-1', originCode: 'ORIGIN-A', originName: 'Origin A',
        planningDate: '2026-03-01', mode: 'MANUAL', status: 'DRAFT', notes: 'Priority run', tripCount: 0,
        assignedOrderCount: 0, confirmedAt: null, cancelledAt: null, cancelReason: null, version: 0,
        createdAt: '2026-01-01T00:00:00Z', updatedAt: '2026-01-01T00:00:00Z',
      },
      trips: [],
    }
    planningApiMocks.createPlanningRun.mockResolvedValue(created)
    const { onCreated } = renderModal()

    await userEvent.click(screen.getByRole('combobox', { name: /^Origen/ }))
    await userEvent.click(await screen.findByRole('option', { name: 'Origin A' }))
    await userEvent.type(screen.getByLabelText(/^Fecha de planificaci/i), '2026-03-01')
    await userEvent.type(screen.getByLabelText(/^Notas/i), '  Priority run  ')
    await userEvent.click(screen.getByRole('button', { name: 'Crear corrida' }))

    await waitFor(() =>
      expect(planningApiMocks.createPlanningRun).toHaveBeenCalledWith('company-1', {
        originId: 'origin-1', planningDate: '2026-03-01', notes: 'Priority run',
      }),
    )
    expect(onCreated).toHaveBeenCalledWith(created)
  })

  it('shows the backend error and does not report a created run when the request fails', async () => {
    originsApiMocks.fetchOrigins.mockResolvedValue(page([{ id: 'origin-1', code: 'ORIGIN-A', name: 'Origin A' }]))
    planningApiMocks.createPlanningRun.mockRejectedValue(
      new ApiError(409, { code: 'conflict', detail: 'An open planning run already exists for origin A on 2026-03-01.' }, 'corr-1', 'boom'),
    )
    const { onCreated } = renderModal()

    await userEvent.click(screen.getByRole('combobox', { name: /^Origen/ }))
    await userEvent.click(await screen.findByRole('option', { name: 'Origin A' }))
    await userEvent.type(screen.getByLabelText(/^Fecha de planificaci/i), '2026-03-01')
    await userEvent.click(screen.getByRole('button', { name: 'Crear corrida' }))

    expect(await screen.findByText('Este cambio entra en conflicto con otra actualización. Recarga e inténtalo de nuevo.')).toBeInTheDocument()
    expect(onCreated).not.toHaveBeenCalled()
  })

  it('calls onClose when Cancel is clicked', async () => {
    originsApiMocks.fetchOrigins.mockResolvedValue(page([]))
    const { onClose } = renderModal()

    await userEvent.click(await screen.findByRole('button', { name: 'Cancelar' }))

    expect(onClose).toHaveBeenCalled()
  })
})
