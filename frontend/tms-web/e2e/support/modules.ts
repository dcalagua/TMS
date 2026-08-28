/**
 * Los módulos del menú lateral, como fuente única para toda la suite.
 *
 * Se mantiene aquí y no dentro de un spec porque varios ficheros recorren la misma lista, y una
 * lista copiada deja de cubrir la pantalla nueva en cuanto alguien añade una. El orden es el del
 * menú, y las rutas son las que declara `src/App.tsx`.
 */
export interface Module {
  readonly path: string;
  /** El rótulo del menú, para que un fallo diga qué pantalla es sin traducir la ruta. */
  readonly label: string;
}

/** Las que el gate nombra explícitamente. */
export const CORE_MODULES: readonly Module[] = [
  { path: "/", label: "Inicio" },
  { path: "/control-tower", label: "Torre de control" },
  { path: "/reporting", label: "Reportes y KPIs" },
  { path: "/masters/locations", label: "Ubicaciones" },
  { path: "/orders", label: "Pedidos" },
  { path: "/planning", label: "Planificación" },
  { path: "/trips", label: "Viajes" },
];

/** El resto del menú, para que el smoke no se limite a las obligatorias. */
export const OTHER_MODULES: readonly Module[] = [
  { path: "/appointments", label: "Citas de muelle" },
  { path: "/settlement", label: "Auditoría de flete" },
  { path: "/work-assignments", label: "Días de trabajo" },
  { path: "/masters/origins", label: "Orígenes" },
  { path: "/masters/destinations", label: "Destinos" },
  { path: "/masters/zones", label: "Zonas" },
  { path: "/masters/frequencies", label: "Frecuencias" },
  { path: "/masters/routes", label: "Rutas" },
  { path: "/fleet/carriers", label: "Transportistas" },
  { path: "/fleet/vehicle-types", label: "Tipos de vehículo" },
  { path: "/fleet/vehicles", label: "Vehículos" },
  { path: "/fleet/drivers", label: "Conductores" },
  { path: "/rates/rate-cards", label: "Tarifarios" },
  { path: "/settings/company", label: "Empresa" },
  { path: "/settings/users", label: "Usuarios y accesos" },
  { path: "/settings/integrations", label: "Integraciones" },
  { path: "/security/audit", label: "Auditoría" },
];

export const ALL_MODULES: readonly Module[] = [...CORE_MODULES, ...OTHER_MODULES];
