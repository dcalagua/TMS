import { useId } from 'react'
import { createPortal } from 'react-dom'
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
  const { open, toggle, close, containerRef, triggerRef, menuRef, menuStyle, registerItem, onKeyDown } =
    useMenu(companies.length)

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
    <div className="tms-min-w-0" ref={containerRef}>
      {/* A long company name must not push the language switch and user menu off a phone: the
          control shrinks with its container and the name truncates instead. */}
      <button
        ref={triggerRef}
        type="button"
        className={`tms-topbar-control${open ? ' is-open' : ''}`}
        aria-haspopup="menu"
        aria-expanded={open}
        aria-controls={open ? menuId : undefined}
        onClick={toggle}
      >
        <i className="bi bi-buildings tms-topbar-control-icon" aria-hidden="true" />
        <span className="visually-hidden">{t('company.label')}: </span>
        <span className="tms-truncate">{selected?.name ?? t('company.select')}</span>
        <i className="bi bi-chevron-down tms-topbar-control-caret" aria-hidden="true" />
      </button>

      {open &&
        createPortal(
          <div
            id={menuId}
            ref={menuRef}
            role="menu"
            tabIndex={-1}
            className="tms-menu tms-menu-wide"
            style={menuStyle}
            onKeyDown={onKeyDown}
          >
            {companies.map((company, index) => (
              <button
                key={company.id}
                ref={registerItem(index)}
                type="button"
                role="menuitemradio"
                aria-checked={company.id === selected?.id}
                className={`tms-menu-item tms-menu-item-stacked${company.id === selected?.id ? ' is-selected' : ''}`}
                onClick={() => {
                  close(false)
                  selectCompany(company.id)
                }}
              >
                <span className="tms-menu-item-title tms-truncate">{company.name}</span>
                <span className="tms-menu-item-meta tms-truncate">{company.organization.name}</span>
              </button>
            ))}
          </div>,
          document.body,
        )}
    </div>
  )
}
