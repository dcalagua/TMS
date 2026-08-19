import { createBrowserRouter } from 'react-router-dom'
import { DashboardPage } from '../pages/DashboardPage'
import { LoginPage } from '../pages/LoginPage'
import { DestinationsPage } from '../pages/masters/DestinationsPage'
import { FrequenciesPage } from '../pages/masters/FrequenciesPage'
import { OriginsPage } from '../pages/masters/OriginsPage'
import { ZonesPage } from '../pages/masters/ZonesPage'
import { NotFoundPage } from '../pages/NotFoundPage'
import { PlaceholderPage } from '../pages/PlaceholderPage'
import { ProtectedRoute } from '../shared/auth/ProtectedRoute'
import { RequireCompany } from '../shared/company/RequireCompany'
import { AppLayout } from '../shared/ui/AppLayout'

export const router = createBrowserRouter([
  { path: '/login', element: <LoginPage /> },
  {
    path: '/',
    element: <ProtectedRoute />,
    children: [
      {
        element: <AppLayout />,
        children: [
          { index: true, element: <DashboardPage /> },
          {
            element: <RequireCompany />,
            children: [
              { path: 'masters/origins', element: <OriginsPage /> },
              { path: 'masters/destinations', element: <DestinationsPage /> },
              { path: 'masters/zones', element: <ZonesPage /> },
              { path: 'masters/frequencies', element: <FrequenciesPage /> },
              { path: 'masters/routes', element: <PlaceholderPage title="Routes" /> },
              { path: 'fleet/carriers', element: <PlaceholderPage title="Carriers" /> },
              { path: 'fleet/vehicle-types', element: <PlaceholderPage title="Vehicle types" /> },
              { path: 'fleet/vehicles', element: <PlaceholderPage title="Vehicles" /> },
              { path: 'orders', element: <PlaceholderPage title="Orders" /> },
              { path: 'planning', element: <PlaceholderPage title="Planning" /> },
              { path: 'trips', element: <PlaceholderPage title="Trips" /> },
              { path: 'admin/security', element: <PlaceholderPage title="Security" /> },
            ],
          },
          { path: '*', element: <NotFoundPage /> },
        ],
      },
    ],
  },
])
