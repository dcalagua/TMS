import { useCompany } from '../company/CompanyContext'

/** Company switcher, built only from what `GET /api/v1/me` returned - the UI cannot offer a
 * company the backend did not list. */
export function CompanySelector() {
  const { status, companies, selected, selectCompany } = useCompany()

  if (status === 'idle') {
    return null
  }

  if (status === 'loading') {
    return <span className="small text-white-50">Loading companies...</span>
  }

  if (status === 'error' || companies.length === 0) {
    return <span className="small text-warning">No company access</span>
  }

  return (
    <div className="dropdown">
      <button
        className="btn btn-sm btn-outline-light dropdown-toggle"
        type="button"
        data-bs-toggle="dropdown"
        aria-expanded="false"
      >
        {selected?.name ?? 'Select company'}
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
