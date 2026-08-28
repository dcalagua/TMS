/**
 * Etiquetas de presentación de los enums que transporta la API.
 *
 * Los valores en sí (`AVAILABLE`, `READY_FOR_PLANNING`, …) son contrato: nunca se traducen,
 * nunca se envían traducidos y nunca se comparan contra texto traducido. Solo su presentación
 * pasa por aquí. La traducción al inglés vive en el diccionario de `lib/i18n.ts`, que ya
 * contiene el par español→inglés de cada una de estas etiquetas.
 */
import { t } from "./i18n";

/** Diccionario ENUM → etiqueta en español. El inglés lo resuelve t() sobre el valor. */
export const ENUM_LABELS = {
  auditAction: {
    ACTIVATE: "Activación",
    ASSIGN_ORDER: "Pedido asignado",
    AUTO_PLAN: "Planificación automática",
    CANCEL: "Cancelación",
    CONFIRM: "Confirmación",
    COST_ACTUAL_RECORDED: "Costo real registrado",
    COST_CLOSED: "Costo cerrado",
    COST_ESTIMATED: "Costo estimado",
    COST_REOPENED: "Costo reabierto",
    CREATE: "Creación",
    CREDENTIAL_CREATE: "Credencial emitida",
    CREDENTIAL_REVOKE: "Credencial revocada",
    CREDENTIAL_ROTATE: "Credencial rotada",
    DEACTIVATE: "Desactivación",
    DELIVERY_RESULT_RECORDED: "Entrega registrada",
    DRIVER_CHANGE: "Cambio de conductor",
    IMPORT_EXECUTED: "Importación ejecutada",
    MOVE_ORDER: "Pedido movido",
    REMOVE_ORDER: "Pedido retirado",
    SHIPMENT_CANCELLED: "Envío cancelado",
    SHIPMENT_COMPLETED: "Envío completado",
    SHIPMENT_CONFIRMED: "Envío confirmado",
    SHIPMENT_DISPATCHED: "Envío despachado",
    SHIPMENT_READY: "Envío listo para despacho",
    TENDER_ACCEPTED: "Oferta aceptada",
    TENDER_CANCELLED: "Oferta retirada",
    TENDER_EXPIRED: "Oferta vencida",
    TENDER_REJECTED: "Oferta rechazada",
    TENDER_SENT: "Oferta enviada",
    UPDATE: "Modificación",
    VEHICLE_CHANGE: "Cambio de vehículo",
  },
  auditAggregateType: {
    APP_USER: "Usuario",
    CARRIER: "Transportista",
    COMPANY: "Empresa",
    DRIVER: "Conductor",
    INTEGRATION_CLIENT: "Credencial de integración",
    LOCATION: "Ubicación",
    MASTER_DATA_IMPORT_BATCH: "Importación de maestros",
    MEMBERSHIP: "Acceso",
    ORDER_IMPORT_BATCH: "Importación de pedidos",
    PLANNING_RUN: "Corrida de planificación",
    RATE_CARD: "Tarifa",
    SHIPMENT: "Envío",
    TRANSPORT_ORDER: "Pedido",
    TRIP: "Viaje",
    TRIP_COST: "Costo del viaje",
    VEHICLE: "Vehículo",
  },
  appointmentPurpose: {
    DELIVERY: "Entrega",
    PICKUP: "Recojo",
  },
  appointmentStatus: {
    ARRIVED: "En la puerta",
    CANCELLED: "Cancelada",
    COMPLETED: "Atendida",
    CONFIRMED: "Confirmada",
    NO_SHOW: "No se presentó",
    REQUESTED: "Solicitada",
    RESCHEDULED: "Reprogramada",
  },
  dayOfWeek: {
    FRIDAY: "Vie",
    MONDAY: "Lun",
    SATURDAY: "Sáb",
    SUNDAY: "Dom",
    THURSDAY: "Jue",
    TUESDAY: "Mar",
    WEDNESDAY: "Mié",
  },
  resourceType: {
    BAY: "Bahía",
    DOCK: "Muelle",
    DOOR: "Puerta",
    YARD: "Patio",
  },
  costComponentReason: {
    DISTANCE_UNKNOWN: "Sin distancia conocida",
    PALLETS_UNKNOWN: "Sin pallets declarados",
    STOPS_UNKNOWN: "Sin paradas registradas",
    VOLUME_UNKNOWN: "Sin volumen declarado",
    WAITING_NOT_RECORDED: "Espera no registrada",
    WEIGHT_UNKNOWN: "Sin peso declarado",
  },
  costQuantitySource: {
    LINEHAUL_SUBTOTAL: "Subtotal del flete",
    MEASURED_ROUTE: "Recorrido medido del viaje",
    ORDER_DECLARED_TOTALS: "Totales declarados de los pedidos",
    RECORDED_WAITING: "Espera registrada",
    ROUTE_REFERENCE: "Distancia de referencia de la ruta",
    TRIP_STOPS: "Paradas del viaje",
  },
  deliveryResult: {
    DELIVERED: "Entregado",
    FAILED: "Entrega fallida",
    NOT_ATTEMPTED: "No intentada",
    PARTIAL: "Entrega parcial",
    REJECTED: "Rechazado",
  },
  departureTimeliness: {
    LATE: "Salió tarde",
    NOT_APPLICABLE: "No aplica",
    NOT_SCHEDULED: "Sin salida planificada",
    ON_TIME: "A tiempo",
    OVERDUE: "Salida vencida",
    SCHEDULED: "Programado",
  },
  driverLicenseStatus: {
    EXPIRED: "Vencida",
    EXPIRING_SOON: "Por vencer",
    UNRECORDED: "Sin registrar",
    VALID: "Vigente",
  },
  evidenceType: {
    DOCUMENT: "Documento",
    PHOTO: "Foto",
    SIGNATURE: "Firma",
  },
  locationRole: {
    DESTINATION: "Destino",
    ORIGIN: "Origen",
  },
  locationType: {
    BRANCH: "Sucursal",
    CUSTOMER: "Cliente",
    DELIVERY_POINT: "Punto de entrega",
    DISTRIBUTION_CENTER: "Centro de distribución",
    HUB: "Hub",
    OTHER: "Otro",
    PLANT: "Planta",
    STORE: "Tienda",
    WAREHOUSE: "Almacén",
  },
  orderFulfillmentStatus: {
    DELIVERED: "Entregado",
    FAILED: "Fallido",
    NOT_ATTEMPTED: "No intentado",
    PARTIALLY_DELIVERED: "Entrega parcial",
    PENDING: "Pendiente",
    REJECTED: "Rechazado",
  },
  orderPriority: {
    HIGH: "Alta",
    LOW: "Baja",
    NORMAL: "Normal",
    URGENT: "Urgente",
  },
  orderStatus: {
    CANCELLED: "Cancelado",
    DELIVERED: "Entregado",
    DELIVERY_FAILED: "Entrega fallida",
    IN_EXECUTION: "En ruta",
    NOT_READY: "No listo",
    PARTIALLY_DELIVERED: "Entregado parcialmente",
    PLANNED: "Planificado",
    READY_FOR_PLANNING: "Listo para planificar",
  },
  planningRunStatus: {
    CANCELLED: "Cancelada",
    CONFIRMED: "Confirmada",
    DRAFT: "Borrador",
  },
  rateCardScope: {
    CARRIER: "Transportista",
    LANE: "Carril (origen-destino)",
    ORIGIN: "Origen",
    ROUTE: "Ruta",
  },
  rateComponent: {
    BASE: "Base",
    DISTANCE: "Distancia",
    FUEL_SURCHARGE: "Recargo por combustible",
    MAXIMUM_ADJUSTMENT: "Ajuste al máximo",
    MINIMUM_ADJUSTMENT: "Ajuste al mínimo",
    OTHER_ACCESSORIAL: "Accesorio",
    PALLETS: "Pallets",
    STOP_OFF: "Paradas adicionales",
    TOLL: "Peajes",
    VOLUME: "Volumen",
    WAITING_TIME: "Tiempo de espera",
    WEIGHT: "Peso",
  },
  stopExecutionStatus: {
    ARRIVED: "En el punto",
    COMPLETED: "Atendida",
    FAILED: "No atendida",
    IN_SERVICE: "En atención",
    PENDING: "Sin iniciar",
    SKIPPED: "Omitida",
  },
  tenderResponseSource: {
    INTEGRATION: "Confirmado por el transportista",
    OPERATOR: "Registrado por nosotros",
  },
  tenderStatus: {
    ACCEPTED: "Aceptada",
    CANCELLED: "Retirada",
    DRAFT: "Borrador",
    EXPIRED: "Vencida",
    REJECTED: "Rechazada",
    SENT: "Esperando respuesta",
  },
  transportEventType: {
    ARRIVED_AT_STOP: "Llegada a la parada",
    DELIVERY_RECORDED: "Entrega registrada",
    EXCEPTION_REPORTED: "Incidencia reportada",
    EXCEPTION_RESOLVED: "Incidencia resuelta",
    SERVICE_STARTED: "Inicio de atención",
    STOP_COMPLETED: "Parada atendida",
    STOP_FAILED: "Parada no atendida",
    STOP_SKIPPED: "Parada omitida",
    TENDER_ACCEPTED: "Oferta aceptada",
    TENDER_CANCELLED: "Oferta retirada",
    TENDER_EXPIRED: "Oferta vencida",
    TENDER_REJECTED: "Oferta rechazada",
    TENDER_SENT: "Oferta enviada al transportista",
    TRIP_CANCELLED: "Viaje cancelado",
    TRIP_COMPLETED: "Viaje cerrado",
    TRIP_CONFIRMED: "Viaje confirmado",
    TRIP_DISPATCHED: "Salida",
    TRIP_READY: "Listo para salir",
  },
  tripExceptionStatus: {
    OPEN: "Abierta",
    RESOLVED: "Resuelta",
  },
  tripExceptionType: {
    ADDRESS_NOT_FOUND: "Dirección no encontrada",
    CUSTOMER_CLOSED: "Cliente cerrado",
    DELIVERY_FAILED: "Entrega fallida",
    DELIVERY_REJECTED: "Entrega rechazada",
    OTHER: "Otra",
    TRAFFIC_DELAY: "Demora por tráfico",
    VEHICLE_BREAKDOWN: "Avería del vehículo",
  },
  tripStatus: {
    CANCELLED: "Cancelado",
    COMPLETED: "Completado",
    CONFIRMED: "Confirmado",
    DRAFT: "Borrador",
    IN_TRANSIT: "En ruta",
    READY_FOR_DISPATCH: "Listo para salir",
  },
  vehicleAvailability: {
    AVAILABLE: "Disponible",
    IN_MAINTENANCE: "En mantenimiento",
    OUT_OF_SERVICE: "Fuera de servicio",
  },
  waterfallCandidateStatus: {
    ACCEPTED: "Aceptó",
    EXPIRED: "Sin respuesta",
    OFFERED: "Ofertado",
    PENDING: "En espera",
    REJECTED: "Rechazó",
    SKIPPED: "No ofertado",
  },
  waterfallStatus: {
    ACTIVE: "En curso",
    ACCEPTED: "Aceptada",
    CANCELLED: "Detenida",
    EXHAUSTED: "Agotada",
  },
  vehicleBodyType: {
    CONTAINER: "Portacontenedor",
    CURTAIN_SIDER: "Cortina lateral",
    DRY_VAN: "Furgón seco",
    FLATBED: "Plataforma",
    OTHER: "Otro",
    REFRIGERATED: "Refrigerado",
    TANKER: "Cisterna",
  },
} as const;

export type EnumGroup = keyof typeof ENUM_LABELS;

/**
 * Etiqueta traducida de un valor de enum. Si el backend introduce un valor que esta versión
 * del frontend no conoce, devuelve el valor crudo en lugar de una celda vacía: es feo, pero
 * es información, y avisa de que hay que actualizar la tabla.
 */
export function enumLabel<G extends EnumGroup>(group: G, value: string | null | undefined): string {
  if (!value) return "-";
  const dict = ENUM_LABELS[group] as Record<string, string>;
  const label = dict[value];
  return label === undefined ? value : t(label);
}

/** Opciones {value,label} de un grupo completo, para selects y filtros. */
export function enumOptions<G extends EnumGroup>(group: G): { value: string; label: string }[] {
  return Object.entries(ENUM_LABELS[group] as Record<string, string>)
    .map(([value, label]) => ({ value, label: t(label) }));
}
