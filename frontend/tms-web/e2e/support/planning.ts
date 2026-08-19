import type { Page, Route } from '@playwright/test'

/**
 * A small stateful planning backend for the end-to-end suite.
 *
 * The board is the one screen where the interesting behaviour is the *sequence* - create a
 * trip, assign an order, watch capacity move, take the order back off - so a stub that returns
 * fixed payloads would prove nothing. This one keeps the run in memory and recomputes capacity
 * the way the backend does, so the UI is driven by state that actually changes.
 *
 * It is a test double, not a model of the domain: it implements exactly the endpoints the board
 * calls, and it is deliberately generous about what it accepts.
 */

const VEHICLE = { id: 'veh-1', code: 'VH-001', licensePlate: 'ABC-123', carrierName: 'Transportes EBIM' }

const WEIGHT_LIMIT = 10_000
const VOLUME_LIMIT = 40
const PALLET_LIMIT = 20

interface StubOrder {
  id: string
  orderNumber: string
  destinationId: string
  destinationCode: string
  destinationName: string
  customerName: string
  priority: string
  totalWeightKg: number
  totalVolumeM3: number
  totalPallets: number
}

const ORDERS: StubOrder[] = [
  {
    id: 'ord-1',
    orderNumber: 'TO-00000001',
    destinationId: 'dest-1',
    destinationCode: 'D-001',
    destinationName: 'Tienda Norte',
    customerName: 'Cliente Norte',
    priority: 'HIGH',
    totalWeightKg: 8850,
    totalVolumeM3: 18,
    totalPallets: 12,
  },
  {
    id: 'ord-2',
    orderNumber: 'TO-00000002',
    destinationId: 'dest-2',
    destinationCode: 'D-002',
    destinationName: 'Tienda Sur',
    customerName: 'Cliente Sur',
    priority: 'NORMAL',
    totalWeightKg: 1200,
    totalVolumeM3: 4,
    totalPallets: 3,
  },
]

interface StubTrip {
  id: string
  tripNumber: number
  vehicleId: string | null
  assignedOrderIds: string[]
  version: number
}

interface StubState {
  runVersion: number
  runStatus: 'DRAFT' | 'CONFIRMED' | 'CANCELLED'
  trips: StubTrip[]
  nextTripNumber: number
}

function dimension(used: number, limit: number | null) {
  if (limit === null) {
    return { used, limit: null, remaining: null, percentUsed: null, exceeded: false, unlimited: true }
  }
  const percentUsed = limit === 0 ? null : Math.round((used / limit) * 1000) / 10
  return {
    used,
    limit,
    remaining: limit - used,
    percentUsed,
    exceeded: used > limit,
    unlimited: false,
  }
}

function tripView(trip: StubTrip) {
  const assigned = ORDERS.filter((order) => trip.assignedOrderIds.includes(order.id))
  const weight = assigned.reduce((total, order) => total + order.totalWeightKg, 0)
  const volume = assigned.reduce((total, order) => total + order.totalVolumeM3, 0)
  const pallets = assigned.reduce((total, order) => total + order.totalPallets, 0)
  const hasVehicle = trip.vehicleId !== null

  const capacity = {
    tripId: trip.id,
    source: hasVehicle ? 'LIVE' : 'NONE',
    orderCount: assigned.length,
    weight: dimension(weight, hasVehicle ? WEIGHT_LIMIT : null),
    volume: dimension(volume, hasVehicle ? VOLUME_LIMIT : null),
    pallets: dimension(pallets, hasVehicle ? PALLET_LIMIT : null),
    withinCapacity: !hasVehicle || (weight <= WEIGHT_LIMIT && volume <= VOLUME_LIMIT && pallets <= PALLET_LIMIT),
  }

  return {
    id: trip.id,
    planningRunId: 'run-1',
    tripNumber: trip.tripNumber,
    status: 'DRAFT',
    vehicleId: trip.vehicleId,
    vehicleCode: hasVehicle ? VEHICLE.code : null,
    vehicleLicensePlate: hasVehicle ? VEHICLE.licensePlate : null,
    carrierId: hasVehicle ? 'car-1' : null,
    carrierName: hasVehicle ? VEHICLE.carrierName : null,
    plannedDepartureAt: null,
    capacity,
    stopCount: new Set(assigned.map((order) => order.destinationId)).size,
    orderCount: assigned.length,
    version: trip.version,
    createdAt: '2026-03-01T00:00:00Z',
    updatedAt: '2026-03-01T00:00:00Z',
  }
}

