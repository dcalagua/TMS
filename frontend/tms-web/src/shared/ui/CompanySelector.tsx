import { useId } from 'react'
import { useTranslation } from 'react-i18next'
import { useCompany } from '../company/CompanyContext'
import { useMenu } from './components/useMenu'

/** Company switcher, built only from what `GET /api/v1/me` returned - the UI cannot offer a
 * company the backend did not list. Selecting one only changes the `X-Company-Id` later
 * requests send; the backend validates that header on every company-scoped call regardless. */
export function CompanySelector() {
  const { t } = useTranslation('common')
  const { status, companies, selected, selectCompany } = useCompany()
  const menuId = useId()
  const { open, toggle, close, containerRef, triggerRef, registerItem, onKeyDown } = useMenu(companies.length)

  if (status === 'idle') {
    return null
  }

  if (status === 'loading') {
    return <span className="small text-body-secondary d-none d-md-inline">{t('company.loading')}</span>
  }

  if (status === 'error' || companies.length === 0) {
    return (
      <span className="tms-badge tms-badge-warning">
        <span className="d-none d-sm-inline">{t('company.noAccess')}</span>
        <span className="d-sm-none">!</span>
      </span>
    )
  }

  return (
    <div className="position-relative tms-min-w-0" ref={containerRef}>
      <button
        ref={triggerRef}
        type="button"
        className="btn btn-sm btn-outline-secondary d-flex align-items-center gap-2 w-100"
        aria-haspopup="menu"
        aria-expanded={open}
        aria-controls={open ? menuId : undefined}
        onClick={toggle}
        // A long company name must not push the language switch and user menu off a phone:
        // the button shrinks with its container and the name truncates instead.
        style={{ maxWidth: '13rem' }}
      >
        <i className="bi bi-building flex-shrink-0" aria-hidden="true" />
        <span className="visually-hidden">{t('company.label')}: </span>
        <span className="tms-truncate">{selected?.name ?? t('company.select')}</span>
        <i className="bi bi-chevron-down small flex-shrink-0 d-none d-sm-inline" aria-hidden="true" />
      </button>

      {open && (
        <div
          id={menuId}
          role="menu"
          tabIndex={-1}
          className="dropdown-menu show shadow-sm"
          style={{ position: 'absolute', right: 0, top: '100%', minWidth: '15rem', zIndex: 1040 }}
          onKeyDown={onKeyDown}
        >
          {companies.map((company, index) => (
            <button
              key={company.id}
              ref={registerItem(index)}
              type="button"
              role="menuitemradio"
              aria-checked={company.id === selected?.id}
              className={`dropdown-item${company.id === selected?.id ? ' active' : ''}`}
              onClick={() => {
                close(false)
                selectCompany(company.id)
              }}
            >
              <span className="d-block tms-truncate">{company.name}</span>
              <span className="d-block small opacity-75 tms-truncate">{company.organization.name}</span>
            </button>
          ))}
        </div>
      )}
    </div>
  )
}
