import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { createMemoryRouter, RouterProvider } from 'react-router-dom'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { appRoutes } from './router'

/**
 * Navigation is asserted against the application's real route table (`appRoutes`) rather than
 * a copy: a click must change `location.pathname` *and* mount that route's screen. Asserting
 * only that a `NavLink` exists is what let the previous suite stay green while the links did
 * not navigate at all.
 */

const company = {
  id: 'company-1',
  code: 'C1',
  name: 'EBIM Logistics',
  timeZone: 'America/Lima',
  organization: { id: 'org-1', code: 'EBIM', name: 'EBIM' },
  permissions: [],
  capabilities: [],
}

vi.mock('../shared/auth/AuthContext', () => ({
  useAuth: () => ({
    status: 'signedIn',
    user: { id: 'user-1', email: 'planner@ebim.test' },
    signIn: vi.fn(),
    signOut: vi.fn(),
  }),
}))

vi.mock('../shared/company/CompanyContext', () => ({
  useCompany: () => ({
    status: 'ready',
    companies: [company],
    selected: company,
    selectCompany: vi.fn(),
    hasPermission: () => true,
    hasCapability: () => true,
    errorMessage: null,
    refetch: vi.fn(),
  }),
}))

/** Every sidebar entry, with the URL it must reach and the `h1` its screen renders. */
const NAV_TARGETS = [
  { link: 'Dashboard', path: '/', heading: 'Dashboard' },
  { link: 'Origins', path: '/masters/origins', heading: 'Origins' },
  { link: 'Destinations', path: '/masters/destinations', heading: 'Destinations' },
  { link: 'Zones', path: '/masters/zones', heading: 'Zones' },
  { link: 'Frequencies', path: '/masters/frequencies', heading: 'Frequencies' },
  { link: 'Routes', path: '/masters/routes', heading: 'Routes' },
  { link: 'Carriers', path: '/fleet/carriers', heading: 'Carriers' },
  { link: 'Vehicle types', path: '/fleet/vehicle-types', heading: 'Vehicle types' },
  { link: 'Vehicles', path: '/fleet/vehicles', heading: 'Vehicles' },
  { link: 'Orders', path: '/orders', heading: 'Orders' },
  { link: 'Planning', path: '/planning', heading: 'Planning' },
  { link: 'Trips', path: '/trips', heading: 'Trips' },
  { link: 'Security', path: '/admin/security', heading: 'Security' },
] as const

function json(body: unknown): Response {
  return new Response(JSON.stringify(body), { status: 200, headers: { 'content-type': 'application/json' } })
}

/** Enough of a backend for every screen to reach its rendered state; the assertions are about
 * routing, so each endpoint answers with a well-formed empty result. */
function stubBackend(input: RequestInfo | URL): Response {
  const url = String(input instanceof Request ? input.url : input)

  if (url.includes('/system/info')) {
    return json({
      application: 'tms-api',
      version: 'test',
      status: 'UP',
      profiles: ['local'],
      timestamp: '2026-08-19T00:00:00Z',
    })
  }

  return json({ content: [], page: 0, size: 25, totalElements: 0 })
}

function renderApp(initialPath = '/') {
  const queryClient = new QueryClient({
    defaultOptions: { queries: { retry: false, staleTime: Infinity } },
  })
  const router = createMemoryRouter(appRoutes, { initialEntries: [initialPath] })

  render(
    <QueryClientProvider client={queryClient}>
      <RouterProvider router={router} />
    </QueryClientProvider>,
  )

  return router
}

function sidebarPanel(): HTMLElement {
  const panel = document.getElementById('tms-sidebar')
  if (!panel) {
    throw new Error('Sidebar panel is not in the document')
  }
  return panel
}

beforeEach(() => {
  vi.spyOn(globalThis, 'fetch').mockImplementation(async (input) => stubBackend(input))
})

afterEach(() => {
  vi.restoreAllMocks()
})

