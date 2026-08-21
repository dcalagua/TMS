import { lazy } from 'react'

/**
 * Every screen that is fetched when it is first opened rather than at startup.
 *
 * Split by feature area, not by component: `PlanningRunsPage` and `PlanningBoardPage` arrive
 * together because opening one almost always means opening the other a second later, and a chunk
 * per component trades one big download for fifty small round trips.
 *
 * These live in their own module rather than beside the route table for a mechanical reason: a
 * file that exports both components and non-components loses Fast Refresh, and `router.tsx`
 * exports `appRoutes` and `router`. Here the exports are components and nothing else, so the dev
 * server can hot-replace a screen without reloading the app.
 *
 * `AppLayout` holds the single `<Suspense>` boundary these need, so the shell stays on screen
 * while one is fetched. Four screens are deliberately *not* here - the login form, the dashboard,
 * the 404 and the placeholder - because they are the first thing anybody sees or are a few lines
 * each, and a spinner would be the wrong answer.
 */

export const ControlTowerPage = lazy(() =>
  import('../pages/control-tower/ControlTowerPage').then((m) => ({ default: m.ControlTowerPage })))
export const ReportsPage = lazy(() =>
  import('../pages/reporting/ReportsPage').then((m) => ({ default: m.ReportsPage })))

export const LocationsPage = lazy(() =>
  import('../pages/masters/LocationsPage').then((m) => ({ default: m.LocationsPage })))
export const OriginsPage = lazy(() =>
  import('../pages/masters/OriginsPage').then((m) => ({ default: m.OriginsPage })))
export const DestinationsPage = lazy(() =>
  import('../pages/masters/DestinationsPage').then((m) => ({ default: m.DestinationsPage })))
export const ZonesPage = lazy(() =>
  import('../pages/masters/ZonesPage').then((m) => ({ default: m.ZonesPage })))
export const FrequenciesPage = lazy(() =>
  import('../pages/masters/FrequenciesPage').then((m) => ({ default: m.FrequenciesPage })))
export const RoutesPage = lazy(() =>
  import('../pages/masters/RoutesPage').then((m) => ({ default: m.RoutesPage })))

export const CarriersPage = lazy(() =>
  import('../pages/fleet/CarriersPage').then((m) => ({ default: m.CarriersPage })))
export const VehicleTypesPage = lazy(() =>
  import('../pages/fleet/VehicleTypesPage').then((m) => ({ default: m.VehicleTypesPage })))
export const VehiclesPage = lazy(() =>
  import('../pages/fleet/VehiclesPage').then((m) => ({ default: m.VehiclesPage })))
export const DriversPage = lazy(() =>
  import('../pages/fleet/DriversPage').then((m) => ({ default: m.DriversPage })))

export const OrdersPage = lazy(() =>
  import('../pages/orders/OrdersPage').then((m) => ({ default: m.OrdersPage })))

export const PlanningRunsPage = lazy(() =>
  import('../pages/planning/PlanningRunsPage').then((m) => ({ default: m.PlanningRunsPage })))
export const PlanningBoardPage = lazy(() =>
  import('../pages/planning/PlanningBoardPage').then((m) => ({ default: m.PlanningBoardPage })))

export const RateCardsPage = lazy(() =>
  import('../pages/rates/RateCardsPage').then((m) => ({ default: m.RateCardsPage })))

export const TripsPage = lazy(() =>
  import('../pages/trips/TripsPage').then((m) => ({ default: m.TripsPage })))
export const TripWorkspacePage = lazy(() =>
  import('../pages/trips/TripWorkspacePage').then((m) => ({ default: m.TripWorkspacePage })))

export const CompanySettingsPage = lazy(() =>
  import('../pages/settings/CompanySettingsPage').then((m) => ({ default: m.CompanySettingsPage })))
export const UsersPage = lazy(() =>
  import('../pages/settings/UsersPage').then((m) => ({ default: m.UsersPage })))
export const IntegrationsPage = lazy(() =>
  import('../pages/settings/IntegrationsPage').then((m) => ({ default: m.IntegrationsPage })))
export const AuditPage = lazy(() =>
  import('../pages/security/AuditPage').then((m) => ({ default: m.AuditPage })))