function runView(state: StubState) {
  return {
    id: 'run-1',
    planNumber: 'PLN-000001',
    originId: 'org-1',
    originCode: 'LIMA',
    originName: 'CD Lima',
    planningDate: '2026-03-01',
    mode: 'MANUAL',
    status: state.runStatus,
    notes: null,
    tripCount: state.trips.length,
    assignedOrderCount: state.trips.reduce((total, trip) => total + trip.assignedOrderIds.length, 0),
    confirmedAt: null,
    cancelledAt: null,
    cancelReason: null,
    version: state.runVersion,
    createdAt: '2026-03-01T00:00:00Z',
    updatedAt: '2026-03-01T00:00:00Z',
  }
}

function tripDetail(state: StubState, trip: StubTrip) {
  const assigned = ORDERS.filter((order) => trip.assignedOrderIds.includes(order.id))
  return {
    trip: tripView(trip),
    assignments: assigned.map((order, index) => ({
      assignmentId: `asg-${order.id}`,
      orderId: order.id,
      orderNumber: order.orderNumber,
      destinationId: order.destinationId,
      destinationCode: order.destinationCode,
      destinationName: order.destinationName,
      customerName: order.customerName,
      serviceDate: '2026-03-01',
      priority: order.priority,
      requestedWindowStart: null,
      requestedWindowEnd: null,
      assignedWeightKg: order.totalWeightKg,
      assignedVolumeM3: order.totalVolumeM3,
      assignedPallets: order.totalPallets,
      wholeOrder: true,
      assignedAt: `2026-03-01T0${index}:00:00Z`,
    })),
    stops: [...new Set(assigned.map((order) => order.destinationId))].map((destinationId, index) => {
      const order = assigned.find((candidate) => candidate.destinationId === destinationId)
      return {
        id: `stop-${destinationId}`,
        sequence: index + 1,
        destinationId,
        destinationCode: order?.destinationCode ?? null,
        destinationName: order?.destinationName ?? null,
        serviceWindowStart: null,
        serviceWindowEnd: null,
        orderCount: assigned.filter((candidate) => candidate.destinationId === destinationId).length,
      }
    }),
  }
}

function json(route: Route, body: unknown, status = 200) {
  return route.fulfill({ status, contentType: 'application/json', body: JSON.stringify(body) })
}

function page<T>(content: T[]) {
  return { content, page: 0, size: 25, totalElements: content.length }
}

/**
 * Installs the planning endpoints. Playwright matches routes in reverse registration order, so
 * this must be registered *after* the catch-all `/api/v1/**` in `stubServices` to take
 * precedence over it.
 */