describe('sidebar navigation', () => {
  it.each(NAV_TARGETS)('navigates to $path and renders its screen when "$link" is clicked', async (target) => {
    const user = userEvent.setup()
    // Start somewhere else so that clicking Dashboard is a real navigation too.
    const router = renderApp(target.path === '/' ? '/orders' : '/')

    await user.click(await screen.findByRole('link', { name: target.link }))

    await waitFor(() => expect(router.state.location.pathname).toBe(target.path))
    expect(await screen.findByRole('heading', { level: 1, name: target.heading })).toBeInTheDocument()
  })

  it('marks the current entry with aria-current so the active state is exposed, not just styled', async () => {
    const user = userEvent.setup()
    renderApp('/')

    await user.click(await screen.findByRole('link', { name: 'Vehicles' }))

    const active = await screen.findByRole('link', { name: 'Vehicles' })
    expect(active).toHaveAttribute('aria-current', 'page')
  })

  it('keeps browser Back and Forward working after navigating', async () => {
    const user = userEvent.setup()
    const router = renderApp('/')

    await user.click(await screen.findByRole('link', { name: 'Zones' }))
    await waitFor(() => expect(router.state.location.pathname).toBe('/masters/zones'))

    await router.navigate(-1)
    await waitFor(() => expect(router.state.location.pathname).toBe('/'))
    expect(await screen.findByRole('heading', { level: 1, name: 'Dashboard' })).toBeInTheDocument()

    await router.navigate(1)
    await waitFor(() => expect(router.state.location.pathname).toBe('/masters/zones'))
    expect(await screen.findByRole('heading', { level: 1, name: 'Zones' })).toBeInTheDocument()
  })

  it('renders the right screen when a URL is opened directly, not only when reached by a click', async () => {
    const router = renderApp('/fleet/vehicle-types')

    expect(router.state.location.pathname).toBe('/fleet/vehicle-types')
    expect(await screen.findByRole('heading', { level: 1, name: 'Vehicle types' })).toBeInTheDocument()
  })
})

describe('mobile offcanvas navigation', () => {
  it('opens the drawer from the top bar toggle', async () => {
    const user = userEvent.setup()
    renderApp('/')

    const toggle = screen.getByRole('button', { name: 'Toggle navigation' })
    expect(toggle).toHaveAttribute('aria-expanded', 'false')

    await user.click(toggle)

    expect(sidebarPanel()).toHaveClass('show')
    expect(toggle).toHaveAttribute('aria-expanded', 'true')
  })

  it('navigates and closes the drawer with a single click on a link', async () => {
    const user = userEvent.setup()
    const router = renderApp('/')

    await user.click(screen.getByRole('button', { name: 'Toggle navigation' }))
    expect(sidebarPanel()).toHaveClass('show')

    await user.click(await screen.findByRole('link', { name: 'Destinations' }))

    await waitFor(() => expect(router.state.location.pathname).toBe('/masters/destinations'))
    expect(await screen.findByRole('heading', { level: 1, name: 'Destinations' })).toBeInTheDocument()
    await waitFor(() => expect(sidebarPanel()).not.toHaveClass('show'))
  })

  it('closes the drawer with Escape without navigating away', async () => {
    const user = userEvent.setup()
    const router = renderApp('/orders')

    await user.click(screen.getByRole('button', { name: 'Toggle navigation' }))
    expect(sidebarPanel()).toHaveClass('show')

    await user.keyboard('{Escape}')

    await waitFor(() => expect(sidebarPanel()).not.toHaveClass('show'))
    expect(router.state.location.pathname).toBe('/orders')
  })

  it('closes the drawer when the backdrop is clicked', async () => {
    const user = userEvent.setup()
    renderApp('/')

    await user.click(screen.getByRole('button', { name: 'Toggle navigation' }))
    await user.click(screen.getByRole('button', { name: 'Close navigation' }))

    await waitFor(() => expect(sidebarPanel()).not.toHaveClass('show'))
  })

  it('navigates from the keyboard: Enter on a focused link', async () => {
    const user = userEvent.setup()
    const router = renderApp('/')

    const link = await screen.findByRole('link', { name: 'Carriers' })
    link.focus()
    await user.keyboard('{Enter}')

    await waitFor(() => expect(router.state.location.pathname).toBe('/fleet/carriers'))
    expect(await screen.findByRole('heading', { level: 1, name: 'Carriers' })).toBeInTheDocument()
  })
})
