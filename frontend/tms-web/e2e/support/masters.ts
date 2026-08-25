import type { Page, Route } from '@playwright/test'

/**
 * A small stateful Locations backend for the end-to-end suite.
 *
 * The drawer's contract is a sequence - open, edit, save, close, see the list change - so a stub
 * that always returns the same page would let a broken cache invalidation pass. This one keeps
 * the collection in memory: what the list shows after a save is what the save actually wrote.
 *
 * It also honours the `role` query parameter, which is not a detail here - it is the whole
 * Origins/Destinations model. Those two screens are this endpoint with `role=ORIGIN` or
 * `role=DESTINATION`, so a stub that ignored the parameter would make the one behaviour worth
 * proving unprovable: a store that ships its own returns must appear in both lists, as one row.
 *
 * It is a test double, not a model of the domain: it implements exactly the endpoints
 * `locationsApi` calls and is deliberately generous about what it accepts.
 */

type StubRole = 'ORIGIN' | 'DESTINATION'

interface StubLocation {
  id: string
  code: string
  name: string
  type: string
  roles: StubRole[]
  address: string | null
  addressReference: string | null
  district: string | null
  province: string | null
  department: string | null
  country: string
  timeZone: string
  latitude: number | null
  longitude: number | null
  zoneId: string | null
  zoneCode: string | null
  zoneName: string | null
  serviceTimeMinutes: number
  externalSystem: string | null
  externalReference: string | null
  active: boolean
  createdAt: string
  updatedAt: string
}

const TIMESTAMP = '2026-01-01T00:00:00Z'

function location(overrides: Partial<StubLocation> & Pick<StubLocation, 'id' | 'code' | 'name'>): StubLocation {
  return {
    type: 'OTHER',
    roles: ['DESTINATION'],
    address: null,
    addressReference: null,
    district: null,
    province: null,
    department: null,
    country: 'PE',
    timeZone: 'America/Lima',
    latitude: null,
    longitude: null,
    zoneId: null,
    zoneCode: null,
    zoneName: null,
    serviceTimeMinutes: 0,
    externalSystem: null,
    externalReference: null,
    active: true,
    createdAt: TIMESTAMP,
    updatedAt: TIMESTAMP,
    ...overrides,
  }
}

function seed(): StubLocation[] {
  return [
    // Ships only.
    location({
      id: 'location-1',
      code: 'LIM-CD1',
      name: 'Centro de distribución Lima',
      type: 'DISTRIBUTION_CENTER',
      roles: ['ORIGIN'],
      address: 'Av. Argentina 1234',
      latitude: -12.046374,
      longitude: -77.042793,
    }),
    location({
      id: 'location-2',
      code: 'AQP-PL1',
      name: 'Planta Arequipa',
      type: 'PLANT',
      roles: ['ORIGIN'],
      address: 'Parque Industrial 500',
      latitude: -16.409047,
      longitude: -71.537451,
      externalSystem: 'EWM',
      externalReference: 'EWM-AQP',
    }),
    // Receives only.
    location({
      id: 'location-3',
      code: 'MIRAFLORES',
      name: 'Tienda Miraflores',
      type: 'STORE',
      roles: ['DESTINATION'],
      address: 'Av. Larco 400',
      district: 'Miraflores',
      province: 'Lima',
      department: 'Lima',
      serviceTimeMinutes: 20,
    }),
  ]
}

function json(route: Route, body: unknown, status = 200) {
  return route.fulfill({
    status,
    contentType: 'application/json',
    headers: { 'access-control-allow-origin': '*' },
    body: JSON.stringify(body),
  })
}

/**
 * Installs the Locations double. Register it AFTER `stubServices`: Playwright matches the most
 * recently registered route first, and `stubServices` claims all of `/api/v1/**`.
 */
export async function stubLocations(page: Page) {
  const locations = seed()
  let nextId = locations.length + 1

  await page.route('**/api/v1/masterdata/locations**', async (route) => {
    const request = route.request()
    const url = new URL(request.url())
    const path = url.pathname
    const method = request.method()

    if (method === 'GET' && !path.includes('/import')) {
      const role = url.searchParams.get('role') as StubRole | null
      const search = (url.searchParams.get('search') ?? '').trim().toLowerCase()
      const visible = locations.filter(
        (candidate) =>
          candidate.active
          && (role === null || candidate.roles.includes(role))
          && (search === ''
            || candidate.code.toLowerCase().includes(search)
            || candidate.name.toLowerCase().includes(search)),
      )
      return json(route, { content: visible, page: 0, size: 25, totalElements: visible.length })
    }

    if (method === 'POST' && (path.endsWith('/activate') || path.endsWith('/deactivate'))) {
      const id = path.split('/').slice(-2)[0]
      const existing = locations.find((candidate) => candidate.id === id)
      if (existing) {
        existing.active = path.endsWith('/activate')
      }
      return json(route, existing ?? {})
    }

    if (method === 'POST') {
      const body = request.postDataJSON() as Partial<StubLocation>
      const created = location({
        ...body,
        id: `location-${(nextId += 1)}`,
        code: body.code ?? '',
        name: body.name ?? '',
        roles: body.roles ?? ['DESTINATION'],
      })
      locations.push(created)
      return json(route, created, 201)
    }

    if (method === 'PUT') {
      const id = path.split('/').pop()
      const body = request.postDataJSON() as Partial<StubLocation>
      const existing = locations.find((candidate) => candidate.id === id)
      if (!existing) {
        return json(route, { title: 'Not found', status: 404, code: 'not-found' }, 404)
      }
      Object.assign(existing, body, { updatedAt: TIMESTAMP })
      return json(route, existing)
    }

    return json(route, {}, 204)
  })

  return { locations }
}
