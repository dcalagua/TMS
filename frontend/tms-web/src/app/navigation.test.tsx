import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { createMemoryRouter, RouterProvider } from 'react-router-dom'
import { ThemeProvider } from '../shared/theme/ThemeProvider'
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
  // The dashboard greets the signed-in user rather than repeating the menu label.
  { link: 'Inicio', path: '/', heading: /^Hola,/ },
  { link: 'Torre de control', path: '/control-tower', heading: 'Torre de control' },
  { link: 'Ubicaciones', path: '/masters/locations', heading: 'Ubicaciones' },
  { link: 'Orígenes', path: '/masters/origins', heading: 'Orígenes' },
  { link: 'Destinos', path: '/masters/destinations', heading: 'Destinos' },
  { link: 'Zonas', path: '/masters/zones', heading: 'Zonas' },
  { link: 'Frecuencias', path: '/masters/frequencies', heading: 'Frecuencias' },
  { link: 'Rutas', path: '/masters/routes', heading: 'Rutas' },
  { link: 'Transportistas', path: '/fleet/carriers', heading: 'Transportistas' },
  { link: 'Tipos de vehículo', path: '/fleet/vehicle-types', heading: 'Tipos de vehículo' },
  { link: 'Vehículos', path: '/fleet/vehicles', heading: 'Vehículos' },
  { link: 'Pedidos', path: '/orders', heading: 'Pedidos' },
  { link: 'Planificación', path: '/planning', heading: 'Planificación' },
  { link: 'Viajes', path: '/trips', heading: 'Viajes' },
  // Configuración replaced the single "Seguridad" placeholder in job 12.
  { link: 'Compañía', path: '/settings/company', heading: 'Compañía' },
  { link: 'Usuarios y accesos', path: '/settings/users', heading: 'Usuarios y accesos' },
]

function json(body: unknown): Response {
  return new Response(JSON.stringify(body), { status: 200, headers: { 'content-type': 'application/json' } })
}

/** Enough of a backend for every screen to reach its rendered state; the assertions are about
 * routing, so each endpoint answers with a well-formed empty result. */
function stubBackend(input: RequestInfo | URL): Response {
  const url = String(input instanceof Request ? input.url : input)

  // The control tower's overview is not a page envelope, so the blanket empty page below would
  // not describe it. It gets its own well-formed empty day; `/control-tower/trips` falls through
  // to the page stub, which is the right shape for it.
  if (url.includes('/control-tower') && !url.includes('/control-tower/trips')) {
    return json({
      date: '2026-08-19',
      generatedAt: '2026-08-19T12:00:00Z',
      summary: {
        tripsDraft: 0,
        tripsScheduled: 0,
        tripsInTransit: 0,
        tripsCompleted: 0,
        tripsCancelled: 0,
        tripsDepartedLate: 0,
        tripsOverdue: 0,
        openExceptions: 0,
        outstandingStops: 0,
        stopsPastWindow: 0,
        ordersUnplanned: 0,
      },
      workload: [],
      openExceptions: [],
      outstandingStops: [],
    })
  }

  // The company settings screen reads a profile, not a page of rows, and it renders its form from
  // that object - so the blanket empty page below would leave it with no `settings` to read.
  if (url.includes('/admin/companies/current')) {
    return json({
      id: 'company-1',
      code: 'C1',
      name: 'EBIM Logistics',
      taxIdentifier: null,
      timeZone: 'America/Lima',
      active: true,
      organization: { id: 'org-1', code: 'EBIM', name: 'EBIM' },
      organizationActive: true,
      canCreateCompany: false,
      settings: { defaultCountry: 'PE', orderNumberPrefix: 'TO-', shipmentNumberPrefix: 'SH-' },
    })
  }

  // The role catalogue is a bare array; the page stub's envelope would break `roles.map`.
  if (url.includes('/admin/users/roles')) {
    return json([])
  }

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
    <ThemeProvider>
      <QueryClientProvider client={queryClient}>
        <RouterProvider router={router} />
      </QueryClientProvider>
    </ThemeProvider>,
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

/** Scoped to the sidebar: the dashboard also renders quick-access links with the same names,
 * and this suite is about the menu. */
function navLink(name: string): HTMLElement {
  return within(sidebarPanel()).getByRole('link', { name })
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

    await user.click(navLink(target.link))

    await waitFor(() => expect(router.state.location.pathname).toBe(target.path))
    expect(await screen.findByRole('heading', { level: 1, name: target.heading })).toBeInTheDocument()
  })

  it('marks the current entry with aria-current so the active state is exposed, not just styled', async () => {
    const user = userEvent.setup()
    renderApp('/')

    await user.click(navLink('Vehículos'))

    const active = navLink('Vehículos')
    expect(active).toHaveAttribute('aria-current', 'page')
  })

  it('keeps browser Back and Forward working after navigating', async () => {
    const user = userEvent.setup()
    const router = renderApp('/')

    await user.click(navLink('Zonas'))
    await waitFor(() => expect(router.state.location.pathname).toBe('/masters/zones'))

    await router.navigate(-1)
    await waitFor(() => expect(router.state.location.pathname).toBe('/'))
    expect(await screen.findByRole('heading', { level: 1, name: /^Hola,/ })).toBeInTheDocument()

    await router.navigate(1)
    await waitFor(() => expect(router.state.location.pathname).toBe('/masters/zones'))
    expect(await screen.findByRole('heading', { level: 1, name: 'Zonas' })).toBeInTheDocument()
  })

  it('renders the right screen when a URL is opened directly, not only when reached by a click', async () => {
    const router = renderApp('/fleet/vehicle-types')

    expect(router.state.location.pathname).toBe('/fleet/vehicle-types')
    expect(await screen.findByRole('heading', { level: 1, name: 'Tipos de vehículo' })).toBeInTheDocument()
  })
})

