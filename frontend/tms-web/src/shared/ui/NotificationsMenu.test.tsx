import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter } from 'react-router-dom'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import type { NotificationFeedView, NotificationView } from '../api/notificationsApi'
import { NotificationsMenu } from './NotificationsMenu'

const apiMocks = vi.hoisted(() => ({
  fetchNotifications: vi.fn(),
  markNotificationRead: vi.fn(),
  markAllNotificationsRead: vi.fn(),
}))
vi.mock('../api/notificationsApi', async () => {
  const actual = await vi.importActual<typeof import('../api/notificationsApi')>('../api/notificationsApi')
  return { ...actual, ...apiMocks }
})

const companyMocks = vi.hoisted(() => ({ useCompany: vi.fn() }))
vi.mock('../company/CompanyContext', () => ({ useCompany: companyMocks.useCompany }))

const navigateMock = vi.hoisted(() => vi.fn())
vi.mock('react-router-dom', async () => {
  const actual = await vi.importActual<typeof import('react-router-dom')>('react-router-dom')
  return { ...actual, useNavigate: () => navigateMock }
})

function alert(overrides: Partial<NotificationView> = {}): NotificationView {
  return {
    id: 'alert-1',
    type: 'TRIP_DELAYED',
    severity: 'WARNING',
    entityType: 'TRIP',
    entityId: 'trip-1',
    entityLabel: 'SH-00000042',
    messageArgs: { shipmentNumber: 'SH-00000042', minutes: 95 },
    occurredAt: '2026-08-20T09:35:00Z',
    readAt: null,
    resolvedAt: null,
    ...overrides,
  }
}

function feed(overrides: Partial<NotificationFeedView> = {}): NotificationFeedView {
  return { unreadCount: 1, notifications: [alert()], ...overrides }
}

function renderMenu() {
  companyMocks.useCompany.mockReturnValue({
    status: 'ready',
    companies: [],
    selected: { id: 'company-1', name: 'Company A' },
    selectCompany: vi.fn(),
  })
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  return render(
    <QueryClientProvider client={client}>
      <MemoryRouter>
        <NotificationsMenu />
      </MemoryRouter>
    </QueryClientProvider>,
  )
}

describe('NotificationsMenu', () => {
  // The suite has two "was never called" assertions, and the shared setup file registers no
  // clearing hook - so this is load-bearing rather than housekeeping.
  beforeEach(() => {
    vi.clearAllMocks()
  })

  it('renders the sentence from the type and its arguments, never from server text', async () => {
    apiMocks.fetchNotifications.mockResolvedValue(feed())

    renderMenu()
    await userEvent.click(await screen.findByRole('button', { name: /1 sin leer/ }))

    expect(screen.getByText('Salió tarde')).toBeInTheDocument()
    expect(
      screen.getByText('El envío SH-00000042 salió 95 minutos después de lo planificado.'),
    ).toBeInTheDocument()
  })

  it('labels an enum-shaped argument instead of showing the contract value to an operator', async () => {
    apiMocks.fetchNotifications.mockResolvedValue(
      feed({
        notifications: [
          alert({
            type: 'EXCEPTION_OPENED',
            messageArgs: { shipmentNumber: 'SH-00000042', exceptionType: 'VEHICLE_BREAKDOWN' },
          }),
        ],
      }),
    )

    renderMenu()
    await userEvent.click(await screen.findByRole('button', { name: /1 sin leer/ }))

    expect(screen.queryByText(/VEHICLE_BREAKDOWN/)).not.toBeInTheDocument()
    expect(screen.getByText(/en el envío SH-00000042/)).toBeInTheDocument()
  })

  it('acknowledges an unread alert and navigates to what it is about', async () => {
    apiMocks.fetchNotifications.mockResolvedValue(feed())
    apiMocks.markNotificationRead.mockResolvedValue(feed({ unreadCount: 0 }))

    renderMenu()
    await userEvent.click(await screen.findByRole('button', { name: /1 sin leer/ }))
    await userEvent.click(screen.getByRole('menuitem', { name: /Salió tarde/ }))

    await waitFor(() => expect(apiMocks.markNotificationRead).toHaveBeenCalledWith('company-1', 'alert-1'))
    expect(navigateMock).toHaveBeenCalledWith('/trips/trip-1')
  })

  it('sends a driver alert to the fleet screen rather than to a shipment', async () => {
    apiMocks.fetchNotifications.mockResolvedValue(
      feed({
        notifications: [
          alert({
            type: 'DRIVER_LICENSE_EXPIRING',
            entityType: 'DRIVER',
            entityId: 'driver-9',
            entityLabel: 'DR-1',
            readAt: '2026-08-20T10:00:00Z',
            messageArgs: {
              driverName: 'Quispe, Ana',
              expiresOn: '2026-09-01',
              shipmentNumber: 'SH-00000042',
            },
          }),
        ],
        unreadCount: 0,
      }),
    )

    renderMenu()
    await userEvent.click(await screen.findByRole('button', { name: 'Avisos' }))
    await userEvent.click(screen.getByRole('menuitem', { name: /Licencia por vencer/ }))

    expect(navigateMock).toHaveBeenCalledWith('/fleet/drivers')
    // Already acknowledged: opening it again must not write a second read.
    expect(apiMocks.markNotificationRead).not.toHaveBeenCalled()
  })

  it('shows no badge and the empty panel when there is nothing to report', async () => {
    apiMocks.fetchNotifications.mockResolvedValue({ unreadCount: 0, notifications: [] })

    renderMenu()
    const bell = await screen.findByRole('button', { name: 'Avisos' })
    await userEvent.click(bell)

    expect(screen.getByText('No tienes avisos')).toBeInTheDocument()
    expect(screen.queryByRole('menuitem')).not.toBeInTheDocument()
  })

  it('clears the badge over everything at once', async () => {
    apiMocks.fetchNotifications.mockResolvedValue(feed({ unreadCount: 3 }))
    apiMocks.markAllNotificationsRead.mockResolvedValue({ unreadCount: 0, notifications: [] })

    renderMenu()
    await userEvent.click(await screen.findByRole('button', { name: /3 sin leer/ }))
    await userEvent.click(screen.getByRole('menuitem', { name: /Marcar todo como leído/ }))

    await waitFor(() => expect(apiMocks.markAllNotificationsRead).toHaveBeenCalledWith('company-1'))
    await waitFor(() => expect(screen.getByRole('button', { name: 'Avisos' })).toBeInTheDocument())
  })

  it('asks for nothing until a company is selected', () => {
    companyMocks.useCompany.mockReturnValue({
      status: 'loading',
      companies: [],
      selected: null,
      selectCompany: vi.fn(),
    })
    const client = new QueryClient({ defaultOptions: { queries: { retry: false } } })

    render(
      <QueryClientProvider client={client}>
        <MemoryRouter>
          <NotificationsMenu />
        </MemoryRouter>
      </QueryClientProvider>,
    )

    expect(apiMocks.fetchNotifications).not.toHaveBeenCalled()
    expect(screen.getByRole('button', { name: 'Avisos' })).toBeInTheDocument()
  })
})
