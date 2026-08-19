import { useQuery } from '@tanstack/react-query'
import { useTranslation } from 'react-i18next'
import { Link } from 'react-router-dom'
import { fetchSystemInfo } from '../shared/api/systemApi'
import { useAuth } from '../shared/auth/AuthContext'
import { useCompany } from '../shared/company/CompanyContext'
import { NAV_GROUPS } from '../shared/ui/navConfig'
import { AppCard, PageHeader, StatusBadge } from '../shared/ui/components'

/**
 * Landing screen: who you are, which company you are operating in, and the modules your
 * membership actually grants in it.
 *
 * There are deliberately no operational counters here. The backend exposes no KPI endpoint
 * yet, and a dashboard that invents numbers is worse than one that shows none.
 */
export function DashboardPage() {
  const { t } = useTranslation(['dashboard', 'navigation', 'common'])
  const { user } = useAuth()
  const { selected, hasCapability, status: companyStatus } = useCompany()

  const backend = useQuery({
    queryKey: ['system', 'info'],
    queryFn: ({ signal }) => fetchSystemInfo(signal),
    retry: false,
  })

  // Quick access mirrors the sidebar's capability gating: it can only offer what `/me` granted.
  const availableGroups = NAV_GROUPS.filter(
    (group) => !group.capability || companyStatus !== 'ready' || hasCapability(group.capability),
  )

  const apiBadge = backend.isError ? (
    <StatusBadge label={t('apiUnreachable')} tone="danger" />
  ) : backend.isSuccess ? (
    <StatusBadge label={t('apiReachable')} tone="success" />
  ) : (
    <StatusBadge label={t('apiChecking')} tone="neutral" />
  )

  return (
    <>
      <PageHeader
        icon="speedometer2"
        title={t('welcome', { name: user?.email ?? '' })}
        description={selected ? t('subtitle', { company: selected.name }) : t('noCompany')}
        actions={apiBadge}
      />

      {backend.isError && (
        <div className="alert alert-warning d-flex align-items-start gap-2 py-2 small" role="alert">
          <i className="bi bi-exclamation-triangle-fill mt-1" aria-hidden="true" />
          <span>{t('apiUnreachableHint')}</span>
        </div>
      )}

      <div className="row g-3">
        <div className="col-12 col-xl-4">
          <AppCard title={t('signedInAs')}>
            <dl className="row row-cols-1 mb-0 small g-0">
              <div className="d-flex justify-content-between gap-3 py-1">
                <dt className="text-body-secondary fw-normal">{t('email')}</dt>
                <dd className="mb-0 text-end tms-truncate">{user?.email ?? '-'}</dd>
              </div>
              <div className="d-flex justify-content-between gap-3 py-1 border-top">
                <dt className="text-body-secondary fw-normal">{t('company')}</dt>
                <dd className="mb-0 text-end tms-truncate">{selected?.name ?? '-'}</dd>
              </div>
              <div className="d-flex justify-content-between gap-3 py-1 border-top">
                <dt className="text-body-secondary fw-normal">{t('organization')}</dt>
                <dd className="mb-0 text-end tms-truncate">{selected?.organization.name ?? '-'}</dd>
              </div>
              <div className="d-flex justify-content-between gap-3 py-1 border-top">
                <dt className="text-body-secondary fw-normal">{t('timeZone')}</dt>
                <dd className="mb-0 text-end tms-truncate">{selected?.timeZone ?? '-'}</dd>
              </div>
            </dl>
          </AppCard>
        </div>

        <div className="col-12 col-xl-8">
          <AppCard title={t('quickAccess')}>
            {availableGroups.length === 0 ? (
              <p className="text-body-secondary small mb-0">{t('quickAccessEmpty')}</p>
            ) : (
              <div className="d-grid gap-3">
                {availableGroups.map((group) => (
                  <div key={group.labelKey}>
                    <p className="tms-section-title mb-2">{t(group.labelKey, { ns: 'navigation' })}</p>
                    <div className="d-flex flex-wrap gap-2">
                      {group.items.map((item) => (
                        <Link
                          key={item.to}
                          to={item.to}
                          className="btn btn-sm btn-outline-secondary d-inline-flex align-items-center gap-2"
                        >
                          <i className={`bi ${item.icon}`} aria-hidden="true" />
                          {t(item.labelKey, { ns: 'navigation' })}
                        </Link>
                      ))}
                    </div>
                  </div>
                ))}
              </div>
            )}
          </AppCard>
        </div>
      </div>
    </>
  )
}
