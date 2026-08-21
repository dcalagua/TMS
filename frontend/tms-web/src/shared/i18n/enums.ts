import { useMemo } from 'react'
import { useTranslation } from 'react-i18next'
import type { DepartureTimeliness } from '../api/controlTowerApi'
import type { DriverLicenseStatus } from '../api/driversApi'
import type { LocationRole, LocationType } from '../api/locationsApi'
import type { OrderFulfillmentStatus, OrderPriority, OrderStatus } from '../api/ordersApi'
import type {
  DeliveryResult,
  EvidenceType,
  PlanningRunStatus,
  StopExecutionStatus,
  TransportEventType,
  TripExceptionStatus,
  TripExceptionType,
  TripStatus,
} from '../api/planningApi'
import type {
  CostComponentReason,
  CostQuantitySource,
  RateCardScope,
  RateComponent,
} from '../api/ratesApi'
import type { TenderResponseSource, TenderStatus } from '../api/tendersApi'
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
  locationType: (value: LocationType) => string
  locationRole: (value: LocationRole) => string
  vehicleBodyType: (value: VehicleBodyType) => string
  vehicleAvailability: (value: VehicleAvailabilityStatus) => string
  driverLicenseStatus: (value: DriverLicenseStatus) => string
  orderStatus: (value: OrderStatus) => string
  orderFulfillmentStatus: (value: OrderFulfillmentStatus) => string
  orderPriority: (value: OrderPriority) => string
  planningRunStatus: (value: PlanningRunStatus) => string
  tripStatus: (value: TripStatus) => string
  /** The control tower's verdict on a departure - decided by the backend, labelled here. */
  departureTimeliness: (value: DepartureTimeliness) => string
  stopExecutionStatus: (value: StopExecutionStatus) => string
  transportEventType: (value: TransportEventType) => string
  tripExceptionType: (value: TripExceptionType) => string
  tripExceptionStatus: (value: TripExceptionStatus) => string
  deliveryResult: (value: DeliveryResult) => string
  evidenceType: (value: EvidenceType) => string
  rateCardScope: (value: RateCardScope) => string
  rateComponent: (value: RateComponent) => string
  costComponentReason: (value: CostComponentReason) => string
  costQuantitySource: (value: CostQuantitySource) => string
  tenderStatus: (value: TenderStatus) => string
  tenderResponseSource: (value: TenderResponseSource) => string
}

export function useEnumLabels(): EnumLabels {
  const { t } = useTranslation('statuses')

  return useMemo<EnumLabels>(
    () => ({
      locationType: (value) => t(`locationType.${value}`),
      locationRole: (value) => t(`locationRole.${value}`),
      vehicleBodyType: (value) => t(`vehicleBodyType.${value}`),
      vehicleAvailability: (value) => t(`vehicleAvailability.${value}`),
      driverLicenseStatus: (value) => t(`driverLicenseStatus.${value}`),
      orderStatus: (value) => t(`orderStatus.${value}`),
      orderFulfillmentStatus: (value) => t(`orderFulfillmentStatus.${value}`),
      orderPriority: (value) => t(`orderPriority.${value}`),
      planningRunStatus: (value) => t(`planningRunStatus.${value}`),
      tripStatus: (value) => t(`tripStatus.${value}`),
      departureTimeliness: (value) => t(`departureTimeliness.${value}`),
      stopExecutionStatus: (value) => t(`stopExecutionStatus.${value}`),
      transportEventType: (value) => t(`transportEventType.${value}`),
      tripExceptionType: (value) => t(`tripExceptionType.${value}`),
      tripExceptionStatus: (value) => t(`tripExceptionStatus.${value}`),
      deliveryResult: (value) => t(`deliveryResult.${value}`),
      evidenceType: (value) => t(`evidenceType.${value}`),
      rateCardScope: (value) => t(`rateCardScope.${value}`),
      rateComponent: (value) => t(`rateComponent.${value}`),
      costComponentReason: (value) => t(`costComponentReason.${value}`),
      costQuantitySource: (value) => t(`costQuantitySource.${value}`),
      tenderStatus: (value) => t(`tenderStatus.${value}`),
      tenderResponseSource: (value) => t(`tenderResponseSource.${value}`),
    }),
    [t],
  )
}
