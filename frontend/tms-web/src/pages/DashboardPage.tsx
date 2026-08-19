import { useQuery } from '@tanstack/react-query'
import { useTranslation } from 'react-i18next'
import { Link } from 'react-router-dom'
import { fetchSystemInfo } from '../shared/api/systemApi'
import { useAuth } from '../shared/auth/AuthContext'
import { useCompany } from '../shared/company/CompanyContext'
import { NAV_GROUPS } from '../shared/ui/navConfig'

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

  return (
    <>
      <div className="mb-4">
        <h1 className="h4 mb-1">{t('welcome', { name: user?.email ?? '' })}</h1>
        <p className="text-body-secondary mb-0">
          {selected ? t('subtitle', { company: selected.name }) : t('noCompany')}
        </p>
      </div>

      <div className="row g-3">
        <div className="col-12 col-lg-5">
          <div className="card shadow-sm h-100">
            <div className="card-header py-2 fw-semibold">{t('signedInAs')}</div>
            <div className="card-body">
              <dl className="row mb-0 small">
                <dt className="col-5 text-body-secondary">{t('email')}</dt>
                <dd className="col-7">{user?.email ?? '-'}</dd>
                <dt className="col-5 text-body-secondary">{t('company')}</dt>
                <dd className="col-7">{selected?.name ?? '-'}</dd>
                <dt className="col-5 text-body-secondary">{t('organization')}</dt>
                <dd className="col-7">{selected?.organization.name ?? '-'}</dd>
                <dt className="col-5 text-body-secondary">{t('timeZone')}</dt>
                <dd className="col-7 mb-0">{selected?.timeZone ?? '-'}</dd>
              </dl>
            </div>
          </div>
        </div>

        <div className="col-12 col-lg-7">
          <div className="card shadow-sm h-100">
            <div className="card-header py-2 fw-semibold d-flex align-items-center justify-content-between">
              <span>{t('quickAccess')}</span>
              <span className="small fw-normal text-body-secondary">
                {backend.isError ? (
                  <span className="text-danger">{t('apiUnreachable')}</span>
                ) : backend.isSuccess ? (
                  <>
                    <i className="bi bi-circle-fill text-success me-1 small" aria-hidden="true" />
                    {t('apiReachable')}
                  </>
                ) : (
                  t('apiChecking')
                )}
              </span>
            </div>
            <div className="card-body">
              {backend.isError && (
                <div className="alert alert-warning py-2 small" role="alert">
                  {t('apiUnreachableHint')}
                </div>
              )}

              {availableGroups.length === 0 ? (
                <p className="text-body-secondary small mb-0">{t('quickAccessEmpty')}</p>
              ) : (
                <div className="d-flex flex-wrap gap-2">
                  {availableGroups.flatMap((group) =>
                    group.items.map((item) => (
                      <Link key={item.to} to={item.to} className="btn btn-sm btn-outline-secondary">
                        {t(item.labelKey, { ns: 'navigation' })}
                      </Link>
                    )),
                  )}
                </div>
              )}
            </div>
          </div>
        </div>
      </div>
    </>
  )
}
