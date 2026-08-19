import { useId } from 'react'
import { createPortal } from 'react-dom'
import { useTranslation } from 'react-i18next'
import { useAuth } from '../auth/AuthContext'
import { confirmDialog } from './components/ConfirmDialog'
import { useMenu } from './components/useMenu'

/** Signed-in user menu: identity and the one place `signOut` is called from the shell. */
export function UserMenu() {
  const { t } = useTranslation('auth')
  const { user, signOut } = useAuth()
  const menuId = useId()
  const { open, toggle, close, containerRef, triggerRef, menuRef, menuStyle, registerItem, onKeyDown } =
    useMenu(1)

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
  const initial = (user?.email ?? '?').charAt(0).toUpperCase()

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
        <span className="d-none d-md-inline tms-truncate">{email}</span>
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
            <p className="tms-menu-header tms-truncate">{email}</p>
            <hr className="tms-menu-divider" />
            <button
              ref={registerItem(0)}
              type="button"
              role="menuitem"
              className="tms-menu-item"
              onClick={() => {
                close(false)
                void handleSignOut()
              }}
            >
              <i className="bi bi-box-arrow-right" aria-hidden="true" />
              <span>{t('signOut.action')}</span>
            </button>
          </div>,
          document.body,
        )}
    </div>
  )
}
