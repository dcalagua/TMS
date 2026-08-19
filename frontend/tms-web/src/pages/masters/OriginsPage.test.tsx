import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { ApiError } from '../../shared/api/httpClient'
import type { OriginView } from '../../shared/api/originsApi'
import { OriginsPage } from './OriginsPage'

const originsApiMocks = vi.hoisted(() => ({
  fetchOrigins: vi.fn(),
  createOrigin: vi.fn(),
  updateOrigin: vi.fn(),
  activateOrigin: vi.fn(),
  deactivateOrigin: vi.fn(),
}))
vi.mock('../../shared/api/originsApi', async () => {
  const actual = await vi.importActual<typeof import('../../shared/api/originsApi')>('../../shared/api/originsApi')
  return { ...actual, ...originsApiMocks }
})

const companyMocks = vi.hoisted(() => ({ useCompany: vi.fn() }))
vi.mock('../../shared/company/CompanyContext', () => ({ useCompany: companyMocks.useCompany }))

const alertMocks = vi.hoisted(() => ({
  notifySuccess: vi.fn(),
  notifyError: vi.fn(),
  confirmAction: vi.fn(),
}))
vi.mock('../../shared/ui/alerts', () => alertMocks)

const ORIGIN: OriginView = {
  id: 'origin-1',
  code: 'NORTH-HUB',
  name: 'North Hub',
  type: 'HUB',
  address: '123 Main St',
  latitude: -12.046374,
  longitude: -77.042793,
  timeZone: 'America/Lima',
  externalReference: null,
  active: true,
  createdAt: '2026-01-01T00:00:00Z',
  updatedAt: '2026-01-01T00:00:00Z',
}

function page(content: OriginView[], overrides: Partial<{ page: number; size: number; totalElements: number }> = {}) {
  return { content, page: overrides.page ?? 0, size: overrides.size ?? 25, totalElements: overrides.totalElements ?? content.length }
}

function mockCompany(canManage: boolean) {
  companyMocks.useCompany.mockReturnValue({
    selected: { id: 'company-1', name: 'Acme Logistics' },
    hasPermission: (permission: string) => (permission === 'masterdata.origin:manage' ? canManage : true),
  })
}

function renderPage() {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  return render(
    <QueryClientProvider client={queryClient}>
      <OriginsPage />
    </QueryClientProvider>,
  )
}

afterEach(() => {
  vi.clearAllMocks()
})

describe('OriginsPage', () => {
  it('shows a loading state while the first page is fetched', () => {
    mockCompany(true)
    originsApiMocks.fetchOrigins.mockReturnValue(new Promise(() => {}))

    renderPage()

    expect(screen.getByText('Loading records...')).toBeInTheDocument()
  })

  it('shows an empty state when the company has no origins yet', async () => {
    mockCompany(true)
    originsApiMocks.fetchOrigins.mockResolvedValue(page([]))

    renderPage()

    expect(await screen.findByText('No origins found')).toBeInTheDocument()
  })

  it('shows an error state with a retry action when the request fails', async () => {
    mockCompany(true)
    originsApiMocks.fetchOrigins.mockRejectedValue(
      new ApiError(500, { code: 'internal-error' }, 'corr-1', 'boom'),
    )

    renderPage()

    expect(await screen.findByText('Something went wrong on our side. Please try again.')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Try again' })).toBeInTheDocument()
  })

  it('lists origins returned by the backend and shows pagination once there is more than one page', async () => {
    mockCompany(true)
    originsApiMocks.fetchOrigins.mockResolvedValue(page([ORIGIN], { totalElements: 60, size: 25 }))

    renderPage()

    expect(await screen.findByText('NORTH-HUB')).toBeInTheDocument()
    expect(screen.getByRole('cell', { name: 'Hub' })).toBeInTheDocument()
    expect(screen.getByText(/Page 1 of 3/)).toBeInTheDocument()
  })

  it('hides create and manage actions for a caller without masterdata.origin:manage', async () => {
    mockCompany(false)
    originsApiMocks.fetchOrigins.mockResolvedValue(page([ORIGIN]))

    renderPage()

    await screen.findByText('NORTH-HUB')

    expect(screen.queryByRole('button', { name: 'New origin' })).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Edit' })).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Deactivate' })).not.toBeInTheDocument()
  })

  it('creates an origin through the modal and refreshes the list', async () => {
    mockCompany(true)
    originsApiMocks.fetchOrigins.mockResolvedValue(page([]))
    originsApiMocks.createOrigin.mockResolvedValue({ ...ORIGIN, code: 'NEW-ORIGIN' })

    renderPage()
    await screen.findByText('No origins found')

    await userEvent.click(screen.getByRole('button', { name: 'New origin' }))
    const dialog = within(screen.getByRole('dialog'))
    await userEvent.type(dialog.getByLabelText(/^code/i), 'NEW-ORIGIN')
    await userEvent.type(dialog.getByLabelText(/^name/i), 'New Origin')
    await userEvent.type(dialog.getByLabelText(/^time zone/i), 'America/Lima')
    await userEvent.click(dialog.getByRole('button', { name: 'Save' }))

    await waitFor(() => expect(originsApiMocks.createOrigin).toHaveBeenCalledWith('company-1', expect.objectContaining({ code: 'NEW-ORIGIN' })))
    await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument())
    expect(alertMocks.notifySuccess).toHaveBeenCalledWith('Origin created')
  })

  it('deactivates an origin only after the confirmation dialog is accepted', async () => {
    mockCompany(true)
    originsApiMocks.fetchOrigins.mockResolvedValue(page([ORIGIN]))
    originsApiMocks.deactivateOrigin.mockResolvedValue({ ...ORIGIN, active: false })

    alertMocks.confirmAction.mockResolvedValueOnce(false)
    renderPage()
    await screen.findByText('NORTH-HUB')

    await userEvent.click(screen.getByRole('button', { name: 'Deactivate' }))
    await waitFor(() => expect(alertMocks.confirmAction).toHaveBeenCalled())
    expect(originsApiMocks.deactivateOrigin).not.toHaveBeenCalled()

    alertMocks.confirmAction.mockResolvedValueOnce(true)
    await userEvent.click(screen.getByRole('button', { name: 'Deactivate' }))

    await waitFor(() => expect(originsApiMocks.deactivateOrigin).toHaveBeenCalledWith('company-1', 'origin-1'))
    expect(alertMocks.notifySuccess).toHaveBeenCalledWith('Origin deactivated', 'North Hub')
  })

  it('applies the code filter to the query', async () => {
    mockCompany(true)
    originsApiMocks.fetchOrigins.mockResolvedValue(page([ORIGIN]))

    renderPage()
    await screen.findByText('NORTH-HUB')

    await userEvent.type(screen.getByLabelText(/^code$/i), 'north')
    await userEvent.click(screen.getByRole('button', { name: 'Apply' }))

    await waitFor(() =>
      expect(originsApiMocks.fetchOrigins).toHaveBeenLastCalledWith(expect.objectContaining({ code: 'north' })),
    )
  })
})
