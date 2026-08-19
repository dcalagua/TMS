import { useTranslation } from 'react-i18next'
import { useCompany } from '../company/CompanyContext'

/** Company switcher, built only from what `GET /api/v1/me` returned - the UI cannot offer a
 * company the backend did not list. */
export function CompanySelector() {
  const { t } = useTranslation('common')
  const { status, companies, selected, selectCompany } = useCompany()

  if (status === 'idle') {
    return null
  }

  if (status === 'loading') {
    return <span className="small text-white-50">{t('company.loading')}</span>
  }

  if (status === 'error' || companies.length === 0) {
    return <span className="small text-warning">{t('company.noAccess')}</span>
  }

  return (
    <div className="dropdown">
      <button
        className="btn btn-sm btn-outline-light dropdown-toggle"
        type="button"
        data-bs-toggle="dropdown"
        aria-expanded="false"
      >
        {selected?.name ?? t('company.select')}
      </button>
      <ul className="dropdown-menu dropdown-menu-end">
        {companies.map((company) => (
          <li key={company.id}>
            <button
              type="button"
              className={`dropdown-item${company.id === selected?.id ? ' active' : ''}`}
              onClick={() => selectCompany(company.id)}
            >
              <span className="d-block">{company.name}</span>
              <span className="d-block small text-body-secondary">{company.organization.name}</span>
            </button>
          </li>
        ))}
      </ul>
    </div>
  )
}
