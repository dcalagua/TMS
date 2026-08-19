import { useMemo } from 'react'
import { useTranslation } from 'react-i18next'
import type { DestinationType } from '../api/destinationsApi'
import type { OrderPriority, OrderStatus } from '../api/ordersApi'
import type { OriginType } from '../api/originsApi'
import type { PlanningRunStatus, TripStatus } from '../api/planningApi'
import type { VehicleBodyType } from '../api/vehicleTypesApi'
import type { VehicleAvailabilityStatus } from '../api/vehiclesApi'

/**
 * Display labels for the enum values the API transports.
 *
 * The values themselves (`AVAILABLE`, `READY_FOR_PLANNING`, ...) are contract and are never
 * translated, sent translated or compared against translated text: only their presentation
 * goes through here. Keeping the labels in the `statuses` bundle rather than as English
 * constants next to the API types is what stops `IN_MAINTENANCE` reaching an operator, and
 * `enums.test.ts` fails if any value ever lacks a label in either language.
 */
export interface EnumLabels {
  originType: (value: OriginType) => string
  destinationType: (value: DestinationType) => string
  vehicleBodyType: (value: VehicleBodyType) => string
  vehicleAvailability: (value: VehicleAvailabilityStatus) => string
  orderStatus: (value: OrderStatus) => string
  orderPriority: (value: OrderPriority) => string
  planningRunStatus: (value: PlanningRunStatus) => string
  tripStatus: (value: TripStatus) => string
}

export function useEnumLabels(): EnumLabels {
  const { t } = useTranslation('statuses')

  return useMemo<EnumLabels>(
    () => ({
      originType: (value) => t(`originType.${value}`),
      destinationType: (value) => t(`destinationType.${value}`),
      vehicleBodyType: (value) => t(`vehicleBodyType.${value}`),
      vehicleAvailability: (value) => t(`vehicleAvailability.${value}`),
      orderStatus: (value) => t(`orderStatus.${value}`),
      orderPriority: (value) => t(`orderPriority.${value}`),
      planningRunStatus: (value) => t(`planningRunStatus.${value}`),
      tripStatus: (value) => t(`tripStatus.${value}`),
    }),
    [t],
  )
}