export async function stubPlanning(target: Page) {
  const state: StubState = {
    runVersion: 1,
    runStatus: 'DRAFT',
    trips: [],
    nextTripNumber: 1,
  }

  const assignedOrderIds = () => new Set(state.trips.flatMap((trip) => trip.assignedOrderIds))

  await target.route('**/api/v1/planning/**', async (route) => {
    const request = route.request()
    const url = new URL(request.url())
    const path = url.pathname
    const method = request.method()

    if (path.endsWith('/planning/eligible-orders')) {
      const taken = assignedOrderIds()
      return json(route, page(ORDERS.filter((order) => !taken.has(order.id)).map((order) => ({
        ...order,
        originId: 'org-1',
        serviceDate: '2026-03-01',
        customerReference: null,
        requestedWindowStart: null,
        requestedWindowEnd: null,
      }))))
    }

    if (path.endsWith('/planning/runs') && method === 'GET') {
      return json(route, page([runView(state)]))
    }

    if (path.endsWith('/planning/runs') && method === 'POST') {
      return json(route, { run: runView(state), trips: state.trips.map(tripView) })
    }

    // POST /planning/runs/{id}/trips
    if (path.endsWith('/trips') && method === 'POST') {
      const trip: StubTrip = {
        id: `trip-${state.nextTripNumber}`,
        tripNumber: state.nextTripNumber,
        vehicleId: (request.postDataJSON() as { vehicleId?: string | null } | null)?.vehicleId ?? null,
        assignedOrderIds: [],
        version: 0,
      }
      state.nextTripNumber += 1
      state.runVersion += 1
      state.trips.push(trip)
      return json(route, tripDetail(state, trip))
    }

    // POST /planning/trips/{id}/assignments
    const assignMatch = /\/planning\/trips\/([^/]+)\/assignments$/.exec(path)
    if (assignMatch && method === 'POST') {
      const trip = state.trips.find((candidate) => candidate.id === assignMatch[1])
      const orderId = (request.postDataJSON() as { orderId?: string } | null)?.orderId
      if (trip && orderId && !trip.assignedOrderIds.includes(orderId)) {
        trip.assignedOrderIds.push(orderId)
        trip.version += 1
      }
      return trip ? json(route, tripDetail(state, trip)) : json(route, { code: 'resource-not-found' }, 404)
    }

    // DELETE /planning/trips/{id}/assignments/{orderId}
    const removeMatch = /\/planning\/trips\/([^/]+)\/assignments\/([^/]+)$/.exec(path)
    if (removeMatch && method === 'DELETE') {
      const trip = state.trips.find((candidate) => candidate.id === removeMatch[1])
      if (trip) {
        trip.assignedOrderIds = trip.assignedOrderIds.filter((id) => id !== removeMatch[2])
        trip.version += 1
        return json(route, tripDetail(state, trip))
      }
      return json(route, { code: 'resource-not-found' }, 404)
    }


    // POST /planning/trips/{id}/assignments/{orderId}/move
    const moveMatch = /\/planning\/trips\/([^/]+)\/assignments\/([^/]+)\/move$/.exec(path)
    if (moveMatch && method === 'POST') {
      const source = state.trips.find((candidate) => candidate.id === moveMatch[1])
      const targetId = (request.postDataJSON() as { targetTripId?: string } | null)?.targetTripId
      const target = state.trips.find((candidate) => candidate.id === targetId)
      if (source && target) {
        source.assignedOrderIds = source.assignedOrderIds.filter((id) => id !== moveMatch[2])
        if (!target.assignedOrderIds.includes(moveMatch[2] as string)) {
          target.assignedOrderIds.push(moveMatch[2] as string)
        }
        source.version += 1
        target.version += 1
        return json(route, tripDetail(state, source))
      }
      return json(route, { code: 'resource-not-found' }, 404)
    }

    // PUT /planning/trips/{id}/stops
    const stopsMatch = /\/planning\/trips\/([^/]+)\/stops$/.exec(path)
    if (stopsMatch && method === 'PUT') {
      const trip = state.trips.find((candidate) => candidate.id === stopsMatch[1])
      return trip ? json(route, tripDetail(state, trip)) : json(route, { code: 'resource-not-found' }, 404)
    }

    // POST /planning/trips/{id}/cancel
    const tripCancelMatch = /\/planning\/trips\/([^/]+)\/cancel$/.exec(path)
    if (tripCancelMatch && method === 'POST') {
      const trip = state.trips.find((candidate) => candidate.id === tripCancelMatch[1])
      if (trip) {
        state.trips = state.trips.filter((candidate) => candidate.id !== trip.id)
        return json(route, tripDetail(state, { ...trip, assignedOrderIds: [] }))
      }
      return json(route, { code: 'resource-not-found' }, 404)
    }

    // PUT /planning/trips/{id}/vehicle
    if (path.endsWith('/vehicle') && (method === 'PUT' || method === 'POST' || method === 'PATCH')) {
      const tripId = path.split('/').at(-2)
      const trip = state.trips.find((candidate) => candidate.id === tripId)
      if (trip) {
        trip.vehicleId = (request.postDataJSON() as { vehicleId?: string } | null)?.vehicleId ?? VEHICLE.id
        trip.version += 1
        return json(route, tripDetail(state, trip))
      }
    }

    // GET /planning/trips/{id}
    const tripMatch = /\/planning\/trips\/([^/]+)$/.exec(path)
    if (tripMatch && method === 'GET') {
      const trip = state.trips.find((candidate) => candidate.id === tripMatch[1])
      return trip ? json(route, tripDetail(state, trip)) : json(route, { code: 'resource-not-found' }, 404)
    }

    // POST /planning/runs/{id}/confirm | /cancel
    if (path.endsWith('/confirm') || path.endsWith('/cancel')) {
      state.runStatus = path.endsWith('/confirm') ? 'CONFIRMED' : 'CANCELLED'
      state.runVersion += 1
      return json(route, { run: runView(state), trips: state.trips.map(tripView) })
    }

    // GET /planning/runs/{id}
    if (path.includes('/planning/runs/')) {
      return json(route, { run: runView(state), trips: state.trips.map(tripView) })
    }

    return json(route, page([]))
  })

  await target.route('**/api/v1/fleet/vehicles**', (route) =>
    json(route, page([{ id: VEHICLE.id, code: VEHICLE.code, licensePlate: VEHICLE.licensePlate, active: true }])),
  )

  return { state, ORDERS, VEHICLE }
}
