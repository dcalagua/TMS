import { useId } from 'react'
import { useTranslation } from 'react-i18next'
import { useAuth } from '../auth/AuthContext'
import { confirmDialog } from './components/ConfirmDialog'
import { useMenu } from './components/useMenu'

/** Signed-in user menu: identity and the one place `signOut` is called from the shell. */
export function UserMenu() {
  const { t } = useTranslation('auth')
  const { user, signOut } = useAuth()
  const menuId = useId()
  const { open, toggle, close, containerRef, triggerRef, registerItem, onKeyDown } = useMenu(1)

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
    <div className="position-relative" ref={containerRef}>
      <button
        ref={triggerRef}
        type="button"
        className="btn btn-sm btn-outline-secondary d-flex align-items-center gap-2"
        aria-haspopup="menu"
        aria-expanded={open}
        aria-controls={open ? menuId : undefined}
        onClick={toggle}
      >
        <span className="tms-brand-mark" aria-hidden="true" style={{ width: '1.25rem', height: '1.25rem' }}>
          {initial}
        </span>
        <span className="d-none d-md-inline tms-truncate" style={{ maxWidth: '12rem' }}>
          {email}
        </span>
      </button>

      {open && (
        <div
          id={menuId}
          role="menu"
          tabIndex={-1}
          className="dropdown-menu show shadow-sm"
          style={{ position: 'absolute', right: 0, top: '100%', minWidth: '13rem', zIndex: 1040 }}
          onKeyDown={onKeyDown}
        >
          <p className="dropdown-header text-truncate mb-0">{email}</p>
          <hr className="dropdown-divider" />
          <button
            ref={registerItem(0)}
            type="button"
            role="menuitem"
            className="dropdown-item d-flex align-items-center gap-2"
            onClick={() => {
              close(false)
              void handleSignOut()
            }}
          >
            <i className="bi bi-box-arrow-right" aria-hidden="true" />
            <span>{t('signOut.action')}</span>
          </button>
        </div>
      )}
    </div>
  )
}
