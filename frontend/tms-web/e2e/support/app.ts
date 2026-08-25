import type { Page, Route } from '@playwright/test'

/**
 * Test doubles for the two services the browser talks to.
 *
 * Supabase Auth and the TMS API are both intercepted, so the suite needs no project
 * credentials, no running backend and no seeded database, and a test can script the exact
 * response sequence it is about. Everything else - React, the router, i18n, the session
 * handling in `AuthContext` and `httpClient` - runs for real.
 */

export const TEST_USER = {
  id: '00000000-0000-0000-0000-0000000000a1',
  email: 'planner@ebim.test',
  password: 'not-a-real-password',
}

export const TEST_COMPANY = {
  id: '00000000-0000-0000-0000-0000000000c1',
  code: 'LIM',
  name: 'EBIM Logistics Peru',
  timeZone: 'America/Lima',
  organization: { id: '00000000-0000-0000-0000-0000000000e1', code: 'EBIM', name: 'EBIM Group' },
  // Exactly the strings the screens check with `hasPermission`; an invented one silently
  // hides the very controls a test is trying to click.
  permissions: [
    'masterdata.origin:manage',
    'masterdata.destination:manage',
    'masterdata.location:manage',
    'masterdata.zone:manage',
    'masterdata.frequency:manage',
    'masterdata.route:manage',
    'fleet.carrier:manage',
    'fleet.vehicle_type:manage',
    'fleet.vehicle:manage',
    'fleet.driver:manage',
    'orders.order:manage',
    'planning.plan:manage',
    'planning.trip:manage',
    'monitoring.transport:read',
    // The Configuración group. This fixture is a company administrator, because the navigation
    // suite asserts that *every* menu entry reaches its screen - a fixture missing one of these
    // hides the entry, and a hidden entry looks exactly like a passing test.
    'iam.company:read',
    'iam.company:manage',
    'iam.user:read',
    'iam.membership:manage',
    'integration.client:read',
    'integration.webhook:read',
    'audit.log:read',
  ],
  capabilities: [
    'MASTER_DATA_VIEW',
    'FLEET_VIEW',
    'ORDERS_VIEW',
    'PLANNING_VIEW',
    'TRIPS_VIEW',
    'TRANSPORT_MONITOR_VIEW',
    'IAM_VIEW',
    'INTEGRATION_VIEW',
    'AUDIT_VIEW',
  ],
}

function session(accessToken: string) {
  return {
    access_token: accessToken,
    token_type: 'bearer',
    expires_in: 3600,
    expires_at: Math.floor(Date.now() / 1000) + 3600,
    refresh_token: `refresh-${accessToken}`,
    user: {
      id: TEST_USER.id,
      aud: 'authenticated',
      role: 'authenticated',
      email: TEST_USER.email,
      app_metadata: { provider: 'email' },
      user_metadata: {},
      created_at: '2026-01-01T00:00:00Z',
    },
  }
}

function json(route: Route, body: unknown, status = 200) {
  return route.fulfill({
    status,
    contentType: 'application/json',
    headers: { 'access-control-allow-origin': '*' },
    body: JSON.stringify(body),
  })
}

function emptyPage() {
  return { content: [], page: 0, size: 25, totalElements: 0 }
}

export interface StubOptions {
  /**
   * How many of the first `/api/v1/**` calls answer 401 before behaving normally. `1`
   * reproduces the defect that used to sign a user out immediately after a valid sign-in.
   */
  initialUnauthorizedCalls?: number
  /** Overrides for specific endpoints, matched by path suffix. */
  responses?: Record<string, unknown>
}

/**
 * Installs the Supabase Auth and TMS API doubles on a page. Call before `page.goto`.
 *
 * @returns counters the test can assert on
 */
