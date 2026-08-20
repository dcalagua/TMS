import { useId } from 'react'
import { createPortal } from 'react-dom'
import { useTranslation } from 'react-i18next'
import { useNavigate } from 'react-router-dom'
import { useAuth } from '../auth/AuthContext'
import { useCompany } from '../company/CompanyContext'
import { LanguageSwitcher } from './LanguageSwitcher'
import { ThemePicker } from './ThemeControls'
import { confirmDialog } from './components/ConfirmDialog'
import { useMenu } from './components/useMenu'

/**
 * Signed-in user: the identity chip in the bar and the panel behind it.
 *
 * The chip carries the display name with the active company underneath, because "who am I"
 * and "whose data am I looking at" are the two questions an operator working across companies
 * has to answer before acting, and the second one is the one that gets people into trouble.
 *
 * The panel is also where the language lives. It used to sit loose in the bar, which spent
 * permanent horizontal room on a control that is changed once and then never again; folding it
 * into a preferences group keeps it discoverable without it competing with the work.
 */
export function UserMenu() {
  const { t } = useTranslation(['auth', 'navigation'])
  const { user, signOut } = useAuth()
  const { profile, selected } = useCompany()
  const navigate = useNavigate()
  const menuId = useId()
  const { open, toggle, close, containerRef, triggerRef, menuRef, menuStyle, registerItem, onKeyDown } =
    useMenu(2)

  async function handleSignOut() {
    const confirmed = await confirmDialog({
      title: t('signOut.title'),
      text: t('signOut.text'),
      confirmLabel: t('signOut.confirm'),
    })
    if (confirmed) {
      await signOut()
    }
  }

  const email = user?.email ?? t('account')
  // The business profile is what the backend resolved for this caller; the auth email is the
  // fallback for the moment before `/me` answers, so the chip never renders nameless.
  const name = profile?.fullName?.trim() || email
  const initial = name.charAt(0).toUpperCase()

  return (
    <div ref={containerRef}>
      <button
        ref={triggerRef}
        type="button"
        className={`tms-topbar-control tms-topbar-user${open ? ' is-open' : ''}`}
        aria-haspopup="menu"
        aria-expanded={open}
        aria-controls={open ? menuId : undefined}
        onClick={toggle}
      >
        <span className="tms-avatar" aria-hidden="true">
          {initial}
        </span>
        <span className="d-none d-md-flex tms-topbar-user-text">
          <span className="tms-topbar-user-name tms-truncate">{name}</span>
          {selected && <span className="tms-topbar-user-scope tms-truncate">{selected.name}</span>}
        </span>
        <i className="bi bi-chevron-down tms-topbar-control-caret" aria-hidden="true" />
      </button>

      {open &&
        createPortal(
          <div
            id={menuId}
            ref={menuRef}
            role="menu"
            tabIndex={-1}
            className="tms-menu tms-menu-account"
            style={menuStyle}
            onKeyDown={onKeyDown}
          >
            <div className="tms-menu-identity">
              <span className="tms-avatar tms-avatar-lg" aria-hidden="true">
                {initial}
              </span>
              <span className="tms-menu-identity-text">
                <span className="tms-menu-identity-name tms-truncate">{name}</span>
                <span className="tms-menu-identity-email tms-truncate">{email}</span>
                {selected && (
                  <span className="tms-menu-identity-org">
                    <i className="bi bi-patch-check" aria-hidden="true" />
                    <span className="tms-truncate">{selected.organization.name}</span>
                  </span>
                )}
              </span>
            </div>

            <div className="tms-menu-body">
              <p className="tms-menu-section">{t('preferences')}</p>

              {/* Not a menuitem: it is a two-option control, and wrapping it as one would
                  promise roving focus that lands on a group rather than on something
                  activatable. */}
              <div className="tms-menu-row">
                <span className="tms-menu-tile" aria-hidden="true">
                  <i className="bi bi-translate" />
                </span>
                <span className="tms-menu-row-label">{t('language')}</span>
                <LanguageSwitcher />
              </div>

              <div className="tms-menu-row tms-menu-row-stacked">
                <span className="tms-menu-row-head">
                  <span className="tms-menu-tile" aria-hidden="true">
                    <i className="bi bi-palette" />
                  </span>
                  <span className="tms-menu-row-label">{t('theme')}</span>
                </span>
                <ThemePicker />
              </div>

              <hr className="tms-menu-divider" />

              <button
                ref={registerItem(0)}
                type="button"
                role="menuitem"
                className="tms-menu-action"
                onClick={() => {
                  close(false)
                  void navigate('/account')
                }}
              >
                <span className="tms-menu-tile" aria-hidden="true">
                  <i className="bi bi-person" />
                </span>
                <span className="tms-menu-action-text">
                  <span className="tms-menu-action-title">{t('myAccount')}</span>
                  <span className="tms-menu-action-meta">{t('myAccountHint')}</span>
                </span>
                <i className="bi bi-chevron-right tms-menu-action-caret" aria-hidden="true" />
              </button>

              <button
                ref={registerItem(1)}
                type="button"
                role="menuitem"
                className="tms-menu-action is-danger"
                onClick={() => {
                  close(false)
                  void handleSignOut()
                }}
              >
                <span className="tms-menu-tile" aria-hidden="true">
                  <i className="bi bi-box-arrow-right" />
                </span>
                <span className="tms-menu-action-text">
                  <span className="tms-menu-action-title">{t('signOut.action')}</span>
                  <span className="tms-menu-action-meta">{t('signOutHint')}</span>
                </span>
              </button>
            </div>
          </div>,
          document.body,
        )}
    </div>
  )
}