describe('mobile offcanvas navigation', () => {
  it('opens the drawer from the top bar toggle', async () => {
    const user = userEvent.setup()
    renderApp('/')

    const toggle = screen.getByRole('button', { name: 'Abrir o cerrar la navegación' })
    expect(toggle).toHaveAttribute('aria-expanded', 'false')

    await user.click(toggle)

    expect(sidebarPanel()).toHaveClass('show')
    expect(toggle).toHaveAttribute('aria-expanded', 'true')
  })

  it('navigates and closes the drawer with a single click on a link', async () => {
    const user = userEvent.setup()
    const router = renderApp('/')

    await user.click(screen.getByRole('button', { name: 'Abrir o cerrar la navegación' }))
    expect(sidebarPanel()).toHaveClass('show')

    await user.click(navLink('Destinos'))

    await waitFor(() => expect(router.state.location.pathname).toBe('/masters/destinations'))
    expect(await screen.findByRole('heading', { level: 1, name: 'Destinos' })).toBeInTheDocument()
    await waitFor(() => expect(sidebarPanel()).not.toHaveClass('show'))
  })

  it('closes the drawer with Escape without navigating away', async () => {
    const user = userEvent.setup()
    const router = renderApp('/orders')

    await user.click(screen.getByRole('button', { name: 'Abrir o cerrar la navegación' }))
    expect(sidebarPanel()).toHaveClass('show')

    await user.keyboard('{Escape}')

    await waitFor(() => expect(sidebarPanel()).not.toHaveClass('show'))
    expect(router.state.location.pathname).toBe('/orders')
  })

  it('closes the drawer when the backdrop is clicked', async () => {
    const user = userEvent.setup()
    renderApp('/')

    await user.click(screen.getByRole('button', { name: 'Abrir o cerrar la navegación' }))
    await user.click(screen.getByRole('button', { name: 'Cerrar navegación' }))

    await waitFor(() => expect(sidebarPanel()).not.toHaveClass('show'))
  })

  it('navigates from the keyboard: Enter on a focused link', async () => {
    const user = userEvent.setup()
    const router = renderApp('/')

    const link = navLink('Transportistas')
    link.focus()
    await user.keyboard('{Enter}')

    await waitFor(() => expect(router.state.location.pathname).toBe('/fleet/carriers'))
    expect(await screen.findByRole('heading', { level: 1, name: 'Transportistas' })).toBeInTheDocument()
  })
})