export async function stubServices(page: Page, options: StubOptions = {}) {
  const counters = { signInCalls: 0, refreshCalls: 0, apiCalls: 0, unauthorizedServed: 0 }
  let issuedToken = 'access-token-1'

  await page.route('**/auth/v1/token**', async (route) => {
    const url = route.request().url()

    if (url.includes('grant_type=refresh_token')) {
      counters.refreshCalls += 1
      issuedToken = `access-token-refreshed-${counters.refreshCalls}`
      return json(route, session(issuedToken))
    }

    counters.signInCalls += 1
    const body = route.request().postDataJSON() as { email?: string; password?: string } | null

    if (body?.password !== TEST_USER.password) {
      return json(route, { error: 'invalid_grant', error_description: 'Invalid login credentials' }, 400)
    }

    issuedToken = `access-token-${counters.signInCalls}`
    return json(route, session(issuedToken))
  })

  await page.route('**/auth/v1/logout**', (route) => route.fulfill({ status: 204, body: '' }))
  await page.route('**/auth/v1/user**', (route) => json(route, session(issuedToken).user))

  await page.route('**/api/v1/**', async (route) => {
    counters.apiCalls += 1
    const path = new URL(route.request().url()).pathname

    if (counters.unauthorizedServed < (options.initialUnauthorizedCalls ?? 0)) {
      counters.unauthorizedServed += 1
      return json(
        route,
        {
          type: 'urn:tms:problem:unauthenticated',
          title: 'Authentication is required',
          status: 401,
          detail: 'Full authentication is required to access this resource.',
          code: 'unauthenticated',
        },
        401,
      )
    }

    for (const [suffix, body] of Object.entries(options.responses ?? {})) {
      if (path.endsWith(suffix)) {
        return json(route, body)
      }
    }

    if (path.endsWith('/me')) {
      return json(route, {
        user: { id: TEST_USER.id, email: TEST_USER.email, fullName: 'Planner de Prueba' },
        companies: [TEST_COMPANY],
      })
    }

    // The control tower's overview is not a page envelope, so the catch-all at the bottom would
    // not describe it. A well-formed empty day, which is what the navigation suite needs; a test
    // about the tower's own numbers passes its own body through `options.responses`.
    if (path.endsWith('/control-tower')) {
      return json(route, {
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

    // The company profile is an object, not a page envelope, and the settings screen renders its
    // form straight from `settings` - so the catch-all below would leave it reading
    // `orderNumberPrefix` off undefined and the whole route would land in an error boundary.
    if (path.endsWith('/admin/companies/current')) {
      return json(route, {
        ...TEST_COMPANY,
        taxIdentifier: null,
        active: true,
        organizationActive: true,
        canCreateCompany: false,
        settings: { defaultCountry: 'PE', orderNumberPrefix: 'TO-', shipmentNumberPrefix: 'SH-' },
      })
    }

    // The role catalogue is a bare array; the empty page envelope below would break `roles.map`.
    if (path.endsWith('/admin/users/roles')) {
      return json(route, [])
    }

    if (path.endsWith('/system/info')) {
      return json(route, {
        application: 'tms-api',
        version: 'e2e',
        status: 'UP',
        profiles: ['local'],
        timestamp: '2026-08-19T00:00:00Z',
      })
    }

    return json(route, emptyPage())
  })

  return counters
}

/** Signs in through the real form, exactly as an operator would. */
export async function signIn(page: Page, password: string = TEST_USER.password) {
  await page.goto('/login')
  await page.getByLabel(/^Correo/).fill(TEST_USER.email)
  await page.getByLabel(/^Contrase/).fill(password)
  await page.getByRole('button', { name: 'Ingresar' }).click()
}

/**
 * Switches the interface language.
 *
 * The ES|EN pair lives inside the account menu rather than loose in the top bar, so reaching
 * it means opening that menu first. Tests go through this helper instead of clicking the
 * segmented option directly: the control moved once already, and every spec that had spelled
 * the interaction out by hand had to be edited.
 *
 * The menu must be reachable, so close any open dialog before calling this - a modal correctly
 * covers the top bar.
 */
export async function switchLanguage(page: Page, language: 'ES' | 'EN') {
  await page.locator('.tms-topbar-user').click()
  await page.getByRole('button', { name: language, exact: true }).click()
  // Close the menu again so it cannot swallow the next click.
  await page.keyboard.press('Escape')
}

/**
 * The page must never scroll sideways. Wide content scrolls inside its own container; if the
 * document itself is wider than the viewport, a layout is broken.
 */
export async function horizontalOverflow(page: Page): Promise<number> {
  return page.evaluate(() => document.documentElement.scrollWidth - window.innerWidth)
}
