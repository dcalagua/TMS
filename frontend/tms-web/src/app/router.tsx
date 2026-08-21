import { createBrowserRouter, type RouteObject } from 'react-router-dom'
import { ControlTowerPage } from '../pages/control-tower/ControlTowerPage'
import { DashboardPage } from '../pages/DashboardPage'
import { CarriersPage } from '../pages/fleet/CarriersPage'
import { DriversPage } from '../pages/fleet/DriversPage'
import { VehicleTypesPage } from '../pages/fleet/VehicleTypesPage'
import { VehiclesPage } from '../pages/fleet/VehiclesPage'
import { LoginPage } from '../pages/LoginPage'
import { DestinationsPage } from '../pages/masters/DestinationsPage'
import { FrequenciesPage } from '../pages/masters/FrequenciesPage'
import { LocationsPage } from '../pages/masters/LocationsPage'
import { OriginsPage } from '../pages/masters/OriginsPage'
import { RoutesPage } from '../pages/masters/RoutesPage'
import { ZonesPage } from '../pages/masters/ZonesPage'
import { NotFoundPage } from '../pages/NotFoundPage'
import { OrdersPage } from '../pages/orders/OrdersPage'
import { PlanningBoardPage } from '../pages/planning/PlanningBoardPage'
import { PlanningRunsPage } from '../pages/planning/PlanningRunsPage'
import { PlaceholderPage } from '../pages/PlaceholderPage'
import { RateCardsPage } from '../pages/rates/RateCardsPage'
import { ReportsPage } from '../pages/reporting/ReportsPage'
import { CompanySettingsPage } from '../pages/settings/CompanySettingsPage'
import { IntegrationsPage } from '../pages/settings/IntegrationsPage'
import { UsersPage } from '../pages/settings/UsersPage'
import { TripsPage } from '../pages/trips/TripsPage'
import { TripWorkspacePage } from '../pages/trips/TripWorkspacePage'
import { ProtectedRoute } from '../shared/auth/ProtectedRoute'
import { RequireCompany } from '../shared/company/RequireCompany'
import { AppLayout } from '../shared/ui/AppLayout'

/** The route table, separate from the browser router so tests can mount the very same routes
 * under a memory router. A navigation test that asserts against a hand-written copy of the
 * route table proves nothing about the app's real one. */
export const appRoutes: RouteObject[] = [
  { path: '/login', element: <LoginPage /> },
  {
    path: '/',
    element: <ProtectedRoute />,
    children: [
      {
        element: <AppLayout />,
        children: [
          { index: true, element: <DashboardPage /> },
          // Outside RequireCompany on purpose: a profile belongs to the person, not to the
          // company the session happens to be scoped to.
          { path: 'account', element: <PlaceholderPage titleKey="items.account" /> },
          {
            element: <RequireCompany />,
            children: [
              // Inside RequireCompany, unlike the dashboard: every number on it is "of this
              // company's day", and there is nothing to show without one.
              { path: 'control-tower', element: <ControlTowerPage /> },
              // Beside the control tower and inside RequireCompany for the same reason: every
              // figure on it is "of this company's operating days", and there is nothing to show
              // without one.
              { path: 'reporting', element: <ReportsPage /> },
              { path: 'masters/locations', element: <LocationsPage /> },
              { path: 'masters/origins', element: <OriginsPage /> },
              { path: 'masters/destinations', element: <DestinationsPage /> },
              { path: 'masters/zones', element: <ZonesPage /> },
              { path: 'masters/frequencies', element: <FrequenciesPage /> },
              { path: 'masters/routes', element: <RoutesPage /> },
              { path: 'fleet/carriers', element: <CarriersPage /> },
              { path: 'fleet/vehicle-types', element: <VehicleTypesPage /> },
              { path: 'fleet/vehicles', element: <VehiclesPage /> },
              { path: 'fleet/drivers', element: <DriversPage /> },
              { path: 'orders', element: <OrdersPage /> },
              { path: 'planning', element: <PlanningRunsPage /> },
              { path: 'planning/:runId', element: <PlanningBoardPage /> },
              { path: 'rates/rate-cards', element: <RateCardsPage /> },
              { path: 'trips', element: <TripsPage /> },
              // A full route, not a drawer over the list: a dispatcher stays inside one trip for
              // minutes, and `/trips/{id}` is what gets pasted into a chat when someone asks
              // where a truck is. See TripWorkspacePage's class comment.
              { path: 'trips/:tripId', element: <TripWorkspacePage /> },
              // Configuración. Inside RequireCompany like every other administration screen: both
              // are about *this* company, and the endpoints behind them refuse a request with no
              // X-Company-Id. The old `/admin/security` placeholder is gone - these two are what it
              // was a placeholder for.
              { path: 'settings/company', element: <CompanySettingsPage /> },
              { path: 'settings/users', element: <UsersPage /> },
              // The Integration Hub. Inside RequireCompany like the other two: a credential and a
              // webhook endpoint both belong to one company, and both endpoints behind this screen
              // refuse a request with no X-Company-Id.
              { path: 'settings/integrations', element: <IntegrationsPage /> },
            ],
          },
          { path: '*', element: <NotFoundPage /> },
        ],
      },
    ],
  },
]

export const router = createBrowserRouter(appRoutes)
