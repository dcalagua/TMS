import { useId } from 'react'
import { createPortal } from 'react-dom'
import { useTranslation } from 'react-i18next'
import { useCompany } from '../company/CompanyContext'
import { useMenu } from './components/useMenu'

export interface CompanySelectorProps {
  /**
   * Where the control is rendered. Only the trigger differs: `topbar` is the compact inline
   * control, `sidebar` is the full-width block that heads the navigation column - avatar,
   * the word "Company" as an overline, and the name on its own line.
   *
   * The variant is a prop rather than a second component because everything that is hard here
   * - the portalled menu, roving focus, `menuitemradio` semantics, closing on select - is
   * shared. Duplicating it to change a trigger is how the two copies drift apart.
   */
  variant?: 'topbar' | 'sidebar'
  /**
   * Appended to the control's own root. Callers use it to hide the control at a breakpoint;
   * it goes here rather than on a wrapper because an extra element in `.tms-topbar-actions`
   * is another flex item, and one that does not carry `min-width: 0` stops the group from
   * shrinking - which is exactly how the top bar overflowed at 1024px.
   */
  className?: string
}

/** Company switcher, built only from what `GET /api/v1/me` returned - the UI cannot offer a
 * company the backend did not list. Selecting one only changes the `X-Company-Id` later
 * requests send; the backend validates that header on every company-scoped call regardless. */
export function CompanySelector({ variant = 'topbar', className = '' }: CompanySelectorProps = {}) {
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

  const name = selected?.name ?? t('company.select')

  return (
    <div
      className={`${variant === 'sidebar' ? 'tms-workspace' : 'tms-min-w-0'}${className ? ` ${className}` : ''}`}
      ref={containerRef}
    >
      {/* A long company name must not push the language switch and user menu off a phone: the
          control shrinks with its container and the name truncates instead. */}
      <button
        ref={triggerRef}
        type="button"
        className={
          variant === 'sidebar'
            ? `tms-workspace-control${open ? ' is-open' : ''}`
            : `tms-topbar-control${open ? ' is-open' : ''}`
        }
        aria-haspopup="menu"
        aria-expanded={open}
        aria-controls={open ? menuId : undefined}
        // The collapsed rail hides the label and the name, which would otherwise leave the
        // control with no accessible name at all. Naming the button explicitly keeps it stable
        // in both states instead of depending on which text happens to be visible.
        aria-label={variant === 'sidebar' ? `${t('company.label')}: ${name}` : undefined}
        onClick={toggle}
      >
        {variant === 'sidebar' ? (
          <>
            {/* The initial stands in for a logo the tenant has not uploaded. It is decorative:
                the name is right beside it, so it carries no information of its own. */}
            <span className="tms-workspace-avatar" aria-hidden="true">
              {name.trim().charAt(0).toUpperCase()}
            </span>
            <span className="tms-workspace-text">
              <span className="tms-workspace-overline">{t('company.label')}</span>
              <span className="tms-workspace-name tms-truncate">{name}</span>
            </span>
            <i className="bi bi-chevron-expand tms-workspace-caret" aria-hidden="true" />
          </>
        ) : (
          <>
            <i className="bi bi-buildings tms-topbar-control-icon" aria-hidden="true" />
            <span className="visually-hidden">{t('company.label')}: </span>
            <span className="tms-truncate">{name}</span>
            <i className="bi bi-chevron-down tms-topbar-control-caret" aria-hidden="true" />
          </>
        )}
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
