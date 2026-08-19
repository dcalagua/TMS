import { describe, expect, it } from 'vitest'
import { DESTINATION_TYPES } from '../api/destinationsApi'
import { ORDER_PRIORITIES, ORDER_STATUSES } from '../api/ordersApi'
import { ORIGIN_TYPES } from '../api/originsApi'
import { PLANNING_RUN_STATUSES, TRIP_STATUSES } from '../api/planningApi'
import { VEHICLE_BODY_TYPES } from '../api/vehicleTypesApi'
import { VEHICLE_AVAILABILITY_STATUSES } from '../api/vehiclesApi'
import i18n from './index'

/** Every enum value the API can send, with the `statuses` group that labels it. */
const GROUPS: [string, readonly string[]][] = [
  ['originType', ORIGIN_TYPES],
  ['destinationType', DESTINATION_TYPES],
  ['vehicleBodyType', VEHICLE_BODY_TYPES],
  ['vehicleAvailability', VEHICLE_AVAILABILITY_STATUSES],
  ['orderStatus', ORDER_STATUSES],
  ['orderPriority', ORDER_PRIORITIES],
  ['planningRunStatus', PLANNING_RUN_STATUSES],
  ['tripStatus', TRIP_STATUSES],
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
