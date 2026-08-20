import { useId } from 'react'
import { createPortal } from 'react-dom'
import { useTranslation } from 'react-i18next'
import { useMenu } from './components/useMenu'

/**
 * Alerts bell.
 *
 * TMS has no notification source yet - nothing produces one in the backend and nothing stores
 * a read state - so this deliberately shows an empty panel rather than sample rows. A bell
 * carrying an invented badge would be the kind of decoration that reads as a working feature
 * and quietly trains operators to ignore it.
 *
 * When the module exists, this is where it plugs in: the trigger, the panel and the keyboard
 * behaviour are already correct, and only the list content has to arrive.
 */
export function NotificationsMenu() {
  const { t } = useTranslation('navigation')
  const menuId = useId()
  const { open, toggle, containerRef, triggerRef, menuRef, menuStyle, onKeyDown } = useMenu(0)

  return (
    <div ref={containerRef}>
      <button
        ref={triggerRef}
        type="button"
        className={`tms-icon-btn${open ? ' is-open' : ''}`}
        aria-haspopup="menu"
        aria-expanded={open}
        aria-controls={open ? menuId : undefined}
        aria-label={t('notifications')}
        title={t('notifications')}
        onClick={toggle}
      >
        <i className="bi bi-bell" aria-hidden="true" />
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
            <p className="tms-menu-header">{t('notifications')}</p>
            <hr className="tms-menu-divider" />
            <div className="tms-menu-empty">
              <i className="bi bi-bell-slash tms-menu-empty-icon" aria-hidden="true" />
              <p className="tms-menu-empty-title">{t('notificationsEmpty')}</p>
              <p className="tms-menu-empty-hint">{t('notificationsEmptyHint')}</p>
            </div>
          </div>,
          document.body,
        )}
    </div>
  )
}
