import { useId, type ReactNode } from 'react'
import { createPortal } from 'react-dom'
import { useTranslation } from 'react-i18next'
import { useDialogBehaviour } from './useDialogBehaviour'

export interface DrawerProps {
  open: boolean
  title: string
  onClose: () => void
  children: ReactNode
  footer?: ReactNode
}

/**
 * Side panel for detail that must stay next to its context - a trip's contents while the board
 * is still visible behind it. Shares {@link useDialogBehaviour} with `TmsModal`, so focus,
 * Escape and scroll locking behave identically; only the placement differs.
 */
export function Drawer({ open, title, onClose, children, footer }: DrawerProps) {
  const { t } = useTranslation('common')
  const titleId = useId()
  const drawerRef = useDialogBehaviour({ open, onClose })

  if (!open) {
    return null
  }

  return createPortal(
    <>
      <div className="tms-overlay" onClick={onClose} role="presentation" />
      <div
        ref={drawerRef}
        className="tms-drawer"
        role="dialog"
        aria-modal="true"
        aria-labelledby={titleId}
        tabIndex={-1}
      >
        <div className="tms-modal-header">
          <h2 className="h6 mb-0 tms-truncate" id={titleId}>
            {title}
          </h2>
          <button type="button" className="tms-icon-btn" onClick={onClose} aria-label={t('actions.close')}>
            <i className="bi bi-x-lg" aria-hidden="true" />
          </button>
        </div>

        <div className="tms-modal-body flex-grow-1">{children}</div>

        {footer && <div className="tms-modal-footer">{footer}</div>}
      </div>
    </>,
    document.body,
  )
}
