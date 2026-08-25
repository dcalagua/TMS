import type {
  DeliveryEvidenceView,
  OrderDeliveryView,
  StopExecutionStatus,
  TripCapacityView,
  TripDetailView,
  TripExceptionView,
  TripStatus,
  TripStopView,
  TripView,
} from '../../shared/api/planningApi'

/** A dimension the backend reported as comfortably within limits. */
function dimension(used: number, limit: number) {
  return {
    used,
    limit,
    remaining: limit - used,
    percentUsed: Math.round((used / limit) * 1000) / 10,
    exceeded: false,
    unlimited: false,
  }
}

export const CAPACITY: TripCapacityView = {
  tripId: 'trip-1',
  source: 'SNAPSHOT',
  orderCount: 2,
  weight: dimension(4000, 8000),
  volume: dimension(16, 32),
  pallets: dimension(9, 18),
  withinCapacity: true,
}

/**
 * A trip in whatever state a test needs, with `allowedTransitions` set to what the backend's own
 * transition table would return - the fixture is only trustworthy if it agrees with
 * `planning.domain.TripStatus`.
 */
const TRANSITIONS: Record<TripStatus, TripStatus[]> = {
  DRAFT: ['CONFIRMED', 'CANCELLED'],
  CONFIRMED: ['READY_FOR_DISPATCH', 'CANCELLED'],
  READY_FOR_DISPATCH: ['IN_TRANSIT', 'CANCELLED'],
  IN_TRANSIT: ['COMPLETED'],
  COMPLETED: [],
  CANCELLED: [],
}

export function tripView(status: TripStatus, overrides: Partial<TripView> = {}): TripView {
  return {
    id: 'trip-1',
    companyId: 'company-1',
    planningRunId: 'run-1',
    planNumber: 'PL-00000001',
    planningDate: '2026-08-20',
    tripNumber: 1,
    shipmentNumber: 'SH-00000042',
    status,
    originId: 'origin-1',
    originCode: 'CD-LIMA',
    originName: 'Centro Lima',
    originLatitude: null,
    originLongitude: null,
    vehicleId: 'vehicle-1',
    vehicleCode: 'VH-1',
    vehicleLicensePlate: 'ABC-123',
    vehicleTypeCode: 'TRUCK-8T',
    carrierId: 'carrier-1',
    carrierName: 'Carrier One',
    // A trip with a driver by default: naming one is optional in every state, so the interesting
    // case for most tests is the one where the fields are populated. Pass nulls through
    // `overrides` for the "no driver yet" screens.
    driverId: 'driver-1',
    driverCode: 'DR-ANA',
    driverName: 'Quispe, Ana',
    driverPhone: '+51 999 111 222',
    driverLicenseNumber: 'Q-987654',
    driverLicenseExpiresOn: '2027-05-31',
    driverLicenseStatus: 'VALID',
    routeId: null,
    routeCode: null,
    routeName: null,
    plannedDepartureAt: '2026-08-20T08:00:00Z',
    readyAt: null,
    actualDepartureAt: null,
    actualCompletionAt: null,
    cancelledAt: null,
    cancelReason: null,
    allowedTransitions: TRANSITIONS[status],
    capacity: CAPACITY,
    stopCount: 1,
    orderCount: 2,
    version: 3,
    createdAt: '2026-08-19T00:00:00Z',
    updatedAt: '2026-08-19T00:00:00Z',
    ...overrides,
  }
}

/**
 * What the backend's `StopExecutionStatus` transition table returns, mirrored here for the same
 * reason `TRANSITIONS` is: a fixture that disagreed with the server would let a test pass on a
 * screen that offers a button the API refuses.
 *
 * <p>Empty unless the trip is out - a stop cannot be worked before its vehicle leaves, which is
 * what `TripViewAssembler.toStopView` encodes.
 */
const STOP_TRANSITIONS: Record<StopExecutionStatus, StopExecutionStatus[]> = {
  PENDING: ['ARRIVED', 'SKIPPED', 'FAILED'],
  ARRIVED: ['IN_SERVICE', 'COMPLETED', 'FAILED'],
  IN_SERVICE: ['COMPLETED', 'FAILED'],
  COMPLETED: [],
  SKIPPED: [],
  FAILED: [],
}

