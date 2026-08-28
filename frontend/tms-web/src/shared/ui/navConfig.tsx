import type { ReactNode } from "react";
import {
  SpeedRounded, BroadcastOnPersonalRounded, BarChartRounded,
  AssignmentTurnedInRounded, ViewKanbanRounded, MapRounded,
  PlaceRounded, TripOriginRounded, PinDropRounded, CropFreeRounded, CalendarViewWeekRounded, AltRouteRounded,
  BusinessRounded, AccountTreeRounded, LocalShippingRounded, BadgeRounded,
  PaidRounded,
  ApartmentRounded, GroupsRounded, PowerRounded, HistoryRounded,
  EventAvailableRounded,
  ReceiptLongRounded,
} from "@mui/icons-material";

/** Una hoja del menú: una pantalla concreta. */
export interface NavLeaf {
  to: string;
  /** Etiqueta en español. El menú nunca lleva texto suelto: pasa por `t()` al pintarse, para
   * que un cambio de idioma no deje media navegación en el idioma anterior. */
  label: string;
  icon: ReactNode;
  /**
   * Capability del backend (`shared/security/Capability` en tms-api) que gobierna la
   * visibilidad. `undefined` = siempre visible. Esconder es solo UX: el backend vuelve a
   * comprobarlo en cada llamada.
   */
  capability?: string;
}

export interface NavSection {
  title: string;
  /** Capability del grupo entero. Una hoja puede además llevar la suya, más estricta. */
  capability?: string;
  items: NavLeaf[];
}

/**
 * Color de acento por ítem del menú → baldosa de icono (estilo iOS: icono sobre pastilla de
 * color redondeada). Paleta pensada para contrastar con el sidebar oscuro del tema, evitando
 * los tonos que se mimetizan con él.
 *
 * Los módulos conservan su color entre pantallas, así que el color acaba siendo parte de cómo
 * el usuario los encuentra.
 */
export const ICON_TINTS: Record<string, string> = {
  "/": "#42A5F5",
  "/control-tower": "#FF5C5C",
  "/reporting": "#26C6DA",
  "/orders": "#29B6F6",
  "/planning": "#B085F5",
  "/trips": "#66BB6A",
  "/appointments": "#FFA726",
  "/settlement": "#8D6E63",
  "/masters/locations": "#4FC3F7",
  "/masters/origins": "#4DB6AC",
  "/masters/destinations": "#7986CB",
  "/masters/zones": "#9CCC65",
  "/masters/frequencies": "#26C6DA",
  "/masters/routes": "#FFB74D",
  "/fleet/carriers": "#7986CB",
  "/fleet/vehicle-types": "#B0BEC5",
  "/fleet/vehicles": "#42A5F5",
  "/fleet/drivers": "#CE93D8",
  "/rates/rate-cards": "#FFCA28",
  "/settings/company": "#B0BEC5",
  "/settings/users": "#42A5F5",
  "/settings/integrations": "#26C6DA",
  "/security/audit": "#FF8A65",
  "/account": "#B0BEC5",
};
export const DEFAULT_TINT = "#4FC3F7";

/**
 * Las hojas que van por encima de los grupos, en orden.
 *
 * Ni la torre de control ni los reportes son un módulo. Cada grupo de abajo es dueño de algo
 * —ubicaciones, vehículos, pedidos, viajes— y estos dos no son dueños de nada: son una forma de
 * mirar el día que produjeron esos módulos, que es exactamente lo que es el dashboard, y por
 * eso los tres van juntos arriba en lugar de archivados bajo el módulo que más filas aporte.
 *
 * Comparten capability, además: `monitoring.transport:read` es lo que comprueban los endpoints
 * de ambos, así que cualquier otra cosa escondería una pantalla a alguien con derecho a ella u
 * ofrecería una que no puede abrir.
 */
export const OVERVIEW_NAV: NavLeaf[] = [
  { to: "/", label: "Inicio", icon: <SpeedRounded /> },
  { to: "/control-tower", label: "Torre de control", icon: <BroadcastOnPersonalRounded />, capability: "TRANSPORT_MONITOR_VIEW" },
  { to: "/reporting", label: "Reportes y KPIs", icon: <BarChartRounded />, capability: "TRANSPORT_MONITOR_VIEW" },
];

/**
 * Los módulos, agrupados por secciones como en el resto de la suite.
 *
 * El orden dentro de cada grupo es el orden en que se monta la operación, no el alfabético: en
 * Flota, los conductores van los últimos, después de lo que conducen, porque un conductor solo
 * es asignable cuando ya hay un vehículo donde meterlo.
 */
