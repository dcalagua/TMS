import { lazy } from "react";

/**
 * Cada pantalla que se descarga cuando se abre por primera vez, en lugar de al arrancar.
 *
 * Partido por área funcional y no por componente: `PlanningRunsPage` y `PlanningBoardPage`
 * llegan juntas porque abrir una casi siempre significa abrir la otra un segundo después, y un
 * trozo por componente cambia una descarga grande por cincuenta viajes de ida y vuelta.
 *
 * Viven en su propio módulo y no junto a la tabla de rutas por una razón mecánica: un fichero
 * que exporta componentes y no-componentes pierde Fast Refresh, y `App.tsx` exporta la app.
 * Aquí las exportaciones son componentes y nada más, así que el servidor de desarrollo puede
 * reemplazar una pantalla en caliente sin recargar la aplicación.
 *
 * `AppLayout` tiene el único límite de `<Suspense>` que estas necesitan, de modo que el armazón
 * se queda en pantalla mientras se descarga una. Cuatro pantallas están deliberadamente fuera
 * —el login, el dashboard, el 404 y la de "en construcción"— porque son lo primero que ve
 * cualquiera o miden cuatro líneas, y un spinner sería la respuesta equivocada.
 */

export const ControlTowerPage = lazy(() =>
  import("../pages/control-tower/ControlTowerPage").then((m) => ({ default: m.ControlTowerPage })));
export const ReportsPage = lazy(() =>
  import("../pages/reporting/ReportsPage").then((m) => ({ default: m.ReportsPage })));

export const LocationsPage = lazy(() =>
  import("../pages/masters/LocationsPage").then((m) => ({ default: m.LocationsPage })));
export const OriginsPage = lazy(() =>
  import("../pages/masters/OriginsPage").then((m) => ({ default: m.OriginsPage })));
export const DestinationsPage = lazy(() =>
  import("../pages/masters/DestinationsPage").then((m) => ({ default: m.DestinationsPage })));
export const ZonesPage = lazy(() =>
  import("../pages/masters/ZonesPage").then((m) => ({ default: m.ZonesPage })));
export const FrequenciesPage = lazy(() =>
  import("../pages/masters/FrequenciesPage").then((m) => ({ default: m.FrequenciesPage })));
export const RoutesPage = lazy(() =>
  import("../pages/masters/RoutesPage").then((m) => ({ default: m.RoutesPage })));

export const CarriersPage = lazy(() =>
  import("../pages/fleet/CarriersPage").then((m) => ({ default: m.CarriersPage })));
export const VehicleTypesPage = lazy(() =>
  import("../pages/fleet/VehicleTypesPage").then((m) => ({ default: m.VehicleTypesPage })));
export const VehiclesPage = lazy(() =>
  import("../pages/fleet/VehiclesPage").then((m) => ({ default: m.VehiclesPage })));
export const DriversPage = lazy(() =>
  import("../pages/fleet/DriversPage").then((m) => ({ default: m.DriversPage })));

export const OrdersPage = lazy(() =>
  import("../pages/orders/OrdersPage").then((m) => ({ default: m.OrdersPage })));

export const PlanningRunsPage = lazy(() =>
  import("../pages/planning/PlanningRunsPage").then((m) => ({ default: m.PlanningRunsPage })));
export const PlanningBoardPage = lazy(() =>
  import("../pages/planning/PlanningBoardPage").then((m) => ({ default: m.PlanningBoardPage })));

export const RateCardsPage = lazy(() =>
  import("../pages/rates/RateCardsPage").then((m) => ({ default: m.RateCardsPage })));

export const TripsPage = lazy(() =>
  import("../pages/trips/TripsPage").then((m) => ({ default: m.TripsPage })));

export const AppointmentsPage = lazy(() =>
  import("../pages/appointments/AppointmentsPage").then((m) => ({ default: m.AppointmentsPage })));
export const TripWorkspacePage = lazy(() =>
  import("../pages/trips/TripWorkspacePage").then((m) => ({ default: m.TripWorkspacePage })));

export const CompanySettingsPage = lazy(() =>
  import("../pages/settings/CompanySettingsPage").then((m) => ({ default: m.CompanySettingsPage })));
export const UsersPage = lazy(() =>
  import("../pages/settings/UsersPage").then((m) => ({ default: m.UsersPage })));
export const IntegrationsPage = lazy(() =>
  import("../pages/settings/IntegrationsPage").then((m) => ({ default: m.IntegrationsPage })));
export const AuditPage = lazy(() =>
  import("../pages/security/AuditPage").then((m) => ({ default: m.AuditPage })));