export function tripStop(
  tripStatus: TripStatus,
  overrides: Partial<TripStopView> = {},
): TripStopView {
  const executionStatus = overrides.executionStatus ?? 'PENDING'
  return {
    id: 'stop-1',
    sequence: 1,
    destinationId: 'destination-1',
    destinationCode: 'ST-1',
    destinationName: 'Tienda Uno',
    latitude: null,
    longitude: null,
    address: 'Av. Siempre Viva 742',
    serviceWindowStart: '09:00:00',
    serviceWindowEnd: '12:00:00',
    orderCount: 1,
    executionStatus,
    allowedExecutionTransitions:
      tripStatus === 'IN_TRANSIT' ? STOP_TRANSITIONS[executionStatus] : [],
    actualArrivalAt: null,
    serviceStartedAt: null,
    actualDepartureAt: null,
    executionNotes: null,
    dwellMinutes: null,
    openExceptionCount: 0,
    ...overrides,
  }
}

export function tripException(overrides: Partial<TripExceptionView> = {}): TripExceptionView {
  return {
    id: 'exception-1',
    tripId: 'trip-1',
    tripStopId: 'stop-1',
    stopSequence: 1,
    stopDestinationCode: 'ST-1',
    stopDestinationName: 'Tienda Uno',
    exceptionType: 'CUSTOMER_CLOSED',
    status: 'OPEN',
    reportedAt: '2026-08-20T11:00:00Z',
    notes: 'Local cerrado al llegar',
    resolvedAt: null,
    resolutionNotes: null,
    ...overrides,
  }
}

export function orderDelivery(overrides: Partial<OrderDeliveryView> = {}): OrderDeliveryView {
  return {
    id: 'delivery-1',
    tripStopId: 'stop-1',
    stopSequence: 1,
    orderId: 'order-1',
    orderNumber: 'ORD-00000001',
    result: 'DELIVERED',
    deliveredAt: '2026-08-20T11:30:00Z',
    receiverName: 'R. Díaz',
    receiverDocument: null,
    notes: null,
    source: 'OPERATOR',
    recordedByName: 'dispatcher@example.com',
    recordedAt: '2026-08-20T11:35:00Z',
    evidence: [],
    ...overrides,
  }
}

export function deliveryEvidence(overrides: Partial<DeliveryEvidenceView> = {}): DeliveryEvidenceView {
  return {
    id: 'evidence-1',
    evidenceType: 'SIGNATURE',
    contentType: 'image/png',
    sizeBytes: 2048,
    checksumSha256: 'a'.repeat(64),
    originalFilename: 'firma.png',
    capturedAt: null,
    uploadedAt: '2026-08-20T11:36:00Z',
    ...overrides,
  }
}

export function tripDetail(
  status: TripStatus,
  overrides: Partial<TripView> = {},
  detailOverrides: {
    stops?: TripStopView[]
    exceptions?: TripExceptionView[]
    deliveries?: OrderDeliveryView[]
  } = {},
): TripDetailView {
  return {
    trip: tripView(status, overrides),
    assignments: [
      {
        assignmentId: 'assignment-1',
        orderId: 'order-1',
        orderNumber: 'ORD-00000001',
        destinationId: 'destination-1',
        destinationCode: 'ST-1',
        destinationName: 'Tienda Uno',
        customerName: 'Cliente Uno',
        serviceDate: '2026-08-20',
        priority: 'NORMAL',
        requestedWindowStart: null,
        requestedWindowEnd: null,
        assignedWeightKg: 4000,
        assignedVolumeM3: 16,
        assignedPallets: 9,
        wholeOrder: true,
        assignedAt: '2026-08-19T00:00:00Z',
      },
    ],
    stops: detailOverrides.stops ?? [tripStop(status)],
    exceptions: detailOverrides.exceptions ?? [],
    // Empty by default, which is what a trip that has not been worked looks like: an order with no
    // delivery has not been recorded, and there is no "pending" result to stand in for it.
    deliveries: detailOverrides.deliveries ?? [],
  }
}
