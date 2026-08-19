import { useCallback, useId, type ReactNode } from 'react'
import { createPortal } from 'react-dom'
import { useTranslation } from 'react-i18next'
import { confirmAction } from '../alerts'
import { LoadingState } from './LoadingState'
import { useDialogBehaviour } from './useDialogBehaviour'

export type TmsDrawerSize = 'sm' | 'md' | 'lg' | 'xl'

export interface TmsDrawerProps {
  open: boolean
  /** Names the panel; also its `aria-labelledby` target. */
  title: string
  /** One line of context under the title; becomes `aria-describedby` when present. */
  subtitle?: string
  onClose: () => void
  children: ReactNode
  /** Action row. Convention: cancel first as a secondary button, the primary action last. */
  footer?: ReactNode
  size?: TmsDrawerSize
  /** Replaces the body with a loading indicator - for a detail panel still fetching. */
  loading?: boolean
  closeOnBackdrop?: boolean
  closeOnEscape?: boolean
  /**
   * `true` while the form inside holds unsaved edits. Closing by X, Escape or backdrop then
   * asks for confirmation first; an explicit action in the footer is the caller's own business
   * and never goes through this.
   */
  dirty?: boolean
}

const SIZE_CLASS: Record<TmsDrawerSize, string> = {
  sm: 'tms-drawer-size-sm',
  md: 'tms-drawer-size-md',
  lg: 'tms-drawer-size-lg',
  xl: 'tms-drawer-size-xl',
}

/**
 * The product's create / edit / detail / configure surface.
 *
 * TMS uses a right-side panel rather than a centred modal for these: the list, board or process
 * the operator came from stays visible behind it, so editing one origin does not replace the
 * screenful of origins they were reading. This is the only dialog surface in the product -
 * confirmations and destructive actions go to SweetAlert2, and anything larger gets its own
 * page. See `docs/reviews/UX_UI_REMEDIATION_V1.md`.
 *
 * Open/closed is React state throughout. Bootstrap's `data-bs-*` data API is deliberately not
 * used: its delegated handlers call `preventDefault()` on anchors and resolve instances against
 * whichever copy of Bootstrap's JS loaded first, which is what once stopped the sidebar links
 * navigating.
 *
 * Focus trapping, initial focus, Escape, scroll locking and focus restoration come from
 * {@link useDialogBehaviour}, so that behaviour is written and tested in one place.
 */
export function TmsDrawer({
  open,
  title,
  subtitle,
  onClose,
  children,
  footer,
  size = 'md',
  loading = false,
  closeOnBackdrop = true,
  closeOnEscape = true,
  dirty = false,
}: TmsDrawerProps) {
  const { t } = useTranslation('common')
  const titleId = useId()
  const subtitleId = useId()

  /**
   * The dismiss path every "soft" close goes through. Unsaved work is never discarded silently;
   * a clean form closes immediately, because a confirmation nobody needs is just friction.
   */
  const requestClose = useCallback(() => {
    if (!dirty) {
      onClose()
      return
    }

    void confirmAction({
      title: t('discardChanges.title'),
      text: t('discardChanges.text'),
      confirmLabel: t('discardChanges.confirm'),
      cancelLabel: t('discardChanges.cancel'),
      dangerous: true,
    }).then((confirmed) => {
      if (confirmed) {
        onClose()
      }
    })
  }, [dirty, onClose, t])

  const drawerRef = useDialogBehaviour({ open, onClose: requestClose, closeOnEscape })

  if (!open) {
    return null
  }

  return createPortal(
    <>
      <div
        className="tms-overlay tms-drawer-backdrop"
        onClick={closeOnBackdrop ? requestClose : undefined}
        role="presentation"
      />
      <div
        ref={drawerRef}
        className={`tms-drawer ${SIZE_CLASS[size]}`}
        role="dialog"
        aria-modal="true"
        aria-labelledby={titleId}
        aria-describedby={subtitle ? subtitleId : undefined}
        tabIndex={-1}
      >
        <div className="tms-drawer-header">
          <div className="tms-min-w-0">
            <h2 className="tms-drawer-title" id={titleId}>
              {title}
            </h2>
            {subtitle && (
              <p className="tms-drawer-subtitle" id={subtitleId}>
                {subtitle}
              </p>
            )}
          </div>
          <button type="button" className="tms-icon-btn" onClick={requestClose} aria-label={t('actions.close')}>
            <i className="bi bi-x-lg" aria-hidden="true" />
          </button>
        </div>

        <div className="tms-drawer-body">{loading ? <LoadingState /> : children}</div>

        {footer && <div className="tms-drawer-footer">{footer}</div>}
      </div>
    </>,
    document.body,
  )
}
