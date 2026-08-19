import { Outlet } from 'react-router-dom'
import { EmptyState } from '../ui/components/EmptyState'
import { ErrorState } from '../ui/components/ErrorState'
import { LoadingState } from '../ui/components/LoadingState'
import { useCompany } from './CompanyContext'

/**
 * Route guard for company-scoped screens. Renders `Outlet` only once a company is selected -
 * a UX convenience, not a security boundary. The company header a screen ends up sending is
 * still independently validated by `CompanyScopeFilter` on the backend for every request.
 */
export function RequireCompany() {
  const { status, companies, selected, errorMessage, refetch } = useCompany()

  if (status === 'idle' || status === 'loading') {
    return <LoadingState label="Loading your companies..." />
  }

  if (status === 'error') {
    return <ErrorState message={errorMessage ?? 'Could not load your companies.'} onRetry={refetch} />
  }

  if (companies.length === 0 || !selected) {
    return (
      <EmptyState
        title="No company access"
        message="Your account has no active company membership. Contact an administrator."
      />
    )
  }

  return <Outlet />
}
