import { createBrowserRouter } from 'react-router-dom'
import { DashboardPage } from '../pages/DashboardPage'
import { LoginPage } from '../pages/LoginPage'
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
              { path: 'masters/origins', element: <PlaceholderPage title="Origins" /> },
              { path: 'masters/destinations', element: <PlaceholderPage title="Destinations" /> },
              { path: 'masters/zones', element: <PlaceholderPage title="Zones" /> },
              { path: 'masters/frequencies', element: <PlaceholderPage title="Frequencies" /> },
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
