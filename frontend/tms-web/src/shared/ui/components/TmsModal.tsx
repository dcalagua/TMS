import { useId, type ReactNode } from 'react'
import { createPortal } from 'react-dom'
import { useTranslation } from 'react-i18next'
import { useDialogBehaviour } from './useDialogBehaviour'

export interface TmsModalProps {
  open: boolean
  title: string
  description?: string
  onClose: () => void
  children: ReactNode
  /** Action row. Convention: cancel on the left as a secondary button, the primary action last. */
  footer?: ReactNode
  size?: 'sm' | 'md' | 'lg'
  /** Set false while a submit is in flight so a stray Escape cannot abandon a saving form. */
  closeOnEscape?: boolean
}

const SIZE_CLASS: Record<NonNullable<TmsModalProps['size']>, string> = {
  sm: 'tms-modal tms-modal-sm',
  md: 'tms-modal',
  lg: 'tms-modal tms-modal-lg',
}

/**
 * The application's dialog. Every form that used to hand-build Bootstrap modal markup goes
 * through this instead, so focus handling, Escape, the backdrop, scroll locking, focus
 * restoration and the ARIA wiring exist once and are testable once. Rendered in a portal so a
 * dialog opened from deep inside a table is not clipped by an ancestor's overflow.
 *
 * Below `sm` it fills the viewport: a floating card the user must scroll around inside a
 * scrolling page is worse than a full-screen sheet on a phone.
 */
export function TmsModal({
  open,
  title,
  description,
  onClose,
  children,
  footer,
  size = 'md',
  closeOnEscape = true,
}: TmsModalProps) {
  const { t } = useTranslation('common')
  const titleId = useId()
  const descriptionId = useId()
  const dialogRef = useDialogBehaviour({ open, onClose, closeOnEscape })

  if (!open) {
    return null
  }

  return createPortal(
    <>
      <div className="tms-overlay" onClick={onClose} role="presentation" />
      <div className="tms-modal-shell">
        <div
          ref={dialogRef}
          className={SIZE_CLASS[size]}
          role="dialog"
          aria-modal="true"
          aria-labelledby={titleId}
          aria-describedby={description ? descriptionId : undefined}
          tabIndex={-1}
        >
          <div className="tms-modal-header">
            <div className="tms-min-w-0">
              <h2 className="h6 mb-0 tms-truncate" id={titleId}>
                {title}
              </h2>
              {description && (
                <p className="mb-0 small text-body-secondary" id={descriptionId}>
                  {description}
                </p>
              )}
            </div>
            <button type="button" className="tms-icon-btn" onClick={onClose} aria-label={t('actions.close')}>
              <i className="bi bi-x-lg" aria-hidden="true" />
            </button>
          </div>

          <div className="tms-modal-body">{children}</div>

          {footer && <div className="tms-modal-footer">{footer}</div>}
        </div>
      </div>
    </>,
    document.body,
  )
}
