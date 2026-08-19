import { Navigate, Outlet, useLocation } from 'react-router-dom'
import { LoadingState } from '../ui/components/LoadingState'
import { useAuth } from './AuthContext'

/** Route-level auth guard. Unauthenticated visitors are sent to `/login`; the backend is the
 * real authority on every request regardless - this only avoids flashing screens that will
 * fail with 401. */
export function ProtectedRoute() {
  const { status } = useAuth()
  const location = useLocation()

  if (status === 'loading') {
    return <LoadingState label="Checking your session..." />
  }

  if (status === 'signedOut') {
    return <Navigate to="/login" replace state={{ from: location }} />
  }

  return <Outlet />
}
