import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { ApiError } from '../../shared/api/httpClient'
import type { ZoneView } from '../../shared/api/zonesApi'
import { ZonesPage } from './ZonesPage'

const zonesApiMocks = vi.hoisted(() => ({
  fetchZones: vi.fn(),
  createZone: vi.fn(),
  updateZone: vi.fn(),
  activateZone: vi.fn(),
  deactivateZone: vi.fn(),
}))
vi.mock('../../shared/api/zonesApi', async () => {
  const actual = await vi.importActual<typeof import('../../shared/api/zonesApi')>('../../shared/api/zonesApi')
  return { ...actual, ...zonesApiMocks }
})

const companyMocks = vi.hoisted(() => ({ useCompany: vi.fn() }))
vi.mock('../../shared/company/CompanyContext', () => ({ useCompany: companyMocks.useCompany }))

const alertMocks = vi.hoisted(() => ({
  notifySuccess: vi.fn(),
  notifyError: vi.fn(),
  confirmAction: vi.fn(),
}))
vi.mock('../../shared/ui/alerts', () => alertMocks)

const ZONE: ZoneView = {
  id: 'zone-1',
  code: 'NORTH-ZONE',
  name: 'North Zone',
  description: 'Northern operational area',
  active: true,
  createdAt: '2026-01-01T00:00:00Z',
  updatedAt: '2026-01-01T00:00:00Z',
}

function page(content: ZoneView[], overrides: Partial<{ page: number; size: number; totalElements: number }> = {}) {
  return { content, page: overrides.page ?? 0, size: overrides.size ?? 25, totalElements: overrides.totalElements ?? content.length }
}

function mockCompany(canManage: boolean) {
  companyMocks.useCompany.mockReturnValue({
    selected: { id: 'company-1', name: 'Acme Logistics' },
    hasPermission: (permission: string) => (permission === 'masterdata.zone:manage' ? canManage : true),
  })
}

function renderPage() {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  return render(
    <QueryClientProvider client={queryClient}>
      <ZonesPage />
    </QueryClientProvider>,
  )
}

afterEach(() => {
  vi.clearAllMocks()
})

describe('ZonesPage', () => {
  it('shows a loading state while the first page is fetched', () => {
    mockCompany(true)
    zonesApiMocks.fetchZones.mockReturnValue(new Promise(() => {}))

    renderPage()

    expect(screen.getByText('Cargando registros...')).toBeInTheDocument()
  })

  it('shows an empty state when the company has no zones yet', async () => {
    mockCompany(true)
    zonesApiMocks.fetchZones.mockResolvedValue(page([]))

    renderPage()

    expect(await screen.findByText('No zones found')).toBeInTheDocument()
  })

  it('shows an error state with a retry action when the request fails', async () => {
    mockCompany(true)
    zonesApiMocks.fetchZones.mockRejectedValue(
      new ApiError(500, { code: 'internal-error' }, 'corr-1', 'boom'),
    )

    renderPage()

    expect(await screen.findByText('Ocurrió un error de nuestro lado. Vuelve a intentarlo.')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Reintentar' })).toBeInTheDocument()
  })

  it('lists zones returned by the backend and shows pagination once there is more than one page', async () => {
    mockCompany(true)
    zonesApiMocks.fetchZones.mockResolvedValue(page([ZONE], { totalElements: 60, size: 25 }))

    renderPage()

    expect(await screen.findByText('NORTH-ZONE')).toBeInTheDocument()
    expect(screen.getByText('Northern operational area')).toBeInTheDocument()
    expect(screen.getByText(/Página 1 de 3/)).toBeInTheDocument()
  })

  it('hides create and manage actions for a caller without masterdata.zone:manage', async () => {
    mockCompany(false)
    zonesApiMocks.fetchZones.mockResolvedValue(page([ZONE]))

    renderPage()

    await screen.findByText('NORTH-ZONE')

    expect(screen.queryByRole('button', { name: 'Nueva zona' })).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Edit' })).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Deactivate' })).not.toBeInTheDocument()
  })

  it('creates a zone through the modal and refreshes the list', async () => {
    mockCompany(true)
    zonesApiMocks.fetchZones.mockResolvedValue(page([]))
    zonesApiMocks.createZone.mockResolvedValue({ ...ZONE, code: 'NEW-ZONE' })

    renderPage()
    await screen.findByText('No zones found')

    await userEvent.click(screen.getByRole('button', { name: 'Nueva zona' }))
    const dialog = within(screen.getByRole('dialog'))
    await userEvent.type(dialog.getByLabelText(/^code/i), 'NEW-ZONE')
    await userEvent.type(dialog.getByLabelText(/^name/i), 'New Zone')
    await userEvent.click(dialog.getByRole('button', { name: 'Save' }))

    await waitFor(() => expect(zonesApiMocks.createZone).toHaveBeenCalledWith('company-1', expect.objectContaining({ code: 'NEW-ZONE' })))
    await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument())
    expect(alertMocks.notifySuccess).toHaveBeenCalledWith('Zone created')
  })

  it('deactivates a zone only after the confirmation dialog is accepted', async () => {
    mockCompany(true)
    zonesApiMocks.fetchZones.mockResolvedValue(page([ZONE]))
    zonesApiMocks.deactivateZone.mockResolvedValue({ ...ZONE, active: false })

    alertMocks.confirmAction.mockResolvedValueOnce(false)
    renderPage()
    await screen.findByText('NORTH-ZONE')

    await userEvent.click(screen.getByRole('button', { name: 'Deactivate' }))
    await waitFor(() => expect(alertMocks.confirmAction).toHaveBeenCalled())
    expect(zonesApiMocks.deactivateZone).not.toHaveBeenCalled()

    alertMocks.confirmAction.mockResolvedValueOnce(true)
    await userEvent.click(screen.getByRole('button', { name: 'Deactivate' }))

    await waitFor(() => expect(zonesApiMocks.deactivateZone).toHaveBeenCalledWith('company-1', 'zone-1'))
    expect(alertMocks.notifySuccess).toHaveBeenCalledWith('Zone deactivated', 'North Zone')
  })

  it('applies the code filter to the query', async () => {
    mockCompany(true)
    zonesApiMocks.fetchZones.mockResolvedValue(page([ZONE]))

    renderPage()
    await screen.findByText('NORTH-ZONE')

    await userEvent.type(screen.getByLabelText(/^code$/i), 'north')
    await userEvent.click(screen.getByRole('button', { name: 'Aplicar filtros' }))

    await waitFor(() =>
      expect(zonesApiMocks.fetchZones).toHaveBeenLastCalledWith(expect.objectContaining({ code: 'north' })),
    )
  })
})
