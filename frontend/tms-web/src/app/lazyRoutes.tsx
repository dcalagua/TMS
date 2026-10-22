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
export const CarrierScorecardPage = lazy(() =>
  import("../pages/reporting/CarrierScorecardPage").then((m) => ({ default: m.CarrierScorecardPage })));

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
// Solo lectura: el almacén (EWM) es el dueño de los perfiles y TMS los recibe por integración.
export const MaterialProfilesPage = lazy(() =>
  import("../pages/masters/MaterialProfilesPage").then((m) => ({ default: m.MaterialProfilesPage })));

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
export const SchedulingReleasePage = lazy(() =>
  import("../pages/scheduling/SchedulingReleasePage").then((m) => ({ default: m.SchedulingReleasePage })));

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
export const SettlementPage = lazy(() =>
  import("../pages/settlement/SettlementPage").then((m) => ({ default: m.SettlementPage })));
export const TolerancePoliciesPage = lazy(() =>
  import("../pages/settlement/TolerancePoliciesPage").then((m) => ({ default: m.TolerancePoliciesPage })));
export const AccrualsPage = lazy(() =>
  import("../pages/settlement/AccrualsPage").then((m) => ({ default: m.AccrualsPage })));
export const PayablesPage = lazy(() =>
  import("../pages/settlement/PayablesPage").then((m) => ({ default: m.PayablesPage })));
export const CostToServePage = lazy(() =>
  import("../pages/settlement/CostToServePage").then((m) => ({ default: m.CostToServePage })));
export const WorkAssignmentsPage = lazy(() =>
  import("../pages/fleet/WorkAssignmentsPage").then((m) => ({ default: m.WorkAssignmentsPage })));
export const OwnFleetCostProfilesPage = lazy(() =>
  import("../pages/costing/OwnFleetCostProfilesPage")
    .then((m) => ({ default: m.OwnFleetCostProfilesPage })));
export const TripWorkspacePage = lazy(() =>
  import("../pages/trips/TripWorkspacePage").then((m) => ({ default: m.TripWorkspacePage })));
export const TripRouteSheetPage = lazy(() =>
  import("../pages/trips/TripRouteSheetPage").then((m) => ({ default: m.TripRouteSheetPage })));
export const DeliveryTrackingPage = lazy(() =>
  import("../pages/delivery-tracking/DeliveryTrackingPage").then((m) => ({ default: m.DeliveryTrackingPage })));
// ADR-019 §2: los envíos que el almacén despachó, con su mapa. Junto al seguimiento de reparto, sin tocarlo.
export const ShipmentTrackingPage = lazy(() =>
  import("../pages/shipment-tracking/ShipmentTrackingPage").then((m) => ({ default: m.ShipmentTrackingPage })));

// ADR-018: el monitor de oficina y las dos pantallas del portal de transportistas. El portal es
// su propio trozo: un transportista nunca descarga una pantalla de oficina, ni al revés.
export const DocumentDeliveriesPage = lazy(() =>
  import("../pages/document-deliveries/DocumentDeliveriesPage").then((m) => ({ default: m.DocumentDeliveriesPage })));
export const PortalTripsPage = lazy(() =>
  import("../pages/portal/PortalTripsPage").then((m) => ({ default: m.PortalTripsPage })));
export const PortalTripPage = lazy(() =>
  import("../pages/portal/PortalTripPage").then((m) => ({ default: m.PortalTripPage })));
// Fase 65: «Mis facturas» del transportista, en el mismo trozo del portal.
export const PortalInvoicesPage = lazy(() =>
  import("../pages/portal/PortalInvoicesPage").then((m) => ({ default: m.PortalInvoicesPage })));
export const PortalInvoicePage = lazy(() =>
  import("../pages/portal/PortalInvoicePage").then((m) => ({ default: m.PortalInvoicePage })));

export const CompanySettingsPage = lazy(() =>
  import("../pages/settings/CompanySettingsPage").then((m) => ({ default: m.CompanySettingsPage })));
export const UsersPage = lazy(() =>
  import("../pages/settings/UsersPage").then((m) => ({ default: m.UsersPage })));
export const IntegrationsPage = lazy(() =>
  import("../pages/settings/IntegrationsPage").then((m) => ({ default: m.IntegrationsPage })));
export const AuditPage = lazy(() =>
  import("../pages/security/AuditPage").then((m) => ({ default: m.AuditPage })));
