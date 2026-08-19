import type { Page, Route } from '@playwright/test'

/**
 * A small stateful origins backend for the end-to-end suite.
 *
 * The drawer's contract is a sequence - open, edit, save, close, see the list change - so a stub
 * that always returns the same page would let a broken cache invalidation pass. This one keeps
 * the collection in memory: what the list shows after a save is what the save actually wrote.
 *
 * It is a test double, not a model of the domain: it implements exactly the endpoints
 * `originsApi` calls and is deliberately generous about what it accepts.
 */

interface StubOrigin {
  id: string
  code: string
  name: string
  type: string
  address: string | null
  latitude: number | null
  longitude: number | null
  timeZone: string
  externalReference: string | null
  active: boolean
  createdAt: string
  updatedAt: string
}

const TIMESTAMP = '2026-01-01T00:00:00Z'

function seed(): StubOrigin[] {
  return [
    {
      id: 'origin-1',
      code: 'LIM-CD1',
      name: 'Centro de distribución Lima',
      type: 'DISTRIBUTION_CENTER',
      address: 'Av. Argentina 1234',
      latitude: -12.046374,
      longitude: -77.042793,
      timeZone: 'America/Lima',
      externalReference: null,
      active: true,
      createdAt: TIMESTAMP,
      updatedAt: TIMESTAMP,
    },
    {
      id: 'origin-2',
      code: 'AQP-PL1',
      name: 'Planta Arequipa',
      type: 'PLANT',
      address: 'Parque Industrial 500',
      latitude: -16.409047,
      longitude: -71.537451,
      timeZone: 'America/Lima',
      externalReference: 'EWM-AQP',
      active: true,
      createdAt: TIMESTAMP,
      updatedAt: TIMESTAMP,
    },
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
 * Installs the origins double. Register it AFTER `stubServices`: Playwright matches the most
 * recently registered route first, and `stubServices` claims all of `/api/v1/**`.
 */
export async function stubOrigins(page: Page) {
  const origins = seed()
  let nextId = origins.length + 1

  await page.route('**/api/v1/masterdata/origins**', async (route) => {
    const request = route.request()
    const path = new URL(request.url()).pathname
    const method = request.method()

    if (method === 'GET') {
      return json(route, {
        content: origins.filter((origin) => origin.active),
        page: 0,
        size: 25,
        totalElements: origins.filter((origin) => origin.active).length,
      })
    }

    if (method === 'POST' && (path.endsWith('/activate') || path.endsWith('/deactivate'))) {
      const id = path.split('/').slice(-2)[0]
      const existing = origins.find((origin) => origin.id === id)
      if (existing) {
        existing.active = path.endsWith('/activate')
      }
      return json(route, existing ?? {})
    }

    if (method === 'POST') {
      const body = request.postDataJSON() as Partial<StubOrigin>
      const created: StubOrigin = {
        id: `origin-${(nextId += 1)}`,
        code: body.code ?? '',
        name: body.name ?? '',
        type: body.type ?? 'OTHER',
        address: body.address ?? null,
        latitude: body.latitude ?? null,
        longitude: body.longitude ?? null,
        timeZone: body.timeZone ?? 'America/Lima',
        externalReference: body.externalReference ?? null,
        active: true,
        createdAt: TIMESTAMP,
        updatedAt: TIMESTAMP,
      }
      origins.push(created)
      return json(route, created, 201)
    }

    if (method === 'PUT') {
      const id = path.split('/').pop()
      const body = request.postDataJSON() as Partial<StubOrigin>
      const existing = origins.find((origin) => origin.id === id)
      if (!existing) {
        return json(route, { title: 'Not found', status: 404, code: 'not-found' }, 404)
      }
      Object.assign(existing, body, { updatedAt: TIMESTAMP })
      return json(route, existing)
    }

    return json(route, {}, 204)
  })

  return { origins }
}
