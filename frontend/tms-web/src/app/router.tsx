import { createBrowserRouter, type RouteObject } from 'react-router-dom'
import { DashboardPage } from '../pages/DashboardPage'
import { CarriersPage } from '../pages/fleet/CarriersPage'
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
              { path: 'masters/locations', element: <LocationsPage /> },
              { path: 'masters/origins', element: <OriginsPage /> },
              { path: 'masters/destinations', element: <DestinationsPage /> },
              { path: 'masters/zones', element: <ZonesPage /> },
              { path: 'masters/frequencies', element: <FrequenciesPage /> },
              { path: 'masters/routes', element: <RoutesPage /> },
              { path: 'fleet/carriers', element: <CarriersPage /> },
              { path: 'fleet/vehicle-types', element: <VehicleTypesPage /> },
              { path: 'fleet/vehicles', element: <VehiclesPage /> },
              { path: 'orders', element: <OrdersPage /> },
              { path: 'planning', element: <PlanningRunsPage /> },
              { path: 'planning/:runId', element: <PlanningBoardPage /> },
              { path: 'trips', element: <PlaceholderPage titleKey="items.trips" /> },
              { path: 'admin/security', element: <PlaceholderPage titleKey="items.security" /> },
            ],
          },
          { path: '*', element: <NotFoundPage /> },
        ],
      },
    ],
  },
]

export const router = createBrowserRouter(appRoutes)