export const NAV_SECTIONS: NavSection[] = [
  {
    title: "Operación",
    items: [
      { to: "/orders", label: "Pedidos", icon: <AssignmentTurnedInRounded />, capability: "ORDERS_VIEW" },
      { to: "/planning", label: "Planificación", icon: <ViewKanbanRounded />, capability: "PLANNING_VIEW" },
      { to: "/trips", label: "Viajes", icon: <MapRounded />, capability: "TRIPS_VIEW" },
      // Después de Viajes: una cita existe por un viaje, y la garita la lee justo antes de que
      // llegue. Su propia entrada y no una pestaña del workspace porque quien la mira - patio,
      // garita, almacén - no planifica envíos.
      { to: "/appointments", label: "Citas de muelle", icon: <EventAvailableRounded />,
        capability: "APPOINTMENTS_VIEW" },
      // Después de las citas y antes de Maestros: la auditoría de flete es lo último que pasa con
      // un envío, y quien la mira es finanzas - no planifica, pero necesita llegar a lo que se
      // planificó. TMS valida y exporta; el ERP paga.
      { to: "/settlement", label: "Auditoría de flete", icon: <ReceiptLongRounded />,
        capability: "SETTLEMENT_VIEW" },
    ],
  },
  {
    title: "Maestros",
    capability: "MASTER_DATA_VIEW",
    items: [
      // Primero, y por encima de Orígenes/Destinos a propósito: la ubicación es el registro
      // canónico y esos dos son proyecciones de compatibilidad. Hay un lugar físico, y "origen"
      // y "destino" son *usos* operativos de él, no registros propios. Las dos entradas siguen
      // en el menú porque así es como sigue pensando el trabajo un planificador, y porque rutas,
      // pedidos y planificación hablan ese vocabulario.
      { to: "/masters/locations", label: "Ubicaciones", icon: <PlaceRounded /> },
      { to: "/masters/origins", label: "Orígenes", icon: <TripOriginRounded /> },
      { to: "/masters/destinations", label: "Destinos", icon: <PinDropRounded /> },
      { to: "/masters/zones", label: "Zonas", icon: <CropFreeRounded /> },
      { to: "/masters/frequencies", label: "Frecuencias", icon: <CalendarViewWeekRounded /> },
      { to: "/masters/routes", label: "Rutas", icon: <AltRouteRounded /> },
    ],
  },
  {
    title: "Flota",
    capability: "FLEET_VIEW",
    items: [
      { to: "/fleet/carriers", label: "Transportistas", icon: <BusinessRounded /> },
      { to: "/fleet/vehicle-types", label: "Tipos de vehículo", icon: <AccountTreeRounded /> },
      { to: "/fleet/vehicles", label: "Vehículos", icon: <LocalShippingRounded /> },
      { to: "/fleet/drivers", label: "Conductores", icon: <BadgeRounded /> },
    ],
  },
  {
    // Último de los módulos y detrás de su propia capability: las tarifas son información
    // comercial, y un rol que hace funcionar el día no gana automáticamente el derecho a ver
    // cuánto vale ese día.
    title: "Comercial",
    capability: "RATES_VIEW",
    items: [
      { to: "/rates/rate-cards", label: "Tarifarios", icon: <PaidRounded /> },
    ],
  },
  {
    /**
     * Configuración, la última y fuera de los grupos de módulo.
     *
     * Mantenerla aparte de Maestros es el punto: un maestro es dato que la operación usa cada
     * día, y esto son decisiones que se toman una vez y se dejan en paz.
     *
     * El grupo va detrás de `IAM_VIEW`, que es cualquiera de los permisos `iam.*:read`. Es
     * deliberadamente laxo: un PLANNER tiene `iam.company:read` y verá la sección con la
     * pantalla de empresa en solo lectura, mientras que la de personas responde 403. Esconder
     * es UX; cada endpoint decide por su cuenta.
     */
    title: "Administración",
    capability: "IAM_VIEW",
    items: [
      { to: "/settings/company", label: "Empresa", icon: <ApartmentRounded /> },
      { to: "/settings/users", label: "Usuarios y accesos", icon: <GroupsRounded /> },
      // Lleva su propia capability, a diferencia de sus dos vecinas: emitir una credencial de
      // máquina no es deliberadamente la misma decisión que invitar a una persona.
      { to: "/settings/integrations", label: "Integraciones", icon: <PowerRounded />, capability: "INTEGRATION_VIEW" },
      // También la suya, y por una razón más afilada que la del hub de integraciones: el
      // rastro de auditoría nombra a colegas. Un PLANNER pasa el filtro IAM_VIEW del grupo,
      // pero `audit.log:read` solo se concede a los dos roles de administración.
      { to: "/security/audit", label: "Auditoría", icon: <HistoryRounded />, capability: "AUDIT_VIEW" },
    ],
  },
];

/** Todas las hojas en una sola lista, para el buscador de menú y el breadcrumb: una pantalla
 * alcanzable tiene que ser buscable y tener migas, y eso se rompe en cuanto cada consumidor
 * itera su propia copia del menú. */
export const ALL_NAV_LEAVES: NavLeaf[] = [
  ...OVERVIEW_NAV,
  ...NAV_SECTIONS.flatMap((section) => section.items),
];

/** La sección a la que pertenece una ruta, para la primera línea del breadcrumb. */
export function sectionOf(pathname: string): string | undefined {
  return NAV_SECTIONS.find((section) => section.items.some((item) => item.to === pathname))?.title;
}

/** La hoja que corresponde a una ruta. Casa el prefijo para que `/trips/{id}` siga estando
 * "en" Viajes y `/planning/{runId}` en Planificación. */
export function leafOf(pathname: string): NavLeaf | undefined {
  const exact = ALL_NAV_LEAVES.find((leaf) => leaf.to === pathname);
  if (exact) return exact;
  return ALL_NAV_LEAVES
    .filter((leaf) => leaf.to !== "/" && pathname.startsWith(`${leaf.to}/`))
    .sort((a, b) => b.to.length - a.to.length)[0];
}
