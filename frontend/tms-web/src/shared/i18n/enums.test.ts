import { describe, expect, it } from 'vitest'
import { DEPARTURE_TIMELINESS } from '../api/controlTowerApi'
import { DRIVER_LICENSE_STATUSES } from '../api/driversApi'
import { LOCATION_ROLES, LOCATION_TYPES } from '../api/locationsApi'
import { ORDER_FULFILLMENT_STATUSES, ORDER_PRIORITIES, ORDER_STATUSES } from '../api/ordersApi'
import {
  DELIVERY_RESULTS,
  EVIDENCE_TYPES,
  PLANNING_RUN_STATUSES,
  STOP_EXECUTION_STATUSES,
  TRANSPORT_EVENT_TYPES,
  TRIP_EXCEPTION_STATUSES,
  TRIP_EXCEPTION_TYPES,
  TRIP_STATUSES,
} from '../api/planningApi'
import {
  COST_COMPONENT_REASONS,
  COST_QUANTITY_SOURCES,
  RATE_CARD_SCOPES,
  RATE_COMPONENTS,
} from '../api/ratesApi'
import { TENDER_RESPONSE_SOURCES, TENDER_STATUSES } from '../api/tendersApi'
import { VEHICLE_BODY_TYPES } from '../api/vehicleTypesApi'
import { VEHICLE_AVAILABILITY_STATUSES } from '../api/vehiclesApi'
import i18n from './index'

/** Every enum value the API can send, with the `statuses` group that labels it. */
const GROUPS: [string, readonly string[]][] = [
  ['locationType', LOCATION_TYPES],
  ['locationRole', LOCATION_ROLES],
  ['vehicleBodyType', VEHICLE_BODY_TYPES],
  ['vehicleAvailability', VEHICLE_AVAILABILITY_STATUSES],
  ['orderStatus', ORDER_STATUSES],
  ['orderFulfillmentStatus', ORDER_FULFILLMENT_STATUSES],
  // Absent until now, which is exactly how the drivers feature reached the repository with no
  // labels at all: nothing asserted that this vocabulary had any.
  ['driverLicenseStatus', DRIVER_LICENSE_STATUSES],
  ['orderPriority', ORDER_PRIORITIES],
  ['planningRunStatus', PLANNING_RUN_STATUSES],
  ['tripStatus', TRIP_STATUSES],
  ['departureTimeliness', DEPARTURE_TIMELINESS],
  ['stopExecutionStatus', STOP_EXECUTION_STATUSES],
  ['transportEventType', TRANSPORT_EVENT_TYPES],
  ['tripExceptionType', TRIP_EXCEPTION_TYPES],
  ['tripExceptionStatus', TRIP_EXCEPTION_STATUSES],
  ['deliveryResult', DELIVERY_RESULTS],
  ['evidenceType', EVIDENCE_TYPES],
  ['rateCardScope', RATE_CARD_SCOPES],
  ['rateComponent', RATE_COMPONENTS],
  ['costComponentReason', COST_COMPONENT_REASONS],
  ['costQuantitySource', COST_QUANTITY_SOURCES],
  ['tenderStatus', TENDER_STATUSES],
  ['tenderResponseSource', TENDER_RESPONSE_SOURCES],
]

describe('enum labels', () => {
  it.each(['es', 'en'])('labels every API enum value in %s', (language) => {
    const t = i18n.getFixedT(language, 'statuses')

    for (const [group, values] of GROUPS) {
      for (const value of values) {
        const key = `${group}.${value}`
        const label = String(t(key as never))
        // A missing key resolves to the key itself, which is what would reach an operator.
        expect(label, key).not.toBe(key)
        expect(label.trim(), key).not.toBe('')
        // A raw enum value would mean the label was never written.
        expect(label, key).not.toBe(value)
      }
    }
  })

  it('translates the values a planner sees most often', () => {
    const es = i18n.getFixedT('es', 'statuses')
    const en = i18n.getFixedT('en', 'statuses')

    expect(es('vehicleAvailability.IN_MAINTENANCE')).toBe('En mantenimiento')
    expect(en('vehicleAvailability.IN_MAINTENANCE')).toBe('In maintenance')
    expect(es('orderStatus.READY_FOR_PLANNING')).toBe('Listo para planificar')
    expect(es('tripStatus.DRAFT')).toBe('Borrador')
  })
})
