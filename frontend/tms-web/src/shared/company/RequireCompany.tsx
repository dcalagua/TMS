import { useTranslation } from 'react-i18next'
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
  const { t } = useTranslation('common')
  const { status, companies, selected, errorMessage, refetch } = useCompany()

  if (status === 'idle' || status === 'loading') {
    return <LoadingState label={t('company.loadingYours')} />
  }

  if (status === 'error') {
    return <ErrorState message={errorMessage ?? t('company.loadError')} onRetry={refetch} />
  }

  if (companies.length === 0 || !selected) {
    return <EmptyState title={t('company.noAccessTitle')} message={t('company.noAccessMessage')} />
  }

  return <Outlet />
}
